package com.ram.trading.backtesting.controller;

import com.ram.trading.backtesting.dto.BacktestResult;
import com.ram.trading.backtesting.dto.BacktestSummary;
import com.ram.trading.backtesting.dto.OptimizationRequest;
import com.ram.trading.backtesting.dto.OptimizationResponse;
import com.ram.trading.backtesting.entity.TechnicalIndicator;
import com.ram.trading.backtesting.service.BacktestingService;
import com.ram.trading.backtesting.service.StrategyOptimizerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/backtest")
@RequiredArgsConstructor
public class BacktestingController {

    private final BacktestingService service;
    private final StrategyOptimizerService optimizerService;

    @GetMapping("/{symbol}")
    public BacktestResult backtest(
            @PathVariable String symbol) throws Exception {

        return service.runBacktest(symbol);
    }

    @GetMapping("/summary/{symbol}")
    public BacktestSummary getSummary(
            @PathVariable String symbol) {

        return service
                .runBacktestSummary(
                        symbol);
    }

    @GetMapping("/summaryBuyStop/{symbol}")
    public BacktestSummary getSummary(
            @PathVariable String symbol,
            @RequestParam Double buy,
            @RequestParam Double target,
            @RequestParam Double stop) {

        return service.runBacktestSummary(
                symbol,
                buy,
                target,
                stop);
    }

    @PostMapping("/optimize")
    public OptimizationResponse optimize(
            @RequestBody OptimizationRequest request) {

        return optimizerService.optimize(request);
    }

    @GetMapping("/latest/{symbol}")
    public TechnicalIndicator getLatest(
            @PathVariable String symbol) {

        return service.findTopBySymbolOrderByTradeDateDesc(
                        symbol);
    }
}