package com.ram.trading.stock.service.earnings;

import com.ram.trading.stock.dto.history.Candle;
import com.ram.trading.stock.dto.history.HistoricalCandleResponse;
import com.ram.trading.stock.service.history.HistoricalCandleService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Scores each announced quarter (earnings drift study, step 2; rules fixed 2026-11-11 in the
 * strategy memory). From the collector's first-seen time:
 * <ul>
 *   <li>reaction day = the first session on or after the day it was seen (MORNING, MARKET), or
 *       after it (EVENING: announced after the close);</li>
 *   <li>reaction = the stock's return that session minus NIFTY's, in %;</li>
 *   <li>entry = the next session's open; drift20/40/60 = return from that open to the close
 *       20/40/60 sessions later, minus NIFTY's over the same days, in %;</li>
 *   <li>growth = net profit vs the previous quarter and the same quarter a year earlier, revenue
 *       vs a year earlier (null until that quarter is known, or when the base is not positive).</li>
 * </ul>
 * Refreshed daily at 08:10 IST (after the 07:30 price bootstrap) until the 60-session drift is
 * known. Every event is scored by the same rule with no choices: this is the forward test.
 * Research only; nothing trades.
 */
@Service
@Slf4j
public class EarningsEventService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
    static final int[] HOLDS = {20, 40, 60};

    private final JdbcTemplate jdbc;
    private final HistoricalCandleService history;

    public EarningsEventService(JdbcTemplate jdbc, HistoricalCandleService history) {
        this.jdbc = jdbc;
        this.history = history;
    }

    @PostConstruct
    void createTable() {
        jdbc.execute("create table if not exists earnings_event ("
                + "symbol varchar(32) not null, period varchar(16) not null, seen_at datetime not null, "
                + "slot varchar(16) not null, reaction_day date, reaction_pct double, entry_day date, entry_price double, "
                + "drift20 double, drift40 double, drift60 double, np_qoq_pct double, np_yoy_pct double, "
                + "rev_yoy_pct double, updated_at datetime not null, primary key (symbol, period), "
                + "index idx_earnings_event_day (reaction_day))");
    }

    /** Daily prices of one instrument, oldest first. */
    record Series(LocalDate[] date, double[] open, double[] close) {
        int at(LocalDate d) {
            int i = java.util.Arrays.binarySearch(date, d);
            return i >= 0 ? i : -1;
        }
    }

    /** Reaction and drifts (null when not known yet). */
    record Scored(LocalDate reactionDay, Double reactionPct, LocalDate entryDay, Double entryPrice, Double[] drift) {
    }

    /** Pure: the event's reaction and drifts from the stock's and NIFTY's daily series. */
    static Scored score(Series stock, Series nifty, LocalDate seenDay, String slot) {
        Double[] drift = new Double[HOLDS.length];
        int r = -1;
        for (int i = 0; i < stock.date().length; i++) {
            LocalDate d = stock.date()[i];
            if ("EVENING".equals(slot) ? d.isAfter(seenDay) : !d.isBefore(seenDay)) {
                r = i;
                break;
            }
        }
        if (r < 1) {
            return new Scored(null, null, null, null, drift);
        }
        int nr = nifty.at(stock.date()[r]), np = nifty.at(stock.date()[r - 1]);
        Double reaction = nr < 0 || np < 0 ? null
                : ((stock.close()[r] / stock.close()[r - 1]) - (nifty.close()[nr] / nifty.close()[np])) * 100;
        int e = r + 1;
        if (e >= stock.date().length) {
            return new Scored(stock.date()[r], reaction, null, null, drift);
        }
        double entry = stock.open()[e];
        int ne = nifty.at(stock.date()[e]);
        for (int k = 0; k < HOLDS.length; k++) {
            int x = e + HOLDS[k] - 1;
            if (x < stock.date().length && ne >= 0) {
                int nx = nifty.at(stock.date()[x]);
                if (nx >= 0) {
                    drift[k] = ((stock.close()[x] / entry) - (nifty.close()[nx] / nifty.open()[ne])) * 100;
                }
            }
        }
        return new Scored(stock.date()[r], reaction, stock.date()[e], entry, drift);
    }

    /** % change from base to value; null when either is unknown or the base is not positive. */
    static Double growth(Double value, Double base) {
        return value == null || base == null || value.isNaN() || base.isNaN() || base <= 0 ? null
                : (value / base - 1) * 100;
    }

    static YearMonth period(String p) {
        return YearMonth.parse(p, PERIOD);
    }

    static String period(YearMonth m) {
        return m.format(PERIOD);
    }

    @Scheduled(cron = "${earnings.events.cron:0 10 8 * * *}", zone = "Asia/Kolkata")
    public String refresh() {
        List<Map<String, Object>> open = jdbc.queryForList("select q.symbol, q.period, q.first_seen_at, q.first_seen_slot "
                + "from earnings_quarter q left join earnings_event e on e.symbol = q.symbol and e.period = q.period "
                + "where q.baseline = 0 and (e.symbol is null or e.drift60 is null) order by q.first_seen_at");
        if (open.isEmpty()) {
            return "no events to score";
        }
        LocalDate first = toTime(open.get(0).get("first_seen_at")).toLocalDate().minusDays(10);
        Series nifty = niftyFrom(first);
        if (nifty == null) {
            log.warn("EARNINGS EVENTS | no NIFTY daily history; {} events wait", open.size());
            return "no NIFTY history";
        }
        int scored = 0;
        for (Map<String, Object> q : open) {
            String symbol = (String) q.get("symbol"), p = (String) q.get("period");
            LocalDateTime seen = toTime(q.get("first_seen_at"));
            try {
                Scored s = score(stockFrom(symbol, seen.toLocalDate().minusDays(10)), nifty, seen.toLocalDate(),
                        (String) q.get("first_seen_slot"));
                YearMonth m = period(p);
                Double np = value(symbol, p, "net_profit");
                jdbc.update("replace into earnings_event (symbol, period, seen_at, slot, reaction_day, reaction_pct, "
                                + "entry_day, entry_price, drift20, drift40, drift60, np_qoq_pct, np_yoy_pct, rev_yoy_pct, "
                                + "updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        symbol, p, seen, q.get("first_seen_slot"), s.reactionDay(), s.reactionPct(), s.entryDay(),
                        s.entryPrice(), s.drift()[0], s.drift()[1], s.drift()[2],
                        growth(np, value(symbol, period(m.minusMonths(3)), "net_profit")),
                        growth(np, value(symbol, period(m.minusMonths(12)), "net_profit")),
                        growth(value(symbol, p, "revenue"), value(symbol, period(m.minusMonths(12)), "revenue")),
                        LocalDateTime.now(IST));
                scored++;
            } catch (Exception ex) {
                log.warn("EARNINGS EVENTS | {} {}: {}", symbol, p, ex.getMessage());
            }
        }
        String line = scored + " of " + open.size() + " events scored";
        log.info("EARNINGS EVENTS | {}", line);
        return line;
    }

    private Double value(String symbol, String period, String column) {
        List<Double> v = jdbc.queryForList("select " + column + " from earnings_quarter where symbol = ? and period = ?",
                Double.class, symbol, period);
        return v.isEmpty() ? null : v.get(0);
    }

    /** The stock's daily prices from the stored bootstrap history. */
    private Series stockFrom(String symbol, LocalDate from) {
        List<Map<String, Object>> rows = jdbc.queryForList("select trade_date, open_price, close_price from historical_price "
                + "where symbol = ? and interval_type = 'DAY' and trade_date >= ? and close_price > 0 order by trade_date",
                symbol, from);
        LocalDate[] d = new LocalDate[rows.size()];
        double[] o = new double[rows.size()], c = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            Object t = rows.get(i).get("trade_date");
            d[i] = t instanceof java.sql.Date sd ? sd.toLocalDate() : (LocalDate) t;
            o[i] = ((Number) rows.get(i).get("open_price")).doubleValue();
            c[i] = ((Number) rows.get(i).get("close_price")).doubleValue();
        }
        return new Series(d, o, c);
    }

    /** NIFTY daily from the Upstox history (the same daily source as the stocks). */
    private Series niftyFrom(LocalDate from) {
        try {
            HistoricalCandleResponse r = history.getHistoricalCandles("NIFTY", "day", from, LocalDate.now(IST))
                    .block(Duration.ofSeconds(30));
            if (r == null || r.getCandles() == null || r.getCandles().isEmpty()) {
                return null;
            }
            List<Candle> c = new ArrayList<>(r.getCandles());
            c.sort(java.util.Comparator.comparing(Candle::getDateTime));
            LocalDate[] d = new LocalDate[c.size()];
            double[] o = new double[c.size()], cl = new double[c.size()];
            for (int i = 0; i < c.size(); i++) {
                d[i] = c.get(i).getDateTime().atZoneSameInstant(IST).toLocalDate();
                o[i] = c.get(i).getOpen().doubleValue();
                cl[i] = c.get(i).getClose().doubleValue();
            }
            return new Series(d, o, cl);
        } catch (Exception ex) {
            log.warn("EARNINGS EVENTS | NIFTY history failed: {}", ex.getMessage());
            return null;
        }
    }

    /** The latest scored events, newest first. */
    public List<Map<String, Object>> latest(int limit) {
        return jdbc.queryForList("select * from earnings_event order by seen_at desc limit ?", Math.max(1, Math.min(limit, 500)));
    }

    private static LocalDateTime toTime(Object o) {
        return o instanceof java.sql.Timestamp ts ? ts.toLocalDateTime()
                : o instanceof LocalDateTime t ? t : LocalDateTime.parse(String.valueOf(o).replace(' ', 'T'));
    }
}
