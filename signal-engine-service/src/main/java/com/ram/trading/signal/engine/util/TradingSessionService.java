package com.ram.trading.signal.engine.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

@Service
@Slf4j
public class TradingSessionService {

    @Value("${trading.market.open}")
    private LocalTime marketOpen;

    @Value("${trading.market.entry.cutoff}")
    private LocalTime entryCutoff;

    @Value("${trading.market.close}")
    private LocalTime marketClose;

    @Value("${trading.market.end}")
    private LocalTime marketEnd;

    /**
     * Market is open for monitoring.
     */
    public boolean isMarketOpen() {

        LocalDate today = LocalDate.now();

        if (today.getDayOfWeek() == DayOfWeek.SATURDAY ||
                today.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }

        LocalTime now = LocalTime.now();

        return !now.isBefore(marketOpen)
                && now.isBefore(marketEnd);
    }

    /**
     * Can create new trades.
     */
    public boolean canCreateTrade() {

        if (!isMarketOpen()) {
            return false;
        }

        LocalTime now = LocalTime.now();

        return now.isBefore(entryCutoff);
    }

    /**
     * Time to force close remaining trades.
     */
    public boolean shouldForceCloseTrades() {

        LocalTime now = LocalTime.now();

        return !now.isBefore(marketClose)
                && now.isBefore(marketEnd);
    }

    /**
     * Entire trading session finished.
     */
    public boolean isMarketClosed() {

        return LocalTime.now().isAfter(marketEnd);
    }
}