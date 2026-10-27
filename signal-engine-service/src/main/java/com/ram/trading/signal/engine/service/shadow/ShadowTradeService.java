package com.ram.trading.signal.engine.service.shadow;

import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import com.ram.trading.signal.engine.entity.ShadowTrade;
import com.ram.trading.signal.engine.repo.ShadowTradeRepository;
import com.ram.trading.signal.engine.service.ExecutionPlanCalculator;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Follows setups rejected by a filter as if they had been traded, so each
 * filter can be measured by what it saved or cost (see ShadowTrade).
 *
 * Every public method swallows its own failures: shadow tracking must never
 * disturb the real entry or exit path.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShadowTradeService {

    public static final String OPEN = "OPEN";

    private final ShadowTradeRepository repository;
    private final ExecutionPlanCalculator executionPlanCalculator;

    @Value("${trading.shadow.enabled:true}")
    private boolean enabled = true;

    /** Open shadows by symbol; ticks for other symbols return after one map lookup. */
    private final Map<String, List<ShadowTrade>> openBySymbol = new ConcurrentHashMap<>();

    /** Last tick price of symbols with an open shadow, used to close them at market close. */
    private final Map<String, Double> lastPriceBySymbol = new ConcurrentHashMap<>();

    /** One shadow per symbol, strategy, direction and filter per day (setups re-trigger every candle). */
    private final Set<String> recordedToday = ConcurrentHashMap.newKeySet();

    private volatile LocalDate recordedDay = LocalDate.now();

    /**
     * Saves run off the caller's thread (ticks arrive on Netty event loops) and in
     * order on one thread, so a shadow's insert always lands before its close.
     */
    private final Scheduler persistence = Schedulers.newSingle("shadow-persist", true);

    @PostConstruct
    void loadOpen() {
        try {
            repository.findByStatus(OPEN).forEach(this::cacheOpen);
        } catch (Exception ex) {
            log.warn("SHADOW | Could not load open shadow trades | Error={}", ex.getMessage());
        }
    }

    /**
     * Records a rejected setup whose stop/target still have to be planned
     * (regime guard, fresh flip, MIDDAY in SIDEWAYS). Planned with the same
     * ExecutionPlanCalculator as real entries; a setup the planner itself would
     * skip is not recorded, since it could not have been traded anyway.
     */
    public void recordRejectedSetup(
            SignalGenerationRequest request,
            String direction,
            String rejectedBy,
            String marketRegime) {

        if (!enabled || request == null || request.getCurrentPrice() == null
                || request.getCurrentPrice() <= 0 || direction == null) {
            return;
        }

        try {
            TradingSignal plan = TradingSignal.builder()
                    .symbol(request.getSymbol())
                    .signal(direction)
                    .entryPrice(request.getCurrentPrice())
                    .build();

            if (!executionPlanCalculator.apply(plan, request)
                    || plan.getStopLoss() == null || plan.getTargetPrice() == null) {
                log.debug("SHADOW | Not recorded, no usable plan | Symbol={} | RejectedBy={}",
                        request.getSymbol(), rejectedBy);
                return;
            }

            record(request.getSymbol(), request.getSetupStrategy(), direction, rejectedBy,
                    marketRegime, plan.getEntryPrice(), plan.getStopLoss(), plan.getTargetPrice());

        } catch (Exception ex) {
            log.warn("SHADOW | Record failed | Symbol={} | RejectedBy={} | Error={}",
                    request.getSymbol(), rejectedBy, ex.getMessage());
        }
    }

    /** Records a rejected signal that already carries its planned stop and target (e.g. EXTENDED). */
    public void recordRejectedSignal(TradingSignal signal, String rejectedBy) {

        if (!enabled || signal == null) {
            return;
        }

        try {
            record(signal.getSymbol(), signal.getStrategy(), signal.getSignal(), rejectedBy,
                    signal.getMarketRegime(), signal.getEntryPrice(), signal.getStopLoss(),
                    signal.getTargetPrice());
        } catch (Exception ex) {
            log.warn("SHADOW | Record failed | Symbol={} | RejectedBy={} | Error={}",
                    signal.getSymbol(), rejectedBy, ex.getMessage());
        }
    }

    private void record(
            String symbol,
            String strategy,
            String direction,
            String rejectedBy,
            String marketRegime,
            Double entry,
            Double stop,
            Double target) {

        if (symbol == null || entry == null || stop == null || target == null
                || entry <= 0 || stop <= 0 || target <= 0 || Math.abs(entry - stop) <= 0) {
            return;
        }

        rollDay();

        String key = symbol + "|" + strategy + "|" + direction + "|" + rejectedBy;
        if (!recordedToday.add(key)) {
            return;
        }

        ShadowTrade shadow = ShadowTrade.builder()
                .symbol(symbol)
                .strategy(strategy)
                .direction(direction.toUpperCase())
                .rejectedBy(rejectedBy)
                .marketRegime(marketRegime)
                .entryTime(LocalDateTime.now())
                .entryPrice(entry)
                .stopLoss(stop)
                .targetPrice(target)
                .status(OPEN)
                .mfeR(0.0)
                .maeR(0.0)
                .build();

        persist(shadow);
        cacheOpen(shadow);

        log.info("SHADOW OPENED | Symbol={} | Strategy={} | Direction={} | RejectedBy={} | Regime={} | Entry={} | Stop={} | Target={}",
                symbol, strategy, shadow.getDirection(), rejectedBy, marketRegime, entry, stop, target);
    }

    /** Called on every live tick: updates MFE/MAE and closes shadows at their stop or target. */
    public void onTick(String symbol, Double price) {

        if (!enabled || symbol == null || price == null || price <= 0) {
            return;
        }

        List<ShadowTrade> open = openBySymbol.get(symbol);
        if (open == null || open.isEmpty()) {
            return;
        }

        lastPriceBySymbol.put(symbol, price);

        try {
            for (ShadowTrade shadow : open) {
                evaluate(shadow, price);
            }
        } catch (Exception ex) {
            log.warn("SHADOW | Tick evaluation failed | Symbol={} | Error={}", symbol, ex.getMessage());
        }
    }

    private void evaluate(ShadowTrade shadow, double price) {

        boolean buy = "BUY".equals(shadow.getDirection());
        double entry = shadow.getEntryPrice();
        double risk = Math.abs(entry - shadow.getStopLoss());
        double r = (buy ? price - entry : entry - price) / risk;

        shadow.setMfeR(round2(Math.max(shadow.getMfeR(), r)));
        shadow.setMaeR(round2(Math.max(shadow.getMaeR(), -r)));

        boolean stopHit = buy ? price <= shadow.getStopLoss() : price >= shadow.getStopLoss();
        boolean targetHit = buy ? price >= shadow.getTargetPrice() : price <= shadow.getTargetPrice();

        if (stopHit) {
            close(shadow, shadow.getStopLoss(), "STOP");
        } else if (targetHit) {
            close(shadow, shadow.getTargetPrice(), "TARGET");
        }
    }

    /**
     * Closes every open shadow at its last tick price (falls back to entry
     * when no tick was seen) and logs one summary line per filter for today.
     */
    public void closeAllAtMarketClose() {

        if (!enabled || openBySymbol.isEmpty()) {
            return;
        }

        try {
            for (List<ShadowTrade> open : List.copyOf(openBySymbol.values())) {
                for (ShadowTrade shadow : List.copyOf(open)) {
                    Double last = lastPriceBySymbol.get(shadow.getSymbol());
                    close(shadow, last != null && last > 0 ? last : shadow.getEntryPrice(), "MARKET_CLOSE");
                }
            }
            // Queued behind the close saves, so the summary reads them.
            persistence.schedule(() -> {
                try {
                    logDailySummary();
                } catch (Exception ex) {
                    log.warn("SHADOW | Summary failed | Error={}", ex.getMessage());
                }
            });
        } catch (Exception ex) {
            log.warn("SHADOW | Market close failed | Error={}", ex.getMessage());
        }
    }

    private void close(ShadowTrade shadow, double exitPrice, String status) {

        synchronized (shadow) {
            if (!OPEN.equals(shadow.getStatus())) {
                return;
            }
            shadow.setStatus(status);
        }

        boolean buy = "BUY".equals(shadow.getDirection());
        double risk = Math.abs(shadow.getEntryPrice() - shadow.getStopLoss());
        double r = (buy ? exitPrice - shadow.getEntryPrice() : shadow.getEntryPrice() - exitPrice) / risk;

        shadow.setExitPrice(exitPrice);
        shadow.setExitTime(LocalDateTime.now());
        shadow.setRMultiple(round2(r));

        List<ShadowTrade> open = openBySymbol.get(shadow.getSymbol());
        if (open != null) {
            open.remove(shadow);
            if (open.isEmpty()) {
                openBySymbol.remove(shadow.getSymbol(), open);
                lastPriceBySymbol.remove(shadow.getSymbol());
            }
        }

        persist(shadow);

        log.info("SHADOW CLOSED | Symbol={} | RejectedBy={} | Direction={} | Status={} | Exit={} | R={} | MFE={} | MAE={}",
                shadow.getSymbol(), shadow.getRejectedBy(), shadow.getDirection(), status,
                exitPrice, shadow.getRMultiple(), shadow.getMfeR(), shadow.getMaeR());
    }

    private void logDailySummary() {

        List<ShadowTrade> today = repository.findByEntryTimeGreaterThanEqual(LocalDate.now().atStartOfDay());

        Map<String, List<ShadowTrade>> byFilter = today.stream()
                .filter(s -> s.getRMultiple() != null)
                .collect(Collectors.groupingBy(ShadowTrade::getRejectedBy));

        byFilter.forEach((filter, trades) -> {
            long wins = trades.stream().filter(s -> s.getRMultiple() > 0).count();
            double totalR = trades.stream().mapToDouble(ShadowTrade::getRMultiple).sum();
            log.info("SHADOW SUMMARY | RejectedBy={} | Trades={} | Wins={} | TotalR={} | AvgR={} | "
                            + "Note=gross; charges cost ~0.2R per trade, so a filter saved money when AvgR < ~0.2",
                    filter, trades.size(), wins, round2(totalR), round2(totalR / trades.size()));
        });
    }

    private void persist(ShadowTrade shadow) {
        persistence.schedule(() -> {
            try {
                repository.save(shadow);
            } catch (Exception ex) {
                log.warn("SHADOW | Save failed | Symbol={} | Error={}", shadow.getSymbol(), ex.getMessage());
            }
        });
    }

    private void cacheOpen(ShadowTrade shadow) {
        openBySymbol.computeIfAbsent(shadow.getSymbol(), s -> new CopyOnWriteArrayList<>()).add(shadow);
    }

    private void rollDay() {
        LocalDate today = LocalDate.now();
        if (!today.equals(recordedDay)) {
            recordedDay = today;
            recordedToday.clear();
        }
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
