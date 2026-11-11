package com.ram.trading.stock.service.earnings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.stock.service.auth.UpstoxTokenService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Earnings drift data, collected forward (option B, 2026-11-11): no free source gives past
 * results dates, so we build our own. Three times a day the top companies by turnover are
 * read from the Upstox quarterly income statement (the API keeps the last 4 quarters). The
 * first poll that sees a quarter dates its announcement:
 * <ul>
 *   <li>MORNING (08:30): announced after the previous evening poll, before today's open;</li>
 *   <li>MARKET (15:40): announced during today's session (or just after the close);</li>
 *   <li>EVENING (21:00): announced after today's close; the reaction session is the next one.</li>
 * </ul>
 * The first-seen numbers are kept as they were (point in time, never overwritten). Quarters
 * already listed when a company is first read are a BASELINE, not announcements. Research data
 * only: nothing here trades. Upstox's own publishing delay is unknown and must be checked
 * against a few real announcement dates before the data is used.
 */
@Service
@Slf4j
public class EarningsCollectorService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate jdbc;
    private final UpstoxTokenService tokens;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<String> lastRun = new AtomicReference<>("not run yet");

    @Value("${earnings.collector.enabled:true}")
    private boolean enabled = true;

    @Value("${earnings.collector.universe:500}")
    private int universe = 500;

    /** Pause between Upstox calls (about 4 a second, well under the API limits). */
    @Value("${earnings.collector.pause-ms:250}")
    private long pauseMs = 250;

    @Value("${upstox.base-url:https://api.upstox.com/v2}")
    private String baseUrl = "https://api.upstox.com/v2";

    public EarningsCollectorService(JdbcTemplate jdbc, UpstoxTokenService tokens) {
        this.jdbc = jdbc;
        this.tokens = tokens;
    }

    @PostConstruct
    void createTable() {
        jdbc.execute("create table if not exists earnings_quarter ("
                + "symbol varchar(32) not null, period varchar(16) not null, isin varchar(16) not null, "
                + "statement varchar(16) not null, revenue double, operating_profit double, net_profit double, "
                + "first_seen_at datetime not null, first_seen_slot varchar(16) not null, baseline tinyint not null, "
                + "primary key (symbol, period), index idx_earnings_seen (first_seen_at))");
    }

    @Scheduled(cron = "${earnings.collector.morning-cron:0 30 8 * * *}", zone = "Asia/Kolkata")
    public void morning() {
        collect("MORNING");
    }

    @Scheduled(cron = "${earnings.collector.market-cron:0 40 15 * * *}", zone = "Asia/Kolkata")
    public void market() {
        collect("MARKET");
    }

    @Scheduled(cron = "${earnings.collector.evening-cron:0 0 21 * * *}", zone = "Asia/Kolkata")
    public void evening() {
        collect("EVENING");
    }

    /** One poll of the universe; returns a one-line summary. Skips if a poll is already running. */
    public String collect(String slot) {
        if (!enabled) {
            return "disabled";
        }
        if (!running.compareAndSet(false, true)) {
            return "already running";
        }
        LocalDateTime started = LocalDateTime.now(IST);
        int companies = 0, failed = 0, fresh = 0, baseline = 0;
        try {
            String token = tokens.getAccessToken().block(Duration.ofSeconds(15));
            if (token == null || token.isBlank()) {
                return record(started, slot, "no Upstox token (not logged in?)");
            }
            for (Map<String, Object> c : companies()) {
                String symbol = (String) c.get("symbol"), isin = (String) c.get("isin");
                try {
                    Fetched f = fetch(token, isin);
                    if (f == null) {
                        failed++;
                        continue;
                    }
                    companies++;
                    boolean known = Boolean.TRUE.equals(jdbc.queryForObject(
                            "select count(*) > 0 from earnings_quarter where symbol = ?", Boolean.class, symbol));
                    for (Map.Entry<String, double[]> q : f.quarters().entrySet()) {
                        int added = jdbc.update("insert ignore into earnings_quarter (symbol, period, isin, statement, "
                                        + "revenue, operating_profit, net_profit, first_seen_at, first_seen_slot, baseline) "
                                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", symbol, q.getKey(), isin, f.statement(),
                                q.getValue()[0], q.getValue()[1], q.getValue()[2], LocalDateTime.now(IST),
                                known ? slot : "BASELINE", known ? 0 : 1);
                        if (added > 0 && known) {
                            fresh++;
                            log.info("EARNINGS | new quarter {} {} seen at {} ({})", symbol, q.getKey(), slot, f.statement());
                        } else if (added > 0) {
                            baseline++;
                        }
                    }
                } catch (Exception ex) {
                    failed++;
                    log.debug("EARNINGS | {} failed: {}", symbol, ex.getMessage());
                }
                Thread.sleep(pauseMs);
            }
            return record(started, slot, String.format("%d companies read, %d failed, %d new quarters, %d baseline",
                    companies, failed, fresh, baseline));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return record(started, slot, "interrupted");
        } catch (Exception ex) {
            log.warn("EARNINGS | poll failed: {}", ex.toString());
            return record(started, slot, "failed: " + ex.getMessage());
        } finally {
            running.set(false);
        }
    }

    private String record(LocalDateTime started, String slot, String text) {
        String line = started + " " + slot + ": " + text;
        lastRun.set(line);
        log.info("EARNINGS | {}", line);
        return line;
    }

    /** The most traded companies of the last 30 days (shares only: ISIN INE, not funds or ETFs). */
    List<Map<String, Object>> companies() {
        return jdbc.queryForList("select h.symbol symbol, max(i.isin) isin from historical_price h "
                + "join instrument_master i on i.trading_symbol = h.symbol and i.segment = 'NSE_EQ' "
                + "and i.instrument_type = 'EQ' and i.isin like 'INE%' "
                + "where h.interval_type = 'DAY' and h.trade_date >= ? group by h.symbol having count(*) >= 15 "
                + "order by avg(h.close_price * h.volume) desc limit ?", LocalDate.now(IST).minusDays(30), universe);
    }

    record Fetched(String statement, Map<String, double[]> quarters) {
    }

    /** Consolidated quarterly statement, else standalone; null when Upstox has neither. */
    private Fetched fetch(String token, String isin) throws Exception {
        for (String type : List.of("consolidated", "standalone")) {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/fundamentals/" + isin
                                    + "/income-statement?type=" + type + "&time_period=quarterly"))
                            .header("Authorization", "Bearer " + token).header("Accept", "application/json")
                            .timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) {
                Map<String, double[]> q = parse(r.body());
                if (!q.isEmpty()) {
                    return new Fetched(type, q);
                }
            }
        }
        return null;
    }

    /** period -> {revenue, operating profit, net profit} (null-safe: a missing value is NaN). */
    Map<String, double[]> parse(String body) throws Exception {
        Map<String, double[]> out = new LinkedHashMap<>();
        for (JsonNode cat : mapper.readTree(body).path("data").path("income_statement")) {
            int k = switch (cat.path("category").asText()) {
                case "revenue" -> 0;
                case "operating_profit" -> 1;
                case "net_profit" -> 2;
                default -> -1;
            };
            if (k < 0) {
                continue;
            }
            for (JsonNode h : cat.path("history")) {
                String period = h.path("period").asText("");
                if (period.isBlank()) {
                    continue;
                }
                double[] v = out.computeIfAbsent(period, p -> new double[]{Double.NaN, Double.NaN, Double.NaN});
                v[k] = h.hasNonNull("value") ? h.get("value").asDouble() : Double.NaN;
            }
        }
        return out;
    }

    public record Status(String lastRun, boolean running, long companies, long quarters, long announcements,
                         List<Map<String, Object>> latest) {
    }

    public Status status() {
        Long companies = jdbc.queryForObject("select count(distinct symbol) from earnings_quarter", Long.class);
        Long quarters = jdbc.queryForObject("select count(*) from earnings_quarter", Long.class);
        Long announced = jdbc.queryForObject("select count(*) from earnings_quarter where baseline = 0", Long.class);
        List<Map<String, Object>> latest = jdbc.queryForList("select symbol, period, first_seen_at, first_seen_slot, "
                + "net_profit from earnings_quarter where baseline = 0 order by first_seen_at desc limit 20");
        return new Status(lastRun.get(), running.get(), companies == null ? 0 : companies, quarters == null ? 0 : quarters,
                announced == null ? 0 : announced, latest);
    }

    /** Slot for a manual run now, by the time of day (as the scheduled polls would label it). */
    static String slotAt(LocalTime t) {
        return t.isBefore(LocalTime.of(9, 15)) ? "MORNING" : t.isBefore(LocalTime.of(15, 40)) ? "MARKET" : "EVENING";
    }

    public String collectNow() {
        return collect(slotAt(LocalTime.now(IST)));
    }
}
