package com.ram.trading.signal.engine;

import com.ram.trading.signal.engine.contant.AiRecommendation;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.ai.*;
import com.ram.trading.signal.engine.dto.ai.decision.Decision;

import com.ram.trading.signal.engine.dto.ai.execution.ExecutionPlan;
import com.ram.trading.signal.engine.dto.ai.news.NewsAnalysis;
import com.ram.trading.signal.engine.dto.ai.risk.RiskAnalysis;
import com.ram.trading.signal.engine.dto.ai.technical.*;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import com.ram.trading.signal.engine.service.ai.mapper.TradingSignalMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TradingSignalMapperTest {

    private TradingSignalMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new TradingSignalMapper();
    }

    @Test
    void shouldMapValidBuySignal() {

        SignalGenerationRequest request =
                SignalGenerationRequest.builder()
                        .symbol("TCS")
                        .build();

        Decision decision =
                Decision.builder()
                        .tradeAllowed(true)
                        .recommendation(AiRecommendation.BUY)
                        .confidence(90)
                        .decisionStrength("STRONG")
                        .build();

        ExecutionPlan executionPlan =
                ExecutionPlan.builder()
                        .entry(2200.0)
                        .target(2230.0)
                        .stopLoss(2180.0)
                        .positionSize(10)
                        .holdingPeriod("INTRADAY")
                        .build();

        RsiAnalysis rsi =
                RsiAnalysis.builder()
                        .value(45.5)
                        .build();

        EmaAnalysis ema =
                EmaAnalysis.builder()
                        .ema20(2195.0)
                        .ema50(2188.0)
                        .build();

        MacdAnalysis macd =
                MacdAnalysis.builder()
                        .value(1.25)
                        .build();

        TechnicalAnalysis technical =
                TechnicalAnalysis.builder()
                        .rsi(rsi)
                        .ema(ema)
                        .macd(macd)
                        .build();

        RiskAnalysis risk =
                RiskAnalysis.builder()
                        .riskLevel("LOW")
                        .build();

        NewsAnalysis news =
                NewsAnalysis.builder()
                        .summary("Positive News")
                        .sentiment("POSITIVE")
                        .score(85)
                        .build();

        AiDecisionResponse response =
                AiDecisionResponse.builder()
                        .decision(decision)
                        .executionPlan(executionPlan)
                        .technicalAnalysis(technical)
                        .riskAnalysis(risk)
                        .newsAnalysis(news)
                        .aiReasoning("BUY Opportunity")
                        .build();

        TradingSignal signal =
                mapper.map(response, request);

        assertNotNull(signal);
        assertEquals("TCS", signal.getSymbol());
        assertEquals("BUY", signal.getSignal());
        assertEquals(90, signal.getConfidence());

        assertEquals(2200.0, signal.getEntryPrice());
        assertEquals(2230.0, signal.getTargetPrice());
        assertEquals(2180.0, signal.getStopLoss());

        assertEquals(45.5, signal.getRsi());
        assertEquals(2195.0, signal.getEma20());
        assertEquals(2188.0, signal.getEma50());
        assertEquals(1.25, signal.getMacd());

        assertEquals("LOW", signal.getRiskLevel());
        assertEquals("Positive News", signal.getNewsSummary());
    }

    @Test
    void shouldReturnHoldWhenTradeNotAllowed() {

        SignalGenerationRequest request =
                SignalGenerationRequest.builder()
                        .symbol("TCS")
                        .build();

        Decision decision =
                Decision.builder()
                        .tradeAllowed(false)
                        .recommendation(AiRecommendation.BUY)
                        .confidence(20)
                        .build();

        ExecutionPlan executionPlan =
                ExecutionPlan.builder()
                        .entry(2200.0)
                        .target(2230.0)
                        .stopLoss(2180.0)
                        .build();

        AiDecisionResponse response =
                AiDecisionResponse.builder()
                        .decision(decision)
                        .executionPlan(executionPlan)
                        .build();

        TradingSignal signal =
                mapper.map(response, request);

        assertEquals("HOLD", signal.getSignal());
    }

    @Test
    void shouldThrowExceptionForInvalidBuyTarget() {

        SignalGenerationRequest request =
                SignalGenerationRequest.builder()
                        .symbol("TCS")
                        .build();

        Decision decision =
                Decision.builder()
                        .tradeAllowed(true)
                        .recommendation(AiRecommendation.BUY)
                        .confidence(90)
                        .build();

        ExecutionPlan executionPlan =
                ExecutionPlan.builder()
                        .entry(2200.0)
                        .target(2100.0)
                        .stopLoss(2180.0)
                        .build();

        AiDecisionResponse response =
                AiDecisionResponse.builder()
                        .decision(decision)
                        .executionPlan(executionPlan)
                        .build();

        assertThrows(
                IllegalStateException.class,
                () -> mapper.map(response, request));
    }

    @Test
    void shouldThrowExceptionForInvalidSellTarget() {

        SignalGenerationRequest request =
                SignalGenerationRequest.builder()
                        .symbol("TCS")
                        .build();

        Decision decision =
                Decision.builder()
                        .tradeAllowed(true)
                        .recommendation(AiRecommendation.SELL)
                        .confidence(90)
                        .build();

        ExecutionPlan executionPlan =
                ExecutionPlan.builder()
                        .entry(2200.0)
                        .target(2250.0)
                        .stopLoss(2230.0)
                        .build();

        AiDecisionResponse response =
                AiDecisionResponse.builder()
                        .decision(decision)
                        .executionPlan(executionPlan)
                        .build();

        assertThrows(
                IllegalStateException.class,
                () -> mapper.map(response, request));
    }

    @Test
    void shouldHandleNullTechnicalAnalysis() {

        SignalGenerationRequest request =
                SignalGenerationRequest.builder()
                        .symbol("TCS")
                        .build();

        Decision decision =
                Decision.builder()
                        .tradeAllowed(true)
                        .recommendation(AiRecommendation.BUY)
                        .confidence(90)
                        .build();

        ExecutionPlan executionPlan =
                ExecutionPlan.builder()
                        .entry(2200.0)
                        .target(2230.0)
                        .stopLoss(2180.0)
                        .build();

        AiDecisionResponse response =
                AiDecisionResponse.builder()
                        .decision(decision)
                        .executionPlan(executionPlan)
                        .technicalAnalysis(null)
                        .build();

        TradingSignal signal =
                mapper.map(response, request);

        assertNotNull(signal);
        assertNull(signal.getRsi());
        assertNull(signal.getEma20());
        assertNull(signal.getMacd());
    }
}