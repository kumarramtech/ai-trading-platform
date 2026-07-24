package com.ram.trading.signal.engine.service.rules;

import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.contant.TradingConstants;
import com.ram.trading.signal.engine.dto.rules.RuleResult;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import org.springframework.stereotype.Component;

@Component
public class EmaRule implements SignalRule {

    @Override
    public RuleResult evaluate(SignalGenerationRequest request) {

        if (request.getEma20() == null || request.getEma50() == null) {
            return RuleResult.builder()
                    .ruleName(getRuleName())
                    .signal(SignalType.NEUTRAL)
                    .score(0)
                    .reason("EMA data unavailable.")
                    .build();
        }

        if (request.getEma20() > request.getEma50()) {

            return RuleResult.builder()
                    .ruleName(getRuleName())
                    .signal(SignalType.BUY)
                    .score(TradingConstants.EMA_SCORE)
                    .reason("Bullish EMA crossover.")
                    .build();
        }

        if (request.getEma20() < request.getEma50()) {

            return RuleResult.builder()
                    .ruleName(getRuleName())
                    .signal(SignalType.SELL)
                    .score(TradingConstants.EMA_SCORE)
                    .reason("Bearish EMA crossover.")
                    .build();
        }

        return RuleResult.builder()
                .ruleName(getRuleName())
                .signal(SignalType.NEUTRAL)
                .score(0)
                .reason("EMA trend is neutral.")
                .build();
    }

    @Override
    public String getRuleName() {
        return "EMA";
    }

}