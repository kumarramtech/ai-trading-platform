package com.ram.trading.signal.engine.service.pickstudy;

import com.ram.trading.signal.engine.calendar.TradingCalendar;
import com.ram.trading.signal.engine.dto.premarket.PreMarketCandidate;
import com.ram.trading.signal.engine.dto.watchlist.TrendingStockResponse;
import com.ram.trading.signal.engine.service.MiddayCandidateStore;
import com.ram.trading.signal.engine.service.PreMarketCandidateStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pick study (measure only, research). Records the intraday pre-market (09:15) and midday
 * (11:00-14:00 discovery) picks, then after the close measures each pick from the time it could be
 * traded (09:30 for pre-market, discovery + 15 min for midday) to the 15:00 exit, against stocks
 * that were NOT picked:
 *   B1 = all liquid stocks not picked (price >= 50), same direction;
 *   B2 = "similar movers": not-picked stocks moving the same way since the open, top 10% by size.
 * It only READS the candidate stores and minute candles. It never trades, never changes a pick,
 * a signal or any intraday / FADE / momentum rule. The result goes to the intraday 60-trade review.
 */
@Slf4j
@Service
public class PickStudyService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalTime OPEN = LocalTime.of(9, 15);
    static final LocalTime PREMARKET_REF = LocalTime.of(9, 30);
    static final LocalTime EXIT = LocalTime.of(15, 0);
    static final double MIN_PRICE = 50;
    static final double SIMILAR_TOP = 0.10;

    private final JdbcTemplate jdbc;
    private final PreMarketCandidateStore preMarket;
    private final MiddayCandidateStore midday;
    private TradingCalendar calendar;
    private Clock clock = Clock.system(IST);

    public PickStudyService(JdbcTemplate jdbc, PreMarketCandidateStore preMarket, MiddayCandidateStore midday) {
        this.jdbc = jdbc;
        this.preMarket = preMarket;
        this.midday = midday;
    }

    @Autowired(required = false)
    void setCalendar(TradingCalendar calendar) {
        this.calendar = calendar;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    @PostConstruct
    void createTable() {
        jdbc.execute("create table if not exists pick_study ("
                + "trade_day date not null, source varchar(12) not null, cycle varchar(5) not null, "
                + "symbol varchar(64) not null, direction varchar(8) not null, ref_time varchar(5) not null, "
                + "ref_price double, exit_price double, move_pct double, b1_pct double, b2_pct double, "
                + "traded tinyint, measured_at datetime, recorded_at datetime not null, "
                + "primary key (trade_day, source, cycle, symbol))");
    }

    private boolean closedToday() {
        return calendar != null && !calendar.isTradingDay();
    }

    // ------------------------------------------------------------ recording (read the stores only)

    @Scheduled(cron = "0 26 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void recordPreMarket() {
        try {
            if (closedToday() || !preMarket.isValidForToday()) {
                return;
            }
            LocalDate day = LocalDate.now(clock);
            int n = 0;
            for (PreMarketCandidate c : preMarket.getCandidates()) {
                n += insert(day, "PREMARKET", "09:15", c.getTradingSymbol(),
                        direction(c.getDirection(), c.getGapPercentage()), PREMARKET_REF);
            }
            log.info("PICK STUDY | {} pre-market picks recorded", n);
        } catch (RuntimeException ex) {
            log.warn("PICK STUDY | pre-market record failed: {}", ex.toString());
        }
    }

    /** 5 minutes after each midday discovery (11:00 ... 14:00); a cycle is recorded once. */
    @Scheduled(cron = "0 5/30 11-14 * * MON-FRI", zone = "Asia/Kolkata")
    public void recordMidday() {
        try {
            LocalDateTime found = midday.getDiscoveredAt();
            LocalDate day = LocalDate.now(clock);
            if (closedToday() || found == null || !found.toLocalDate().equals(day)) {
                return;
            }
            LocalTime cycle = found.toLocalTime().truncatedTo(ChronoUnit.MINUTES);
            int n = 0;
            for (TrendingStockResponse c : midday.getCandidates()) {
                n += insert(day, "MIDDAY", hhmm(cycle), c.getTradingSymbol(),
                        direction(c.getTrendDirection(), c.getChangePercentage()), cycle.plusMinutes(15));
            }
            log.info("PICK STUDY | {} midday picks recorded for the {} cycle", n, hhmm(cycle));
        } catch (RuntimeException ex) {
            log.warn("PICK STUDY | midday record failed: {}", ex.toString());
        }
    }

    private int insert(LocalDate day, String source, String cycle, String symbol, String dir, LocalTime ref) {
        if (symbol == null || symbol.isBlank()) {
            return 0;
        }
        return jdbc.update("insert ignore into pick_study (trade_day, source, cycle, symbol, direction, ref_time, recorded_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?)", day, source, cycle, symbol.trim(), dir, hhmm(ref),
                Timestamp.valueOf(LocalDateTime.now(clock)));
    }

    /** LONG for bullish, SHORT for bearish; otherwise the sign of the day's move at the pick. */
    static String direction(String label, Double movePct) {
        if (label != null) {
            String l = label.trim().toUpperCase();
            if (l.equals("BULLISH") || l.equals("LONG") || l.equals("BUY")) {
                return "LONG";
            }
            if (l.equals("BEARISH") || l.equals("SHORT") || l.equals("SELL")) {
                return "SHORT";
            }
        }
        return movePct != null && movePct < 0 ? "SHORT" : "LONG";
    }

    static String hhmm(LocalTime t) {
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    // ------------------------------------------------------------ measuring (after the close)

    @Scheduled(cron = "0 36 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void measureToday() {
        try {
            if (!closedToday()) {
                measure(LocalDate.now(clock));
            }
        } catch (RuntimeException ex) {
            log.warn("PICK STUDY | measure failed: {}", ex.toString());
        }
    }

    /** Measures (or re-measures) one day's picks; returns how many were measured. */
    public synchronized int measure(LocalDate day) {
        List<Map<String, Object>> picks = jdbc.queryForList(
                "select source, cycle, symbol, direction, ref_time from pick_study where trade_day = ?", day);
        if (picks.isEmpty()) {
            return 0;
        }
        Map<String, Double> open = prices(day, OPEN, true);
        Map<String, Double> exit = prices(day, EXIT, false);
        Set<String> traded = new HashSet<>(jdbc.queryForList(
                "select distinct symbol from paper_trade where entry_time >= ? and entry_time < ?", String.class,
                Timestamp.valueOf(day.atStartOfDay()), Timestamp.valueOf(day.plusDays(1).atStartOfDay())));

        // Picked symbols per (source, cycle) are left out of that cycle's baselines.
        Map<String, Set<String>> pickedByCycle = new HashMap<>();
        for (Map<String, Object> p : picks) {
            pickedByCycle.computeIfAbsent(p.get("source") + "|" + p.get("cycle"), k -> new HashSet<>())
                    .add((String) p.get("symbol"));
        }
        Map<String, Map<String, Double>> refCache = new HashMap<>();
        Map<String, Baselines> baseCache = new HashMap<>();
        int n = 0;
        LocalDateTime now = LocalDateTime.now(clock);
        for (Map<String, Object> p : picks) {
            String ref = (String) p.get("ref_time");
            String symbol = (String) p.get("symbol");
            String dir = (String) p.get("direction");
            String cycleKey = p.get("source") + "|" + p.get("cycle");
            Map<String, Double> refPrices = refCache.computeIfAbsent(ref, r -> prices(day, LocalTime.parse(r), false));
            Baselines b = baseCache.computeIfAbsent(cycleKey + "|" + ref,
                    k -> baselines(open, refPrices, exit, pickedByCycle.get(cycleKey)));
            Double r0 = refPrices.get(symbol), r1 = exit.get(symbol);
            Double move = r0 == null || r1 == null ? null : signed(r1 / r0 - 1, dir);
            jdbc.update("update pick_study set ref_price = ?, exit_price = ?, move_pct = ?, b1_pct = ?, b2_pct = ?, "
                            + "traded = ?, measured_at = ? where trade_day = ? and source = ? and cycle = ? and symbol = ?",
                    r0, r1, move, b.b1(dir), b.b2(dir), traded.contains(symbol) ? 1 : 0, Timestamp.valueOf(now),
                    day, p.get("source"), p.get("cycle"), symbol);
            n++;
        }
        log.info("PICK STUDY | {} picks measured for {}", n, day);
        return n;
    }

    static double signed(double ret, String dir) {
        return ("SHORT".equals(dir) ? -ret : ret) * 100;
    }

    /** Close of each symbol's last minute candle at or before {@code t} (the first open at or after, for the open). */
    private Map<String, Double> prices(LocalDate day, LocalTime t, boolean openOfFirst) {
        Map<String, Double> out = new HashMap<>();
        String sql = openOfFirst
                ? "select symbol, open_price p from minute_candle where candle_minute >= ? and candle_minute <= ? "
                + "and open_price is not null order by candle_minute desc"
                : "select symbol, close_price p from minute_candle where candle_minute >= ? and candle_minute <= ? "
                + "and close_price is not null order by candle_minute";
        LocalDateTime from = openOfFirst ? day.atTime(t) : day.atTime(t).minusMinutes(10);
        LocalDateTime to = openOfFirst ? day.atTime(t).plusMinutes(10) : day.atTime(t);
        // Ordered so the wanted row comes last and overwrites the others.
        jdbc.query(sql, rs -> {
            out.put(rs.getString("symbol"), rs.getDouble("p"));
        }, Timestamp.valueOf(from), Timestamp.valueOf(to));
        out.remove("NIFTY");
        out.remove("BANKNIFTY");
        return out;
    }

    /** Average signed move (%) of the comparison stocks, per direction. */
    record Baselines(Double b1Long, Double b2Long, Double b2Short) {
        Double b1(String dir) {
            return b1Long == null ? null : ("SHORT".equals(dir) ? -b1Long : b1Long);
        }

        Double b2(String dir) {
            return "SHORT".equals(dir) ? b2Short : b2Long;
        }
    }

    static Baselines baselines(Map<String, Double> open, Map<String, Double> ref, Map<String, Double> exit,
                               Set<String> picked) {
        double sum = 0;
        int n = 0;
        List<double[]> up = new ArrayList<>(), down = new ArrayList<>(); // {move since open, ref->exit return}
        for (Map.Entry<String, Double> e : ref.entrySet()) {
            String s = e.getKey();
            Double r0 = e.getValue(), r1 = exit.get(s), o = open.get(s);
            if (picked != null && picked.contains(s) || r0 == null || r1 == null || r0 < MIN_PRICE || r0 <= 0) {
                continue;
            }
            double ret = r1 / r0 - 1;
            sum += ret;
            n++;
            if (o != null && o > 0) {
                double since = r0 / o - 1;
                (since >= 0 ? up : down).add(new double[]{Math.abs(since), ret});
            }
        }
        return new Baselines(n == 0 ? null : sum / n * 100, topMean(up, false), topMean(down, true));
    }

    /** Mean signed ref->exit move of the top 10% biggest movers in one direction. */
    static Double topMean(List<double[]> movers, boolean shortSide) {
        if (movers.isEmpty()) {
            return null;
        }
        movers.sort(Comparator.comparingDouble((double[] m) -> m[0]).reversed());
        int k = Math.max(1, (int) Math.round(movers.size() * SIMILAR_TOP));
        double sum = 0;
        for (int i = 0; i < k; i++) {
            sum += movers.get(i)[1];
        }
        return (shortSide ? -sum : sum) / k * 100;
    }

    // ------------------------------------------------------------ report

    record Row(LocalDate day, String source, boolean traded, double move, Double b1, Double b2) {
    }

    record Group(String label, int picks, int days, double movePct, Double vsAllPct, Double vsAllT,
                 Double vsMoversPct, Double vsMoversT, double hitPct) {
    }

    public List<Group> summary() {
        List<Row> rows = new ArrayList<>();
        jdbc.query("select trade_day, source, traded, move_pct, b1_pct, b2_pct from pick_study "
                + "where move_pct is not null order by trade_day", rs -> {
            rows.add(new Row(rs.getDate("trade_day").toLocalDate(), rs.getString("source"), rs.getInt("traded") == 1,
                    rs.getDouble("move_pct"), (Double) rs.getObject("b1_pct"), (Double) rs.getObject("b2_pct")));
        });
        return groups(rows);
    }

    static List<Group> groups(List<Row> rows) {
        Map<String, List<Row>> by = new LinkedHashMap<>();
        for (String src : List.of("PREMARKET", "MIDDAY")) {
            List<Row> s = rows.stream().filter(r -> r.source().equals(src)).toList();
            by.put(src + " all", s);
            by.put(src + " traded", s.stream().filter(Row::traded).toList());
            by.put(src + " not traded", s.stream().filter(r -> !r.traded()).toList());
        }
        List<Group> out = new ArrayList<>();
        by.forEach((label, list) -> {
            if (!list.isEmpty()) {
                out.add(group(label, list));
            }
        });
        return out;
    }

    /** Excess t-stats use one value per day (the day's average), as picks on one day move together. */
    static Group group(String label, List<Row> rows) {
        double move = rows.stream().mapToDouble(Row::move).average().orElse(0);
        double hit = rows.stream().filter(r -> r.move() > 0).count() * 100.0 / rows.size();
        double[] vsAll = dailyExcess(rows, true), vsMovers = dailyExcess(rows, false);
        int days = (int) rows.stream().map(Row::day).distinct().count();
        return new Group(label, rows.size(), days, move, mean(vsAll), t(vsAll), mean(vsMovers), t(vsMovers), hit);
    }

    static double[] dailyExcess(List<Row> rows, boolean all) {
        Map<LocalDate, double[]> byDay = new LinkedHashMap<>(); // {sum, n}
        for (Row r : rows) {
            Double b = all ? r.b1() : r.b2();
            if (b != null) {
                double[] a = byDay.computeIfAbsent(r.day(), d -> new double[2]);
                a[0] += r.move() - b;
                a[1]++;
            }
        }
        return byDay.values().stream().mapToDouble(a -> a[0] / a[1]).toArray();
    }

    static Double mean(double[] x) {
        return x.length == 0 ? null : java.util.Arrays.stream(x).average().orElse(0);
    }

    static Double t(double[] x) {
        if (x.length < 2) {
            return null;
        }
        double m = mean(x), ss = 0;
        for (double v : x) {
            ss += (v - m) * (v - m);
        }
        double sd = Math.sqrt(ss / (x.length - 1));
        return sd == 0 ? null : m / (sd / Math.sqrt(x.length));
    }

    public String report() {
        List<Group> g = summary();
        StringBuilder out = new StringBuilder("PICK STUDY (measure only; no rule changes)\n"
                + "Each pick from when it could be traded (09:30 pre-market, discovery+15m midday) to 15:00,\n"
                + "signed by direction, vs stocks NOT picked: all liquid stocks, and similar movers (top 10% same way).\n\n");
        if (g.isEmpty()) {
            return out.append("No measured picks yet (recording starts with the next session).\n").toString();
        }
        out.append(String.format("%-22s %6s %5s %8s %10s %6s %11s %6s %6s%n",
                "group", "picks", "days", "move%", "vs all%", "t", "vs movers%", "t", "hit%"));
        for (Group x : g) {
            out.append(String.format("%-22s %6d %5d %+8.2f %10s %6s %11s %6s %6.0f%n", x.label(), x.picks(), x.days(),
                    x.movePct(), fmt(x.vsAllPct(), "%+.2f"), fmt(x.vsAllT(), "%.1f"), fmt(x.vsMoversPct(), "%+.2f"),
                    fmt(x.vsMoversT(), "%.1f"), x.hitPct()));
        }
        return out.append("\nGross of costs. t uses one value per day; read nothing into it before about 20 days.\n")
                .toString();
    }

    private static String fmt(Double v, String f) {
        return v == null ? "-" : String.format(f, v);
    }
}
