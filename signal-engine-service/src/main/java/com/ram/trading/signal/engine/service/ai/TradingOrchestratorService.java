package com.ram.trading.signal.engine.service.ai;

import com.ram.trading.signal.engine.service.ai.mapper.TradingDecisionMapper;
import com.ram.trading.signal.engine.service.rules.TradingDecisionEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.ram.trading.signal.engine.dto.ai.AiDecisionResponse;
import com.ram.trading.signal.engine.dto.ai.TradingDecisionRequest;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import com.ram.trading.signal.engine.dto.rules.TradingDecision;
import reactor.core.publisher.Mono;


@Service
@RequiredArgsConstructor
@Slf4j
public class TradingOrchestratorService {

    private final TradingDecisionEngine tradingDecisionEngine;

    private final TradingDecisionMapper tradingDecisionMapper;

    private final AiDecisionIntegrationService aiDecisionIntegrationService;

    public Mono<AiDecisionResponse> executeTrade(
            SignalGenerationRequest signalRequest,
            String newsSummary,
            String sectorSummary,
            String portfolioSummary) {

        log.info("Starting AI Trading Pipeline for {}",
                signalRequest.getSymbol());

        TradingDecision technicalDecision =
                generateTechnicalDecision(signalRequest);

        TradingDecisionRequest aiRequest =
                buildAIRequest(
                        signalRequest,
                        technicalDecision,
                        newsSummary,
                        sectorSummary,
                        portfolioSummary);

        return callAI(aiRequest)
                .doOnNext(response -> {

                    log.info("Calling AI Service...");

                    log.info(
                            "AI Recommendation={}",
                            response.getDecision().getRecommendation());

                    log.info(
                            "AI Confidence={}",
                            response.getDecision().getConfidence());

                });
    }

    private TradingDecision generateTechnicalDecision(
            SignalGenerationRequest request){
        return tradingDecisionEngine.generateDecision(request);

    }

    private TradingDecisionRequest buildAIRequest(SignalGenerationRequest signalRequest,
            TradingDecision decision, String newsSummary, String sectorSummary, String portfolioSummary){
        return tradingDecisionMapper.map(signalRequest, decision, newsSummary,
                sectorSummary, portfolioSummary);
    }
    private Mono<AiDecisionResponse> callAI(
            TradingDecisionRequest request) {

        return aiDecisionIntegrationService.getDecision(request);
    }

}