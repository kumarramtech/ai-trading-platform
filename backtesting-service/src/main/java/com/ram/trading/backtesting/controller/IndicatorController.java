package com.ram.trading.backtesting.controller;

import com.ram.trading.backtesting.entity.TechnicalIndicator;
import com.ram.trading.backtesting.service.IndicatorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/indicators")
public class IndicatorController {

    private final IndicatorService indicatorService;

    @PostMapping("/generate/{symbol}")
    public String generateRsi(
            @PathVariable String symbol) {

        indicatorService.generateRsi(symbol);

        return "RSI generated successfully";
    }

    @GetMapping("/{symbol}")
    public List<TechnicalIndicator> getIndicators(
            @PathVariable String symbol) {

        return indicatorService
                .findBySymbolOrderByTradeDateAsc(symbol);
    }
}