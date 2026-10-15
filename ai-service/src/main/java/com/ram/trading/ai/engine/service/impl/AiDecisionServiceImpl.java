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
    public AiDecisionResponse evaluate(TradingDecisionRequest request) {

        String symbol = request != null
                && request.getSignalRequest() != null
                ? request.getSignalRequest().getSymbol()
                : "UNKNOWN";

        try {
            // STEP 0: Fail closed for an invalid request.
            if (request == null
                    || request.getSignalRequest() == null
                    || request.getSignalRequest().getSymbol() == null
                    || request.getSignalRequest().getSymbol().isBlank()) {

                log.warn("Invalid AI decision request. Blocking trade.");
                return buildSafeFallbackResponse();
            }

            log.info("Generating AI decision for {}", symbol);

            // STEP 1: Read previous evaluation state.
            // Redis failures must not bypass safety handling.
            AiEvaluationState previousState =
                    aiCallControlService.getEvaluationState(request);

            // STEP 2: Build the exact request cache key.
            String cacheKey = null;

            try {
                cacheKey = CacheKeyBuilder.buildAiDecisionKey(request);
            } catch (IllegalArgumentException ex) {
                log.warn(
                        "Unable to build AI cache key for {}. "
                                + "Previous response reuse and exact-cache lookup disabled.",
                        symbol);
            }

            // STEP 3: Validate the previous AI response.
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

            String previousCacheKey =
                    previousState != null
                            ? previousState.getCacheKey()
                            : null;

            boolean cacheKeyMatches =
                    cacheKey != null
                            && !cacheKey.isBlank()
                            && cacheKey.equals(previousCacheKey);

            // STEP 4: Apply existing AI call-control rules.
            boolean callControlRequiresAi =
                    aiCallControlService.shouldCallAi(
                            request,
                            previousState);

            boolean shouldCallAi =
                    previousResponseInvalid
                            || !cacheKeyMatches
                            || callControlRequiresAi;

            if (!shouldCallAi && validatedPreviousResponse != null) {
                log.info(
                        "AI CALL SKIPPED | {} | REUSING_MATCHING_AI_RESPONSE",
                        symbol);

                return validatedPreviousResponse;
            }

            if (!cacheKeyMatches) {
                log.info(
                        "AI PREVIOUS RESPONSE NOT REUSED | {} | cacheKey missing or changed",
                        symbol);
            }

            // STEP 5: Check the exact-request cache.

            boolean exactCacheLookupAllowed =
                    cacheKey != null
                            && !cacheKey.isBlank()
                            && !previousResponseInvalid
                            && !callControlRequiresAi;


            if (exactCacheLookupAllowed) {
                AiDecisionResponse cachedResponse =
                        redisCacheService.get(
                                cacheKey,
                                AiDecisionResponse.class);

                if (cachedResponse != null) {
                    AiDecisionResponse validatedCachedResponse =
                            aiDecisionValidator.validate(cachedResponse);

                    if (validatedCachedResponse == cachedResponse) {
                        log.info(
                                "AI CACHE HIT | {} | Reusing validated cached response",
                                symbol);

                        // If recording the evaluation fails, the outer catch
                        // blocks the decision instead of bypassing safety.
                        aiCallControlService.recordAiEvaluation(
                                request,
                                validatedCachedResponse);

                        return validatedCachedResponse;
                    }

                    log.warn(
                            "AI CACHE ENTRY INVALID | {} | Requesting fresh AI decision",
                            symbol);
                }
            }

            // STEP 6: Build the prompt without logging the full prompt,
            // which may contain market, portfolio or news context.
            String prompt = promptBuilder.buildPrompt(request);

            log.info("AI CALL ALLOWED | {} | Sending decision request", symbol);

            // STEP 7: Call the AI provider.
            String aiResponse = aiGatewayService.analyze(prompt);

            log.info(
                    "AI RESPONSE RECEIVED | {} | Parsing and validating response",
                    symbol);

            // STEP 8: Parse and validate the AI response.
            AiDecisionResponse parsedResponse = parser.parse(aiResponse);

            AiDecisionResponse response =
                    aiDecisionValidator.validate(parsedResponse);

            if (response == null
                    || response.getDecision() == null
                    || response.getDecision().getRecommendation() == null
                    || response.getDecision().getTradeAllowed() == null) {

                log.error(
                        "AI response validation produced an invalid decision for {}. Blocking trade.",
                        symbol);

                return buildSafeFallbackResponse();
            }

            log.info(
                    "AI FINAL DECISION | {} | recommendation={} | tradeAllowed={} | confidence={}",
                    symbol,
                    response.getDecision().getRecommendation(),
                    response.getDecision().getTradeAllowed(),
                    response.getDecision().getConfidence());

            // STEP 9: Cache the validated response.
            if (cacheKey != null && !cacheKey.isBlank()) {
                redisCacheService.put(
                        cacheKey,
                        response,
                        CacheConstants.AI_DECISION_TTL);
            }

            // STEP 10: Record the successful evaluation.
            aiCallControlService.recordAiEvaluation(
                    request,
                    response);

            return response;

        } catch (LLMProviderException | CircuitBreakerOpenException ex) {

            log.error(
                    "AI PROVIDER UNAVAILABLE | {} | Blocking trade",
                    symbol,
                    ex);

            // Do not record a failed AI evaluation.
            return buildSafeFallbackResponse();

        } catch (Exception ex) {

            log.error(
                    "AI DECISION PROCESSING FAILED | {} | Blocking trade",
                    symbol,
                    ex);

            return buildSafeFallbackResponse();
        }
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