package com.ram.trading.ai.engine.controller;

import com.ram.trading.ai.engine.dto.SignalExplanationRequest;
import com.ram.trading.ai.engine.dto.SignalExplanationResponse;
import com.ram.trading.ai.engine.dto.TradeReviewRequest;
import com.ram.trading.ai.engine.dto.TradeReviewResponse;
import com.ram.trading.ai.engine.service.AIAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}