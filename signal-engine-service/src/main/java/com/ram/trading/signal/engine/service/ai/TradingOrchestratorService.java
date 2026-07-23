package com.ram.trading.signal.engine.service.ai;

import com.ram.trading.signal.engine.service.EngineeringFilterService;
import com.ram.trading.signal.engine.service.ai.mapper.TradingDecisionMapper;
import com.ram.trading.signal.engine.service.context.TradingContextService;
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

    private final TradingContextService tradingContextService;

    private final EngineeringFilterService engineeringFilterService;

    public Mono<AiDecisionResponse> executeTrade(
            SignalGenerationRequest signalRequest) {

        log.info("======================================================");
        log.info("Starting AI Trading Pipeline for {}",
                signalRequest.getSymbol());
        log.info("======================================================");

        TradingDecision technicalDecision =
                generateTechnicalDecision(signalRequest);

        log.info("Technical Decision Generated");
        log.info("Symbol      : {}", signalRequest.getSymbol());
        log.info("Signal      : {}", technicalDecision.getSignal());
        log.info("Confidence  : {}", technicalDecision.getConfidence());
        log.info("Reasons     : {}", technicalDecision.getReasons());

        boolean eligible =
                engineeringFilterService.isEligibleForAI(technicalDecision);

        log.info("Engineering Filter Result");
        log.info("Eligible For AI : {}", eligible);

        if (!eligible) {
            log.info("Engineering Filter Rejected {}",
                    signalRequest.getSymbol());

            return Mono.empty();
        }

        return tradingContextService
                .buildTradingContext(signalRequest.getSymbol())
                .flatMap(context -> {

                    TradingDecisionRequest aiRequest =
                            tradingDecisionMapper.map(
                                    signalRequest,
                                    technicalDecision,
                                    context);

                    return callAI(aiRequest);
                });
    }

    private TradingDecision generateTechnicalDecision(
            SignalGenerationRequest request){
        return tradingDecisionEngine.generateDecision(request);

    }

    private Mono<AiDecisionResponse> callAI(
            TradingDecisionRequest request) {

        return aiDecisionIntegrationService.getDecision(request);
    }

}