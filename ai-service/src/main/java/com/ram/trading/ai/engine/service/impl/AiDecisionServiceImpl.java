package com.ram.trading.ai.engine.service.impl;


import com.ram.trading.ai.engine.cache.CacheConstants;
import com.ram.trading.ai.engine.cache.CacheKeyBuilder;
import com.ram.trading.ai.engine.cache.RedisCacheService;
import com.ram.trading.ai.engine.constant.AiRecommendation;
import com.ram.trading.ai.engine.dto.AiDecisionResponse;
import com.ram.trading.ai.engine.dto.TradingDecisionRequest;
import com.ram.trading.ai.engine.dto.decision.Decision;
import com.ram.trading.ai.engine.dto.execution.ExecutionPlan;
import com.ram.trading.ai.engine.gateway.AIGatewayService;
import com.ram.trading.ai.engine.parser.AiDecisionResponseParser;
import com.ram.trading.ai.engine.prompt.AiDecisionPromptBuilder;
import com.ram.trading.ai.engine.service.AiDecisionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiDecisionServiceImpl implements AiDecisionService {

    private final AIGatewayService aiGatewayService;

    private final AiDecisionPromptBuilder promptBuilder;

    private final AiDecisionResponseParser parser;

    private final RedisCacheService redisCacheService;

    @Override
    public AiDecisionResponse evaluate(TradingDecisionRequest request) {

        log.info("Generating AI decision for {}",
                request.getSignalRequest().getSymbol());

        String cacheKey = CacheKeyBuilder.buildAiDecisionKey(request);

        AiDecisionResponse cachedResponse =
                redisCacheService.get(cacheKey, AiDecisionResponse.class);

        if (cachedResponse != null) {

            log.info("=========================================");
            log.info("AI CACHE HIT");
            log.info("KEY : {}", cacheKey);
            log.info("Returning cached AI Decision");
            log.info("=========================================");

            return cachedResponse;
        }

        try {

            log.info("=========================================");
            log.info("AI CACHE MISS");
            log.info("KEY : {}", cacheKey);
            log.info("Invoking AI Gateway...");
            log.info("=========================================");

            String prompt = promptBuilder.buildPrompt(request);

            String aiResponse =
                    aiGatewayService.analyze(prompt);

            log.info("AI RAW RESPONSE:\n{}", aiResponse);

            AiDecisionResponse response =
                    parser.parse(aiResponse);

            log.info("Parsed Response : {}", response);

            redisCacheService.put(
                    cacheKey,
                    response,
                    CacheConstants.AI_DECISION_TTL);

            return response;

        } catch (Exception ex) {

            log.error("=========================================");
            log.error("AI Provider Unavailable.");
            log.error("Using Engineering Decision.");
            log.error("=========================================", ex);

            return buildFallbackResponse(request);
        }
    }

    private AiDecisionResponse buildFallbackResponse(
            TradingDecisionRequest request) {

        AiDecisionResponse response =
                new AiDecisionResponse();

        Decision decision =
                new Decision();

        AiRecommendation recommendation =
                AiRecommendation.valueOf(
                        request.getTechnicalDecision()
                                .getSignal()
                                .name());

        double currentPrice =
                request.getSignalRequest()
                        .getCurrentPrice();

        decision.setRecommendation(recommendation);

        decision.setTradeAllowed(
                recommendation != AiRecommendation.HOLD);

        decision.setConfidence(
                request.getTechnicalDecision()
                        .getConfidence());

        decision.setReason(
                "AI unavailable. Technical analysis used.");

        response.setDecision(decision);

        ExecutionPlan executionPlan =
                new ExecutionPlan();

        executionPlan.setEntry(currentPrice);

        switch (recommendation) {

            case BUY -> {

                executionPlan.setTarget(
                        currentPrice * 1.02);

                executionPlan.setStopLoss(
                        currentPrice * 0.99);
            }

            case SELL -> {

                executionPlan.setTarget(
                        currentPrice * 0.98);

                executionPlan.setStopLoss(
                        currentPrice * 1.01);
            }

            default -> {

                executionPlan.setTarget(currentPrice);

                executionPlan.setStopLoss(currentPrice);
            }
        }

        executionPlan.setHoldingPeriod("INTRADAY");

        executionPlan.setExitStrategy(
                "Technical Strategy");

        /*
         * Keep Position Size as 0.
         * Risk Management Service will decide.
         */
        executionPlan.setPositionSize(0);

        response.setExecutionPlan(executionPlan);

        response.setAiReasoning(
                "AI providers unavailable. Engineering decision applied.");

        return response;
    }

    /*private AiDecisionResponse buildSafeFallbackResponse() {

        AiDecisionResponse response = new AiDecisionResponse();

        Decision decision = new Decision();

        decision.setRecommendation(AiRecommendation.HOLD);
        decision.setTradeAllowed(false);
        decision.setConfidence(0);
        decision.setReason(
                "Unexpected AI error. Trade blocked as a safety measure.");

        response.setDecision(decision);

        response.setAiReasoning(
                "Safe fallback response generated due to unexpected AI failure.");

        return response;
    }*/
}