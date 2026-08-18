package com.ram.trading.signal.engine.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketSessionServiceImpl implements MarketSessionService {

    private final MarketSessionProperties properties;

    private ZoneId getZoneId() {
        return ZoneId.of(properties.getTimezone());
    }

    private LocalTime now() {
        return LocalTime.now(getZoneId());
    }

    private boolean isWeekend() {

        LocalDate today = LocalDate.now(getZoneId());

        DayOfWeek day = today.getDayOfWeek();

        return day == DayOfWeek.SATURDAY
                || day == DayOfWeek.SUNDAY;
    }

    @Override
    public boolean isMarketOpen() {

        if (!properties.isEnabled()) {
            return true;
        }

        if (isWeekend()) {

            log.info("Market Closed : Weekend");

            return false;
        }

        LocalTime currentTime = now();

        LocalTime marketOpen =
                LocalTime.parse(properties.getOpen());

        LocalTime marketEnd =
                LocalTime.parse(properties.getEnd());

        boolean marketOpenNow =
                !currentTime.isBefore(marketOpen)
                        && currentTime.isBefore(marketEnd);

        if (!marketOpenNow) {
            log.info("Market Closed : {}", currentTime);
        }

        return marketOpenNow;
    }

    @Override
    public boolean isTradingAllowed() {

        if (!isMarketOpen()) {
            return false;
        }

        LocalTime currentTime = now();

        LocalTime entryCutoff =
                LocalTime.parse(properties.getEntryCutoff());

        return currentTime.isBefore(entryCutoff);
    }

    @Override
    public boolean shouldForceCloseTrades() {

        if (!isMarketOpen()) {
            return false;
        }

        LocalTime currentTime = now();

        LocalTime forceClose =
                LocalTime.parse(properties.getClose());

        LocalTime marketEnd =
                LocalTime.parse(properties.getEnd());

        return !currentTime.isBefore(forceClose)
                && currentTime.isBefore(marketEnd);
    }

    @Override
    public boolean isMarketClosed() {

        if (isWeekend()) {
            return true;
        }

        LocalTime currentTime = now();

        LocalTime marketEnd =
                LocalTime.parse(properties.getEnd());

        return !currentTime.isBefore(marketEnd);
    }

    private LocalTime parseTime(String value, String propertyName) {

        if (value == null || value.isBlank()) {

            throw new IllegalStateException(
                    "Missing market session configuration: "
                            + propertyName);
        }

        return LocalTime.parse(value);
    }
}