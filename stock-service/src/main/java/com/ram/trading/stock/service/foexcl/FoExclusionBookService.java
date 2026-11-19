package com.ram.trading.stock.service.foexcl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.stock.service.auth.UpstoxTokenService;
import com.ram.trading.stock.service.calendar.MarketCalendarService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipInputStream;

/**
 * F&amp;O EXCLUSION paper book (separate strategy, paper only; built 2026-11-19 after test F2 of the pre-registered
 * study research/fo_inclusion_study.py passed: 55 trades, +1.85% net, t 6.0). Rules, fixed from that test:
 * <ul>
 *   <li>event: a stock being removed from F&amp;O. NSE stops listing new monthly futures for it, so in the daily
 *       F&amp;O bhavcopy it has fewer futures expiries than the other stocks. Its event day is its LAST futures
 *       expiry (the last day it has futures);</li>
 *   <li>eligible: previous close &gt;= Rs 100 and 20-day average traded value &gt;= Rs 10 crore;</li>
 *   <li>on the event day: 09:20 short Rs 50,000 of the stock, 15:25 cover;</li>
 *   <li>costs 0.13% of the entry value a round trip (as in the study); the result is also shown against NIFTY.</li>
 * </ul>
 * It reads the public NSE F&amp;O bhavcopy and Upstox market data, writes only its own tables, never places an order
 * and does not touch the intraday, FADE, momentum, options, index-rebalance, earnings or collector code.
 */
