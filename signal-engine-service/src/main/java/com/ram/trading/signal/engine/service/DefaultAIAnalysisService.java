package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.dto.SignalExplanation;
import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.service.interfac.AIAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class DefaultAIAnalysisService implements AIAnalysisService {

    @Override
    public SignalExplanation explainSignal(TradingSignal signal,TechnicalIndicatorResponse indicator) {

        String explanation;

        if ("BUY".equals(signal.getSignal())) {

            explanation =
                    "Bullish conditions detected. "
                            + "Momentum and trend indicators support a BUY signal.";

        } else if ("SELL".equals(signal.getSignal())) {

            explanation =
                    "Bearish conditions detected. "
                            + "EMA trend and MACD momentum support a SELL signal.";

        } else {

            explanation =
                    "Market conditions are neutral. "
                            + "No strong directional signal detected.";
        }

        return SignalExplanation.builder()
                .symbol(signal.getSymbol())
                .signal(signal.getSignal())
                .confidence(signal.getConfidence())
                .explanation(explanation)
                .build();
    }
}