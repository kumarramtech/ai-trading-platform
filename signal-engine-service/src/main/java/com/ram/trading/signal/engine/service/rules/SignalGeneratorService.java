package com.ram.trading.signal.engine.service.rules;


import com.ram.trading.signal.engine.dto.rules.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
@Service
@RequiredArgsConstructor
@Slf4j
public class SignalGeneratorService {

    private final TradingDecisionEngine decisionEngine;

    public TradingSignalResponse generateSignal(
            SignalGenerationRequest request) {

        TradingDecision decision =
                decisionEngine.generateDecision(request);

        return TradingSignalResponse.builder()
                .symbol(request.getSymbol())
                .signal(decision.getSignal())
                .confidence(decision.getConfidence())
                .confidenceLevel(decision.getConfidenceLevel())
                .reasons(decision.getReasons())
                .generatedTime(LocalDateTime.now())
                .build();

    }

}