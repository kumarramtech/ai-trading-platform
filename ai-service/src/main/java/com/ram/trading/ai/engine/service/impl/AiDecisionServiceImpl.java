package com.ram.trading.ai.engine.service.impl;


import com.ram.trading.ai.engine.cache.CacheConstants;
import com.ram.trading.ai.engine.cache.CacheKeyBuilder;
import com.ram.trading.ai.engine.cache.RedisCacheService;
import com.ram.trading.ai.engine.constant.AiRecommendation;
import com.ram.trading.ai.engine.dto.AiDecisionResponse;
import com.ram.trading.ai.engine.dto.TradingDecisionRequest;
import com.ram.trading.ai.engine.dto.decision.Decision;
import com.ram.trading.ai.engine.parser.AiDecisionResponseParser;
import com.ram.trading.ai.engine.prompt.AiDecisionPromptBuilder;
import com.ram.trading.ai.engine.service.AiDecisionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiDecisionServiceImpl implements AiDecisionService {

    private final ChatClient chatClient;

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
            log.info("Invoking OpenAI...");
            log.info("=========================================");

            String prompt = promptBuilder.buildPrompt(request);

            log.debug("Prompt Generated Successfully.");

            String aiResponse = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

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

            return buildFallbackResponse();
        }
    }

    private AiDecisionResponse buildFallbackResponse() {

        AiDecisionResponse response = new AiDecisionResponse();

        Decision decision = new Decision();

        decision.setRecommendation(AiRecommendation.HOLD);
        decision.setTradeAllowed(false);
        decision.setConfidence(0);
        decision.setReason("AI Service Unavailable. Using Safe Fallback Decision.");

        response.setDecision(decision);

        return response;
    }
}