package com.ram.trading.market.data.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Asks stock-service (GET /market/trading-day) whether today is an NSE trading day, once per
 * day, so scheduled jobs can skip market holidays the way their MON-FRI cron skips weekends.
 * Fail-open: when stock-service cannot answer, a weekday counts as a trading day (and the
 * answer is retried on the next call), so a real trading day is never missed.
 */
@Component
@Slf4j
public class TradingCalendar {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${market.calendar.url:http://localhost:8081}")
    private String baseUrl = "http://localhost:8081";

    private volatile LocalDate cachedDay;
    private volatile boolean cachedTrading = true;
    private volatile String cachedReason = "trading day";

    /** True on an NSE trading day (IST). */
    public boolean isTradingDay() {
        LocalDate today = LocalDate.now(IST);
        if (today.equals(cachedDay)) {
            return cachedTrading;
        }
        DayOfWeek dow = today.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            remember(today, false, "weekend");
            return false;
        }
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/market/trading-day?date=" + today))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) {
                JsonNode n = mapper.readTree(r.body());
                remember(today, n.path("tradingDay").asBoolean(true), n.path("reason").asText(""));
                if (!cachedTrading) {
                    log.info("TRADING CALENDAR | {} closed ({}): scheduled market jobs skip today", today, cachedReason);
                }
                return cachedTrading;
            }
            log.warn("TRADING CALENDAR | stock-service HTTP {}: treating {} as a trading day", r.statusCode(), today);
        } catch (Exception ex) {
            log.warn("TRADING CALENDAR | stock-service unavailable ({}): treating {} as a trading day", ex.getMessage(), today);
        }
        return true;
    }

    /** Why today is closed, e.g. "NSE holiday: Diwali-Balipratipada" (after {@link #isTradingDay}). */
    public String reason() {
        return cachedReason;
    }

    private void remember(LocalDate day, boolean trading, String reason) {
        cachedTrading = trading;
        cachedReason = reason;
        cachedDay = day;
    }
}
