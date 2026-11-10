package com.ram.trading.stock.service.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NSE trading calendar. Holidays come from Upstox (GET /v2/market/holidays, no login
 * needed), refreshed at startup and daily at 06:00 IST, and stored in market_holiday so a
 * restart or an Upstox outage keeps the list. A day is closed on Saturday, Sunday, or when
 * Upstox lists it as a TRADING_HOLIDAY with NSE among the closed exchanges (special
 * sessions such as Budget day or Muhurat are not closures).
 */
@Service
@Slf4j
public class MarketCalendarService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** date -> description, NSE-closed holidays only. */
    private final Map<LocalDate, String> holidays = new ConcurrentHashMap<>();

    @Value("${market.calendar.holidays-url:https://api.upstox.com/v2/market/holidays}")
    private String holidaysUrl = "https://api.upstox.com/v2/market/holidays";

    public MarketCalendarService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record TradingDay(LocalDate date, boolean tradingDay, String reason) {
    }

    @PostConstruct
    void init() {
        jdbc.execute("create table if not exists market_holiday (holiday_date date primary key, "
                + "description varchar(200), updated_at timestamp default current_timestamp)");
        loadStored();
        refresh();
    }

    /** 06:00 IST daily: re-fetch the year's holidays (Upstox can add or move one). */
    @Scheduled(cron = "${market.calendar.refresh-cron:0 0 6 * * *}", zone = "Asia/Kolkata")
    public void refresh() {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(holidaysUrl))
                    .header("Accept", "application/json").timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                log.warn("MARKET CALENDAR | holidays fetch HTTP {} | keeping {} stored", r.statusCode(), holidays.size());
                return;
            }
            Map<LocalDate, String> fetched = parse(r.body());
            if (fetched.isEmpty()) {
                log.warn("MARKET CALENDAR | holidays fetch returned none | keeping {} stored", holidays.size());
                return;
            }
            for (Map.Entry<LocalDate, String> e : fetched.entrySet()) {
                jdbc.update("replace into market_holiday (holiday_date, description) values (?, ?)", e.getKey(), e.getValue());
            }
            // Drop stored days of the fetched years that Upstox no longer lists (a moved holiday).
            for (LocalDate d : holidays.keySet()) {
                boolean sameYear = fetched.keySet().stream().anyMatch(f -> f.getYear() == d.getYear());
                if (sameYear && !fetched.containsKey(d)) {
                    jdbc.update("delete from market_holiday where holiday_date = ?", d);
                }
            }
            loadStored();
            LocalDate today = LocalDate.now(IST);
            log.info("MARKET CALENDAR | {} NSE holidays stored | today {} = {}", holidays.size(), today,
                    check(today).reason());
        } catch (Exception ex) {
            log.warn("MARKET CALENDAR | holidays fetch failed: {} | keeping {} stored", ex.getMessage(), holidays.size());
        }
    }

    /** NSE-closed trading holidays from the Upstox response. */
    Map<LocalDate, String> parse(String body) throws Exception {
        Map<LocalDate, String> out = new java.util.TreeMap<>();
        JsonNode data = mapper.readTree(body).path("data");
        for (JsonNode h : data) {
            if (!"TRADING_HOLIDAY".equals(h.path("holiday_type").asText())) {
                continue;
            }
            boolean nseClosed = false;
            for (JsonNode ex : h.path("closed_exchanges")) {
                nseClosed |= "NSE".equals(ex.asText());
            }
            if (nseClosed && h.hasNonNull("date")) {
                out.put(LocalDate.parse(h.get("date").asText()), h.path("description").asText("holiday"));
            }
        }
        return out;
    }

    private void loadStored() {
        Map<LocalDate, String> stored = new ConcurrentHashMap<>();
        jdbc.query("select holiday_date, description from market_holiday", rs -> {
            stored.put(rs.getDate(1).toLocalDate(), rs.getString(2));
        });
        holidays.clear();
        holidays.putAll(stored);
    }

    public TradingDay check(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return new TradingDay(date, false, "weekend");
        }
        String holiday = holidays.get(date);
        if (holiday != null) {
            return new TradingDay(date, false, "NSE holiday: " + holiday);
        }
        return new TradingDay(date, true, "trading day");
    }

    public TradingDay today() {
        return check(LocalDate.now(IST));
    }

    void holidaysForTest(Map<LocalDate, String> days) {
        holidays.clear();
        holidays.putAll(days);
    }

    public Map<LocalDate, String> holidays() {
        return new java.util.TreeMap<>(holidays);
    }
}
