package com.ram.trading.ai.engine.controller;

import com.ram.trading.ai.engine.dto.MarketSentimentResponse;
import com.ram.trading.ai.engine.service.MarketSentimentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/market")
@RequiredArgsConstructor
public class MarketIntelligenceController {

    private final
    MarketSentimentService service;

    @GetMapping("/sentiment/{symbol}")
    public MarketSentimentResponse
            getSentiment(@PathVariable String symbol) {

        return service.getSentiment(symbol);
    }
}