@Service
@Slf4j
public class FoExclusionBookService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final String NIFTY = "NSE_INDEX|Nifty 50";
    static final double NOTIONAL = 50_000, COST_PCT = 0.13, MIN_PRICE = 100, MIN_TURNOVER = 10e7;
    static final String RULES = "Stocks being removed from F&O (fewer futures expiries than the rest in the NSE F&O "
            + "bhavcopy): on their last futures day, 09:20 short Rs 50,000, 15:25 cover; price >= Rs 100 and 20-day "
            + "traded value >= Rs 10 cr; costs 0.13% a round trip";

    private final JdbcTemplate jdbc;
    private final UpstoxTokenService tokens;
    private final MarketCalendarService calendar;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final AtomicBoolean busy = new AtomicBoolean();

    @Value("${fo-exclusion.book.enabled:true}")
    private boolean enabled = true;

    @Value("${upstox.base-url:https://api.upstox.com/v2}")
    private String baseUrl = "https://api.upstox.com/v2";

    @Value("${fo-exclusion.book.notify-url:http://localhost:8087/notifications/send}")
    private String notifyUrl = "http://localhost:8087/notifications/send";

    public FoExclusionBookService(JdbcTemplate jdbc, UpstoxTokenService tokens, MarketCalendarService calendar) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.calendar = calendar;
    }

    @PostConstruct
    void createTables() {
        jdbc.execute("create table if not exists fo_excl_event (id bigint auto_increment primary key, "
                + "event_date date not null, symbol varchar(40) not null, expiries varchar(100), detected_from date, "
                + "created_at datetime not null, unique key uq_fo_excl_event (event_date, symbol))");
        jdbc.execute("create table if not exists fo_excl_trade (id bigint auto_increment primary key, "
                + "event_date date not null, symbol varchar(40) not null, instrument_key varchar(64), status varchar(10) not null, "
                + "reason varchar(255), qty int, entry_price double, nifty_entry double, entry_at datetime, exit_price double, "
                + "nifty_exit double, exit_at datetime, gross_pnl double, charges double, net_pnl double, excess_pct double, "
                + "unique key uq_fo_excl_trade (event_date, symbol))");
    }

    // ------------------------------------------------------------------ detection (pure, tested)

    /** {symbol: sorted futures expiries} from the NSE F&amp;O UDiFF bhavcopy CSV (stock futures rows, FinInstrmTp STF). */
    static Map<String, TreeSet<LocalDate>> stockFutures(Reader csv) throws Exception {
        Map<String, TreeSet<LocalDate>> out = new TreeMap<>();
        for (CSVRecord r : CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setTrim(true)
                .setIgnoreHeaderCase(true).build().parse(csv)) {
            if (!r.isMapped("FinInstrmTp") || !"STF".equals(r.get("FinInstrmTp"))) {
                continue;
            }
            try {
                out.computeIfAbsent(r.get("TckrSymb"), k -> new TreeSet<>()).add(LocalDate.parse(r.get("XpryDt")));
            } catch (Exception ex) {
                // malformed row: skipped
            }
        }
        return out;
    }

    /**
     * Stocks with fewer futures expiries than the full set of stock-futures expiries listed that day (normally 3):
     * NSE lists no new series for a stock it is removing. Returns {symbol: last futures day}. Empty when the day's
     * file looks incomplete (fewer than 2 expiries overall or fewer than 50 stocks).
     */
    static Map<String, LocalDate> beingRemoved(Map<String, TreeSet<LocalDate>> futures) {
        TreeSet<LocalDate> all = new TreeSet<>();
        futures.values().forEach(all::addAll);
        Map<String, LocalDate> out = new TreeMap<>();
        if (all.size() < 2 || futures.size() < 50) {
            return out;
        }
        for (Map.Entry<String, TreeSet<LocalDate>> e : futures.entrySet()) {
            if (e.getValue().size() < all.size() && e.getValue().last().isBefore(all.last())) {
                out.put(e.getKey(), e.getValue().last());
            }
        }
        return out;
    }

    /** Average daily traded value of the last 20 sessions, from daily candles [time, o, h, l, c, volume], newest first. */
    static double avgTurnover(JsonNode candles) {
        if (candles.size() < 20) {
            return 0;
        }
        double sum = 0;
        for (int i = 0; i < 20; i++) {
            sum += candles.get(i).get(4).asDouble() * candles.get(i).get(5).asDouble();
        }
        return sum / 20;
    }

    /** Paper result of one short: [gross Rs, charges Rs, net Rs, excess % vs NIFTY after costs]. */
    static double[] shortResult(int qty, double in, double out, double niftyIn, double niftyOut) {
        double gross = qty * (in - out);
        double charges = qty * in * COST_PCT / 100;
        double excess = -((out / in - 1) - (niftyOut / niftyIn - 1)) * 100 - COST_PCT;
        return new double[]{gross, charges, gross - charges, excess};
    }

    // ------------------------------------------------------------------ evening scan

    @Scheduled(cron = "0 45 19,22 * * *", zone = "Asia/Kolkata")
    public void scanJob() {
        scan(LocalDate.now(IST));
    }

    /** Reads the latest F&amp;O bhavcopy on or before {@code day} (up to 5 days back) and records new removals. */
    public synchronized String scan(LocalDate day) {
        if (!enabled) {
            return "disabled";
        }
        for (int back = 0; back < 5; back++) {
            LocalDate d = day.minusDays(back);
            String url = "https://nsearchives.nseindia.com/content/fo/BhavCopy_NSE_FO_0_0_0_"
                    + d.format(DateTimeFormatter.BASIC_ISO_DATE) + "_F_0000.csv.zip";
            try {
                HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                        .header("User-Agent", "research-client/1.0").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() != 200) {
                    continue;
                }
                Map<String, TreeSet<LocalDate>> fut;
                try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(r.body()))) {
                    if (z.getNextEntry() == null) {
                        continue;
                    }
                    fut = stockFutures(new InputStreamReader(z, StandardCharsets.UTF_8));
                }
                Map<String, LocalDate> removed = beingRemoved(fut);
                List<String> fresh = new ArrayList<>();
                for (Map.Entry<String, LocalDate> e : removed.entrySet()) {
                    int n = jdbc.update("insert ignore into fo_excl_event (event_date, symbol, expiries, detected_from, created_at) "
                                    + "values (?, ?, ?, ?, ?)", Date.valueOf(e.getValue()), e.getKey(),
                            fut.get(e.getKey()).toString(), Date.valueOf(d), Timestamp.valueOf(LocalDateTime.now(IST)));
                    if (n > 0) {
                        fresh.add(e.getKey() + " (last futures day " + e.getValue() + ")");
                    }
                }
                String msg = String.format("F&O bhavcopy %s: %d stock futures, %d being removed%s", d, fut.size(), removed.size(),
                        fresh.isEmpty() ? "" : "; NEW: " + String.join(", ", fresh));
                if (!fresh.isEmpty()) {
                    notifySlack("F&O EXCLUSION book: removal found", msg);
                }
                log.info("FO EXCLUSION | {}", msg);
                return msg;
            } catch (Exception ex) {
                log.warn("FO EXCLUSION | bhavcopy {} failed: {}", d, ex.toString());
            }
        }
        return "no F&O bhavcopy found for the 5 days up to " + day;
    }

    // ------------------------------------------------------------------ trading

    @Scheduled(cron = "0 20,22,24 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void entryJob() {
        enter(LocalDate.now(IST), !LocalTime.now(IST).isBefore(LocalTime.of(9, 24)));
    }

    @Scheduled(cron = "0 25,26,27 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void exitJob() {
        exit(LocalDate.now(IST));
    }

    @Scheduled(cron = "0 50 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void settleJob() {
        settleFromCandles();
    }

    public synchronized String enter(LocalDate today, boolean lastAttempt) {
        if (!enabled || !calendar.check(today).tradingDay()) {
            return "disabled or not a trading day";
        }
        List<Map<String, Object>> events = jdbc.queryForList("select e.* from fo_excl_event e left join fo_excl_trade t "
                + "on t.event_date = e.event_date and t.symbol = e.symbol where e.event_date = ? and t.id is null", Date.valueOf(today));
        if (events.isEmpty()) {
            return "no event today";
        }
        try {
            String token = token();
            Map<String, String> keys = new HashMap<>();
            for (Map<String, Object> e : events) {
                String sym = (String) e.get("symbol");
                List<String> k = jdbc.queryForList("select instrument_key from instrument_master where segment = 'NSE_EQ' "
                        + "and upper(trading_symbol) = ? limit 1", String.class, sym);
                if (k.isEmpty()) {
                    record(today, sym, null, "SKIPPED", "symbol not in instrument_master");
                } else {
                    keys.put(sym, k.get(0));
                }
            }
            List<String> all = new ArrayList<>(keys.values());
            all.add(NIFTY);
            Map<String, Double> ltp = ltp(token, all);
            Double nifty = ltp.get(NIFTY);
            if (nifty == null || nifty <= 0) {
                throw new IllegalStateException("no NIFTY price");
            }
            List<String> opened = new ArrayList<>();
            for (Map.Entry<String, String> k : keys.entrySet()) {
                String sym = k.getKey(), key = k.getValue();
                Double px = ltp.get(key);
                if (px == null || px <= 0) {
                    if (lastAttempt) {
                        record(today, sym, key, "SKIPPED", "no price at 09:20");
                    }
                    continue;
                }
                JsonNode daily = get(token, "/historical-candle/" + enc(key) + "/day/" + today.minusDays(1) + "/"
                        + today.minusDays(45)).path("data").path("candles");
                double prevClose = daily.size() > 0 ? daily.get(0).get(4).asDouble() : 0;
                double turnover = avgTurnover(daily);
                if (prevClose < MIN_PRICE || turnover < MIN_TURNOVER) {
                    record(today, sym, key, "SKIPPED", String.format("ineligible: prev close %.1f, 20-day value Rs %.1f cr",
                            prevClose, turnover / 1e7));
                    continue;
                }
                int qty = (int) Math.max(1, Math.floor(NOTIONAL / px));
                jdbc.update("insert ignore into fo_excl_trade (event_date, symbol, instrument_key, status, qty, entry_price, "
                                + "nifty_entry, entry_at) values (?, ?, ?, 'OPEN', ?, ?, ?, ?)",
                        Date.valueOf(today), sym, key, qty, px, nifty, Timestamp.valueOf(LocalDateTime.now(IST)));
                opened.add(String.format("%s %d @ %.2f", sym, qty, px));
            }
            String msg = String.format("%s: %d paper shorts opened at 09:20 (%s), NIFTY %.1f", today, opened.size(),
                    String.join(", ", opened), nifty);
            notifySlack("F&O EXCLUSION book: entries", msg);
            log.info("FO EXCLUSION | {}", msg);
            return msg;
        } catch (Exception ex) {
            log.warn("FO EXCLUSION | entry failed: {}", ex.toString());
            if (lastAttempt) {
                for (Map<String, Object> e : events) {
                    record(today, (String) e.get("symbol"), null, "SKIPPED", "data error at entry: " + ex.getMessage());
                }
            }
            return "entry failed: " + ex.getMessage();
        }
    }

    private void record(LocalDate day, String sym, String key, String status, String reason) {
        jdbc.update("insert ignore into fo_excl_trade (event_date, symbol, instrument_key, status, reason) values (?, ?, ?, ?, ?)",
                Date.valueOf(day), sym, key, status, trim(reason, 255));
    }

    public synchronized String exit(LocalDate today) {
        if (!enabled || !busy.compareAndSet(false, true)) {
            return "busy or disabled";
        }
        try {
            List<Map<String, Object>> open = jdbc.queryForList(
                    "select * from fo_excl_trade where status = 'OPEN' and event_date = ?", Date.valueOf(today));
            if (open.isEmpty()) {
                return "nothing open";
            }
            String token = token();
            List<String> keys = new ArrayList<>(open.stream().map(r -> (String) r.get("instrument_key")).toList());
            keys.add(NIFTY);
            Map<String, Double> ltp = ltp(token, keys);
            Double nifty = ltp.get(NIFTY);
            if (nifty == null || nifty <= 0) {
                return "no NIFTY price, will retry";
            }
            int closed = 0;
            for (Map<String, Object> r : open) {
                Double px = ltp.get((String) r.get("instrument_key"));
                if (px != null && px > 0) {
                    close(r, px, nifty, "15:25");
                    closed++;
                }
            }
            summary(today);
            return closed + " closed";
        } catch (Exception ex) {
            log.warn("FO EXCLUSION | exit failed: {}", ex.toString());
            return "exit failed: " + ex.getMessage();
        } finally {
            busy.set(false);
        }
    }

    /** Anything still open (exit missed) is closed at the close of its 15:25 one-minute candle. */
    public synchronized String settleFromCandles() {
        List<Map<String, Object>> open = jdbc.queryForList("select * from fo_excl_trade where status = 'OPEN'");
        if (open.isEmpty()) {
            return "nothing open";
        }
        try {
            String token = token();
            Map<LocalDate, Double> niftyAt = new HashMap<>();
            for (Map<String, Object> r : open) {
                LocalDate day = ((Date) r.get("event_date")).toLocalDate();
                if (day.equals(LocalDate.now(IST)) && LocalTime.now(IST).isBefore(LocalTime.of(15, 30))) {
                    continue;
                }
                Double n = niftyAt.computeIfAbsent(day, d -> candle1525(token, NIFTY, d));
                Double px = candle1525(token, (String) r.get("instrument_key"), day);
                if (n != null && px != null) {
                    close(r, px, n, "15:25 candle (settled after the close)");
                }
            }
            return "settled";
        } catch (Exception ex) {
            log.warn("FO EXCLUSION | settle failed: {}", ex.toString());
            return "settle failed: " + ex.getMessage();
        }
    }

    private void close(Map<String, Object> r, double px, double nifty, String how) {
        double[] res = shortResult(((Number) r.get("qty")).intValue(), d(r, "entry_price"), px, d(r, "nifty_entry"), nifty);
        jdbc.update("update fo_excl_trade set status = 'CLOSED', reason = ?, exit_price = ?, nifty_exit = ?, exit_at = ?, "
                        + "gross_pnl = ?, charges = ?, net_pnl = ?, excess_pct = ? where id = ? and status = 'OPEN'",
                how, px, nifty, Timestamp.valueOf(LocalDateTime.now(IST)), res[0], res[1], res[2], res[3], r.get("id"));
    }

    private void summary(LocalDate day) {
        Map<String, Object> s = jdbc.queryForMap("select count(*) n, coalesce(sum(net_pnl), 0) net, coalesce(avg(excess_pct), 0) ex "
                + "from fo_excl_trade where status = 'CLOSED' and event_date = ?", Date.valueOf(day));
        Map<String, Object> all = jdbc.queryForMap("select count(*) n, coalesce(sum(net_pnl), 0) net, coalesce(avg(excess_pct), 0) ex "
                + "from fo_excl_trade where status = 'CLOSED'");
        String msg = String.format("%s: %s shorts covered at 15:25, net Rs %.0f, average %+.2f%% vs NIFTY after costs%n"
                        + "Book so far: %s trades, net Rs %.0f, average %+.2f%% (study F2: +1.85%%)",
                day, s.get("n"), d(s, "net"), d(s, "ex"), all.get("n"), d(all, "net"), d(all, "ex"));
        notifySlack("F&O EXCLUSION book: day closed", msg);
        log.info("FO EXCLUSION | {}", msg.replace('\n', ' '));
    }

    // ------------------------------------------------------------------ report

    public String report() {
        StringBuilder sb = new StringBuilder("F&O EXCLUSION PAPER BOOK (separate strategy, paper only)\n").append(RULES).append("\n\n");
        List<Map<String, Object>> up = jdbc.queryForList("select event_date, symbol, expiries, detected_from from fo_excl_event "
                + "where event_date >= ? order by event_date, symbol", Date.valueOf(LocalDate.now(IST)));
        sb.append(up.isEmpty() ? "No upcoming removal (the NSE F&O bhavcopy is checked every evening).\n" : "Upcoming:\n");
        for (Map<String, Object> u : up) {
            sb.append(String.format("  %s  %-12s futures %s (seen %s)%n", u.get("event_date"), u.get("symbol"), u.get("expiries"),
                    u.get("detected_from")));
        }
        List<Map<String, Object>> trades = jdbc.queryForList("select * from fo_excl_trade order by event_date, symbol");
        if (!trades.isEmpty()) {
            sb.append("\nTrades:\n");
        }
        for (Map<String, Object> t : trades) {
            sb.append(String.format("  %s  %-12s %-7s %s%n", t.get("event_date"), t.get("symbol"), t.get("status"),
                    "CLOSED".equals(t.get("status")) ? String.format("qty %s  %.2f -> %.2f  net Rs %.0f  %+.2f%% vs NIFTY",
                            t.get("qty"), d(t, "entry_price"), d(t, "exit_price"), d(t, "net_pnl"), d(t, "excess_pct"))
                            : String.valueOf(t.get("reason") == null ? "" : t.get("reason"))));
        }
        Map<String, Object> all = jdbc.queryForMap("select count(*) n, coalesce(sum(net_pnl), 0) net, avg(excess_pct) ex, "
                + "coalesce(sum(net_pnl > 0), 0) wins from fo_excl_trade where status = 'CLOSED'");
        sb.append(String.format("%nClosed %s, winners %s, net Rs %.0f, average %s vs NIFTY after costs (study F2: +1.85%%)%n",
                all.get("n"), all.get("wins"), d(all, "net"), all.get("ex") == null ? "-" : String.format("%+.2f%%", d(all, "ex"))));
        return sb.toString();
    }

    // ------------------------------------------------------------------ Upstox

    private String token() {
        String t = tokens.getAccessToken().block(Duration.ofSeconds(15));
        if (t == null || t.isBlank()) {
            throw new IllegalStateException("no Upstox token");
        }
        return t.trim();
    }

    private JsonNode get(String token, String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + token).header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IllegalStateException("Upstox " + path.split("\\?")[0] + " HTTP " + r.statusCode());
        }
        return mapper.readTree(r.body());
    }

    /** Last traded prices by instrument key (the response is keyed by symbol; instrument_token carries the key). */
    Map<String, Double> ltp(String token, List<String> keys) throws Exception {
        Map<String, Double> out = new HashMap<>();
        for (int i = 0; i < keys.size(); i += 400) {
            String joined = String.join(",", keys.subList(i, Math.min(keys.size(), i + 400)));
            JsonNode data = get(token, "/market-quote/ltp?instrument_key=" + enc(joined)).path("data");
            data.fields().forEachRemaining(f -> {
                String k = f.getValue().path("instrument_token").asText(null);
                double p = f.getValue().path("last_price").asDouble(0);
                if (k != null) {
                    out.put(k, p);
                }
            });
        }
        return out;
    }

    private Double candle1525(String token, String key, LocalDate day) {
        try {
            JsonNode c = get(token, day.equals(LocalDate.now(IST))
                    ? "/historical-candle/intraday/" + enc(key) + "/1minute"
                    : "/historical-candle/" + enc(key) + "/1minute/" + day + "/" + day).path("data").path("candles");
            for (JsonNode x : c) {
                if (x.get(0).asText().contains("T15:25:")) {
                    return x.get(4).asDouble();
                }
            }
        } catch (Exception ex) {
            log.warn("FO EXCLUSION | 15:25 candle for {} {} failed: {}", key, day, ex.toString());
        }
        return null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static double d(Map<String, Object> r, String col) {
        Object v = r.get(col);
        return v == null ? 0 : ((Number) v).doubleValue();
    }

    private static String trim(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }

    private void notifySlack(String title, String message) {
        try {
            String body = mapper.writeValueAsString(Map.of("channel", "SLACK", "title", title, "message", message));
            http.sendAsync(HttpRequest.newBuilder(URI.create(notifyUrl)).header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ex -> {
                        log.warn("FO EXCLUSION | Slack send failed: {}", ex.toString());
                        return null;
                    });
        } catch (Exception ex) {
            log.warn("FO EXCLUSION | Slack send failed: {}", ex.toString());
        }
    }
}
