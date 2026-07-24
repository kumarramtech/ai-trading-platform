package com.ram.trading.signal.engine.service.rules;

import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.contant.TradingConstants;
import com.ram.trading.signal.engine.dto.rules.RuleResult;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MacdRule implements SignalRule {

    @Override
    public RuleResult evaluate(SignalGenerationRequest request) {

        log.info(
                "MACD Rule Input -> Symbol={}, MACD={}, SignalLine={}",
                request.getSymbol(),
                request.getMacd(),
                request.getSignalLine());

        if (request.getMacd() == null || request.getSignalLine() == null) {

            return RuleResult.builder()
                    .signal(SignalType.NEUTRAL)
                    .ruleName(getRuleName())
                    .score(0)
                    .reason("MACD data unavailable.")
                    .build();
        }

        if (request.getMacd() > request.getSignalLine()) {

            return RuleResult.builder()
                    .signal(SignalType.BUY)
                    .score(TradingConstants.MACD_SCORE)
                    .ruleName(getRuleName())
                    .reason("Bullish MACD crossover.")
                    .build();
        }

        if (request.getMacd() < request.getSignalLine()) {

            return RuleResult.builder()
                    .signal(SignalType.SELL)
                    .ruleName(getRuleName())
                    .score(TradingConstants.MACD_SCORE)
                    .reason("Bearish MACD crossover.")
                    .build();
        }

        return RuleResult.builder()
                .signal(SignalType.NEUTRAL)
                .ruleName(getRuleName())
                .score(0)
                .reason("MACD is neutral.")
                .build();
    }

    @Override
    public String getRuleName() {
        return "MACD";
    }

}