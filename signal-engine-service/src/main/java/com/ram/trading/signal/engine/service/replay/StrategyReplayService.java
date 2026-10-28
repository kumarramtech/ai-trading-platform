package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.contant.Trend;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.indicator.Candle;
import com.ram.trading.signal.engine.dto.indicator.HistoricalCandleResponse;
import com.ram.trading.signal.engine.dto.premarket.JudasSwingResult;
import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import com.ram.trading.signal.engine.dto.premarket.ORBResult;
import com.ram.trading.signal.engine.dto.premarket.OpeningRange;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import com.ram.trading.signal.engine.service.ExecutionPlanCalculator;
import com.ram.trading.signal.engine.service.JudasSwingDetector;
import com.ram.trading.signal.engine.service.MinuteCandleStore;
import com.ram.trading.signal.engine.service.ORBValidator;
import com.ram.trading.signal.engine.service.OpeningRangeService;
import com.ram.trading.signal.engine.service.charges.IntradayChargesCalculator;
import com.ram.trading.signal.engine.service.shadow.TrendPullbackShadowStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Replays the live entry rules over past sessions of 1-minute candles, so the
 * strategy is judged on hundreds of setups instead of a handful of paper trades.
 *
 * The live code is reused, not copied: each day gets its own MinuteCandleStore,
 * OpeningRangeService, ORBValidator, JudasSwingDetector and ExecutionPlanCalculator
 * on a clock fixed to that day, fed one minute at a time (no look-ahead). The
 * index regime is rebuilt from NIFTY and BANKNIFTY candles with the market-stream
 * rule (both indices past 0.15% + 0.05% hysteresis from the open, 5-minute hold).
 *
 * Exits: stop or target on the 1-minute high/low (stop first when one candle
 * touches both), otherwise the 15:00 square-off. Slippage on entry and exit,
 * real intraday charges, Rs 250 risk per trade.
 *
 * Not modelled: the daily-indicator rules, the AI veto, trailing stops,
 * max-open-trades and the daily loss limit. "LIVE_RULES" therefore means the
 * mechanical gates only (stop distance, liquidity, regime guard, fresh flip).
 */
