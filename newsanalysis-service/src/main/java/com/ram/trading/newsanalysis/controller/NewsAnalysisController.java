package com.ram.trading.newsanalysis.controller;

import com.ram.trading.newsanalysis.dto.NewsAnalysisRequest;
import com.ram.trading.newsanalysis.dto.NewsAnalysisResponse;
import com.ram.trading.newsanalysis.service.NewsAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/news")
@RequiredArgsConstructor
public class NewsAnalysisController {

    private final NewsAnalysisService service;

    @PostMapping("/analyze")
    public NewsAnalysisResponse analyze(
            @RequestBody NewsAnalysisRequest request) {

        return service.analyze(
                request.getSymbol(),
                request.getHeadline());
    }
}