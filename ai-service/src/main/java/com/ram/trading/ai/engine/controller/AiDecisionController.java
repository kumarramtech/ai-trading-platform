package com.ram.trading.ai.engine.controller;

import com.ram.trading.ai.engine.constant.AiRecommendation;
import com.ram.trading.ai.engine.dto.AiDecisionResponse;
import com.ram.trading.ai.engine.dto.TradingDecisionRequest;
import com.ram.trading.ai.engine.dto.decision.Decision;
import com.ram.trading.ai.engine.dto.execution.ExecutionPlan;
import com.ram.trading.ai.engine.service.AiDecisionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiDecisionController {

    private final AiDecisionService aiDecisionService;

    @PostMapping("/trading-decision")
    public AiDecisionResponse evaluate(
            @RequestBody TradingDecisionRequest request) {

        /*Decision decision = Decision.builder()
                .tradeAllowed(true)
                .recommendation(AiRecommendation.BUY)
                .confidence(90)
                .decisionStrength("VERY_STRONG")
                .build();

        ExecutionPlan executionPlan = ExecutionPlan.builder()
                .entry(2200.0)
                .target(2230.0)
                .stopLoss(2185.0)
                .build();

        return AiDecisionResponse.builder()
                .decision(decision)
                .executionPlan(executionPlan)
                .build();*/

        return aiDecisionService.evaluate(request);
    }

}