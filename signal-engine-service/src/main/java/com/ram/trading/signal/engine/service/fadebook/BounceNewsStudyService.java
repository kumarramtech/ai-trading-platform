package com.ram.trading.signal.engine.service.fadebook;

import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import com.ram.trading.signal.engine.service.charges.IntradayChargesCalculator;
import com.ram.trading.signal.engine.service.replay.DailyBars;
import com.ram.trading.signal.engine.service.replay.OpeningScreen;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * BOUNCE NO-NEWS STUDY: the buy-side mirror of the FADE news study. PRE-REGISTERED 2026-11-21,
 * before any data. Shadow paper only: no orders, its own table, nothing in the FADE book changes.
 *
 * <p>Why this is a new test, not a re-run: plain buy-the-losers already failed (Oct 30 entry
 * study, Round 3 BOUNCE books, Nov 20 regime study, mirror cells gross about +-0.2%). The new
 * reason is news (Chan 2003): a fall WITHOUT news is more likely liquidity selling that reverses;
 * a fall on news tends to drift on. So only the NO_NEWS buys are judged.
 *
 * <p>Rules (fixed; never change them after seeing data):
 * <ul>
 *   <li>Screen at the 09:45 candle close, same as FADE ({@link OpeningScreen#rank}): liquid stocks,
 *       turnover ranks 501-600, move from the open less NIFTY's <= -2%, weakest first, top 5.</li>
 *   <li>Buy each at the 09:45 close plus slippage, Rs 40,000 each, no stop, sell at the 14:59
 *       candle close less slippage; intraday charges.</li>
 *   <li>Tag each buy at entry with {@link FadeNewsTagger#lookup} (NO_NEWS / NEWS / CATALYST;
 *       ERROR and EMPTY left out). Tags are only made at entry; never backfilled.</li>
 * </ul>
 *
 * <p>Pass mark, judged on the NO_NEWS buys once {@value #MIN_SESSIONS} sessions with a tagged buy
 * have been collected from {@link #TEST_SINCE}; ALL must hold:
 * <ol>
 *   <li>net >= +{@value #MIN_BPS} bps per buy after charges and slippage;</li>
 *   <li>t >= {@value #MIN_T};</li>
 *   <li>both halves of the sessions net positive;</li>
 *   <li>NIFTY-hedged net > 0 (the buy less NIFTY over the same minutes): stock picking, not market;</li>
 *   <li>NO_NEWS buys earn more per buy than the NEWS + CATALYST buys;</li>
 *   <li>worst day of the NO_NEWS buys better than Rs {@value #WORST_DAY}.</li>
 * </ol>
 * PASSED: build a separate BOUNCE paper book on these exact rules (NO_NEWS only) for its own test.
 * FAILED: switch the study off (trading.bounce-news-study.enabled=false) and drop the idea.
 */
@Service
@Slf4j
public class BounceNewsStudyService {

    /** First session counted (the first trading day after the rules were committed). Never move it. */
    static final LocalDate TEST_SINCE = LocalDate.of(2026, 11, 23);
    static final int MIN_SESSIONS = 60;
    static final double MIN_BPS = 15;
    static final double MIN_T = 2;
    static final double WORST_DAY = -5000;

    static final int POOL_FROM = 500; // 0-based: rank 501
    static final int POOL_TO = 600;   // exclusive: rank 600
    static final double MIN_DROP = 2;
    static final int POSITIONS = 5;
    static final double NOTIONAL = 40_000;
    static final LocalTime EXIT_CANDLE = LocalTime.of(14, 59);

    static final String OPEN = "OPEN";
    static final String CLOSED = "CLOSED";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);

    private final JdbcTemplate jdbc;
    private final IntradayChargesCalculator charges;
    private final FadeNewsTagger tagger;

    private com.ram.trading.signal.engine.calendar.TradingCalendar calendar;

    @Value("${trading.bounce-news-study.enabled:true}")
    private boolean enabled = true;

    @Value("${paper.trading.slippage-pct:0.03}")
    private double slippagePct = 0.03;

    @Value("${trading.liquidity.min-price:50}")
    private double minPrice = 50;

    @Value("${trading.liquidity.min-average-turnover-crore:5}")
    private double minTurnoverCrore = 5;

    public BounceNewsStudyService(JdbcTemplate jdbc, IntradayChargesCalculator charges, FadeNewsTagger tagger) {
        this.jdbc = jdbc;
        this.charges = charges;
        this.tagger = tagger;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCalendar(com.ram.trading.signal.engine.calendar.TradingCalendar calendar) {
        this.calendar = calendar;
    }

    @PostConstruct
    void createTable() {
        jdbc.execute("create table if not exists bounce_news_trade ("
                + "id bigint auto_increment primary key, trade_date date not null, symbol varchar(64) not null, "
                + "turnover_rank int not null, rel_move_pct double not null, nifty_move_pct double not null, "
                + "quantity int not null, entry_price double not null, nifty_entry double, "
                + "exit_price double, nifty_exit double, charges double, net_pnl double, status varchar(8) not null, "
                + "tag varchar(12), kept int, returned int, headlines varchar(2000), tagged_at datetime, "
                + "unique key uk_bounce_news_day_symbol (trade_date, symbol), index idx_bounce_news_day (trade_date))");
    }

    private boolean closedToday() {
        return calendar != null && !calendar.isTradingDay();
    }

    // ------------------------------------------------------------ schedule

    /** 09:47:10 onwards (retried to 09:55 after a restart): the 09:45 screen and the shadow buys. */
    @Scheduled(cron = "10 47-55 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void enterAtScreen() {
        if (!enabled || closedToday()) {
            return;
        }
        try {
            enter(LocalDateTime.now(IST));
        } catch (Exception ex) {
            log.error("BOUNCE NEWS | Entry failed", ex);
        }
    }

    /** 15:01 onwards (retried to 15:31): sell every open buy at its day's 14:59 close. */
    @Scheduled(cron = "0 1-31/5 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void exitAll() {
        if (!enabled) {
            return;
        }
        try {
            closeOpen(LocalDate.now(IST));
        } catch (Exception ex) {
            log.error("BOUNCE NEWS | Exit failed", ex);
        }
    }

    // ------------------------------------------------------------ entry

    void enter(LocalDateTime now) {
        LocalDate day = now.toLocalDate();
        Integer done = jdbc.queryForObject("select count(*) from bounce_news_trade where trade_date = ?", Integer.class, day);
        if (done != null && done > 0) {
            return;
        }
        Map<String, List<MinuteCandle>> series = loadCandles(day, MARKET_OPEN, OpeningScreen.ENTRY, null);
        List<MinuteCandle> nifty = series.remove("NIFTY");
        series.remove("BANKNIFTY");
        DailyBars daily = DailyBars.load(jdbc, null, day.minusDays(45), day);
        OpeningScreen.Ranking ranking = OpeningScreen.rank(day, series, nifty, daily, minPrice, minTurnoverCrore);
        List<OpeningScreen.Ranked> picks = select(ranking);
        if (picks == null) {
            log.warn("BOUNCE NEWS | No screen for {} yet", day);
            return;
        }
        Double niftyEntry = nifty == null ? null : nifty.stream()
                .filter(c -> c.getMinute().toLocalTime().equals(OpeningScreen.ENTRY))
                .map(MinuteCandle::getClose).findFirst().orElse(null);
        for (OpeningScreen.Ranked r : picks) {
            double entry = r.price() * (1 + slippagePct / 100);
            int qty = (int) Math.floor(NOTIONAL / entry);
            if (qty < 1) {
                continue;
            }
            jdbc.update("insert ignore into bounce_news_trade (trade_date, symbol, turnover_rank, rel_move_pct, "
                            + "nifty_move_pct, quantity, entry_price, nifty_entry, status) values (?,?,?,?,?,?,?,?,?)",
                    day, r.symbol(), r.rank() + 1, round(r.rel()), round(ranking.niftyMove()), qty, round(entry),
                    niftyEntry, OPEN);
            tagger.lookup(r.symbol(), now)
                    .subscribe(tag -> jdbc.update("update bounce_news_trade set tag = ?, kept = ?, returned = ?, "
                                    + "headlines = ?, tagged_at = ? where trade_date = ? and symbol = ? and tag is null",
                            tag.tag(), tag.kept(), tag.returned(), tag.headlines(), now, day, r.symbol()),
                            ex -> log.warn("BOUNCE NEWS | Tag failed {}: {}", r.symbol(), ex.toString()));
        }
        log.info("BOUNCE NEWS | {} | NIFTY {}% at 09:45 | shadow buys {} | {}", day,
                String.format("%+.2f", ranking.niftyMove()), picks.size(),
                picks.stream().map(p -> p.symbol() + String.format(" %+.2f", p.rel())).toList());
    }

    /** Pure: the day's buys (weakest first, at most 5); null when there is no usable screen. */
    static List<OpeningScreen.Ranked> select(OpeningScreen.Ranking ranking) {
        if (ranking == null || ranking.ranked().size() < POOL_FROM + (POOL_TO - POOL_FROM) / 2) {
            return null;
        }
        return ranking.ranked().stream()
                .filter(r -> r.rank() >= POOL_FROM && r.rank() < POOL_TO && r.rel() <= -MIN_DROP)
                .sorted(Comparator.comparingDouble(OpeningScreen.Ranked::rel))
                .limit(POSITIONS)
                .toList();
    }

    // ------------------------------------------------------------ exit

    /** Sells open buys of any day up to {@code today} once that day's 14:59 candle exists. */
    void closeOpen(LocalDate today) {
        List<Map<String, Object>> open = jdbc.queryForList("select id, trade_date, symbol, quantity, entry_price "
                + "from bounce_news_trade where status = 'OPEN' and trade_date <= ?", today);
        for (Map<String, Object> r : open) {
            LocalDate day = toDate(r.get("trade_date"));
            if (day.equals(today) && LocalTime.now(IST).isBefore(LocalTime.of(15, 0))) {
                continue;
            }
            String symbol = (String) r.get("symbol");
            Double px = closeAtOrBefore(symbol, day.atTime(EXIT_CANDLE));
            Double nx = closeAtOrBefore("NIFTY", day.atTime(EXIT_CANDLE));
            if (px == null) {
                continue;
            }
            int qty = ((Number) r.get("quantity")).intValue();
            double entry = ((Number) r.get("entry_price")).doubleValue();
            double out = px * (1 - slippagePct / 100);
            double cost = charges.roundTrip("BUY", entry, out, qty).getTotal();
            jdbc.update("update bounce_news_trade set exit_price = ?, nifty_exit = ?, charges = ?, net_pnl = ?, "
                            + "status = 'CLOSED' where id = ?", round(out), nx, cost,
                    round((out - entry) * qty - cost), r.get("id"));
        }
    }

    // ------------------------------------------------------------ report

    /** One closed buy: net bps of its notional, hedged bps (null when NIFTY is unknown), net Rs. */
    public record Row(LocalDate day, String symbol, String tag, double bps, Double hedgedBps, double net) {
    }

    public record Stats(int n, double meanBps, double t) {
    }

    public record Report(LocalDate since, int sessions, int minSessions, int tagged, int excluded, Stats noNews,
                         Stats withNews, Stats firstHalf, Stats secondHalf, Stats hedged, double noNewsRs,
                         double worstDay, String verdict, List<String> checks, String next) {
    }

    public Report report() {
        List<Row> rows = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("select trade_date d, symbol s, tag, net_pnl net, entry_price p, "
                + "quantity q, nifty_entry ni, nifty_exit nx from bounce_news_trade where status = 'CLOSED' "
                + "and net_pnl is not null and trade_date >= ? order by trade_date, id", TEST_SINCE)) {
            double notional = ((Number) r.get("p")).doubleValue() * ((Number) r.get("q")).doubleValue();
            double net = ((Number) r.get("net")).doubleValue();
            double bps = notional > 0 ? net / notional * 10000 : 0;
            Number ni = (Number) r.get("ni"), nx = (Number) r.get("nx");
            Double hedged = ni == null || nx == null || ni.doubleValue() <= 0 ? null
                    : bps - (nx.doubleValue() / ni.doubleValue() - 1) * 10000;
            rows.add(new Row(toDate(r.get("d")), (String) r.get("s"), (String) r.get("tag"), bps, hedged, net));
        }
        return evaluate(rows);
    }

    /** Pure: statistics and the verdict against the fixed pass mark. */
    static Report evaluate(List<Row> all) {
        List<Row> in = all.stream().filter(r -> isStudied(r.tag())).toList();
        List<Row> none = in.stream().filter(r -> FadeNewsTagger.NO_NEWS.equals(r.tag())).toList();
        List<Row> with = in.stream().filter(r -> !FadeNewsTagger.NO_NEWS.equals(r.tag())).toList();
        List<LocalDate> days = in.stream().map(Row::day).distinct().sorted().toList();
        int sessions = days.size();
        LocalDate split = sessions == 0 ? null : days.get(sessions / 2);
        TreeMap<LocalDate, Double> byDay = new TreeMap<>();
        none.forEach(r -> byDay.merge(r.day(), r.net(), Double::sum));

        Stats s0 = stats(none.stream().map(Row::bps).toList());
        Stats s1 = stats(with.stream().map(Row::bps).toList());
        Stats h1 = stats(none.stream().filter(r -> r.day().isBefore(split)).map(Row::bps).toList());
        Stats h2 = stats(none.stream().filter(r -> !r.day().isBefore(split)).map(Row::bps).toList());
        Stats hedged = stats(none.stream().filter(r -> r.hedgedBps() != null).map(Row::hedgedBps).toList());
        double worst = byDay.values().stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double total = none.stream().mapToDouble(Row::net).sum();

        List<String> checks = new ArrayList<>();
        check(checks, s0.n() > 0 && s0.meanBps() >= MIN_BPS,
                String.format("NO_NEWS net %+.1f bps per buy (need >= +%.0f)", s0.meanBps(), MIN_BPS));
        check(checks, s0.t() >= MIN_T, String.format("NO_NEWS t %.2f (need >= %.1f)", s0.t(), MIN_T));
        check(checks, h1.n() > 0 && h1.meanBps() > 0 && h2.n() > 0 && h2.meanBps() > 0,
                String.format("halves %+.1f / %+.1f bps (need both > 0)", h1.meanBps(), h2.meanBps()));
        check(checks, hedged.n() > 0 && hedged.meanBps() > 0,
                String.format("NIFTY-hedged %+.1f bps (need > 0: stock picking, not market direction)", hedged.meanBps()));
        check(checks, s0.n() > 0 && s0.meanBps() > s1.meanBps(),
                String.format("NO_NEWS %+.1f vs with news %+.1f bps (need higher)", s0.meanBps(), s1.meanBps()));
        check(checks, worst > WORST_DAY, String.format("worst NO_NEWS day Rs %,.0f (need better than Rs %,.0f)",
                worst, WORST_DAY));

        String verdict, next;
        if (sessions < MIN_SESSIONS) {
            verdict = "COLLECTING " + sessions + "/" + MIN_SESSIONS + " sessions";
            next = "No decision before " + MIN_SESSIONS + " sessions; the checks show the running state only.";
        } else if (checks.stream().allMatch(c -> c.startsWith("PASS"))) {
            verdict = "PASSED";
            next = "Build a separate BOUNCE paper book on these exact rules (NO_NEWS buys only) for its own test.";
        } else {
            verdict = "FAILED";
            next = "Switch the study off (trading.bounce-news-study.enabled=false) and drop the idea.";
        }
        return new Report(TEST_SINCE, sessions, MIN_SESSIONS, in.size(), all.size() - in.size(), s0, s1, h1, h2,
                hedged, round(total), worst, verdict, checks, next);
    }

    private static boolean isStudied(String tag) {
        return FadeNewsTagger.NO_NEWS.equals(tag) || FadeNewsTagger.NEWS.equals(tag)
                || FadeNewsTagger.CATALYST.equals(tag);
    }

    static Stats stats(List<Double> v) {
        int n = v.size();
        if (n == 0) {
            return new Stats(0, 0, 0);
        }
        double mean = v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = n < 2 ? 0 : v.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum() / (n - 1);
        return new Stats(n, mean, var > 0 ? mean / Math.sqrt(var / n) : 0);
    }

    private static void check(List<String> checks, boolean ok, String text) {
        checks.add((ok ? "PASS " : "FAIL ") + text);
    }

    public static String toText(Report r) {
        StringBuilder out = new StringBuilder();
        out.append("BOUNCE NO-NEWS STUDY (shadow paper; pre-registered 2026-11-21, sessions counted from ")
                .append(r.since()).append(")\n");
        out.append("Buy the 5 weakest of ranks 501-600 (<= -2% vs NIFTY at 09:45), Rs 40k each, sell 14:59; "
                + "judged on NO_NEWS buys only.\n");
        out.append(String.format("  %s | tagged buys %d (left out %d) | NO_NEWS %d buys, Rs %+,.0f | with news %d buys, %+.1f bps%n",
                r.verdict(), r.tagged(), r.excluded(), r.noNews().n(), r.noNewsRs(), r.withNews().n(),
                r.withNews().meanBps()));
        r.checks().forEach(c -> out.append("  ").append(c).append("\n"));
        out.append("  ").append(r.next()).append("\n");
        return out.toString();
    }

    // ------------------------------------------------------------ data

    private Double closeAtOrBefore(String symbol, LocalDateTime t) {
        List<Double> v = jdbc.queryForList("select close_price from minute_candle where symbol = ? and "
                        + "candle_minute >= ? and candle_minute <= ? and close_price > 0 order by candle_minute desc limit 1",
                Double.class, symbol, Timestamp.valueOf(t.toLocalDate().atTime(OpeningScreen.ENTRY)), Timestamp.valueOf(t));
        return v.isEmpty() ? null : v.get(0);
    }

    /** Stored minute candles of the day from {@code from} to {@code to} inclusive, sorted, by symbol. */
    private Map<String, List<MinuteCandle>> loadCandles(LocalDate day, LocalTime from, LocalTime to,
                                                        List<String> symbols) {
        StringBuilder sql = new StringBuilder("select symbol, candle_minute, open_price, high_price, low_price, "
                + "close_price, volume from minute_candle where candle_minute >= ? and candle_minute <= ?");
        List<Object> args = new ArrayList<>(List.of(Timestamp.valueOf(day.atTime(from)), Timestamp.valueOf(day.atTime(to))));
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

    private static LocalDate toDate(Object o) {
        return o instanceof java.sql.Date d ? d.toLocalDate()
                : o instanceof LocalDate d ? d : LocalDate.parse(String.valueOf(o).substring(0, 10));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
