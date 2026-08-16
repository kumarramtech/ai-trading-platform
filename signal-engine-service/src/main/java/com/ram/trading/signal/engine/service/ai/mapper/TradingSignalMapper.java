package com.ram.trading.signal.engine.service.ai.mapper;

import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.ai.AiDecisionResponse;
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

        log.info(
                "EXECUTION PRICE SOURCE | Symbol={} | MarketPrice={}",
                request.getSymbol(),
                request.getCurrentPrice());

        TradingSignal signal = TradingSignal.builder()
                .symbol(request.getSymbol())

                .signal(mapSignal(aiResponse))

                .confidence(
                        aiResponse.getDecision() != null
                                ? aiResponse.getDecision().getConfidence()
                                : 0)

                // IMPORTANT:
                // Actual execution entry price must ALWAYS come
                // from the current market price, NOT from AI.
                .entryPrice(request.getCurrentPrice())

                // AI can suggest the target price.
                .targetPrice(
                        aiResponse.getExecutionPlan() != null
                                && aiResponse.getExecutionPlan().getTarget() != null
                                ? aiResponse.getExecutionPlan().getTarget()
                                : request.getCurrentPrice())

                // AI can suggest the stop loss.
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
                                : String.valueOf(
                                aiResponse.getExecutionPlan().getPositionSize()))

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

                // IMPORTANT:
                // Technical indicators are calculated by our Signal Engine.
                // Never take these values from the AI response.
                .rsi(request.getRsi())
                .ema20(request.getEma20())
                .ema50(request.getEma50())
                .macd(request.getMacd())

                .build();

        log.info(
                "Using Engineering Technical Indicators | Symbol={} RSI={} EMA20={} EMA50={} MACD={}",
                request.getSymbol(),
                request.getRsi(),
                request.getEma20(),
                request.getEma50(),
                request.getMacd());

        validateExecutionPlan(signal);

        log.info(
                "Mapped AI Response -> TradingSignal | Symbol={} Signal={} Confidence={} Entry={} Target={} StopLoss={}",
                signal.getSymbol(),
                signal.getSignal(),
                signal.getConfidence(),
                signal.getEntryPrice(),
                signal.getTargetPrice(),
                signal.getStopLoss());

        return signal;
    }

    private String mapSignal(AiDecisionResponse response){
        if(Boolean.FALSE.equals(response.getDecision().getTradeAllowed())){
            return SignalType.HOLD.name();
        }
        return response.getDecision().getRecommendation().name();

    }

    private void validateExecutionPlan(TradingSignal signal) {

        if (SignalType.HOLD.name()
                .equalsIgnoreCase(signal.getSignal())) {

            return;
        }

        if (signal.getEntryPrice() == null ||
                signal.getTargetPrice() == null ||
                signal.getStopLoss() == null) {

            log.error(
                    "Invalid execution plan with null values | Symbol={} | Entry={} | Target={} | StopLoss={}",
                    signal.getSymbol(),
                    signal.getEntryPrice(),
                    signal.getTargetPrice(),
                    signal.getStopLoss()
            );

            applySafeExecutionPlan(signal);

            return;
        }

        boolean invalid = false;

        if (SignalType.BUY.name()
                .equalsIgnoreCase(signal.getSignal())) {

            invalid =
                    signal.getTargetPrice() <= signal.getEntryPrice()
                            || signal.getStopLoss() >= signal.getEntryPrice();

        } else if (SignalType.SELL.name()
                .equalsIgnoreCase(signal.getSignal())) {

            invalid =
                    signal.getTargetPrice() >= signal.getEntryPrice()
                            || signal.getStopLoss() <= signal.getEntryPrice();
        }

        if (invalid) {

            log.warn(
                    "Invalid AI Execution Plan detected | Symbol={} | Signal={} | Entry={} | Target={} | StopLoss={} | Applying fallback",
                    signal.getSymbol(),
                    signal.getSignal(),
                    signal.getEntryPrice(),
                    signal.getTargetPrice(),
                    signal.getStopLoss()
            );

            applySafeExecutionPlan(signal);

        } else {

            log.info(
                    "AI Execution Plan Validated Successfully | Symbol={}",
                    signal.getSymbol()
            );
        }
    }

    private void applySafeExecutionPlan(TradingSignal signal) {

        double entryPrice = signal.getEntryPrice();

        if (SignalType.BUY.name()
                .equalsIgnoreCase(signal.getSignal())) {

            signal.setTargetPrice(entryPrice * 1.02);
            signal.setStopLoss(entryPrice * 0.99);

        } else if (SignalType.SELL.name()
                .equalsIgnoreCase(signal.getSignal())) {

            signal.setTargetPrice(entryPrice * 0.98);
            signal.setStopLoss(entryPrice * 1.01);
        }

        log.info(
                "Fallback Execution Plan Applied | Symbol={} | Signal={} | Entry={} | Target={} | StopLoss={}",
                signal.getSymbol(),
                signal.getSignal(),
                signal.getEntryPrice(),
                signal.getTargetPrice(),
                signal.getStopLoss()
        );
    }

}