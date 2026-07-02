package com.ram.trading.stock.controller;



import com.ram.trading.stock.dto.history.HistoricalCandleResponse;
import com.ram.trading.stock.service.history.HistoricalCandleService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/stocks")
public class HistoricalCandleController {

    private final HistoricalCandleService historicalCandleService;

    @GetMapping("/{symbol}/history")
    public Mono<HistoricalCandleResponse> getHistoricalCandles(

            @PathVariable String symbol,

            @RequestParam String interval,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {

        return historicalCandleService.getHistoricalCandles(
                symbol,
                interval,
                from,
                to);

    }

}