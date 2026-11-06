package com.ram.trading.signal.engine.service.fadebook;

import com.ram.trading.signal.engine.client.NotificationClient;
import com.ram.trading.signal.engine.dto.notification.NotificationChannel;
import com.ram.trading.signal.engine.dto.notification.NotificationRequest;
import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import com.ram.trading.signal.engine.entity.FadePaperTrade;
import com.ram.trading.signal.engine.repo.FadePaperTradeRepository;
import com.ram.trading.signal.engine.service.charges.IntradayChargesCalculator;
import com.ram.trading.signal.engine.service.replay.DailyBars;
import com.ram.trading.signal.engine.service.replay.OpeningScreen;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The FADE paper book (Round 3 lead, 2026-11-03): at 09:45 short the stocks among
 * turnover ranks 501-600 that are up >= 2% from the open vs NIFTY, biggest move first,
 * at most {@code positions} of them at Rs {@code notional} each, and hold to 15:00.
 *
 * Loss limits, checked on every completed minute candle:
 *  - each short has a stop {@code stop-pct} above entry (a gap through it fills at the open),
 *  - the DAILY STOP: once realized + open P&L (after slippage and estimated charges)
 *    reaches -{@code daily-stop}, every short is covered at that minute's close and the
 *    book is done for the day.
 *
 * Same selection ({@link OpeningScreen}) and the same minute-by-minute rules as the
 * replay's RiskBookStudy, so paper results can be compared with the replay directly.
 * Paper only: its own table, no orders, no balance-margin calls, no effect on the live
 * engine (whose rules stay frozen). Progress is stored per trade, so a restart resumes
 * from the last processed minute.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FadePaperBookService {

    static final String OPEN = "OPEN";
    static final String CLOSED = "CLOSED";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    /** A restart may still enter from the 09:45 screen until then (later minutes are replayed). */
    private static final LocalTime LAST_ENTRY = LocalTime.of(9, 55);

    private final JdbcTemplate jdbc;
    private final FadePaperTradeRepository repository;
    private final IntradayChargesCalculator charges;
    private final NotificationClient notificationClient;

    private Clock clock = Clock.system(IST);

    @Value("${trading.fade-book.enabled:true}")
    private boolean enabled = true;

    @Value("${trading.fade-book.positions:5}")
    private int positions = 5;

    @Value("${trading.fade-book.notional:40000}")
    private double notional = 40_000;

    @Value("${trading.fade-book.stop-pct:2}")
    private double stopPct = 2;

    /** Rs; 0 = none. */
    @Value("${trading.fade-book.daily-stop:1500}")
    private double dailyStop = 1500;

    /** Rs; 0 = none. */
    @Value("${trading.fade-book.take-profit:0}")
    private double takeProfit = 0;

    @Value("${trading.fade-book.nifty-up-only:false}")
    private boolean niftyUpOnly = false;

    @Value("${trading.fade-book.exit:15:00}")
    private LocalTime exit = LocalTime.of(15, 0);

    @Value("${paper.trading.slippage-pct:0.03}")
    private double slippagePct = 0.03;

    @Value("${trading.liquidity.min-price:50}")
    private double minPrice = 50;

    @Value("${trading.liquidity.min-average-turnover-crore:5}")
    private double minTurnoverCrore = 5;

    // The pool (Round 4 rows map onto these): 1-based turnover ranks, inclusive.
    @Value("${trading.fade-book.pool-from-rank:501}")
    private int poolFromRank = 501;

    @Value("${trading.fade-book.pool-to-rank:600}")
    private int poolToRank = 600;

    @Value("${trading.fade-book.min-move:2}")
    private double minMove = 2;

    /** % vs NIFTY; 0 = no maximum. */
    @Value("${trading.fade-book.max-move:0}")
    private double maxMove = 0;

    /** Last 5 sessions' turnover / the 20-session average; 0 = no filter. */
    @Value("${trading.fade-book.min-turnover-spike:0}")
    private double minSpike = 0;

    @Value("${trading.fade-book.max-turnover-spike:0}")
    private double maxSpike = 0;

    /** Gap from yesterday's close, %. */
    @Value("${trading.fade-book.min-gap:-100}")
    private double minGap = -100;

    @Value("${trading.fade-book.max-gap:100}")
    private double maxGap = 100;

    /** Day the 09:45 screen last ran (with or without picks), so it runs once a day. */
    private volatile LocalDate screenedDay;

    /** The rules one day runs under. positions 0 = every candidate; stopPct 0 = no stop. */
    record Settings(int positions, double notional, double stopPct, double dailyStop, double takeProfit,
                    boolean niftyUpOnly, double slippagePct) {

        String label() {
            return String.format("%sx%.0f stop %s day %s%s%s", positions > 0 ? String.valueOf(positions) : "all",
                    notional, stopPct > 0 ? trim(stopPct) + "%" : "none",
                    dailyStop > 0 ? trim(dailyStop) : "none",
                    takeProfit > 0 ? " tp " + trim(takeProfit) : "", niftyUpOnly ? " nifty-up" : "");
        }
    }

    /** Which stocks are candidates at 09:45. Ranks 0-based, to exclusive. */
    record Pool(int from, int to, double minMove, double maxMove, double minSpike, double maxSpike,
                double minGap, double maxGap) {

        static final Pool ROUND3 = new Pool(500, 600, 2, 0, 0, 0, -100, 100);

        boolean needsSpike() {
            return minSpike > 0 || maxSpike > 0;
        }

        boolean accepts(OpeningScreen.Ranked r, double spike) {
            double gap = OpeningScreen.gapPct(r);
            return r.rank() >= from && r.rank() < to && r.rel() >= minMove && (maxMove <= 0 || r.rel() < maxMove)
                    && (minSpike <= 0 || spike >= minSpike) && (maxSpike <= 0 || spike < maxSpike)
                    && gap >= minGap && gap < maxGap;
        }

        String label() {
            StringBuilder s = new StringBuilder(String.format("ranks %d-%d up >= %s%%", from + 1, to, trim(minMove)));
            if (maxMove > 0) {
                s.append(" < ").append(trim(maxMove)).append('%');
            }
            if (minSpike > 0) {
                s.append(" spike >= ").append(trim(minSpike));
            }
            if (maxSpike > 0) {
                s.append(" spike < ").append(trim(maxSpike));
            }
            if (minGap > -100) {
                s.append(" gap >= ").append(trim(minGap)).append('%');
            }
            if (maxGap < 100) {
                s.append(" gap < ").append(trim(maxGap)).append('%');
            }
            return s.toString();
        }
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    Settings settings() {
        return new Settings(positions, notional, stopPct, dailyStop, takeProfit, niftyUpOnly, slippagePct);
    }

    Pool pool() {
        return new Pool(poolFromRank - 1, poolToRank, minMove, maxMove, minSpike, maxSpike, minGap, maxGap);
    }

    /**
     * The pool's candidates, biggest move first, as a screen; null when the liquid
     * stocks do not reach half the pool. {@code spike} is only asked when the pool filters on it.
     */
    static OpeningScreen.Screen select(OpeningScreen.Ranking ranking, Pool pool,
                                       java.util.function.ToDoubleFunction<OpeningScreen.Ranked> spike) {
        if (ranking == null || ranking.ranked().size() < pool.from() + (pool.to() - pool.from()) / 2) {
            return null;
        }
        List<OpeningScreen.Ranked> fade = ranking.ranked().stream()
                .filter(r -> r.rank() >= pool.from() && r.rank() < pool.to() && r.rel() >= pool.minMove())
                .filter(r -> pool.accepts(r, pool.needsSpike() ? spike.applyAsDouble(r) : 0))
                .sorted(Comparator.comparingDouble(OpeningScreen.Ranked::rel).reversed())
                .toList();
        return new OpeningScreen.Screen(ranking.niftyMove(), ranking.ranked().size(), fade, List.of());
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    // ------------------------------------------------------------ schedule

    /** 09:46:40: the 09:45 candle is complete and stored. */
    @Scheduled(cron = "40 46 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void enterAtScreen() {
        if (!enabled) {
            return;
        }
        try {
            enter();
        } catch (Exception ex) {
            log.error("FADE BOOK | Entry failed", ex);
        }
    }

    /** Every minute: stops and the daily stop on the candles completed since the last run. */
    @Scheduled(cron = "20 * 9-14 * * MON-FRI", zone = "Asia/Kolkata")
    public void monitor() {
        if (!enabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (now.toLocalTime().isBefore(OpeningScreen.ENTRY.plusMinutes(2))) {
            return;
        }
        try {
            // Catch-up after a restart during the entry window.
            if (now.toLocalTime().isBefore(LAST_ENTRY) && !now.toLocalDate().equals(screenedDay)) {
                enter();
            }
            process(now.toLocalDate(), now.truncatedTo(ChronoUnit.MINUTES).toLocalTime());
        } catch (Exception ex) {
            log.error("FADE BOOK | Monitor failed", ex);
        }
    }

    /** 15:00:20: last stop checks through the 14:59 candle, then cover what is left. */
    @Scheduled(cron = "20 0 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void squareOff() {
        if (!enabled) {
            return;
        }
        try {
            LocalDate day = LocalDate.now(clock);
            process(day, exit);
            List<FadePaperTrade> open = repository.findByTradeDateAndStatus(day, OPEN);
            LocalDateTime at = day.atTime(exit);
            open.forEach(t -> close(t, t.getLastPrice(), at, "TIME", settings(), charges));
            repository.saveAll(open);
            List<FadePaperTrade> all = repository.findByTradeDate(day);
            if (!all.isEmpty()) {
                notifySlack("FADE book " + day + ": Rs " + String.format("%.0f", dayNet(all)), toText(day));
            }
        } catch (Exception ex) {
            log.error("FADE BOOK | Square-off failed", ex);
        }
    }

    // ------------------------------------------------------------ entry

    /** Runs today's 09:45 screen and opens the shorts; nothing when today already has trades. */
    public List<FadePaperTrade> enter() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate day = now.toLocalDate();
        if (!repository.findByTradeDate(day).isEmpty()) {
            screenedDay = day;
            return List.of();
        }
        Map<String, List<MinuteCandle>> series = loadCandles(day, MARKET_OPEN, OpeningScreen.ENTRY);
        List<MinuteCandle> nifty = series.remove("NIFTY");
        series.remove("BANKNIFTY");
        DailyBars daily = DailyBars.load(jdbc, null, day.minusDays(45), day);
        Pool pool = pool();
        OpeningScreen.Screen screen = select(
                OpeningScreen.rank(day, series, nifty, daily, minPrice, minTurnoverCrore), pool,
                r -> OpeningScreen.turnoverSpike(daily, r, day));
        if (screen == null) {
            // Retried by the monitor until LAST_ENTRY (candles may still be arriving).
            log.warn("FADE BOOK | No screen for {} | Stocks with candles={} | NIFTY candles={}",
                    day, series.size(), nifty == null ? 0 : nifty.size());
            return List.of();
        }
        screenedDay = day;

        Settings s = settings();
        List<FadePaperTrade> trades = open(day, screen, s, charges);
        repository.saveAll(trades);
        log.info("FADE BOOK | {} | NIFTY {}% at 09:45 | Liquid={} | Candidates={} | Shorts={} | Book={} | Pool={}",
                day, String.format("%+.2f", screen.niftyMove()), screen.liquid(), screen.fade().size(),
                trades.size(), s.label(), pool.label());
        notifySlack("FADE book: " + trades.size() + " short(s) at 09:45", entryText(day, screen, trades, s, pool));
        return trades;
    }

    /** The shorts a screen opens under the given settings (not saved). */
    static List<FadePaperTrade> open(LocalDate day, OpeningScreen.Screen screen, Settings s,
                                     IntradayChargesCalculator charges) {
        List<FadePaperTrade> trades = new ArrayList<>();
        if (s.niftyUpOnly() && screen.niftyMove() <= 0) {
            return trades;
        }
        LocalDateTime at = day.atTime(OpeningScreen.ENTRY);
        for (OpeningScreen.Ranked r : screen.fade()) {
            if (s.positions() > 0 && trades.size() >= s.positions()) {
                break;
            }
            double entry = r.price() * (1 - s.slippagePct() / 100);
            int qty = (int) Math.floor(s.notional() / entry);
            if (qty < 1) {
                continue;
            }
            trades.add(FadePaperTrade.builder()
                    .tradeDate(day)
                    .symbol(r.symbol())
                    .turnoverRank(r.rank() + 1)
                    .relMovePct(round(r.rel()))
                    .niftyMovePct(round(screen.niftyMove()))
                    .quantity(qty)
                    .entryPrice(round(entry))
                    .entryTime(at.plusMinutes(1))
                    .stopLoss(s.stopPct() > 0 ? round(entry * (1 + s.stopPct() / 100)) : null)
                    .lastPrice(r.price())
                    .lastMinute(at)
                    .status(OPEN)
                    .book(s.label())
                    .build());
        }
        return trades;
    }

    // ------------------------------------------------------------ minutes

    /** Applies every completed candle from the last processed minute up to (not including) {@code before}. */
    void process(LocalDate day, LocalTime before) {
        List<FadePaperTrade> open = repository.findByTradeDateAndStatus(day, OPEN);
        if (open.isEmpty()) {
            return;
        }
        LocalTime from = open.stream().map(t -> t.getLastMinute().toLocalTime()).min(Comparator.naturalOrder())
                .orElse(OpeningScreen.ENTRY).plusMinutes(1);
        if (!from.isBefore(before)) {
            return;
        }
        Map<String, List<MinuteCandle>> bars = loadCandles(day, from, before.minusMinutes(1),
                open.stream().map(FadePaperTrade::getSymbol).toList());
        double realized = repository.findByTradeDateAndStatus(day, CLOSED).stream()
                .mapToDouble(t -> t.getNetPnl() == null ? 0 : t.getNetPnl()).sum();

        List<FadePaperTrade> changed = advance(open, bars, realized, settings(), charges);
        repository.saveAll(open);
        changed.stream().filter(t -> "DAY_STOP".equals(t.getExitReason()) || "TAKE_PROFIT".equals(t.getExitReason()))
                .findFirst()
                .ifPresent(t -> notifySlack("FADE book " + t.getExitReason().replace('_', ' ').toLowerCase()
                        + " at " + t.getExitTime().toLocalTime(), toText(day)));
        changed.forEach(t -> log.info("FADE BOOK | Closed {} | {} | exit {} | net Rs {}",
                t.getSymbol(), t.getExitReason(), t.getExitPrice(), t.getNetPnl()));
    }

    /**
     * The replay's minute loop on live candles: per short, a stop touched by the high
     * closes it at the stop (or the open on a gap); then the book's realized + open P&L
     * is checked against the daily stop and take profit. Mutates the trades; returns
     * those closed.
     */
    static List<FadePaperTrade> advance(List<FadePaperTrade> open, Map<String, List<MinuteCandle>> bars,
                                        double realized, Settings s, IntradayChargesCalculator charges) {
        TreeMap<LocalDateTime, Map<String, MinuteCandle>> byMinute = new TreeMap<>();
        bars.forEach((symbol, list) -> list.forEach(c ->
                byMinute.computeIfAbsent(c.getMinute(), k -> new HashMap<>()).put(symbol, c)));

        List<FadePaperTrade> closed = new ArrayList<>();
        for (Map.Entry<LocalDateTime, Map<String, MinuteCandle>> m : byMinute.entrySet()) {
            LocalDateTime minute = m.getKey();
            for (FadePaperTrade t : open) {
                if (!OPEN.equals(t.getStatus()) || !minute.isAfter(t.getLastMinute())) {
                    continue;
                }
                MinuteCandle c = m.getValue().get(t.getSymbol());
                if (c == null) {
                    continue;
                }
                t.setLastMinute(minute);
                if (t.getStopLoss() != null && c.getHigh() >= t.getStopLoss()) {
                    close(t, Math.max(t.getStopLoss(), c.getOpen()), minute.plusMinutes(1), "STOP", s, charges);
                    realized += t.getNetPnl();
                    closed.add(t);
                    continue;
                }
                t.setLastPrice(c.getClose());
            }
            double book = realized;
            for (FadePaperTrade t : open) {
                if (OPEN.equals(t.getStatus())) {
                    book += mark(t, t.getLastPrice(), s, charges);
                }
            }
            boolean stop = s.dailyStop() > 0 && book <= -s.dailyStop();
            boolean profit = s.takeProfit() > 0 && book >= s.takeProfit();
            if (stop || profit) {
                for (FadePaperTrade t : open) {
                    if (OPEN.equals(t.getStatus())) {
                        close(t, t.getLastPrice(), minute.plusMinutes(1), stop ? "DAY_STOP" : "TAKE_PROFIT", s, charges);
                        closed.add(t);
                    }
                }
                break;
            }
        }
        return closed;
    }

    /** Open P&L if covered at {@code ref} now (slippage against, charges estimated at entry). */
    static double mark(FadePaperTrade t, double ref, Settings s, IntradayChargesCalculator charges) {
        double out = ref * (1 + s.slippagePct() / 100);
        double est = charges.roundTrip("SELL", t.getEntryPrice(), t.getEntryPrice(), t.getQuantity()).getTotal();
        return (t.getEntryPrice() - out) * t.getQuantity() - est;
    }

    static void close(FadePaperTrade t, double ref, LocalDateTime at, String reason, Settings s,
                      IntradayChargesCalculator charges) {
        double out = ref * (1 + s.slippagePct() / 100);
        double cost = charges.roundTrip("SELL", t.getEntryPrice(), out, t.getQuantity()).getTotal();
        t.setExitPrice(round(out));
        t.setExitTime(at);
        t.setExitReason(reason);
        t.setCharges(cost);
        t.setNetPnl(round((t.getEntryPrice() - out) * t.getQuantity() - cost));
        t.setStatus(CLOSED);
    }

    // ------------------------------------------------------------ data

    private Map<String, List<MinuteCandle>> loadCandles(LocalDate day, LocalTime from, LocalTime to) {
        return loadCandles(day, from, to, null);
    }

    /** Stored minute candles of the day from {@code from} to {@code to} inclusive, sorted, by symbol. */
    private Map<String, List<MinuteCandle>> loadCandles(LocalDate day, LocalTime from, LocalTime to,
                                                        List<String> symbols) {
        StringBuilder sql = new StringBuilder("select symbol, candle_minute, open_price, high_price, low_price, "
                + "close_price, volume from minute_candle where candle_minute >= ? and candle_minute <= ?");
        List<Object> args = new ArrayList<>(List.of(Timestamp.valueOf(day.atTime(from)),
                Timestamp.valueOf(day.atTime(to))));
        if (symbols != null) {
            sql.append(" and symbol in (").append(String.join(",", symbols.stream().map(x -> "?").toList())).append(")");
            args.addAll(symbols);
        }
        sql.append(" order by symbol, candle_minute");

        Map<String, List<MinuteCandle>> out = new HashMap<>();
        jdbc.query(sql.toString(), rs -> {
            double open = rs.getDouble("open_price");
            boolean missing = rs.wasNull();
            double high = rs.getDouble("high_price");
            missing |= rs.wasNull();
            double low = rs.getDouble("low_price");
            missing |= rs.wasNull();
            double close = rs.getDouble("close_price");
            missing |= rs.wasNull();
            if (missing) {
                return;
            }
            String symbol = rs.getString("symbol");
            out.computeIfAbsent(symbol, k -> new ArrayList<>()).add(MinuteCandle.builder()
                    .symbol(symbol)
                    .minute(rs.getTimestamp("candle_minute").toLocalDateTime())
                    .open(open).high(high).low(low).close(close)
                    .volume(rs.getLong("volume"))
                    .build());
        }, args.toArray());
        return out;
    }

    // ------------------------------------------------------------ report

    /** Today's shorts and the book's record since it started (plain text). */
    public String toText(LocalDate day) {
        Settings s = settings();
        List<FadePaperTrade> all = repository.findAllByOrderByTradeDateAscIdAsc();
        List<FadePaperTrade> today = all.stream().filter(t -> t.getTradeDate().equals(day)).toList();

        StringBuilder out = new StringBuilder(String.format("FADE PAPER BOOK %s  |  book %s  |  %s%s%n", day, s.label(), pool().label(),
                enabled ? "" : "  |  DISABLED"));
        if (today.isEmpty()) {
            out.append("No shorts today.\n");
        } else {
            out.append(String.format("%-12s %4s %6s %9s %5s %9s %9s %9s %-9s %8s%n",
                    "Symbol", "rank", "rel%", "entry", "qty", "stop", "last", "exit", "reason", "net Rs"));
            for (FadePaperTrade t : today) {
                out.append(String.format("%-12s %4d %+6.2f %9.2f %5d %9s %9.2f %9s %-9s %8s%n",
                        t.getSymbol(), t.getTurnoverRank(), t.getRelMovePct(), t.getEntryPrice(), t.getQuantity(),
                        t.getStopLoss() == null ? "-" : String.format("%.2f", t.getStopLoss()), t.getLastPrice(),
                        t.getExitPrice() == null ? "-" : String.format("%.2f", t.getExitPrice()),
                        t.getExitReason() == null ? OPEN : t.getExitReason(),
                        t.getNetPnl() == null ? String.format("~%.0f", mark(t, t.getLastPrice(), s, charges))
                                : String.format("%.0f", t.getNetPnl())));
            }
            out.append(String.format("Today: Rs %.0f%s%n", dayNet(today),
                    today.stream().anyMatch(t -> OPEN.equals(t.getStatus())) ? " (open P&L marked at the last close)" : ""));
        }

        Map<LocalDate, List<FadePaperTrade>> byDay = new LinkedHashMap<>();
        all.stream().filter(t -> CLOSED.equals(t.getStatus()))
                .forEach(t -> byDay.computeIfAbsent(t.getTradeDate(), k -> new ArrayList<>()).add(t));
        byDay.values().removeIf(list -> list.stream().anyMatch(t -> t.getNetPnl() == null));
        if (!byDay.isEmpty()) {
            double[] nets = byDay.values().stream().mapToDouble(FadePaperBookService::dayNet).toArray();
            double total = java.util.Arrays.stream(nets).sum();
            long wins = java.util.Arrays.stream(nets).filter(n -> n > 0).count();
            long halts = byDay.values().stream()
                    .filter(list -> list.stream().anyMatch(t -> "DAY_STOP".equals(t.getExitReason()))).count();
            out.append(String.format("%nSince %s: %d session(s) with shorts | total Rs %.0f | Rs %.0f/session | "
                            + "win days %d | worst Rs %.0f | best Rs %.0f | day stops %d%n",
                    byDay.keySet().iterator().next(), nets.length, total, total / nets.length, wins,
                    java.util.Arrays.stream(nets).min().orElse(0), java.util.Arrays.stream(nets).max().orElse(0), halts));
        }
        out.append("Paper only: shorts at the 09:45 close less slippage; stops and the day stop on 1-minute candles.\n");
        return out.toString();
    }

    /** One short for a dashboard. {@code pnl}: the final net, or while open the estimate at the last close. */
    public record TradeView(Long id, String symbol, Integer turnoverRank, Double relMovePct, Double niftyMovePct,
                            Integer quantity, Double entryPrice, LocalDateTime entryTime, Double stopLoss,
                            Double lastPrice, Double exitPrice, LocalDateTime exitTime, String exitReason,
                            String status, Double charges, double pnl, boolean pnlEstimated) {
    }

    public record DayView(LocalDate date, int trades, int winners, double net) {
    }

    /** Finished sessions only; a session with an open short is left out, as in the text report. */
    public record RecordView(LocalDate since, int sessions, double total, double perSession, int winDays,
                             double worstDay, double bestDay, int dayStops, List<DayView> days) {
    }

    public record BookView(LocalDate date, boolean enabled, boolean paperOnly, String book, String pool,
                           List<TradeView> trades, int openTrades, double dayPnl, boolean dayPnlEstimated,
                           RecordView record) {
    }

    /** The same figures as {@link #toText}, as data. */
    public BookView toView(LocalDate day) {
        return view(day, repository.findAllByOrderByTradeDateAscIdAsc(), settings(), pool(), enabled, charges);
    }

    static BookView view(LocalDate day, List<FadePaperTrade> all, Settings s, Pool pool, boolean enabled,
                         IntradayChargesCalculator charges) {
        List<TradeView> trades = all.stream().filter(t -> t.getTradeDate().equals(day)).map(t -> {
            boolean estimated = t.getNetPnl() == null;
            return new TradeView(t.getId(), t.getSymbol(), t.getTurnoverRank(), t.getRelMovePct(), t.getNiftyMovePct(),
                    t.getQuantity(), t.getEntryPrice(), t.getEntryTime(), t.getStopLoss(), t.getLastPrice(),
                    t.getExitPrice(), t.getExitTime(), t.getExitReason(), t.getStatus(), t.getCharges(),
                    round(estimated ? mark(t, t.getLastPrice(), s, charges) : t.getNetPnl()), estimated);
        }).toList();
        int open = (int) trades.stream().filter(t -> OPEN.equals(t.status())).count();
        double dayPnl = round(trades.stream().mapToDouble(TradeView::pnl).sum());
        boolean estimated = trades.stream().anyMatch(TradeView::pnlEstimated);
        return new BookView(day, enabled, true, s.label(), pool.label(), trades, open, dayPnl, estimated, record(all));
    }

    private static RecordView record(List<FadePaperTrade> all) {
        Map<LocalDate, List<FadePaperTrade>> byDay = new TreeMap<>();
        all.forEach(t -> byDay.computeIfAbsent(t.getTradeDate(), k -> new ArrayList<>()).add(t));
        byDay.values().removeIf(list -> list.stream().anyMatch(t -> !CLOSED.equals(t.getStatus()) || t.getNetPnl() == null));
        List<DayView> days = byDay.entrySet().stream()
                .map(e -> new DayView(e.getKey(), e.getValue().size(),
                        (int) e.getValue().stream().filter(t -> t.getNetPnl() > 0).count(), round(dayNet(e.getValue()))))
                .toList();
        double total = days.stream().mapToDouble(DayView::net).sum();
        int dayStops = (int) byDay.values().stream()
                .filter(list -> list.stream().anyMatch(t -> "DAY_STOP".equals(t.getExitReason()))).count();
        return new RecordView(days.isEmpty() ? null : days.get(0).date(), days.size(), round(total),
                days.isEmpty() ? 0 : round(total / days.size()),
                (int) days.stream().filter(d -> d.net() > 0).count(),
                days.stream().mapToDouble(DayView::net).min().orElse(0),
                days.stream().mapToDouble(DayView::net).max().orElse(0), dayStops, days);
    }

    private static String entryText(LocalDate day, OpeningScreen.Screen screen, List<FadePaperTrade> trades,
                                    Settings s, Pool pool) {
        StringBuilder out = new StringBuilder(String.format(
                "FADE BOOK %s | NIFTY %+.2f%% at 09:45 | %d candidates (%s vs NIFTY) | book %s%n",
                day, screen.niftyMove(), screen.fade().size(), pool.label(), s.label()));
        if (trades.isEmpty()) {
            out.append(s.niftyUpOnly() && screen.niftyMove() <= 0 ? "NIFTY not up: no shorts today."
                    : "No candidates: no shorts today.");
            return out.toString();
        }
        for (FadePaperTrade t : trades) {
            out.append(String.format("SHORT %-12s rank %3d  %+.2f%%  qty %d @ %.2f  stop %s%n",
                    t.getSymbol(), t.getTurnoverRank(), t.getRelMovePct(), t.getQuantity(), t.getEntryPrice(),
                    t.getStopLoss() == null ? "none" : String.format("%.2f", t.getStopLoss())));
        }
        return out.append(s.dailyStop() > 0 ? String.format("Day stop: all covered at Rs -%.0f.", s.dailyStop())
                : "No day stop.").toString();
    }

    static double dayNet(List<FadePaperTrade> trades) {
        return trades.stream().mapToDouble(t -> t.getNetPnl() == null ? 0 : t.getNetPnl()).sum();
    }

    private void notifySlack(String title, String text) {
        notificationClient.sendNotification(NotificationRequest.builder()
                        .channel(NotificationChannel.SLACK)
                        .title(title)
                        .message(text)
                        .build())
                .doOnError(ex -> log.warn("FADE BOOK | Slack send failed | {}", ex.getMessage()))
                .onErrorResume(ex -> Mono.empty())
                .subscribe();
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
