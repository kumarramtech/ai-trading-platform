package com.ram.trading.ai.engine.controller;

import com.ram.trading.ai.engine.dto.*;
import com.ram.trading.ai.engine.service.AIAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AIController {

    private final AIAnalysisService service;

    @PostMapping("/explain-signal")
    public SignalExplanationResponse explainSignal(
            @RequestBody
            SignalExplanationRequest request) {

        return service.explainSignal(request);
    }

    @PostMapping("/review-trade")
    public TradeReviewResponse reviewTrade(@RequestBody TradeReviewRequest request) {
        return service.reviewTrade(request);
    }

    @PostMapping("/strategy-review")
    public StrategyReviewResponse reviewStrategy(@RequestBody List<TradeReviewRequest> trades) {
        return service.reviewStrategy(
                trades);
    }

    @PostMapping("/risk-analysis")
    public RiskAnalysisResponse analyzeRisk(
            @RequestBody RiskAnalysisRequest request) {

        return service.analyzeRisk(request);
    }
}