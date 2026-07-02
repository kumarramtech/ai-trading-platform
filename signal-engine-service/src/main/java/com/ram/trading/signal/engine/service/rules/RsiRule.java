package com.ram.trading.signal.engine.service.rules;

import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.contant.TradingConstants;
import com.ram.trading.signal.engine.dto.rules.RuleResult;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RsiRule implements SignalRule {

    @Override
    public RuleResult evaluate(SignalGenerationRequest request) {

        Double rsi = request.getRsi();

        if (rsi == null) {
            return RuleResult.builder()
                    .signal(SignalType.NEUTRAL)
                    .score(0)
                    .reason("RSI not available.")
                    .build();
        }

        if (rsi < TradingConstants.RSI_OVERSOLD) {
            return RuleResult.builder()
                    .signal(SignalType.BUY)
                    .score(TradingConstants.RSI_SCORE)
                    .reason("RSI indicates oversold market.")
                    .build();
        }

        if (rsi > TradingConstants.RSI_OVERBOUGHT) {
            return RuleResult.builder()
                    .signal(SignalType.SELL)
                    .score(TradingConstants.RSI_SCORE)
                    .reason("RSI indicates overbought market.")
                    .build();
        }

        return RuleResult.builder()
                .signal(SignalType.NEUTRAL)
                .score(0)
                .reason("RSI is neutral.")
                .build();
    }

    @Override
    public String getRuleName() {
        return "RSI";
    }
}