@Service
@Slf4j
public class StrategyReplayService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime OPEN = LocalTime.of(9, 15);
    private static final LocalTime ENTRY_START = LocalTime.of(9, 30);
    private static final LocalTime JUDAS_END = LocalTime.of(10, 30);
    private static final int CHUNK_CALENDAR_DAYS = 7;

    private final JdbcTemplate jdbc;
    private final IntradayChargesCalculator charges;
    private final TrendPullbackShadowStrategy trendPullback;
    private final WebClient stockService;
    // A plain thread: the replay blocks on HTTP and JDBC, which Reactor forbids on its own non-blocking threads.
    private final Scheduler worker = Schedulers.fromExecutorService(
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "strategy-replay");
                thread.setDaemon(true);
                return thread;
            }));
    private final AtomicReference<Status> status = new AtomicReference<>(Status.idle());

    @Value("${trading.strategy.orb.end:12:30}")
    private LocalTime orbEnd = LocalTime.of(12, 30);

    @Value("${trading.market.close:15:00}")
    private LocalTime squareOff = LocalTime.of(15, 0);

    @Value("${trading.risk.risk-per-trade:250}")
    private double riskPerTrade = 250;

    @Value("${paper.trading.slippage-pct:0.03}")
    private double slippagePct = 0.03;

    @Value("${trading.liquidity.min-price:50}")
    private double minPrice = 50;

    @Value("${trading.liquidity.min-average-turnover-crore:5}")
    private double minTurnoverCrore = 5;

    @Value("${trading.regime.fresh-flip-minutes:15}")
    private long freshFlipMinutes = 15;

    @Value("${trading.reentry.cooldown-minutes:15}")
    private long cooldownMinutes = 15;

    /** Paper capital x intraday leverage: the most notional one position may carry. */
    @Value("${trading.replay.max-notional:500000}")
    private double maxNotional = 500000;

    /** Upstox historical API calls per second (its limit is far higher). */
    @Value("${trading.replay.requests-per-second:6}")
    private int requestsPerSecond = 6;

    public StrategyReplayService(
            JdbcTemplate jdbc,
            IntradayChargesCalculator charges,
            TrendPullbackShadowStrategy trendPullback,
            WebClient.Builder builder,
            @Value("${stock.service.url}") String stockServiceUrl) {
        this.jdbc = jdbc;
        this.charges = charges;
        this.trendPullback = trendPullback;
        // A 1-minute week is ~2,000 candles (~250 KB); the 256 KB default codec limit is too small.
        this.stockService = builder.baseUrl(stockServiceUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    // ------------------------------------------------------------------ job

    public record Status(String state, String progress, LocalDateTime startedAt, LocalDateTime finishedAt,
                         String report) {
        static Status idle() {
            return new Status("IDLE", "no replay run yet", null, null, null);
        }
    }

    public Status status() {
        return status.get();
    }

    /** Starts a replay in the background; false when one is already running. */
    public synchronized boolean start(LocalDate from, LocalDate to, int universeSize, int universeOffset) {
        if ("RUNNING".equals(status.get().state())) {
            return false;
        }
        LocalDateTime started = LocalDateTime.now(IST);
        status.set(new Status("RUNNING", "starting", started, null, null));
        Mono.fromCallable(() -> run(from, to, universeSize, universeOffset))
                .subscribeOn(worker)
                .subscribe(
                        report -> {
                            status.set(new Status("DONE", "finished", started, LocalDateTime.now(IST), report));
                            log.info("STRATEGY REPLAY DONE\n{}", report);
                        },
                        error -> {
                            log.error("STRATEGY REPLAY FAILED", error);
                            status.set(new Status("FAILED", String.valueOf(error.getMessage()), started,
                                    LocalDateTime.now(IST), null));
                        });
        return true;
    }

    private void progress(String text) {
        Status s = status.get();
        status.set(new Status(s.state(), text, s.startedAt(), null, null));
        log.info("STRATEGY REPLAY | {}", text);
    }

    // ------------------------------------------------------------- results

    /** One setup and what it would have done. */
    public record Result(
            LocalDate day, LocalTime time, String symbol, String strategy, String direction,
            String regime, String relation, String gate, String exit, LocalTime exitTime,
            double entry, double stop, double target, double exitPrice, int quantity,
            double grossPnl, double charges, double netR,
            double gapPct, double orWidthPct, double orRvol, double relStrengthPct,
            float[] pathHigh, float[] pathLow, float[] pathClose) {

        boolean passesLiveRules() {
            return "LIVE_RULES".equals(gate);
        }
    }

    String run(LocalDate from, LocalDate to, int universeSize, int universeOffset) {
        List<String> universe = universe(to, universeSize, universeOffset);
        progress("universe: " + universe.size() + " symbols, turnover rank " + (universeOffset + 1) + "-" + (universeOffset + universe.size()));

        DailyBars daily = DailyBars.load(jdbc, universe, from.minusDays(45), to.plusDays(1));

        List<Result> results = new ArrayList<>();
        int days = 0;
        for (LocalDate chunkStart = from; !chunkStart.isAfter(to); chunkStart = chunkStart.plusDays(CHUNK_CALENDAR_DAYS)) {
            LocalDate chunkEnd = chunkStart.plusDays(CHUNK_CALENDAR_DAYS - 1);
            if (chunkEnd.isAfter(to)) {
                chunkEnd = to;
            }
            progress("fetching " + chunkStart + " .. " + chunkEnd + " (" + results.size() + " setups so far)");

            Map<String, Map<LocalDate, List<MinuteCandle>>> candles = fetch(universe, chunkStart, chunkEnd);
            Map<LocalDate, List<MinuteCandle>> nifty = candles.remove("NIFTY");
            Map<LocalDate, List<MinuteCandle>> bank = candles.remove("BANKNIFTY");
            if (nifty == null || bank == null) {
                log.warn("STRATEGY REPLAY | No index candles for {} .. {}, chunk skipped", chunkStart, chunkEnd);
                continue;
            }

            for (LocalDate day : new TreeSet<>(nifty.keySet())) {
                if (!bank.containsKey(day)) {
                    continue;
                }
                results.addAll(replayDay(day, candles, nifty.get(day), bank.get(day), daily));
                days++;
            }
        }

        progress("simulated " + days + " sessions, " + results.size() + " setups; building report");
        return ReplayReport.render(from, to, days, universe.size(), results, riskPerTrade)
                + "\n" + ReplayExperiments.render(results, days, charges, slippagePct, maxNotional);
    }

    /** The most traded stocks (20-session average turnover) priced at or above the liquidity minimum. */
    private List<String> universe(LocalDate asOf, int size, int offset) {
        return jdbc.queryForList(
                "select symbol from historical_price where interval_type = 'DAY' "
                        + "and trade_date between ? and ? and close_price >= ? "
                        + "group by symbol having count(*) >= 10 "
                        + "order by avg(close_price * volume) desc limit ?",
                String.class, asOf.minusDays(30), asOf, minPrice, size);
    }

    private Map<String, Map<LocalDate, List<MinuteCandle>>> fetch(List<String> universe, LocalDate from, LocalDate to) {
        List<String> symbols = new ArrayList<>(universe);
        symbols.add("NIFTY");
        symbols.add("BANKNIFTY");

        Map<String, Map<LocalDate, List<MinuteCandle>>> bySymbol = new HashMap<>();
        Flux.fromIterable(symbols)
                .delayElements(Duration.ofMillis(1000L / Math.max(1, requestsPerSecond)))
                .flatMap(symbol -> stockService.get()
                        .uri(b -> b.path("/api/v1/stocks/{symbol}/history")
                                .queryParam("interval", "1minute")
                                .queryParam("from", from)
                                .queryParam("to", to)
                                .build(symbol))
                        .retrieve()
                        .bodyToMono(HistoricalCandleResponse.class)
                        .timeout(Duration.ofSeconds(30))
                        .map(r -> Map.entry(symbol, byDay(symbol, r.getCandles())))
                        .onErrorResume(e -> {
                            log.debug("STRATEGY REPLAY | History unavailable | Symbol={} | {}", symbol, e.getMessage());
                            return Mono.empty();
                        }), 3)
                .doOnNext(e -> bySymbol.put(e.getKey(), e.getValue()))
                .blockLast(Duration.ofMinutes(30));
        return bySymbol;
    }

    private static Map<LocalDate, List<MinuteCandle>> byDay(String symbol, List<Candle> candles) {
        Map<LocalDate, List<MinuteCandle>> days = new HashMap<>();
        if (candles == null) {
            return days;
        }
        for (Candle c : candles) {
            if (c.getDateTime() == null || c.getClose() == null) {
                continue;
            }
            LocalDateTime minute = c.getDateTime().atZoneSameInstant(IST).toLocalDateTime();
            days.computeIfAbsent(minute.toLocalDate(), d -> new ArrayList<>())
                    .add(MinuteCandle.builder()
                            .symbol(symbol)
                            .minute(minute)
                            .open(c.getOpen().doubleValue())
                            .high(c.getHigh().doubleValue())
                            .low(c.getLow().doubleValue())
                            .close(c.getClose().doubleValue())
                            .volume(c.getVolume())
                            .build());
        }
        days.values().forEach(list -> list.sort(Comparator.comparing(MinuteCandle::getMinute)));
        return days;
    }

    // ----------------------------------------------------------------- day

    private List<Result> replayDay(
            LocalDate day,
            Map<String, Map<LocalDate, List<MinuteCandle>>> candles,
            List<MinuteCandle> nifty,
            List<MinuteCandle> bank,
            DailyBars daily) {

        Clock clock = Clock.fixed(day.atTime(15, 30).atZone(IST).toInstant(), IST);
        MinuteCandleStore store = new MinuteCandleStore(null);
        store.setClock(clock);
        OpeningRangeService openingRanges = new OpeningRangeService(store);
        openingRanges.setClock(clock);
        ORBValidator orb = new ORBValidator(store);
        orb.setClock(clock);
        orb.setOrbEnd(orbEnd);
        JudasSwingDetector judas = new JudasSwingDetector(store);
        judas.setClock(clock);
        ExecutionPlanCalculator planner = new ExecutionPlanCalculator(openingRanges);

        RegimeTimeline regime = RegimeTimeline.build(nifty, bank);
        double niftyOpen = nifty.get(0).getOpen();
        Map<LocalTime, Double> niftyClose = new HashMap<>();
        nifty.forEach(c -> niftyClose.put(c.getMinute().toLocalTime(), c.getClose()));

        // minute -> candles of every symbol completing at that minute
        TreeMap<LocalTime, List<MinuteCandle>> byMinute = new TreeMap<>();
        Map<String, List<MinuteCandle>> daySeries = new HashMap<>();
        candles.forEach((symbol, days) -> {
            List<MinuteCandle> list = days.get(day);
            if (list == null || list.isEmpty()) {
                return;
            }
            daySeries.put(symbol, list);
            list.forEach(c -> byMinute.computeIfAbsent(c.getMinute().toLocalTime(), t -> new ArrayList<>()).add(c));
        });

        Set<String> processed = new HashSet<>();
        Set<String> pullbackSeen = new HashSet<>();
        List<Result> results = new ArrayList<>();

        for (Map.Entry<LocalTime, List<MinuteCandle>> minute : byMinute.entrySet()) {
            LocalTime t = minute.getKey();
            if (t.isBefore(OPEN) || !t.isBefore(squareOff)) {
                continue;
            }
            minute.getValue().forEach(store::save);
            if (t.isBefore(ENTRY_START)) {
                continue;
            }

            for (MinuteCandle candle : minute.getValue()) {
                String symbol = candle.getSymbol();
                OpeningRange range = openingRanges.get(symbol);
                if (range == null) {
                    range = openingRanges.calculate(symbol);
                }
                if (range == null || !range.isComplete()) {
                    continue;
                }

                List<Setup> setups = new ArrayList<>();

                if (t.isBefore(JUDAS_END)) {
                    JudasSwingResult j = judas.detect(range, t);
                    if (j != null && j.isDetected() && candle.getMinute().equals(j.getConfirmationTime())
                            && processed.add(symbol + "|JUDAS|" + t)) {
                        setups.add(new Setup("JUDAS", direction(j.getDirection()), j.getSweepExtreme(), null));
                    }
                }
                if (t.isBefore(orbEnd)) {
                    ORBResult o = orb.validate(range, t);
                    if (o != null && o.isValid() && candle.getMinute().equals(o.getConfirmationTime())
                            && processed.add(symbol + "|ORB|" + t)) {
                        setups.add(new Setup("ORB", direction(o.getDirection()), null, null));
                    }
                }

                // Research strategies: one gap trade per symbol (09:30), one fade per symbol and side.
                ResearchStrategies.gap(candle, range, daily.statsBefore(symbol, day), t)
                        .ifPresent(s -> setups.add(new Setup(s.getStrategy(), s.getSignal(), null, s)));
                if (!t.isBefore(LocalTime.of(10, 0))) {
                    List<MinuteCandle> soFar = store.getToday(symbol);
                    ResearchStrategies.meanReversion(candle, soFar, t)
                            .filter(s -> processed.add(symbol + "|MR|" + s.getSignal()))
                            .ifPresent(s -> setups.add(new Setup(s.getStrategy(), s.getSignal(), null, s)));
                }

                // One pullback per symbol and regime spell, like the live one-shadow-per-day rule.
                RegimeTimeline.Point point = regime.at(t);
                String pullbackKey = point == null ? null : symbol + "|" + point.since();
                if (pullbackKey != null && point.trend() != Trend.SIDEWAYS && !pullbackSeen.contains(pullbackKey)) {
                    trendPullback.detect(candle, daySeries.get(symbol).get(0).getOpen(), point.trend(),
                                    Duration.between(point.since(), t).toMinutes(),
                                    store.getToday(symbol), t)
                            .ifPresent(s -> {
                                pullbackSeen.add(pullbackKey);
                                setups.add(new Setup(TrendPullbackShadowStrategy.STRATEGY, s.getSignal(), null, s));
                            });
                }

                for (Setup setup : setups) {
                    if (!"BUY".equals(setup.direction()) && !"SELL".equals(setup.direction())) {
                        continue;
                    }
                    Result r = evaluate(day, t, candle, setup, range, point, planner,
                            daily.statsBefore(symbol, day), daySeries.get(symbol), niftyOpen, niftyClose);
                    if (r != null) {
                        results.add(r);
                    }
                }
            }
        }
        return enforceOnePositionPerSymbol(results);
    }

    private record Setup(String strategy, String direction, Double invalidationLevel, TradingSignal plannedSignal) {
    }

    private Result evaluate(
            LocalDate day, LocalTime t, MinuteCandle candle, Setup setup, OpeningRange range,
            RegimeTimeline.Point point, ExecutionPlanCalculator planner, DailyBars.Stats stats,
            List<MinuteCandle> series, double niftyOpen, Map<LocalTime, Double> niftyClose) {

        boolean buy = "BUY".equals(setup.direction());
        double price = candle.getClose();
        Trend trend = point != null ? point.trend() : null;

        String relation = trend == null || trend == Trend.SIDEWAYS ? "SIDEWAYS"
                : (trend == Trend.BULLISH) == buy ? "ALIGNED" : "COUNTER";
        boolean freshFlip = point != null && freshFlipMinutes > 0 && "ALIGNED".equals(relation)
                && Duration.between(point.since(), t).toMinutes() < freshFlipMinutes;

        // Plan: the live calculator, or the strategy's own plan; a refused plan is still followed.
        String planGate = null;
        double stop;
        double target;
        if (setup.plannedSignal() != null) {
            stop = setup.plannedSignal().getStopLoss();
            target = setup.plannedSignal().getTargetPrice();
        } else {
            TradingSignal plan = TradingSignal.builder().symbol(candle.getSymbol()).signal(setup.direction())
                    .entryPrice(price).build();
            SignalGenerationRequest request = SignalGenerationRequest.builder()
                    .symbol(candle.getSymbol()).currentPrice(price).setupStrategy(setup.strategy())
                    .setupDirection(SignalType.valueOf(setup.direction()))
                    .setupInvalidationLevel(setup.invalidationLevel()).build();
            AtomicReference<ExecutionPlanCalculator.RefusedPlan> refused = new AtomicReference<>();
            if (planner.apply(plan, request, refused::set)) {
                if (plan.getStopLoss() == null || plan.getTargetPrice() == null) {
                    return null;
                }
                stop = plan.getStopLoss();
                target = plan.getTargetPrice();
            } else {
                if (refused.get() == null) {
                    return null;
                }
                planGate = refused.get().reason();
                stop = refused.get().stop();
                target = refused.get().target();
            }
        }

        boolean liquid = price >= minPrice
                && (stats == null || stats.avgTurnover20() >= minTurnoverCrore * 1e7);

        String gate = planGate != null ? planGate
                : !liquid ? "ILLIQUID"
                // Fades and gap trades are judged by regime in the report, not blocked by it.
                : ResearchStrategies.isRegimeFree(setup.strategy()) ? "LIVE_RULES"
                : "COUNTER".equals(relation) ? "REGIME_GUARD"
                : freshFlip ? "FRESH_REGIME_FLIP"
                : "LIVE_RULES";

        // Fill and size like paper trading: slippage against us, Rs 250 risk incl. slippage.
        double slip = price * slippagePct / 100.0;
        double entry = buy ? price + slip : price - slip;
        double riskPerShare = Math.abs(entry - stop);
        if (riskPerShare <= 0 || (buy ? stop >= entry : stop <= entry)) {
            return null;
        }
        int quantity = (int) Math.floor(riskPerTrade / riskPerShare);
        quantity = (int) Math.min(quantity, Math.floor(maxNotional / entry));
        if (quantity < 1) {
            return null;
        }

        String exit = "SQUARE_OFF";
        double exitRef = price;
        LocalTime exitTime = squareOff;
        for (MinuteCandle c : series) {
            LocalTime ct = c.getMinute().toLocalTime();
            if (!ct.isAfter(t)) {
                continue;
            }
            if (!ct.isBefore(squareOff)) {
                break;
            }
            exitRef = c.getClose();
            exitTime = ct;
            boolean stopHit = buy ? c.getLow() <= stop : c.getHigh() >= stop;
            boolean targetHit = buy ? c.getHigh() >= target : c.getLow() <= target;
            if (stopHit) {
                exit = "STOP";
                exitRef = stop;
                break;
            }
            if (targetHit) {
                exit = "TARGET";
                exitRef = target;
                break;
            }
        }
        // The path after entry up to the square-off, for the exit experiments.
        List<MinuteCandle> after = series.stream()
                .filter(c -> c.getMinute().toLocalTime().isAfter(t) && c.getMinute().toLocalTime().isBefore(squareOff))
                .toList();
        float[] pathHigh = new float[after.size()];
        float[] pathLow = new float[after.size()];
        float[] pathClose = new float[after.size()];
        for (int i = 0; i < after.size(); i++) {
            pathHigh[i] = after.get(i).getHigh().floatValue();
            pathLow[i] = after.get(i).getLow().floatValue();
            pathClose[i] = after.get(i).getClose().floatValue();
        }

        double exitPrice = buy ? exitRef - exitRef * slippagePct / 100.0 : exitRef + exitRef * slippagePct / 100.0;
        double gross = (buy ? exitPrice - entry : entry - exitPrice) * quantity;
        double cost = charges.roundTrip(setup.direction(), entry, exitPrice, quantity).getTotal();

        // What a trader sees at 09:30.
        double gapPct = stats != null && stats.prevClose() > 0
                ? (range.getOpen() - stats.prevClose()) / stats.prevClose() * 100.0 : Double.NaN;
        double orWidthPct = range.getLow() != null && range.getLow() > 0
                ? (range.getHigh() - range.getLow()) / range.getLow() * 100.0 : Double.NaN;
        double orRvol = stats != null && stats.avgVolume20() > 0 && range.getVolume() != null
                ? range.getVolume() / stats.avgVolume20() : Double.NaN;
        Double niftyAtT = niftyClose.get(t);
        double stockMove = (price - series.get(0).getOpen()) / series.get(0).getOpen() * 100.0;
        double niftyMove = niftyAtT != null ? (niftyAtT - niftyOpen) / niftyOpen * 100.0 : 0.0;
        double relStrength = (stockMove - niftyMove) * (buy ? 1 : -1);

        return new Result(day, t, candle.getSymbol(), setup.strategy(), setup.direction(),
                trend == null ? "UNKNOWN" : trend.name(), relation, gate, exit, exitTime,
                entry, stop, target, exitPrice, quantity, gross, cost,
                (gross - cost) / riskPerTrade, gapPct, orWidthPct, orRvol, relStrength,
                pathHigh, pathLow, pathClose);
    }

    /**
     * Live trades one position per symbol, with a re-entry cooldown: of the
     * LIVE_RULES setups, one that overlaps an earlier one in the same symbol is
     * dropped. Setups blocked by a filter are all kept, since none was traded.
     */
    private List<Result> enforceOnePositionPerSymbol(List<Result> results) {
        results.sort(Comparator.comparing(Result::time));
        Map<String, LocalTime> busyUntil = new HashMap<>();
        List<Result> kept = new ArrayList<>();
        for (Result r : results) {
            if (!r.passesLiveRules()) {
                kept.add(r);
                continue;
            }
            LocalTime free = busyUntil.get(r.symbol());
            if (free != null && r.time().isBefore(free)) {
                continue;
            }
            kept.add(r);
            busyUntil.put(r.symbol(), r.exitTime().plusMinutes(cooldownMinutes));
        }
        return kept;
    }

    private static String direction(String raw) {
        if (raw == null) {
            return "NONE";
        }
        return switch (raw.toUpperCase()) {
            case "BULLISH", "BULLISH_REVERSAL", "BUY" -> "BUY";
            case "BEARISH", "BEARISH_REVERSAL", "SELL" -> "SELL";
            default -> "NONE";
        };
    }

    static <T> Map<String, List<T>> group(List<T> items, Function<T, String> key) {
        Map<String, List<T>> groups = new LinkedHashMap<>();
        items.forEach(i -> groups.computeIfAbsent(key.apply(i), k -> new ArrayList<>()).add(i));
        return groups;
    }
}
