package com.ram.trading.newsanalysis.controller;

import com.ram.trading.newsanalysis.dto.*;
import com.ram.trading.newsanalysis.service.NewsAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/news")
@RequiredArgsConstructor
public class NewsAnalysisController {

    private final NewsAnalysisService newsAnalysisService;

    @PostMapping("/analyze")
    public Mono<NewsAnalysisResponse> analyze(
            @RequestBody NewsAnalysisRequest request) {

        return newsAnalysisService.analyze(
                request.getSymbol());

    }

    @GetMapping("/latest")
    public Mono<List<NewsArticle>> getLatestNews(
            @RequestParam String symbol) {

        return newsAnalysisService.getLatestNews(symbol);
    }

    @GetMapping("/market-trend")
    public Mono<MarketTrendAnalysisResponse> analyzeMarketTrend() {

        return newsAnalysisService.analyzeMarketTrend();
    }

    @GetMapping("/market-trend/stocks")
    public Mono<SectorStockAnalysisResponse> analyzeMarketTrendStocks() {

        return newsAnalysisService
                .analyzeMarketTrend()
                .flatMap(newsAnalysisService::analyzeSectorStocks);
    }
}