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
                        && validatedPreviousResponse != previousState.getAiDecisionResponse();

        boolean shouldCallAi =
                previousResponseInvalid
                        || aiCallControlService
                        .shouldCallAi(
                                request,
                                previousState);

        if (!shouldCallAi) {

            if (validatedPreviousResponse != null) {

                log.info(
                        "AI CALL SKIPPED | {} | REUSING_PREVIOUS_AI_RESPONSE",
                        symbol);

                return validatedPreviousResponse;
            }

            /*
             * Safety fallback.
             *
             * Normally this should never happen because
             * a state is recorded only after a successful AI
             * evaluation.
             */
            log.warn(
                    "AI CALL SKIPPED | {} | Previous AI response unavailable | FALLBACK",
                    symbol);

            return buildFallbackResponse(request);
        }

        /*
         * ============================================================
         * STEP 3 : EXISTING EXACT REQUEST CACHE
         * ============================================================
         *
         * V2 logic remains unchanged.
         */
        String cacheKey =
                CacheKeyBuilder
                        .buildAiDecisionKey(request);

        AiDecisionResponse cachedResponse =
                redisCacheService.get(
                        cacheKey,
                        AiDecisionResponse.class);

        if (cachedResponse != null) {

            AiDecisionResponse validatedCachedResponse =
                    aiDecisionValidator.validate(cachedResponse);

            if (validatedCachedResponse == cachedResponse) {

                log.info(
                        "=========================================");

                log.info(
                        "AI CACHE HIT");

                log.info(
                        "KEY : {}",
                        cacheKey);

                log.info(
                        "Returning cached AI Decision for {}",
                        symbol);

                log.info(
                        "=========================================");

                aiCallControlService
                        .recordAiEvaluation(
                                request,
                                validatedCachedResponse);

                return validatedCachedResponse;
            }

            log.warn(
                    "AI CACHE ENTRY INVALID | {} | Ignoring cached response and requesting a fresh AI decision",
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

            log.info(
                    "Invoking AI Gateway...");

            log.info(
                    "=========================================");

            String prompt =
                    promptBuilder
                            .buildPrompt(request);

            String aiResponse =
                    aiGatewayService
                            .analyze(prompt);

            log.info(
                    "AI RAW RESPONSE:\n{}",
                    aiResponse);

            AiDecisionResponse response =
                    aiDecisionValidator.validate(
                            parser.parse(aiResponse));

            log.info(
                    "Parsed Response : {}",
                    response);

            /*
             * Existing exact-request cache.
             */
            redisCacheService.put(
                    cacheKey,
                    response,
                    CacheConstants.AI_DECISION_TTL);

            /*
             * Record successful AI evaluation state.
             */
            aiCallControlService
                    .recordAiEvaluation(
                            request,
                            response);

            return response;

        } catch (LLMProviderException | CircuitBreakerOpenException ex) {

            log.error(
                    "=========================================");

            log.error(
                    "AI PROVIDERS UNAVAILABLE.");

            log.error(
                    "Using Engineering Decision as availability fallback.");

            log.error(
                    "=========================================",
                    ex);

            /*
             * IMPORTANT:
             *
             * Do NOT record AI evaluation state here.
             * The AI provider path failed, therefore the next
             * evaluation must remain eligible for AI.
             */
            return buildFallbackResponse(request);

        } catch (Exception ex) {

            log.error(
                    "Unexpected AI decision processing error. "
                            + "Blocking trade as a safety measure.",
                    ex);

            /*
             * Parser/validation/unexpected processing errors must NOT
             * fall back to a directional Engineering trade.
             * Use a safe HOLD response instead.
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