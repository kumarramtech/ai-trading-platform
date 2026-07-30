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
            log.info("Invoking LLM Gateway...");
            log.info("=========================================");

            String prompt = promptBuilder.buildPrompt(request);

            log.debug("Prompt Generated Successfully.");

            String aiResponse =
                    aiGatewayService.analyze(prompt);

            log.info("AI RAW RESPONSE:\n{}", aiResponse);

            AiDecisionResponse response = parser.parse(aiResponse);

            log.info("========== EXECUTION PLAN ==========");
            if (response.getExecutionPlan() != null) {
                log.info("Entry      : {}", response.getExecutionPlan().getEntry());
                log.info("Target     : {}", response.getExecutionPlan().getTarget());
                log.info("Stop Loss  : {}", response.getExecutionPlan().getStopLoss());
                log.info("Position   : {}", response.getExecutionPlan().getPositionSize());
                log.info("Holding    : {}", response.getExecutionPlan().getHoldingPeriod());
                log.info("Exit       : {}", response.getExecutionPlan().getExitStrategy());
            } else {
                log.info("Execution Plan is NULL");
            }
            log.info("===================================");

            log.info("Parsed Response : {}", response);
            log.info("Decision Object : {}", response.getDecision());

            log.info("AI Decision_Recommendation : {}",
                    response.getDecision().getRecommendation());

            if (response.getNewsAnalysis() != null) {

                log.info("========== Parsed News ==========");
                log.info("Summary   : {}", response.getNewsAnalysis().getSummary());
                log.info("Sentiment : {}", response.getNewsAnalysis().getSentiment());
                log.info("Score     : {}", response.getNewsAnalysis().getScore());
                log.info("================================");
            }

            redisCacheService.put(
                    cacheKey,
                    response,
                    CacheConstants.AI_DECISION_TTL);

            return response;

        } catch (Exception ex) {

            log.error("AI Decision Failed", ex);

            if (ex.getMessage() != null &&
                    ex.getMessage().contains("429")) {

                log.warn("OpenAI quota exceeded. Using Engineering Decision.");

                return buildFallbackResponse(request);
            }

            return buildSafeFallbackResponse();
        }
    }

    private AiDecisionResponse buildFallbackResponse(
            TradingDecisionRequest request) {

        AiDecisionResponse response = new AiDecisionResponse();

        Decision decision = new Decision();

        AiRecommendation recommendation =
                AiRecommendation.valueOf(
                        request.getTechnicalDecision().getSignal().name());

        double currentPrice =
                request.getSignalRequest().getCurrentPrice();

        decision.setRecommendation(recommendation);
        decision.setTradeAllowed(true);
        decision.setConfidence(
                request.getTechnicalDecision().getConfidence());
        decision.setReason(
                "OpenAI unavailable. Using Engineering Decision.");

        response.setDecision(decision);

        ExecutionPlan executionPlan = new ExecutionPlan();

        executionPlan.setEntry(currentPrice);

        switch (recommendation) {

            case BUY -> {
                executionPlan.setTarget(
                        currentPrice * 1.02);      // +2%
                executionPlan.setStopLoss(
                        currentPrice * 0.99);      // -1%
            }

            case SELL -> {
                executionPlan.setTarget(
                        currentPrice * 0.98);      // -2%
                executionPlan.setStopLoss(
                        currentPrice * 1.01);      // +1%
            }

            default -> {
                executionPlan.setTarget(currentPrice);
                executionPlan.setStopLoss(currentPrice);
            }
        }

        executionPlan.setHoldingPeriod("INTRADAY");
        executionPlan.setExitStrategy("Engineering Fallback");
        executionPlan.setPositionSize(1);

        response.setExecutionPlan(executionPlan);

        response.setAiReasoning(
                "OpenAI unavailable (429). Engineering decision used.");

        return response;
    }

    private AiDecisionResponse buildSafeFallbackResponse() {

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
    }
}