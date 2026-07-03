package com.ram.trading.signal.engine.service.ai.mapper;

import com.ram.trading.signal.engine.dto.ai.TradingDecisionRequest;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import com.ram.trading.signal.engine.dto.rules.TradingDecision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class TradingDecisionMapper {

    public TradingDecisionRequest map(
            SignalGenerationRequest signalRequest,
            TradingDecision technicalDecision,
            String newsSummary,
            String sectorSummary,
            String portfolioSummary) {

        log.debug("Preparing TradingDecisionRequest for AI Service.");

        return TradingDecisionRequest.builder()
                .signalRequest(signalRequest)
                .technicalDecision(technicalDecision)
                .newsSummary(newsSummary)
                .sectorSummary(sectorSummary)
                .portfolioSummary(portfolioSummary)
                .build();

    }

}