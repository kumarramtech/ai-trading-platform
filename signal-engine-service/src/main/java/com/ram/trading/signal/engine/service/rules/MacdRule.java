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
                "MACD Rule Input -> Symbol={}, PreviousMACD={}, PreviousSignal={}, MACD={}, SignalLine={}",
                request.getSymbol(),
                request.getPreviousMacd(),
                request.getPreviousSignalLine(),
                request.getMacd(),
                request.getSignalLine());

        if (request.getMacd() == null || request.getSignalLine() == null) {

            return RuleResult.builder()
                    .signal(SignalType.NEUTRAL)
                    .ruleName(getRuleName())
                    .score(0)
                    .maxScore(TradingConstants.MACD_SCORE)
                    .reason("MACD data unavailable.")
                    .build();
        }

        boolean bullishMomentum =
                request.getMacd() > request.getSignalLine();

        boolean bearishMomentum =
                request.getMacd() < request.getSignalLine();

        boolean hasPreviousValues =
                request.getPreviousMacd() != null
                        && request.getPreviousSignalLine() != null;

        boolean bullishCrossover =
                hasPreviousValues
                        && request.getPreviousMacd() <= request.getPreviousSignalLine()
                        && request.getMacd() > request.getSignalLine();

        boolean bearishCrossover =
                hasPreviousValues
                        && request.getPreviousMacd() >= request.getPreviousSignalLine()
                        && request.getMacd() < request.getSignalLine();

        if (bullishMomentum) {

            return RuleResult.builder()
                    .signal(SignalType.BUY)
                    .score(TradingConstants.MACD_SCORE)
                    .maxScore(TradingConstants.MACD_SCORE)
                    .ruleName(getRuleName())
                    .reason(
                            bullishCrossover
                                    ? "Fresh bullish MACD crossover."
                                    : "Bullish MACD momentum (MACD above signal line; no fresh crossover).")
                    .build();
        }

        if (bearishMomentum) {

            return RuleResult.builder()
                    .signal(SignalType.SELL)
                    .score(TradingConstants.MACD_SCORE)
                    .maxScore(TradingConstants.MACD_SCORE)
                    .ruleName(getRuleName())
                    .reason(
                            bearishCrossover
                                    ? "Fresh bearish MACD crossover."
                                    : "Bearish MACD momentum (MACD below signal line; no fresh crossover).")
                    .build();
        }

        return RuleResult.builder()
                .signal(SignalType.NEUTRAL)
                .score(0)
                .maxScore(TradingConstants.MACD_SCORE)
                .ruleName(getRuleName())
                .reason("MACD is neutral.")
                .build();
    }

    @Override
    public String getRuleName() {
        return "MACD";
    }

}