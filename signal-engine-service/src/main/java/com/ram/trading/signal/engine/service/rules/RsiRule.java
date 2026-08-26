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

        Double ema20 = request.getEma20();
        Double ema50 = request.getEma50();

        /*
         * ============================================================
         * RSI DATA VALIDATION
         * ============================================================
         */

        if (rsi == null) {

            return RuleResult.builder()
                    .signal(SignalType.NEUTRAL)
                    .ruleName(getRuleName())
                    .score(0)
                    .reason("RSI not available.")
                    .build();
        }

        /*
         * ============================================================
         * EMA TREND DATA VALIDATION
         * ============================================================
         *
         * RSI should not independently generate BUY/SELL when the
         * broader trend is unknown.
         */

        if (ema20 == null || ema50 == null) {

            return RuleResult.builder()
                    .signal(SignalType.NEUTRAL)
                    .ruleName(getRuleName())
                    .score(0)
                    .reason(
                            String.format(
                                    "RSI=%.2f but EMA trend unavailable. RSI direction not confirmed.",
                                    rsi))
                    .build();
        }

        /*
         * ============================================================
         * OVERSOLD
         * ============================================================
         *
         * RSI oversold is a BUY candidate only when the broader
         * EMA trend is bullish.
         *
         * RSI oversold + bearish trend
         *     -> NEUTRAL
         *
         * This prevents bottom-fishing in a strong downtrend.
         */

        if (rsi < TradingConstants.RSI_OVERSOLD) {

            if (ema20 > ema50) {

                return RuleResult.builder()
                        .signal(SignalType.BUY)
                        .score(TradingConstants.RSI_SCORE)
                        .ruleName(getRuleName())
                        .reason(
                                String.format(
                                        "RSI=%.2f indicates oversold market and EMA trend is bullish (EMA20=%.2f > EMA50=%.2f).",
                                        rsi,
                                        ema20,
                                        ema50))
                        .build();
            }

            return RuleResult.builder()
                    .signal(SignalType.NEUTRAL)
                    .score(0)
                    .ruleName(getRuleName())
                    .reason(
                            String.format(
                                    "RSI=%.2f is oversold but EMA trend is bearish/neutral (EMA20=%.2f <= EMA50=%.2f). RSI BUY not confirmed.",
                                    rsi,
                                    ema20,
                                    ema50))
                    .build();
        }

        /*
         * ============================================================
         * OVERBOUGHT
         * ============================================================
         *
         * RSI overbought is a SELL candidate only when the broader
         * EMA trend is bearish.
         *
         * RSI overbought + bullish trend
         *     -> NEUTRAL
         *
         * This prevents shorting a strong bullish trend merely because
         * RSI is temporarily overbought.
         */

        if (rsi > TradingConstants.RSI_OVERBOUGHT) {

            if (ema20 < ema50) {

                return RuleResult.builder()
                        .signal(SignalType.SELL)
                        .score(TradingConstants.RSI_SCORE)
                        .ruleName(getRuleName())
                        .reason(
                                String.format(
                                        "RSI=%.2f indicates overbought market and EMA trend is bearish (EMA20=%.2f < EMA50=%.2f).",
                                        rsi,
                                        ema20,
                                        ema50))
                        .build();
            }

            return RuleResult.builder()
                    .signal(SignalType.NEUTRAL)
                    .score(0)
                    .ruleName(getRuleName())
                    .reason(
                            String.format(
                                    "RSI=%.2f is overbought but EMA trend is bullish/neutral (EMA20=%.2f >= EMA50=%.2f). RSI SELL not confirmed.",
                                    rsi,
                                    ema20,
                                    ema50))
                    .build();
        }

        /*
         * ============================================================
         * NORMAL RSI RANGE
         * ============================================================
         */

        return RuleResult.builder()
                .signal(SignalType.NEUTRAL)
                .score(0)
                .ruleName(getRuleName())
                .reason(
                        String.format(
                                "RSI=%.2f is within the neutral range.",
                                rsi))
                .build();
    }

    @Override
    public String getRuleName() {
        return "RSI";
    }
}