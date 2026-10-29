package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.dto.indicator.Candle;
import com.ram.trading.signal.engine.dto.indicator.HistoricalCandleResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Swing (multi-day, long-only, delivery) backtest on daily candles: can momentum
 * held for days beat its costs where intraday patterns could not?
 *
 * Daily candles come from the Upstox history API (years, one call per symbol),
 * for the most traded stocks today (note: survivorship bias, delisted names are
 * missing). Signals use the close of day t; orders fill at the open of t+1 with
 * slippage; a stop gapped through fills at the open. ₹1 lakh, no leverage, at
 * most maxPositions at a time, ₹1,000 (1%) risk per position, real delivery
 * charges (STT 0.1% both sides, stamp, DP charge, brokerage, GST).
 *
 *  BREAKOUT   close above the 55-day high, volume >= 1.5x its 20-day average,
 *             close above the 100-day average. Stop 2 ATR; exit on a close below
 *             the 20-day low (trend following, Donchian style).
 *  PULLBACK   close above the 100-day average and 2-day RSI below 10 (a dip in
 *             an uptrend). Stop 2.5 ATR; exit on a close above the 5-day average
 *             or after 10 days.
 *  ROTATION   each week, the 5 strongest stocks by 3-month return (excluding the
 *             last week) above their 100-day average, equal weight, rebalanced.
 */
