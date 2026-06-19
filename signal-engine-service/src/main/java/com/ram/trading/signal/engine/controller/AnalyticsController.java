package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.dto.TradeInsightsResponse;
import com.ram.trading.signal.engine.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/trade-insights")
    public TradeInsightsResponse getTradeInsights() {

        return analyticsService.getTradeInsights();
    }
}