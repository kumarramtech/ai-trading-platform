package com.ram.trading.signal.engine.risk;

import com.ram.trading.signal.engine.client.TradeExecutionClient.TradeExecutionPosition;
import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import com.ram.trading.signal.engine.entity.MinuteCandleEntity;
import com.ram.trading.signal.engine.entity.PaperTrade;
import com.ram.trading.signal.engine.repo.MinuteCandleRepository;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Entry rules from the 2026-11-02 review, when 9 ORB longs opened between 09:35
 * and 09:40 on a +0.75% NIFTY gap, each stock already 1-3% above its open, and
 * all faded together with the index:
 *
 *  - CHASED          : no ORB entry already more than max-move-from-open-pct past
 *                      today's open in the trade direction (the first push is over).
 *  - GAP_DAY_WAIT    : on a NIFTY gap of at least gap-day.min-gap-pct, no ORB entry
 *                      before gap-day.wait-until (the opening range is the gap move).
 *  - DIRECTION_CAP   : at most max-open-per-direction open trades long, and the same
 *                      short; same-side trades on one day are one market bet.
 *  - ENTRY_BURST     : at most max-entries-per-window entries in any window-minutes.
 *  - OPEN_RISK       : realized net P&L today minus the risk still open at the current
 *                      stops minus this trade's risk must stay above -max-daily-loss,
 *                      so the day stop cannot be overrun by trades that are already open.
 *
 * Each method returns the rule's code when the entry is refused, null otherwise.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EntryExposureGuard {

    private static final String NIFTY = "NIFTY";
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final Set<String> ACTIVE_LIVE_STATUSES = Set.of("OPEN", "ENTRY_PENDING", "EXIT_PENDING");

    private final PaperTradeRepository paperTradeRepository;
    private final MinuteCandleRepository minuteCandleRepository;
    private final LiveTradeSnapshotService liveTradeSnapshotService;

    /** Strategies the CHASED and GAP_DAY_WAIT rules apply to (comma separated). */
    @Value("${trading.entry.open-move-strategies:ORB}")
    private Set<String> openMoveStrategies = Set.of("ORB");

    /** 0 disables CHASED. */
    @Value("${trading.entry.max-move-from-open-pct:1.5}")
    private double maxMoveFromOpenPct = 1.5;

    /** 0 disables GAP_DAY_WAIT. */
    @Value("${trading.entry.gap-day.min-gap-pct:0.5}")
    private double gapDayMinGapPct = 0.5;

    @Value("${trading.entry.gap-day.wait-until:10:00}")
    private LocalTime gapDayWaitUntil = LocalTime.of(10, 0);

    /** 0 disables DIRECTION_CAP. */
    @Value("${trading.risk.max-open-per-direction:3}")
    private int maxOpenPerDirection = 3;

    /** 0 disables ENTRY_BURST. */
    @Value("${trading.risk.entry-burst.max-entries:2}")
    private int burstMaxEntries = 2;

    @Value("${trading.risk.entry-burst.window-minutes:15}")
    private long burstWindowMinutes = 15;

    @Value("${trading.risk.max-daily-loss:2000}")
    private double maxDailyLoss = 2000;

    /** Risk budgeted for the new trade (its stop-out loss after sizing). */
    @Value("${trading.risk.risk-per-trade:250}")
    private double riskPerTrade = 250;

    /** OPEN_RISK on/off; open trades count at their current (trailed) stop. */
    @Value("${trading.risk.open-risk-in-daily-loss:true}")
    private boolean openRiskInDailyLoss = true;

    private volatile LocalDate gapDay;
    private volatile Double gapPct;

    /** CHASED and GAP_DAY_WAIT: rules on the market at the moment of the setup. */
    public String marketRejection(SignalGenerationRequest request, LocalTime now) {

        if (request == null || request.getSetupStrategy() == null
                || !openMoveStrategies.contains(request.getSetupStrategy().toUpperCase(Locale.ROOT))) {
            return null;
        }

        SignalType direction = request.getSetupDirection();
        Double move = request.getStockChangePct();

        if (maxMoveFromOpenPct > 0 && move != null && Double.isFinite(move)) {
            double withTrade = direction == SignalType.SELL ? -move : move;
            if (withTrade > maxMoveFromOpenPct) {
                log.info("ENTRY GUARD | CHASED | Symbol={} | Strategy={} | Direction={} | MoveFromOpen={}% | Max={}%",
                        request.getSymbol(), request.getSetupStrategy(), direction, round2(move), maxMoveFromOpenPct);
                return "CHASED";
            }
        }

        if (gapDayMinGapPct > 0 && now != null && now.isBefore(gapDayWaitUntil)) {
            Double gap = niftyGapPct(LocalDate.now());
            if (gap != null && Math.abs(gap) >= gapDayMinGapPct) {
                log.info("ENTRY GUARD | GAP_DAY_WAIT | Symbol={} | Strategy={} | NiftyGap={}% | Until={}",
                        request.getSymbol(), request.getSetupStrategy(), round2(gap), gapDayWaitUntil);
                return "GAP_DAY_WAIT";
            }
        }

        return null;
    }

    /** DIRECTION_CAP, ENTRY_BURST and OPEN_RISK against today's open book (paper rows, or live positions). */
    public String exposureRejection(String symbol, String direction) {

        if (direction == null) {
            return null;
        }
        boolean longTrade = SignalType.BUY.name().equalsIgnoreCase(direction);
        if (!longTrade && !SignalType.SELL.name().equalsIgnoreCase(direction)) {
            return null;
        }

        Book book = liveTradeSnapshotService.isLiveMode() ? liveBook(longTrade) : paperBook(longTrade);
        if (book == null) {
            // Live data missing: RiskManagementService already refuses the entry (fails closed).
            return null;
        }

        String rejection = null;
        if (maxOpenPerDirection > 0 && book.openSameDirection >= maxOpenPerDirection) {
            rejection = "DIRECTION_CAP";
        } else if (burstMaxEntries > 0 && book.recentEntries >= burstMaxEntries) {
            rejection = "ENTRY_BURST";
        } else if (openRiskInDailyLoss && maxDailyLoss > 0
                && book.realizedToday - book.openRisk - riskPerTrade < -maxDailyLoss) {
            rejection = "OPEN_RISK";
        }

        if (rejection != null) {
            log.info("ENTRY GUARD | {} | Symbol={} | Direction={} | OpenSameDirection={} | EntriesLast{}Min={} | RealizedToday={} | OpenRisk={} | NewRisk={} | MaxDailyLoss={}",
                    rejection, symbol, direction, book.openSameDirection, burstWindowMinutes, book.recentEntries,
                    round2(book.realizedToday), round2(book.openRisk), riskPerTrade, maxDailyLoss);
        }
        return rejection;
    }

    private record Book(long openSameDirection, long recentEntries, double realizedToday, double openRisk) {
    }

    private Book paperBook(boolean longTrade) {

        LocalDate today = LocalDate.now();
        LocalDateTime windowStart = LocalDateTime.now().minusMinutes(burstWindowMinutes);

        List<PaperTrade> open = paperTradeRepository.findByStatus(SignalStatus.OPEN);

        long sameDirection = open.stream()
                .filter(t -> longTrade == SignalType.BUY.name().equalsIgnoreCase(t.getSignal()))
                .count();

        double openRisk = open.stream()
                .mapToDouble(t -> riskAtStop(SignalType.BUY.name().equalsIgnoreCase(t.getSignal()),
                        t.getEntryPrice(),
                        t.getCurrentStopLoss() != null ? t.getCurrentStopLoss() : t.getStopLoss(),
                        t.getQuantity()))
                .sum();

        List<PaperTrade> all = paperTradeRepository.findAll();

        long recentEntries = all.stream()
                .filter(t -> t.getEntryTime() != null && !t.getEntryTime().isBefore(windowStart))
                .count();

        double realizedToday = all.stream()
                .filter(t -> t.getExitTime() != null && t.getExitTime().toLocalDate().equals(today))
                .filter(t -> t.getProfitLoss() != null)
                .mapToDouble(PaperTrade::getProfitLoss)
                .sum();

        return new Book(sameDirection, recentEntries, realizedToday, openRisk);
    }

    private Book liveBook(boolean longTrade) {

        Optional<List<TradeExecutionPosition>> snapshot = liveTradeSnapshotService.freshTrades();
        if (snapshot.isEmpty()) {
            return null;
        }
        List<TradeExecutionPosition> trades = snapshot.get();
        LocalDateTime windowStart = LocalDateTime.now().minusMinutes(burstWindowMinutes);

        List<TradeExecutionPosition> open = trades.stream()
                .filter(t -> t.status() != null
                        && ACTIVE_LIVE_STATUSES.contains(t.status().trim().toUpperCase(Locale.ROOT)))
                .toList();

        long sameDirection = open.stream()
                .filter(t -> longTrade == SignalType.BUY.name().equalsIgnoreCase(t.side()))
                .count();

        double openRisk = open.stream()
                .mapToDouble(t -> riskAtStop(SignalType.BUY.name().equalsIgnoreCase(t.side()),
                        t.averageEntryPrice() == null ? null : t.averageEntryPrice().doubleValue(),
                        t.currentStopLoss() != null ? t.currentStopLoss().doubleValue()
                                : t.initialStopLoss() == null ? null : t.initialStopLoss().doubleValue(),
                        t.remainingQuantity() != null && t.remainingQuantity() > 0
                                ? t.remainingQuantity() : t.filledQuantity()))
                .sum();

        long recentEntries = trades.stream()
                .map(t -> t.openedAt() != null ? t.openedAt() : t.createdAt())
                .filter(at -> at != null && !at.isBefore(windowStart))
                .count();

        // Gross of charges here; the daily-loss check in RiskManagementService uses net.
        double realizedToday = trades.stream()
                .filter(t -> t.realizedPnl() != null)
                .mapToDouble(t -> t.realizedPnl().doubleValue())
                .sum();

        return new Book(sameDirection, recentEntries, realizedToday, openRisk);
    }

    /** Loss if the trade is stopped at its stop now; 0 once the stop has locked in a profit. */
    static double riskAtStop(boolean longTrade, Double entry, Double stop, Integer quantity) {
        if (entry == null || stop == null || quantity == null || quantity <= 0) {
            return 0.0;
        }
        double perShare = longTrade ? entry - stop : stop - entry;
        return Math.max(0.0, perShare * quantity);
    }

    /**
     * NIFTY's opening gap today: first 1-minute candle's open vs the last candle
     * of the previous session, in %. Null until both are known (cached once found).
     */
    Double niftyGapPct(LocalDate today) {

        if (today.equals(gapDay) && gapPct != null) {
            return gapPct;
        }

        LocalDateTime open = today.atTime(MARKET_OPEN);
        Optional<MinuteCandleEntity> first =
                minuteCandleRepository.findFirstBySymbolAndMinuteGreaterThanEqualOrderByMinuteAsc(NIFTY, open);
        Optional<MinuteCandleEntity> previous =
                minuteCandleRepository.findFirstBySymbolAndMinuteLessThanOrderByMinuteDesc(NIFTY, today.atStartOfDay());

        if (first.isEmpty() || previous.isEmpty()
                || first.get().getOpenPrice() == null || previous.get().getClosePrice() == null
                || previous.get().getClosePrice() <= 0) {
            return null;
        }

        double gap = (first.get().getOpenPrice() / previous.get().getClosePrice() - 1.0) * 100.0;
        gapDay = today;
        gapPct = gap;
        log.info("ENTRY GUARD | NIFTY gap {} = {}% | Open={} | PreviousClose={} ({})",
                today, round2(gap), first.get().getOpenPrice(), previous.get().getClosePrice(),
                previous.get().getMinute());
        return gap;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
