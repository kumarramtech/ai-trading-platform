package com.ram.trading.signal.engine.service.ai.mapper;

import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.ai.AiDecisionResponse;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class TradingSignalMapper {

    public TradingSignal map(
            AiDecisionResponse aiResponse,
            SignalGenerationRequest request) {


        TradingSignal signal =  TradingSignal.builder()
                .symbol(request.getSymbol())
                .signal(mapSignal(aiResponse))
                .confidence(aiResponse.getDecision()
                                .getConfidence())
                .entryPrice(aiResponse.getExecutionPlan()
                                .getEntry())
                .targetPrice(aiResponse.getExecutionPlan()
                                .getTarget())
                .stopLoss(aiResponse.getExecutionPlan()
                                .getStopLoss())
                .rsi(aiResponse.getTechnicalAnalysis()
                                .getRsi()
                                .getValue())
                .ema20(aiResponse.getTechnicalAnalysis()
                                .getEma()
                                .getEma20())
                .ema50(aiResponse.getTechnicalAnalysis()
                                .getEma()
                                .getEma50())
                .macd(aiResponse.getTechnicalAnalysis()
                                .getMacd()
                                .getValue())
                .reason(aiResponse.getAiReasoning())
                .aiRecommendation(
                        aiResponse.getDecision()
                                .getRecommendation()
                                .name())

                .aiRecommendation(
                        aiResponse.getDecision()
                                .getRecommendation()
                                .name())

                .aiReasoning(
                        aiResponse.getAiReasoning())

                .riskLevel(
                        aiResponse.getRiskAnalysis()
                                .getRiskLevel())

                .positionSize(
                        String.valueOf(
                                aiResponse.getExecutionPlan()
                                        .getPositionSize()==null?null:aiResponse.getExecutionPlan()
                                        .getPositionSize()))
                .exitStrategy(
                aiResponse.getExecutionPlan()
                        .getHoldingPeriod())
                .build();
        log.info(
                "Mapped AI Response -> TradingSignal | Symbol={} Signal={} Confidence={}",
                signal.getSymbol(),
                signal.getSignal(),
                signal.getConfidence());
        return signal;

    }

    private String mapSignal(AiDecisionResponse response){
        if(Boolean.FALSE.equals(response.getDecision().getTradeAllowed())){
            return "HOLD";
        }
        return response.getDecision().getRecommendation().name();

    }

}