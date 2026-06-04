package com.ram.trading.backtesting.controller;

import com.ram.trading.backtesting.dto.BacktestResult;
import com.ram.trading.backtesting.dto.BacktestSummary;
import com.ram.trading.backtesting.service.BacktestingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/backtest")
@RequiredArgsConstructor
public class BacktestingController {

    private final BacktestingService service;

    @GetMapping("/{symbol}")
    public BacktestResult backtest(
            @PathVariable String symbol) {

        return service.runBacktest(symbol);
    }

    @GetMapping("/summary/{symbol}")
    public BacktestSummary summary(
            @PathVariable String symbol) {

        return service.runBacktestSummary(symbol);
    }
}