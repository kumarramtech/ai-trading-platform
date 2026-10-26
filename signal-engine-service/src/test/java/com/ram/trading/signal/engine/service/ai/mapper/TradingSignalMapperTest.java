package com.ram.trading.signal.engine.service.ai.mapper;

import com.ram.trading.signal.engine.contant.AiRecommendation;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.ai.AiDecisionResponse;
import com.ram.trading.signal.engine.dto.ai.decision.Decision;
import com.ram.trading.signal.engine.dto.ai.execution.ExecutionPlan;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TradingSignalMapperTest {

    private final TradingSignalMapper mapper = new TradingSignalMapper();

    @Test
    void sellWithoutAiPlanGetsDirectionallyValidSeed() {
        // MARICO 2026-10-26: AI returns direction only, no executionPlan
        TradingSignal signal = mapper.map(response(AiRecommendation.SELL, true, null), request(762.8d));

        assertEquals("SELL", signal.getSignal());
        assertEquals(762.8d, signal.getEntryPrice());
        assertTrue(signal.getTargetPrice() < signal.getEntryPrice());
        assertTrue(signal.getStopLoss() > signal.getEntryPrice());
    }

    @Test
    void buyWithoutAiPlanGetsDirectionallyValidSeed() {
        TradingSignal signal = mapper.map(response(AiRecommendation.BUY, true, null), request(100.0d));

        assertTrue(signal.getTargetPrice() > signal.getEntryPrice());
        assertTrue(signal.getStopLoss() < signal.getEntryPrice());
    }

    @Test
    void aiSuppliedPricesAreIgnored() {
        ExecutionPlan aiPlan = ExecutionPlan.builder().target(150.0d).stopLoss(50.0d).build();

        TradingSignal signal = mapper.map(response(AiRecommendation.BUY, true, aiPlan), request(100.0d));

        assertEquals(100.0d, signal.getEntryPrice());
        assertNotEquals(150.0d, signal.getTargetPrice());
        assertNotEquals(50.0d, signal.getStopLoss());
    }

    @Test
    void holdCarriesNoSeedPlan() {
        TradingSignal signal = mapper.map(response(AiRecommendation.SELL, false, null), request(100.0d));

        assertEquals("HOLD", signal.getSignal());
        assertNull(signal.getTargetPrice());
        assertNull(signal.getStopLoss());
    }

    private AiDecisionResponse response(AiRecommendation recommendation, boolean tradeAllowed, ExecutionPlan plan) {
        return AiDecisionResponse.builder()
                .decision(Decision.builder()
                        .recommendation(recommendation)
                        .tradeAllowed(tradeAllowed)
                        .confidence(71)
                        .build())
                .executionPlan(plan)
                .build();
    }

    private SignalGenerationRequest request(double price) {
        return SignalGenerationRequest.builder()
                .symbol("TEST")
                .currentPrice(price)
                .build();
    }
}
