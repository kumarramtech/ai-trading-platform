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

    @Override
    public boolean isMarketOpen() {

        if (!properties.isEnabled()) {
            return true;
        }

        ZoneId zoneId = ZoneId.of(properties.getTimezone());

        LocalDate today = LocalDate.now(zoneId);
        LocalTime now = LocalTime.now(zoneId);

        DayOfWeek day = today.getDayOfWeek();

        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {

            log.info("Market Closed : Weekend");

            return false;
        }

        LocalTime open = LocalTime.parse(properties.getOpenTime());
        LocalTime close = LocalTime.parse(properties.getCloseTime());

        boolean openNow =
                !now.isBefore(open)
                        && !now.isAfter(close);

        if (!openNow) {

            log.info("Market Closed : Current Time {}", now);
        }

        return openNow;
    }

    @Override
    public boolean isTradingAllowed() {
        return isMarketOpen();
    }
}