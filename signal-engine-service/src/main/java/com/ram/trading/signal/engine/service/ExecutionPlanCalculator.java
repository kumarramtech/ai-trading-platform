package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.premarket.OpeningRange;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import com.ram.trading.signal.engine.util.PriceTicks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Owns the stop-loss and target of every entry.
 *
 * The AI only confirms or vetoes the direction. It no longer supplies prices,
 * because an LLM asked to produce prices produced invalid plans for about
 * three quarters of its BUY/SELL decisions, which were then thrown away.
 *
 * Rules (deterministic and repeatable, so they can be back-tested):
 *
 *  - Stop   : just beyond the opposite Opening Range level
 *             (BUY -> below OR low, SELL -> above OR high), plus a small buffer.
 *  - Risk   : the distance entry -> stop, clamped to [minRiskPct, maxRiskPct]
 *             of the entry price, so a very wide or very tight range cannot
 *             produce an unusable stop.
 *  - Target : entry +/- rewardRisk x risk.
 *  - If no usable Opening Range exists, a fixed fallbackRiskPct is used.
 *
 * Entry price is never changed here.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExecutionPlanCalculator {

    private final OpeningRangeService openingRangeService;

    @Value("${trading.execution-plan.reward-risk:2.0}")
    private double rewardRisk = 2.0;

    @Value("${trading.execution-plan.min-risk-pct:0.40}")
    private double minRiskPct = 0.40;

    @Value("${trading.execution-plan.max-risk-pct:1.00}")
    private double maxRiskPct = 1.00;

    @Value("${trading.execution-plan.fallback-risk-pct:0.60}")
    private double fallbackRiskPct = 0.60;

    @Value("${trading.execution-plan.or-buffer-pct:0.05}")
    private double orBufferPct = 0.05;

    /** Stop and target are aligned to the exchange tick so the broker accepts them. */
    @Value("${trading.price.tick-size:0.05}")
    private double priceTickSize = 0.05;

    /**
     * Overwrites stopLoss and targetPrice of a BUY/SELL signal.
     * HOLD signals and signals without a valid entry price are left untouched.
     */
    public void apply(TradingSignal signal, SignalGenerationRequest request) {

        if (signal == null || signal.getSignal() == null) {
            return;
        }

        final boolean buy = SignalType.BUY.name().equalsIgnoreCase(signal.getSignal());
        final boolean sell = SignalType.SELL.name().equalsIgnoreCase(signal.getSignal());

        if (!buy && !sell) {
            return;
        }

        final Double entry = signal.getEntryPrice();

        if (entry == null || !Double.isFinite(entry) || entry <= 0.0) {
            log.warn("EXECUTION PLAN SKIPPED | Invalid entry price | Symbol={} | Entry={}",
                    signal.getSymbol(), entry);
            return;
        }

        final double minRisk = entry * minRiskPct / 100.0;
        final double maxRisk = entry * maxRiskPct / 100.0;

        double risk = entry * fallbackRiskPct / 100.0;
        String source = "FALLBACK_PCT";

        final OpeningRange openingRange = openingRangeService.get(signal.getSymbol());

        if (openingRange != null) {

            final Double level = buy ? openingRange.getLow() : openingRange.getHigh();

            if (level != null && Double.isFinite(level) && level > 0.0) {

                final double buffer = entry * orBufferPct / 100.0;

                final double rawRisk = buy
                        ? entry - (level - buffer)
                        : (level + buffer) - entry;

                if (rawRisk > 0.0) {
                    risk = Math.min(Math.max(rawRisk, minRisk), maxRisk);
                    source = (risk == rawRisk) ? "OPENING_RANGE" : "OPENING_RANGE_CLAMPED";
                }
            }
        }

        final double stop = PriceTicks.alignStop(
                buy ? entry - risk : entry + risk, signal.getSignal(), priceTickSize);
        final double target = PriceTicks.alignTarget(buy
                ? entry + rewardRisk * risk
                : entry - rewardRisk * risk, signal.getSignal(), priceTickSize);

        final boolean ordered = buy
                ? (stop < entry && entry < target)
                : (target < entry && entry < stop);

        if (!ordered || target <= 0.0 || stop <= 0.0) {
            log.warn("EXECUTION PLAN REJECTED | Unusable prices | Symbol={} | Signal={} | Entry={} | Stop={} | Target={}",
                    signal.getSymbol(), signal.getSignal(), entry, stop, target);
            return;
        }

        signal.setStopLoss(stop);
        signal.setTargetPrice(target);

        log.info("EXECUTION PLAN | Symbol={} | Signal={} | Source={} | Entry={} | StopLoss={} | Target={} | RiskPct={} | RewardRisk={}",
                signal.getSymbol(),
                signal.getSignal(),
                source,
                entry,
                stop,
                target,
                round2(risk / entry * 100.0),
                rewardRisk);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
