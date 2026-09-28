package com.ram.trading.ai.engine.service.impl;


import com.ram.trading.ai.engine.cache.CacheConstants;
import com.ram.trading.ai.engine.cache.CacheKeyBuilder;
import com.ram.trading.ai.engine.cache.RedisCacheService;
import com.ram.trading.ai.engine.constant.AiRecommendation;
import com.ram.trading.ai.engine.dto.AiDecisionResponse;
import com.ram.trading.ai.engine.dto.AiEvaluationState;
import com.ram.trading.ai.engine.dto.TradingDecisionRequest;
import com.ram.trading.ai.engine.dto.decision.Decision;
import com.ram.trading.ai.engine.dto.execution.ExecutionPlan;
import com.ram.trading.ai.engine.exception.CircuitBreakerOpenException;
import com.ram.trading.ai.engine.exception.LLMProviderException;
import com.ram.trading.ai.engine.gateway.AIGatewayService;
import com.ram.trading.ai.engine.parser.AiDecisionResponseParser;
import com.ram.trading.ai.engine.prompt.AiDecisionPromptBuilder;
import com.ram.trading.ai.engine.service.AiCallControlService;
import com.ram.trading.ai.engine.service.AiDecisionService;
import com.ram.trading.ai.engine.service.AiDecisionValidator;
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

    private final AiCallControlService aiCallControlService;

    private final AiDecisionValidator aiDecisionValidator;

    @Override
    public AiDecisionResponse evaluate(
            TradingDecisionRequest request) {

        String symbol =
                request != null &&
                        request.getSignalRequest() != null
                        ? request.getSignalRequest().getSymbol()
                        : "UNKNOWN";

        log.info(
                "Generating AI decision for {}",
                symbol);

        /*
         * ============================================================
         * STEP 1 : READ AI EVALUATION STATE ONCE
         * ============================================================
         */

        AiEvaluationState previousState =
                aiCallControlService
                        .getEvaluationState(request);

        /*
         * ============================================================
         * STEP 2 : REDIS AI CALL CONTROL
         * ============================================================
         */

        AiDecisionResponse validatedPreviousResponse =
                previousState != null
                        ? aiDecisionValidator.validate(
                        previousState.getAiDecisionResponse())
                        : null;

        boolean previousResponseInvalid =
                previousState != null
                        && previousState.getAiDecisionResponse() != null
                        && validatedPreviousResponse
                        != previousState.getAiDecisionResponse();

        boolean shouldCallAi =
                previousResponseInvalid
                        || aiCallControlService.shouldCallAi(
                        request,
                        previousState);

        if (!shouldCallAi) {

            if (validatedPreviousResponse != null) {

                log.info(
                        "AI CALL SKIPPED | {} | REUSING_PREVIOUS_AI_RESPONSE",
                        symbol);

                return validatedPreviousResponse;
            }

            log.warn(
                    "AI CALL SKIPPED | {} | Previous AI response unavailable | FALLBACK",
                    symbol);

            return buildFallbackResponse(request);
        }

        /*
         * ============================================================
         * STEP 3 : EXACT REQUEST CACHE
         * ============================================================
         */

        String cacheKey =
                CacheKeyBuilder.buildAiDecisionKey(request);

        AiDecisionResponse cachedResponse =
                redisCacheService.get(
                        cacheKey,
                        AiDecisionResponse.class);

        if (cachedResponse != null) {

            AiDecisionResponse validatedCachedResponse =
                    aiDecisionValidator.validate(cachedResponse);

            if (validatedCachedResponse == cachedResponse) {

                log.info(
                        "AI CACHE HIT | {} | KEY={}",
                        symbol,
                        cacheKey);

                aiCallControlService.recordAiEvaluation(
                        request,
                        validatedCachedResponse);

                return validatedCachedResponse;
            }

            log.warn(
                    "AI CACHE ENTRY INVALID | {} | Requesting fresh AI decision",
                    symbol);
        }

        try {

            log.info(
                    "=========================================");

            log.info(
                    "AI CALL ALLOWED | {}",
                    symbol);

            log.info(
                    "AI CACHE MISS");

            /*
             * ========================================================
             * BUILD STRUCTURED AI PROMPT
             * ========================================================
             */

            String prompt =
                    promptBuilder.buildPrompt(request);

            /*
             * IMPORTANT:
             * Keep this temporarily while testing.
             *
             * This lets us verify that the AI receives real JSON
             * instead of Java object references.
             */

            log.info(
                    "AI PROMPT | {}:\n{}",
                    symbol,
                    prompt);

            /*
             * ========================================================
             * CALL AI
             * ========================================================
             */

            String aiResponse =
                    aiGatewayService.analyze(prompt);

            log.info(
                    "AI RAW RESPONSE | {}:\n{}",
                    symbol,
                    aiResponse);

            /*
             * ========================================================
             * PARSE + VALIDATE
             * ========================================================
             */

            AiDecisionResponse parsedResponse =
                    parser.parse(aiResponse);

            AiDecisionResponse response =
                    aiDecisionValidator.validate(
                            parsedResponse);

            log.info(
                    "AI FINAL DECISION | {} | recommendation={} | tradeAllowed={} | confidence={}",
                    symbol,
                    response.getDecision() != null
                            ? response.getDecision().getRecommendation()
                            : null,
                    response.getDecision() != null
                            ? response.getDecision().getTradeAllowed()
                            : null,
                    response.getDecision() != null
                            ? response.getDecision().getConfidence()
                            : null);

            /*
             * ========================================================
             * CACHE
             * ========================================================
             */

            redisCacheService.put(
                    cacheKey,
                    response,
                    CacheConstants.AI_DECISION_TTL);

            /*
             * ========================================================
             * RECORD AI EVALUATION
             * ========================================================
             */

            aiCallControlService.recordAiEvaluation(
                    request,
                    response);

            return response;

        } catch (LLMProviderException |
                 CircuitBreakerOpenException ex) {

            log.error(
                    "AI PROVIDERS UNAVAILABLE. "
                            + "Using Engineering Decision as availability fallback.",
                    ex);

            /*
             * Do NOT record AI evaluation state.
             *
             * AI failed, so the next evaluation should remain
             * eligible for another AI call.
             */

            return buildFallbackResponse(request);

        } catch (Exception ex) {

            log.error(
                    "Unexpected AI decision processing error. "
                            + "Blocking trade as a safety measure.",
                    ex);

            /*
             * Parser/validation/unexpected processing errors
             * must result in HOLD.
             */

            return buildSafeFallbackResponse();
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