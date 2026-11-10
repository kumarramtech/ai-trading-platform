package com.ram.trading.signal.engine.calendar;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "Is the market live?" for the dashboard: the NSE calendar (stock-service) plus the clock
 * gives the session state; the age of the last stored NIFTY minute candle tells whether our
 * data feed is actually live. Read-only.
 */
@Service
public class MarketStatusService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalTime PRE_OPEN = LocalTime.of(9, 0);
    static final LocalTime OPEN = LocalTime.of(9, 15);
    static final LocalTime CLOSE = LocalTime.of(15, 30);
    private static final Duration FEED_STALE_AFTER = Duration.ofMinutes(3);

    private final TradingCalendar calendar;
    private final JdbcTemplate jdbc;

    public MarketStatusService(TradingCalendar calendar, JdbcTemplate jdbc) {
        this.calendar = calendar;
        this.jdbc = jdbc;
    }

    public record Holiday(LocalDate date, String name) {
    }

    /**
     * status: OPEN, PRE_OPEN (09:00-09:15), BEFORE_OPEN, AFTER_CLOSE, HOLIDAY or WEEKEND.
     * live: true only while OPEN. feedLive: a NIFTY candle within the last 3 minutes.
     */
    public record MarketStatus(LocalDateTime now, String exchange, String status, boolean live, String message,
                               boolean tradingDay, String reason, LocalTime opensAt, LocalTime closesAt,
                               LocalDateTime nextOpen, LocalDateTime lastCandle, boolean feedLive,
                               List<Holiday> upcomingHolidays) {
    }

    public MarketStatus status() {
        return status(LocalDateTime.now(IST));
    }

    MarketStatus status(LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        LocalTime t = now.toLocalTime();
        Map.Entry<Boolean, String> day = calendar.check(today);
        boolean tradingDay = day.getKey();

        String status;
        if (!tradingDay) {
            status = "weekend".equals(day.getValue()) ? "WEEKEND" : "HOLIDAY";
        } else if (t.isBefore(PRE_OPEN)) {
            status = "BEFORE_OPEN";
        } else if (t.isBefore(OPEN)) {
            status = "PRE_OPEN";
        } else if (t.isBefore(CLOSE)) {
            status = "OPEN";
        } else {
            status = "AFTER_CLOSE";
        }
        boolean live = "OPEN".equals(status);

        LocalDateTime nextOpen = live ? null : nextOpen(now, tradingDay);
        LocalDateTime lastCandle = lastNiftyCandle(today);
        boolean feedLive = live && lastCandle != null && Duration.between(lastCandle, now).compareTo(FEED_STALE_AFTER) <= 0;

        String message = switch (status) {
            case "OPEN" -> feedLive ? "Market is live" : "Market is open, but our data feed is not live";
            case "PRE_OPEN" -> "Pre-open session; market opens at 09:15";
            case "BEFORE_OPEN" -> "Market opens today at 09:15";
            case "AFTER_CLOSE" -> "Market closed for the day";
            case "HOLIDAY" -> "Market closed: " + day.getValue().replace("NSE holiday: ", "");
            default -> "Market closed: weekend";
        };

        List<Holiday> upcoming = new ArrayList<>();
        calendar.upcomingHolidays(today).forEach((d, n) -> {
            if (upcoming.size() < 3) {
                upcoming.add(new Holiday(d, n));
            }
        });
        return new MarketStatus(now, "NSE", status, live, message, tradingDay, day.getValue(), OPEN, CLOSE,
                nextOpen, lastCandle, feedLive, upcoming);
    }

    /** The next session start: today at 09:15 if still ahead, else the next trading day's 09:15. */
    private LocalDateTime nextOpen(LocalDateTime now, boolean tradingToday) {
        if (tradingToday && now.toLocalTime().isBefore(OPEN)) {
            return now.toLocalDate().atTime(OPEN);
        }
        LocalDate d = now.toLocalDate().plusDays(1);
        for (int i = 0; i < 14; i++, d = d.plusDays(1)) {
            if (calendar.check(d).getKey()) {
                return d.atTime(OPEN);
            }
        }
        return null;
    }

    private LocalDateTime lastNiftyCandle(LocalDate today) {
        try {
            java.sql.Timestamp ts = jdbc.queryForObject("select max(candle_minute) from minute_candle "
                    + "where symbol = 'NIFTY' and candle_minute >= ?", java.sql.Timestamp.class, today.atStartOfDay());
            return ts == null ? null : ts.toLocalDateTime();
        } catch (Exception ex) {
            return null;
        }
    }
}
