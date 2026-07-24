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

        return aiDecisionService.evaluate(request);
    }

}