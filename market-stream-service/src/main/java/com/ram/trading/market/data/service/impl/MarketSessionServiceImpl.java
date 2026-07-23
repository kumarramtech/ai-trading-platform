package com.ram.trading.market.data.service.impl;

import com.ram.trading.market.data.service.MarketSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

@Slf4j
@Service
public class MarketSessionServiceImpl implements MarketSessionService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final LocalTime MARKET_START = LocalTime.of(9, 15);
    private static final LocalTime MARKET_END = LocalTime.of(15, 30);

    @Override
    public boolean isMarketOpen() {

        LocalDate today = LocalDate.now(IST);

        if (today.getDayOfWeek() == DayOfWeek.SATURDAY
                || today.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }

        LocalTime now = LocalTime.now(IST);

        return !now.isBefore(MARKET_START)
                && !now.isAfter(MARKET_END);
    }

    @Override
    public boolean isPreMarket() {

        LocalTime now = LocalTime.now(IST);

        return now.isBefore(MARKET_START);
    }

    @Override
    public boolean isAfterMarket() {

        LocalTime now = LocalTime.now(IST);

        return now.isAfter(MARKET_END);
    }
}