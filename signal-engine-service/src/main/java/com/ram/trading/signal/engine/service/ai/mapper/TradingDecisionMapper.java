package com.ram.trading.signal.engine.service.ai.mapper;

import com.ram.trading.signal.engine.dto.ai.TradingDecisionRequest;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import com.ram.trading.signal.engine.dto.rules.TradingDecision;
import com.ram.trading.signal.engine.service.context.TradingContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class TradingDecisionMapper {

    public TradingDecisionRequest map(
            SignalGenerationRequest signalRequest,
            TradingDecision technicalDecision,
            TradingContext context) {

        log.debug("Preparing AI request for {}",
                signalRequest.getSymbol());

        // Handle missing TradingContext safely
        if (context == null) {

            log.warn("TradingContext is null for {}. Building AI request without market context.",
                    signalRequest.getSymbol());

            return TradingDecisionRequest.builder()
                    .signalRequest(signalRequest)
                    .technicalDecision(technicalDecision)
                    .build();
        }

        return TradingDecisionRequest.builder()
                .signalRequest(signalRequest)
                .technicalDecision(technicalDecision)
                .newsSummary(context.getNewsSummary())
                .newsSentiment(context.getNewsSentiment())
                .newsScore(context.getNewsScore())
                .sectorSummary(context.getSectorSummary())
                .portfolioContext(context.getPortfolioContext())
                .openPositionContext(context.getOpenPositionContext())
                .build();
    }

}