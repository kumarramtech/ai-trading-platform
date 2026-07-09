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

        log.debug("Preparing TradingDecisionRequest for AI Service.");

        return TradingDecisionRequest.builder()
                .signalRequest(signalRequest)
                .technicalDecision(technicalDecision)
                .newsSummary(context.getNewsSummary())
                .newsSentiment(context.getNewsSentiment())
                .newsScore(context.getNewsScore())
                .sectorSummary(context.getSectorSummary())
                .portfolioContext(context.getPortfolioContext())
                .build();

    }

}