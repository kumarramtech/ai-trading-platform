package com.ram.trading.newsanalysis.controller;

import com.ram.trading.newsanalysis.dto.NewsAnalysisRequest;
import com.ram.trading.newsanalysis.dto.NewsAnalysisResponse;
import com.ram.trading.newsanalysis.dto.NewsArticle;
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
}