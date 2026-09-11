package com.ram.trading.signal.engine.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

@Service
@Slf4j
public class TradingSessionService {

    @Value("${trading.market.open}")
    private LocalTime marketOpen;

    @Value("${trading.market.entry.cut}")
    private LocalTime entryCutoff;

    @Value("${trading.market.close}")
    private LocalTime marketClose;

    @Value("${trading.market.end}")
    private LocalTime marketEnd;

    /**
     * Strategy entries are allowed only after the opening range has been formed.
     * Defaults to 09:30 if no explicit property is configured.
     */
    @Value("${trading.market.strategy.entry.start:09:30}")
    private LocalTime strategyEntryStart;

    @Value("${trading.market.timezone:Asia/Kolkata}")
    private String timezone;

    private ZoneId zoneId() {
        return ZoneId.of(timezone);
    }

    /**
     * Market is open for monitoring.
     *
     * Monitoring starts at the configured market open (normally 09:15)
     * and continues until the configured market end (normally 15:30).
     */
    public boolean isMarketOpen() {

        LocalDate today = LocalDate.now(zoneId());

        if (today.getDayOfWeek() == DayOfWeek.SATURDAY ||
                today.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }

        LocalTime now = LocalTime.now(zoneId());

        return !now.isBefore(marketOpen)
                && now.isBefore(marketEnd);
    }

    /**
     * Legacy/general trade-creation window.
     *
     * This remains separate from the strategy-entry window so that
     * monitoring/session behavior is not changed accidentally.
     */
    public boolean canCreateTrade() {

        if (!isMarketOpen()) {
            return false;
        }

        LocalTime now = LocalTime.now(zoneId());

        return now.isBefore(entryCutoff);
    }

    /**
     * Strategy-entry window.
     *
     * Opening-range analysis is allowed before this time, but a new
     * JUDAS/ORB strategy trade must not be created before this boundary.
     */
    public boolean canCreateStrategyEntry() {

        if (!isMarketOpen()) {
            return false;
        }

        LocalTime now = LocalTime.now(zoneId());

        return !now.isBefore(strategyEntryStart)
                && now.isBefore(entryCutoff);
    }

    /**
     * Time to force close remaining trades.
     */
    public boolean shouldForceCloseTrades() {

        LocalTime now = LocalTime.now(zoneId());

        return !now.isBefore(marketClose)
                && now.isBefore(marketEnd);
    }

    /**
     * Entire trading session finished.
     */
    public boolean isMarketClosed() {

        LocalTime now = LocalTime.now(zoneId());

        return !now.isBefore(marketEnd);
    }
}