@Service
@Slf4j
public class SwingBacktestService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate jdbc;
    private final WebClient stockService;
    private final Scheduler worker = Schedulers.fromExecutorService(
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "swing-backtest");
                thread.setDaemon(true);
                return thread;
            }));
    private final AtomicReference<String> status = new AtomicReference<>("IDLE | no swing backtest run yet");

    @Value("${trading.swing.capital:100000}")
    private double capital = 100000;

    @Value("${trading.swing.risk-per-trade:1000}")
    private double riskPerTrade = 1000;

    @Value("${trading.swing.max-positions:5}")
    private int maxPositions = 5;

    /** Next-open fills: the opening auction is noisier than a mid-session fill. */
    @Value("${trading.swing.slippage-pct:0.10}")
    private double slippagePct = 0.10;

    @Value("${trading.swing.min-turnover-crore:10}")
    private double minTurnoverCrore = 10;

    @Value("${trading.replay.requests-per-second:6}")
    private int requestsPerSecond = 6;

    /**
     * Only the stocks among the N most traded on that day (20-day average turnover)
     * are eligible, as NSE builds its momentum indices from the Nifty 200. 0 = off.
     */
    @Value("${trading.swing.liquid-rank:200}")
    private int liquidRank = 200;

    // day -> symbols in the top liquidRank by turnover that day (point in time)
    private final Map<LocalDate, java.util.Set<String>> liquidByDay = new ConcurrentHashMap<>();

    private boolean eligible(Map<String, Series> data, String symbol, LocalDate day) {
        if (liquidRank <= 0) {
            return true;
        }
        return liquidByDay.computeIfAbsent(day, d -> {
            List<Map.Entry<String, Double>> turnover = new ArrayList<>();
            for (Series s : data.values()) {
                int i = s.indexOf(d);
                if (i >= 20) {
                    turnover.add(Map.entry(s.symbol(), sma(s.volume(), i, 20) * s.close()[i]));
                }
            }
            turnover.sort(Map.Entry.<String, Double>comparingByValue().reversed());
            java.util.Set<String> top = new java.util.HashSet<>();
            turnover.stream().limit(liquidRank).forEach(e -> top.add(e.getKey()));
            return top;
        }).contains(symbol);
    }

    public SwingBacktestService(JdbcTemplate jdbc, WebClient.Builder builder,
                                @Value("${stock.service.url}") String stockServiceUrl) {
        this.jdbc = jdbc;
        this.stockService = builder.baseUrl(stockServiceUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    public String status() {
        return status.get();
    }

    public synchronized boolean start(int years, int universeSize, LocalDate to) {
        if (status.get().startsWith("RUNNING")) {
            return false;
        }
        status.set("RUNNING | starting " + LocalDateTime.now(IST));
        Mono.fromCallable(() -> run(years, universeSize, to))
                .subscribeOn(worker)
                .subscribe(report -> {
                    status.set(report);
                    log.info("SWING BACKTEST DONE\n{}", report);
                }, error -> {
                    log.error("SWING BACKTEST FAILED", error);
                    status.set("FAILED | " + error.getMessage());
                });
        return true;
    }

    // ------------------------------------------------------------------ data

    /** Daily series of one symbol, oldest first. */
    record Series(String symbol, LocalDate[] date, double[] open, double[] high, double[] low,
                  double[] close, double[] volume) {
        int size() {
            return date.length;
        }

        int indexOf(LocalDate day) {
            int i = java.util.Arrays.binarySearch(date, day);
            return i >= 0 ? i : -1;
        }
    }

    String run(int years, int universeSize, LocalDate to) {
        liquidByDay.clear();
        LocalDate from = to.minusYears(years);
        // Symbols listed today (an older period also misses names delisted since); liquidity is then point in time.
        List<String> universe = jdbc.queryForList(
                "select symbol from historical_price where interval_type = 'DAY' and trade_date >= ? "
                        + "group by symbol having count(*) >= 15 "
                        + "order by avg(close_price * volume) desc limit ?",
                String.class, LocalDate.now(IST).minusDays(30), universeSize);
        status.set("RUNNING | fetching " + universe.size() + " symbols, " + from + " .. " + to);

        Map<String, Series> data = new ConcurrentHashMap<>();
        List<String> symbols = new ArrayList<>(universe);
        symbols.add("NIFTY");
        // NIFTY a year earlier, so its 200-day average (the market filter) is known from the first session.
        LocalDate niftyFrom = from.minusYears(1);
        Flux.fromIterable(symbols)
                .delayElements(Duration.ofMillis(1000L / Math.max(1, requestsPerSecond)))
                .flatMap(symbol -> stockService.get()
                        .uri(b -> b.path("/api/v1/stocks/{symbol}/history")
                                .queryParam("interval", "day")
                                .queryParam("from", "NIFTY".equals(symbol) ? niftyFrom : from).queryParam("to", to)
                                .build(symbol))
                        .retrieve().bodyToMono(HistoricalCandleResponse.class)
                        .timeout(Duration.ofSeconds(30))
                        .map(r -> toSeries(symbol, r.getCandles()))
                        .onErrorResume(e -> Mono.empty()), 3)
                .filter(s -> s.size() > 120)
                .doOnNext(s -> data.put(s.symbol(), s))
                .blockLast(Duration.ofMinutes(30));

        Series niftyFull = data.remove("NIFTY");
        if (niftyFull == null) {
            throw new IllegalStateException("No NIFTY daily history");
        }
        Series nifty = since(niftyFull, from);
        status.set("RUNNING | simulating " + data.size() + " symbols over " + nifty.size() + " sessions");

        StringBuilder out = new StringBuilder();
        out.append(String.format("SWING BACKTEST  %s to %s  |  %d sessions, %d stocks, eligible: top %d by turnover each day (point in time), long only%n",
                nifty.date()[0], nifty.date()[nifty.size() - 1], nifty.size(), data.size(), liquidRank));
        out.append(String.format("Rs %.0f capital, no leverage, max %d positions, Rs %.0f risk/position, next-open fills, "
                + "%.2f%% slippage/side, delivery charges. Survivorship: stocks delisted since are missing.%n%n",
                capital, maxPositions, riskPerTrade, slippagePct));

        double niftyReturn = nifty.close()[nifty.size() - 1] / nifty.close()[0] - 1;
        out.append(String.format("BENCHMARK  NIFTY buy-and-hold: %+.1f%% total, %+.1f%%/year, max DD %.1f%%%n%n",
                niftyReturn * 100, annualised(niftyReturn, nifty.size()), maxDrawdownPct(nifty.close())));

        LocalDate[] calendar = nifty.date();
        out.append(render("BREAKOUT (55-day high + volume, trail 20-day low)",
                simulateSignals(data, calendar, Strategy.BREAKOUT)));
        out.append(render("PULLBACK (RSI2 < 10 in an uptrend, exit above 5-day average)",
                simulateSignals(data, calendar, Strategy.PULLBACK)));
        out.append(renderRotation(rotation(data, calendar), calendar.length));

        out.append("MOMENTUM PORTFOLIOS (equal weight, point-in-time top-liquidity universe; 6m = 126 sessions, skip 1m = 21)\n");
        out.append("  market filter: at each rebalance, NIFTY's previous close below its N-day average = sell all, hold cash;"
                + " idle cash earns the liquid-fund yield shown (its own charges ignored)\n");
        for (PortfolioRule rule : List.of(
                new PortfolioRule("top 30 monthly, 6m-1m vol-adj, no costs", 30, true, 126, 21, true, 0, false),
                new PortfolioRule("top 30 monthly, 6m-1m vol-adj, Rs 0 brokerage", 30, true, 126, 21, true, 0, true),
                new PortfolioRule("top 30 monthly, 6m-1m vol-adj, Rs 20 brokerage", 30, true, 126, 21, true, 20, true),
                new PortfolioRule("top 20 monthly, 6m-1m vol-adj, Rs 0 brokerage", 20, true, 126, 21, true, 0, true),
                new PortfolioRule("top 10 monthly, 6m-1m vol-adj, Rs 0 brokerage", 10, true, 126, 21, true, 0, true),
                new PortfolioRule("top 30 monthly, 12m-1m vol-adj, Rs 0 brokerage", 30, true, 252, 21, true, 0, true),
                new PortfolioRule("top 30 monthly, 6m-1m raw return, Rs 0 brokerage", 30, true, 126, 21, false, 0, true),
                new PortfolioRule("top 30 + NIFTY>200DMA, cash 0%, Rs 0", 30, true, 126, 21, true, 0, true, 200, 0),
                new PortfolioRule("top 30 + NIFTY>200DMA, cash 6.5%, Rs 0", 30, true, 126, 21, true, 0, true, 200, 6.5),
                new PortfolioRule("top 20 + NIFTY>200DMA, cash 6.5%, Rs 0", 20, true, 126, 21, true, 0, true, 200, 6.5),
                new PortfolioRule("top 30 + NIFTY>100DMA, cash 6.5%, Rs 0", 30, true, 126, 21, true, 0, true, 100, 6.5),
                new PortfolioRule("top 30 + NIFTY>200DMA, cash 6.5%, Rs 20", 30, true, 126, 21, true, 20, true, 200, 6.5),
                new PortfolioRule("top 20 + NIFTY>200DMA, cash 6.5%, Rs 20", 20, true, 126, 21, true, 20, true, 200, 6.5))) {
            out.append(renderPortfolio(rule, momentumPortfolio(data, calendar, rule, niftyFull), calendar, nifty.close()));
        }
        out.append("\n");
        out.append("Go-live bar for swing: avg >= +0.30R net, PF >= 1.5, max DD <= 15% of capital, beats NIFTY.\n");
        return out.toString();
    }

    /** The part of a series from {@code from} on. */
    static Series since(Series s, LocalDate from) {
        int k = 0;
        while (k < s.size() && s.date()[k].isBefore(from)) {
            k++;
        }
        int n = s.size();
        return new Series(s.symbol(), java.util.Arrays.copyOfRange(s.date(), k, n),
                java.util.Arrays.copyOfRange(s.open(), k, n), java.util.Arrays.copyOfRange(s.high(), k, n),
                java.util.Arrays.copyOfRange(s.low(), k, n), java.util.Arrays.copyOfRange(s.close(), k, n),
                java.util.Arrays.copyOfRange(s.volume(), k, n));
    }

    /** Index close above its {@code n}-day average on {@code day}; true while that is not known yet. */
    static boolean aboveAverage(Series index, LocalDate day, int n) {
        int i = index.indexOf(day);
        if (i < n - 1) {
            return true;
        }
        return index.close()[i] > sma(index.close(), i, n);
    }

    private static Series toSeries(String symbol, List<Candle> candles) {
        List<Candle> list = new ArrayList<>();
        if (candles != null) {
            candles.stream().filter(c -> c.getDateTime() != null && c.getClose() != null && c.getOpen() != null)
                    .sorted(Comparator.comparing(Candle::getDateTime)).forEach(list::add);
        }
        int n = list.size();
        LocalDate[] d = new LocalDate[n];
        double[] o = new double[n], h = new double[n], l = new double[n], c = new double[n], v = new double[n];
        for (int i = 0; i < n; i++) {
            Candle k = list.get(i);
            d[i] = k.getDateTime().atZoneSameInstant(IST).toLocalDate();
            o[i] = k.getOpen().doubleValue();
            h[i] = k.getHigh().doubleValue();
            l[i] = k.getLow().doubleValue();
            c[i] = k.getClose().doubleValue();
            v[i] = k.getVolume() == null ? 0 : k.getVolume();
        }
        return new Series(symbol, d, o, h, l, c, v);
    }

    // ----------------------------------------------------------- indicators

    static double sma(double[] x, int end, int n) {
        if (end - n + 1 < 0) {
            return Double.NaN;
        }
        double s = 0;
        for (int i = end - n + 1; i <= end; i++) {
            s += x[i];
        }
        return s / n;
    }

    static double highest(double[] x, int fromInclusive, int toInclusive) {
        double m = Double.NEGATIVE_INFINITY;
        for (int i = Math.max(0, fromInclusive); i <= toInclusive; i++) {
            m = Math.max(m, x[i]);
        }
        return m;
    }

    static double lowest(double[] x, int fromInclusive, int toInclusive) {
        double m = Double.POSITIVE_INFINITY;
        for (int i = Math.max(0, fromInclusive); i <= toInclusive; i++) {
            m = Math.min(m, x[i]);
        }
        return m;
    }

    static double atr(Series s, int end, int n) {
        if (end - n < 0) {
            return Double.NaN;
        }
        double sum = 0;
        for (int i = end - n + 1; i <= end; i++) {
            double tr = Math.max(s.high()[i] - s.low()[i],
                    Math.max(Math.abs(s.high()[i] - s.close()[i - 1]), Math.abs(s.low()[i] - s.close()[i - 1])));
            sum += tr;
        }
        return sum / n;
    }

    /** Cutler RSI over n days ending at end. */
    static double rsi(double[] close, int end, int n) {
        if (end - n < 0) {
            return Double.NaN;
        }
        double up = 0, down = 0;
        for (int i = end - n + 1; i <= end; i++) {
            double d = close[i] - close[i - 1];
            if (d > 0) up += d; else down -= d;
        }
        if (down == 0) {
            return 100;
        }
        return 100 - 100 / (1 + up / down);
    }

    // ---------------------------------------------------------- simulation

    enum Strategy { BREAKOUT, PULLBACK }

    record Trade(String symbol, LocalDate entryDay, LocalDate exitDay, double entry, double exit,
                 int quantity, double riskRupees, double gross, double charges, String reason) {
        double net() {
            return gross - charges;
        }

        double netR() {
            return net() / riskRupees;
        }
    }

    private record Position(Series s, int entryIndex, double entry, double stop, int quantity, double risk) {
    }

    private record Signal(Series s, int index, double rank, double atr) {
    }

    List<Trade> simulateSignals(Map<String, Series> data, LocalDate[] calendar, Strategy strategy) {
        List<Trade> trades = new ArrayList<>();
        Map<String, Position> open = new HashMap<>();
        double cash = capital;

        for (int day = 101; day < calendar.length - 1; day++) {
            LocalDate today = calendar[day];
            LocalDate tomorrow = calendar[day + 1];

            // 1. Exits decided on today's close, filled at tomorrow's open; stops intraday.
            for (Position p : new ArrayList<>(open.values())) {
                Series s = p.s();
                int i = s.indexOf(today);
                int next = s.indexOf(tomorrow);
                if (i < 0 || next < 0) {
                    continue;
                }
                String reason = null;
                double fill = 0;
                if (s.low()[i] <= p.stop() && i >= p.entryIndex()) {
                    reason = "STOP";
                    fill = Math.min(s.open()[i], p.stop());     // a gap below the stop fills at the open
                } else if (strategy == Strategy.BREAKOUT && s.close()[i] < lowest(s.low(), i - 20, i - 1)) {
                    reason = "TRAIL_20D_LOW";
                    fill = s.open()[next];
                } else if (strategy == Strategy.PULLBACK
                        && (s.close()[i] > sma(s.close(), i, 5) || i - p.entryIndex() >= 10)) {
                    reason = i - p.entryIndex() >= 10 ? "TIME_10D" : "ABOVE_SMA5";
                    fill = s.open()[next];
                }
                if (reason != null) {
                    double exit = fill * (1 - slippagePct / 100);
                    double gross = (exit - p.entry()) * p.quantity();
                    double charges = DeliveryCharges.roundTrip(p.entry(), exit, p.quantity());
                    trades.add(new Trade(s.symbol(), s.date()[p.entryIndex()], "STOP".equals(reason) ? today : tomorrow,
                            p.entry(), exit, p.quantity(), p.risk(), gross, charges, reason));
                    cash += p.quantity() * exit - charges;
                    open.remove(s.symbol());
                }
            }

            // 2. New signals on today's close, bought at tomorrow's open.
            List<Signal> signals = new ArrayList<>();
            for (Series s : data.values()) {
                if (open.containsKey(s.symbol())) {
                    continue;
                }
                int i = s.indexOf(today);
                if (i < 101 || s.indexOf(tomorrow) < 0) {
                    continue;
                }
                double close = s.close()[i];
                double turnover = sma(s.volume(), i, 20) * close;
                if (close < 50 || turnover < minTurnoverCrore * 1e7 || !eligible(data, s.symbol(), today)) {
                    continue;
                }
                double sma100 = sma(s.close(), i, 100);
                if (!(close > sma100)) {
                    continue;
                }
                double a = atr(s, i, 14);
                if (strategy == Strategy.BREAKOUT) {
                    double volRatio = s.volume()[i] / sma(s.volume(), i - 1, 20);
                    if (close > highest(s.high(), i - 55, i - 1) && volRatio >= 1.5) {
                        signals.add(new Signal(s, i, volRatio, a));
                    }
                } else {
                    double r2 = rsi(s.close(), i, 2);
                    if (r2 < 10) {
                        signals.add(new Signal(s, i, -r2, a));
                    }
                }
            }
            signals.sort(Comparator.comparingDouble(Signal::rank).reversed());

            for (Signal sig : signals) {
                if (open.size() >= maxPositions) {
                    break;
                }
                Series s = sig.s();
                int next = s.indexOf(tomorrow);
                double entry = s.open()[next] * (1 + slippagePct / 100);
                double stopDistance = (strategy == Strategy.BREAKOUT ? 2.0 : 2.5) * sig.atr();
                if (!(stopDistance > 0)) {
                    continue;
                }
                double maxNotional = Math.min(cash, capital / maxPositions * 1.5);
                int qty = (int) Math.min(Math.floor(riskPerTrade / stopDistance), Math.floor(maxNotional / entry));
                if (qty < 1) {
                    continue;
                }
                cash -= qty * entry;
                open.put(s.symbol(), new Position(s, next, entry, entry - stopDistance, qty, qty * stopDistance));
            }
        }
        // Close what is still open at the last close.
        for (Position p : open.values()) {
            Series s = p.s();
            int last = s.size() - 1;
            double exit = s.close()[last] * (1 - slippagePct / 100);
            trades.add(new Trade(s.symbol(), s.date()[p.entryIndex()], s.date()[last], p.entry(), exit, p.quantity(),
                    p.risk(), (exit - p.entry()) * p.quantity(), DeliveryCharges.roundTrip(p.entry(), exit, p.quantity()),
                    "END"));
        }
        return trades;
    }

    /**
     * Weekly rotation: on the first session of each week, sell at the open what left
     * the top 5 (3-month return excluding the last week, above the 100-day average,
     * ranked on the previous close) and buy what joined, equal weight. Returns the
     * portfolio's net return for each week, keyed by the week's first session.
     */
    TreeMap<LocalDate, Double> rotation(Map<String, Series> data, LocalDate[] calendar) {
        List<Integer> rebalanceDays = new ArrayList<>();
        for (int d = 102; d < calendar.length; d++) {
            if (calendar[d].getDayOfWeek().getValue() < calendar[d - 1].getDayOfWeek().getValue()
                    || java.time.temporal.ChronoUnit.DAYS.between(calendar[d - 1], calendar[d]) > 3) {
                rebalanceDays.add(d);
            }
        }
        TreeMap<LocalDate, Double> weekly = new TreeMap<>();
        List<String> held = new ArrayList<>();
        for (int k = 0; k + 1 < rebalanceDays.size(); k++) {
            int r = rebalanceDays.get(k);
            int r2 = rebalanceDays.get(k + 1);
            LocalDate signalDay = calendar[r - 1];

            List<Map.Entry<String, Double>> ranked = new ArrayList<>();
            for (Series s : data.values()) {
                int i = s.indexOf(signalDay);
                if (i < 101 || s.indexOf(calendar[r]) < 0) {
                    continue;
                }
                double close = s.close()[i];
                if (close < 50 || sma(s.volume(), i, 20) * close < minTurnoverCrore * 1e7
                        || !eligible(data, s.symbol(), signalDay)
                        || !(close > sma(s.close(), i, 100))) {
                    continue;
                }
                ranked.add(Map.entry(s.symbol(), s.close()[i - 5] / s.close()[i - 68] - 1));
            }
            ranked.sort(Map.Entry.<String, Double>comparingByValue().reversed());
            List<String> target = ranked.stream().limit(maxPositions).map(Map.Entry::getKey).toList();

            // Costs as a fraction of the portfolio: each slot is 1/maxPositions of it.
            double slot = capital / maxPositions;
            double costs = 0;
            for (String sym : held) {
                if (!target.contains(sym)) {
                    costs += DeliveryCharges.sellSide(slot) + slot * slippagePct / 100;
                }
            }
            for (String sym : target) {
                if (!held.contains(sym)) {
                    costs += DeliveryCharges.buySide(slot) + slot * slippagePct / 100;
                }
            }
            double gross = 0;
            for (String sym : target) {
                Series s = data.get(sym);
                int a = s.indexOf(calendar[r]);
                int b = s.indexOf(calendar[r2]);
                gross += a < 0 || b < 0 ? 0 : s.open()[b] / s.open()[a] - 1;
            }
            double weekReturn = (target.isEmpty() ? 0 : gross / maxPositions) - costs / capital;
            weekly.put(calendar[r], weekReturn);
            held = new ArrayList<>(target);
        }
        return weekly;
    }

    // -------------------------------------------------------------- report

    private String render(String title, List<Trade> trades) {
        StringBuilder out = new StringBuilder(title).append("\n");
        if (trades.isEmpty()) {
            return out.append("  no trades\n\n").toString();
        }
        trades.sort(Comparator.comparing(Trade::exitDay));
        double net = 0, gain = 0, loss = 0, sumR = 0, wins = 0, peak = 0, equity = 0, maxDd = 0, charges = 0, holdDays = 0;
        Map<YearMonth, Double> monthly = new TreeMap<>();
        Map<Integer, double[]> yearly = new TreeMap<>();
        for (Trade t : trades) {
            net += t.net();
            charges += t.charges();
            sumR += t.netR();
            holdDays += java.time.temporal.ChronoUnit.DAYS.between(t.entryDay(), t.exitDay());
            if (t.net() > 0) {
                wins++;
                gain += t.net();
            } else {
                loss -= t.net();
            }
            equity += t.net();
            peak = Math.max(peak, equity);
            maxDd = Math.max(maxDd, peak - equity);
            monthly.merge(YearMonth.from(t.exitDay()), t.net(), Double::sum);
            yearly.computeIfAbsent(t.exitDay().getYear(), y -> new double[2]);
            yearly.get(t.exitDay().getYear())[0] += t.net();
            yearly.get(t.exitDay().getYear())[1]++;
        }
        int n = trades.size();
        long positiveMonths = monthly.values().stream().filter(v -> v > 0).count();
        out.append(String.format("  trades %d | win %.0f%% | avg %+.2fR net | PF %.2f | net Rs %.0f (%+.1f%% of capital) | "
                        + "charges Rs %.0f | max DD Rs %.0f (%.1f%%) | avg hold %.1f days%n",
                n, wins / n * 100, sumR / n, loss > 0 ? gain / loss : 99, net, net / capital * 100, charges,
                maxDd, maxDd / capital * 100, holdDays / n));
        out.append(String.format("  per month: avg Rs %.0f | positive months %d of %d%n",
                monthly.values().stream().mapToDouble(Double::doubleValue).average().orElse(0),
                positiveMonths, monthly.size()));
        yearly.forEach((y, v) -> out.append(String.format("  %d: Rs %8.0f over %3.0f trades%n", y, v[0], v[1])));
        Map<String, double[]> byReason = new TreeMap<>();
        trades.forEach(t -> {
            double[] a = byReason.computeIfAbsent(t.reason(), k -> new double[2]);
            a[0] += t.netR();
            a[1]++;
        });
        byReason.forEach((k, v) -> out.append(String.format("  exit %-14s n=%4.0f avg %+.2fR%n", k, v[1], v[0] / v[1])));
        out.append("  monthly Rs:");
        monthly.forEach((m, v) -> out.append(String.format(" %s %.0f |", m, v)));
        return out.append("\n\n").toString();
    }

    /**
     * A momentum portfolio: how many stocks, how often, how ranked, at what costs.
     * {@code regimeSma} > 0 holds cash while NIFTY is below that average (checked at
     * each rebalance); idle cash earns {@code cashYieldPct} a year.
     */
    record PortfolioRule(String name, int top, boolean monthly, int lookback, int skip, boolean volAdjusted,
                         double brokerage, boolean costs, int regimeSma, double cashYieldPct) {
        PortfolioRule(String name, int top, boolean monthly, int lookback, int skip, boolean volAdjusted,
                      double brokerage, boolean costs) {
            this(name, top, monthly, lookback, skip, volAdjusted, brokerage, costs, 0, 0);
        }
    }

    /** Periods spent fully in cash by the market filter, per rule name (for the report). */
    private final Map<String, Integer> cashPeriods = new ConcurrentHashMap<>();

    /**
     * Momentum portfolio held in equal weight and rebalanced on the first session of
     * each month (or week): ranked on the previous close by the return over
     * {@code lookback} sessions excluding the last {@code skip}, optionally divided by
     * the daily volatility over the same window (as NSE's momentum indices do). Only
     * that day's top-liquidity stocks qualify. Equity is tracked in rupees so the
     * fixed costs per order (brokerage, DP charge) weigh as they would on ₹1 lakh.
     * Returns each period's net return keyed by its first session.
     */
    TreeMap<LocalDate, Double> momentumPortfolio(Map<String, Series> data, LocalDate[] calendar, PortfolioRule rule) {
        return momentumPortfolio(data, calendar, rule, null);
    }

    TreeMap<LocalDate, Double> momentumPortfolio(Map<String, Series> data, LocalDate[] calendar, PortfolioRule rule,
                                                 Series index) {
        int warmup = rule.lookback() + 2;
        List<Integer> rebalanceDays = new ArrayList<>();
        for (int d = warmup; d < calendar.length; d++) {
            boolean newPeriod = rule.monthly()
                    ? calendar[d].getMonthValue() != calendar[d - 1].getMonthValue()
                    : calendar[d].getDayOfWeek().getValue() < calendar[d - 1].getDayOfWeek().getValue()
                    || java.time.temporal.ChronoUnit.DAYS.between(calendar[d - 1], calendar[d]) > 3;
            if (newPeriod) {
                rebalanceDays.add(d);
            }
        }
        TreeMap<LocalDate, Double> periods = new TreeMap<>();
        double equity = capital;
        int inCash = 0;
        List<String> held = new ArrayList<>();
        for (int k = 0; k + 1 < rebalanceDays.size(); k++) {
            int r = rebalanceDays.get(k);
            int r2 = rebalanceDays.get(k + 1);
            LocalDate signalDay = calendar[r - 1];
            boolean riskOn = rule.regimeSma() <= 0 || index == null
                    || aboveAverage(index, signalDay, rule.regimeSma());

            List<Map.Entry<String, Double>> ranked = new ArrayList<>();
            for (Series s : data.values()) {
                int i = s.indexOf(signalDay);
                if (i < rule.lookback() + 1 || s.indexOf(calendar[r]) < 0 || s.close()[i] < 50
                        || !eligible(data, s.symbol(), signalDay)) {
                    continue;
                }
                int end = i - rule.skip();
                int start = i - rule.lookback();
                double ret = s.close()[end] / s.close()[start] - 1;
                double score = ret;
                if (rule.volAdjusted()) {
                    double sum = 0, sq = 0;
                    int n = 0;
                    for (int j = start + 1; j <= end; j++) {
                        double dr = s.close()[j] / s.close()[j - 1] - 1;
                        sum += dr;
                        sq += dr * dr;
                        n++;
                    }
                    double sd = n > 1 ? Math.sqrt(Math.max(0, sq / n - (sum / n) * (sum / n))) : 0;
                    score = sd > 0 ? ret / sd : ret;
                }
                ranked.add(Map.entry(s.symbol(), score));
            }
            ranked.sort(Map.Entry.<String, Double>comparingByValue().reversed());
            List<String> target = riskOn
                    ? ranked.stream().limit(rule.top()).map(Map.Entry::getKey).toList()
                    : List.of();
            if (!riskOn) {
                inCash++;
            }

            double start = equity;
            double slot = equity / rule.top();
            double costs = 0;
            if (rule.costs()) {
                for (String sym : held) {
                    if (!target.contains(sym)) {
                        costs += DeliveryCharges.sellSide(slot, rule.brokerage()) + slot * slippagePct / 100;
                    }
                }
                for (String sym : target) {
                    if (!held.contains(sym)) {
                        costs += DeliveryCharges.buySide(slot, rule.brokerage()) + slot * slippagePct / 100;
                    }
                }
            }
            double invested = 0;
            double value = 0;
            for (String sym : target) {
                Series s = data.get(sym);
                int a = s.indexOf(calendar[r]);
                int b = s.indexOf(calendar[r2]);
                invested += slot;
                value += a < 0 || b < 0 ? slot : slot * s.open()[b] / s.open()[a];
            }
            long days = java.time.temporal.ChronoUnit.DAYS.between(calendar[r], calendar[r2]);
            double interest = (start - invested) * rule.cashYieldPct() / 100 * days / 365;
            equity = equity - invested + value - costs + interest;
            periods.put(calendar[r], equity / start - 1);
            held = new ArrayList<>(target);
        }
        cashPeriods.put(rule.name(), inCash);
        return periods;
    }

    private String renderPortfolio(PortfolioRule rule, TreeMap<LocalDate, Double> periods, LocalDate[] calendar,
                                   double[] niftyClose) {
        if (periods.isEmpty()) {
            return "  " + rule.name() + ": no data\n";
        }
        double equity = 1, peak = 1, maxDd = 0;
        Map<YearMonth, Double> monthly = new TreeMap<>();
        for (Map.Entry<LocalDate, Double> p : periods.entrySet()) {
            double before = equity;
            equity *= 1 + p.getValue();
            peak = Math.max(peak, equity);
            maxDd = Math.max(maxDd, (peak - equity) / peak);
            monthly.merge(YearMonth.from(p.getKey()), (equity - before) * capital, Double::sum);
        }
        // NIFTY over the same window, for a like-for-like comparison.
        int first = java.util.Arrays.binarySearch(calendar, periods.firstKey());
        double niftySame = niftyClose[niftyClose.length - 1] / niftyClose[Math.max(0, first)] - 1;
        int sessions = calendar.length - Math.max(0, first);
        double total = equity - 1;
        return String.format("  %-46s total %+7.1f%% | %+6.1f%%/yr | max DD %5.1f%% | +months %2d/%2d | avg month Rs %6.0f"
                        + " | worst month %+5.1f%% | in cash %2d | NIFTY same window %+6.1f%%/yr%n",
                rule.name(), total * 100, annualised(total, sessions), maxDd * 100,
                monthly.values().stream().filter(v -> v > 0).count(), monthly.size(),
                monthly.values().stream().mapToDouble(Double::doubleValue).average().orElse(0),
                periods.values().stream().mapToDouble(Double::doubleValue).min().orElse(0) * 100,
                cashPeriods.getOrDefault(rule.name(), 0), annualised(niftySame, sessions));
    }

    private String renderRotation(TreeMap<LocalDate, Double> weekly, int sessions) {
        StringBuilder out = new StringBuilder("ROTATION (top 5 by 3-month strength, weekly rebalance)\n");
        if (weekly.isEmpty()) {
            return out.append("  no data\n\n").toString();
        }
        double equity = 1, peak = 1, maxDd = 0;
        Map<YearMonth, Double> monthly = new TreeMap<>();
        for (Map.Entry<LocalDate, Double> w : weekly.entrySet()) {
            double before = equity;
            equity *= 1 + w.getValue();
            peak = Math.max(peak, equity);
            maxDd = Math.max(maxDd, (peak - equity) / peak);
            monthly.merge(YearMonth.from(w.getKey()), (equity - before) * capital, Double::sum);
        }
        double total = equity - 1;
        out.append(String.format("  weeks %d | total %+.1f%% | %+.1f%%/year | max DD %.1f%% | positive months %d of %d | "
                        + "avg month Rs %.0f on Rs %.0f%n",
                weekly.size(), total * 100, annualised(total, sessions - 101), maxDd * 100,
                monthly.values().stream().filter(v -> v > 0).count(), monthly.size(),
                monthly.values().stream().mapToDouble(Double::doubleValue).average().orElse(0), capital));
        out.append("  monthly Rs:");
        monthly.forEach((m, v) -> out.append(String.format(" %s %.0f |", m, v)));
        return out.append("\n\n").toString();
    }

    private static double annualised(double total, int sessions) {
        return sessions <= 0 ? 0 : (Math.pow(1 + total, 250.0 / sessions) - 1) * 100;
    }

    private static double maxDrawdownPct(double[] close) {
        double peak = close[0], dd = 0;
        for (double c : close) {
            peak = Math.max(peak, c);
            dd = Math.max(dd, (peak - c) / peak);
        }
        return dd * 100;
    }

    /** Indian equity delivery charges (Upstox-style flat Rs 20 brokerage per order). */
    static final class DeliveryCharges {
        static final double BROKERAGE = 20.0;
        static final double STT_PCT = 0.1;
        static final double EXCHANGE_PCT = 0.00297;
        static final double SEBI_PCT = 0.0001;
        static final double STAMP_BUY_PCT = 0.015;
        static final double DP_PER_SELL = 18.5;
        static final double GST_PCT = 18.0;

        private DeliveryCharges() {
        }

        static double buySide(double value) {
            return buySide(value, BROKERAGE);
        }

        static double sellSide(double value) {
            return sellSide(value, BROKERAGE);
        }

        static double buySide(double value, double brokerage) {
            double fees = brokerage + value * (EXCHANGE_PCT + SEBI_PCT) / 100;
            return fees + value * (STT_PCT + STAMP_BUY_PCT) / 100 + fees * GST_PCT / 100;
        }

        static double sellSide(double value, double brokerage) {
            double fees = brokerage + DP_PER_SELL + value * (EXCHANGE_PCT + SEBI_PCT) / 100;
            return fees + value * STT_PCT / 100 + fees * GST_PCT / 100;
        }

        static double roundTrip(double entry, double exit, int quantity) {
            return buySide(entry * quantity) + sellSide(exit * quantity);
        }
    }
}
