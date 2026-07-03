package com.ram.trading.signal.engine.service.ai;

import com.ram.trading.signal.engine.client.AIServiceClient;
import com.ram.trading.signal.engine.dto.ai.AiDecisionResponse;
import com.ram.trading.signal.engine.dto.ai.TradingDecisionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiDecisionIntegrationService {

    private final AIServiceClient client;

    public AiDecisionResponse getDecision(
            TradingDecisionRequest request) {

        log.info("Calling AI Decision Service");

        return client.evaluate(request);

    }

}