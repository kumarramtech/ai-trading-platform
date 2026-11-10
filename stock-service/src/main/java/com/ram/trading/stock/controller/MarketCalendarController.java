package com.ram.trading.stock.controller;

import com.ram.trading.stock.service.calendar.MarketCalendarService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/** NSE trading calendar for the other services' schedulers (read-only, plus a manual refresh). */
@RestController
@RequiredArgsConstructor
public class MarketCalendarController {

    private final MarketCalendarService calendar;

    /** {date, tradingDay, reason}; date defaults to today (IST). */
    @GetMapping("/market/trading-day")
    public MarketCalendarService.TradingDay tradingDay(@RequestParam(required = false) String date) {
        return date == null ? calendar.today() : calendar.check(LocalDate.parse(date));
    }

    @GetMapping("/market/holidays")
    public Map<LocalDate, String> holidays() {
        return calendar.holidays();
    }

    @PostMapping("/market/holidays/refresh")
    public Map<LocalDate, String> refresh() {
        calendar.refresh();
        return calendar.holidays();
    }
}
