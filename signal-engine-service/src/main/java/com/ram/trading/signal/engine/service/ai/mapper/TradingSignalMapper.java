package com.ram.trading.signal.engine.service.ai.mapper;

import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.ai.AiDecisionResponse;
import com.ram.trading.signal.engine.dto.ai.technical.TechnicalAnalysis;
import com.ram.trading.signal.engine.dto.portfolio.RiskLevel;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class TradingSignalMapper {

    public TradingSignal map(
            AiDecisionResponse aiResponse,
            SignalGenerationRequest request) {

        TradingSignal signal = TradingSignal.builder()
                .symbol(request.getSymbol())
                .signal(mapSignal(aiResponse))
                .confidence(aiResponse.getDecision() != null
                        ? aiResponse.getDecision().getConfidence()
                        : 0)

                .entryPrice(
                        aiResponse.getExecutionPlan() != null
                                && aiResponse.getExecutionPlan().getEntry() != null
                                ? aiResponse.getExecutionPlan().getEntry()
                                : request.getCurrentPrice())

                .targetPrice(
                        aiResponse.getExecutionPlan() != null
                                && aiResponse.getExecutionPlan().getTarget() != null
                                ? aiResponse.getExecutionPlan().getTarget()
                                : request.getCurrentPrice())

                .stopLoss(
                        aiResponse.getExecutionPlan() != null
                                && aiResponse.getExecutionPlan().getStopLoss() != null
                                ? aiResponse.getExecutionPlan().getStopLoss()
                                : request.getCurrentPrice())

                .reason(aiResponse.getAiReasoning())

                .aiRecommendation(
                        aiResponse.getDecision() == null
                                ? SignalType.HOLD.name()
                                : aiResponse.getDecision().getRecommendation().name())

                .aiReasoning(aiResponse.getAiReasoning())

                .riskLevel(
                        aiResponse.getRiskAnalysis() == null
                                ? RiskLevel.UNKNOWN.name()
                                : aiResponse.getRiskAnalysis().getRiskLevel())

                .positionSize(
                        aiResponse.getExecutionPlan() == null
                                || aiResponse.getExecutionPlan().getPositionSize() == null
                                ? null
                                : String.valueOf(aiResponse.getExecutionPlan().getPositionSize()))

                .exitStrategy(
                        aiResponse.getExecutionPlan() == null
                                ? null
                                : aiResponse.getExecutionPlan().getHoldingPeriod())

                .newsSummary(
                        aiResponse.getNewsAnalysis() == null
                                ? null
                                : aiResponse.getNewsAnalysis().getSummary())

                .newsSentiment(
                        aiResponse.getNewsAnalysis() == null
                                ? null
                                : aiResponse.getNewsAnalysis().getSentiment())

                .newsScore(
                        aiResponse.getNewsAnalysis() == null
                                ? null
                                : aiResponse.getNewsAnalysis().getScore())

                .build();

        TechnicalAnalysis technical = aiResponse.getTechnicalAnalysis();

        if (technical != null) {

            // RSI
            if (technical.getRsi() != null) {
                signal.setRsi(technical.getRsi().getValue());
            } else {
                signal.setRsi(request.getRsi());
            }

            // EMA
            if (technical.getEma() != null) {
                signal.setEma20(technical.getEma().getEma20());
                signal.setEma50(technical.getEma().getEma50());
            } else {
                signal.setEma20(request.getEma20());
                signal.setEma50(request.getEma50());
            }

            // MACD
            if (technical.getMacd() != null) {
                signal.setMacd(technical.getMacd().getValue());
            } else {
                signal.setMacd(request.getMacd());
            }

        } else {

            signal.setRsi(request.getRsi());
            signal.setEma20(request.getEma20());
            signal.setEma50(request.getEma50());
            signal.setMacd(request.getMacd());
        }

        validateExecutionPlan(signal);

        log.info(
                "Mapped AI Response -> TradingSignal | Symbol={} Signal={} Confidence={}",
                signal.getSymbol(),
                signal.getSignal(),
                signal.getConfidence());

        return signal;
    }

    private String mapSignal(AiDecisionResponse response){
        if(Boolean.FALSE.equals(response.getDecision().getTradeAllowed())){
            return SignalType.HOLD.name();
        }
        return response.getDecision().getRecommendation().name();

    }

    private void validateExecutionPlan(TradingSignal signal) {

        if (SignalType.HOLD.name().equalsIgnoreCase(signal.getSignal())) {
            return;
        }

        if (signal.getEntryPrice() == null ||
                signal.getTargetPrice() == null ||
                signal.getStopLoss() == null) {

            log.error("Entry      : {}", signal.getEntryPrice());
            log.error("Target     : {}", signal.getTargetPrice());
            log.error("Stop Loss  : {}", signal.getStopLoss());

            throw new IllegalStateException(
                    "AI Execution Plan contains null values.");
        }

        if ("BUY".equalsIgnoreCase(signal.getSignal())) {

            if (signal.getTargetPrice() <= signal.getEntryPrice()) {
                throw new IllegalStateException(
                        "Invalid BUY Execution Plan from AI.");
            }

            if (signal.getStopLoss() >= signal.getEntryPrice()) {
                throw new IllegalStateException(
                        "Invalid BUY Stop Loss from AI.");
            }

        } else if ("SELL".equalsIgnoreCase(signal.getSignal())) {

            if (signal.getTargetPrice() >= signal.getEntryPrice()) {
                throw new IllegalStateException(
                        "Invalid SELL Execution Plan from AI.");
            }

            if (signal.getStopLoss() <= signal.getEntryPrice()) {
                throw new IllegalStateException(
                        "Invalid SELL Stop Loss from AI.");
            }
        }

        log.info(
                "AI Execution Plan Validated Successfully for {}",
                signal.getSymbol());
    }

}