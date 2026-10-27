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
 *  - Stop   : JUDAS -> beyond the sweep candle's extreme plus sweepBufferPct;
 *             the trade is skipped if that is wider than maxRiskPct.
 *             Others -> just beyond the opposite Opening Range level
 *             (BUY -> below OR low, SELL -> above OR high), plus a small buffer.
 *  - Risk   : the distance entry -> stop, clamped to [minRiskPct, maxRiskPct]
 *             of the entry price, so a very wide or very tight range cannot
 *             produce an unusable stop. A structural stop closer than
 *             minStopPct, or an OR stop wider than maxRiskPct, skips the
 *             trade instead of being clamped.
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

    /**
     * A setup whose structural stop (sweep extreme or OR level, with buffer) is
     * closer than this % of entry is skipped, not widened: a stop that tight sits
     * inside ordinary intraday noise (2026-10-26: five of five trades with stops of
     * 0.38-0.49% were stopped out). 0 disables the rule.
     */
    @Value("${trading.execution-plan.min-stop-pct:0.60}")
    private double minStopPct = 0.60;

    /** Skip, rather than clamp, an opening-range stop wider than maxRiskPct. */
    @Value("${trading.execution-plan.reject-clamped-or-stop:true}")
    private boolean rejectClampedOrStop = true;

    @Value("${trading.execution-plan.fallback-risk-pct:0.60}")
    private double fallbackRiskPct = 0.60;

    @Value("${trading.execution-plan.or-buffer-pct:0.05}")
    private double orBufferPct = 0.05;

    /** JUDAS stop distance beyond the sweep extreme, so a plain retest of it does not stop the trade out. */
    @Value("${trading.execution-plan.sweep-buffer-pct:0.10}")
    private double sweepBufferPct = 0.10;

    /** Stop and target are aligned to the exchange tick so the broker accepts them. */
    @Value("${trading.price.tick-size:0.05}")
    private double priceTickSize = 0.05;

    /**
     * Overwrites stopLoss and targetPrice of a BUY/SELL signal.
     * HOLD signals and signals without a valid entry price are left untouched.
     *
     * @return false only when the setup must not be traded (its structural
     *         stop is wider than maxRiskPct, tighter than minStopPct or already
     *         breached); true otherwise.
     */
    public boolean apply(TradingSignal signal, SignalGenerationRequest request) {

        if (signal == null || signal.getSignal() == null) {
            return true;
        }

        final boolean buy = SignalType.BUY.name().equalsIgnoreCase(signal.getSignal());
        final boolean sell = SignalType.SELL.name().equalsIgnoreCase(signal.getSignal());

        if (!buy && !sell) {
            return true;
        }

        final Double entry = signal.getEntryPrice();

        if (entry == null || !Double.isFinite(entry) || entry <= 0.0) {
            log.warn("EXECUTION PLAN SKIPPED | Invalid entry price | Symbol={} | Entry={}",
                    signal.getSymbol(), entry);
            return true;
        }

        final double minRisk = entry * minRiskPct / 100.0;
        final double maxRisk = entry * maxRiskPct / 100.0;

        double risk = entry * fallbackRiskPct / 100.0;
        String source = "FALLBACK_PCT";

        final Double sweepExtreme = request != null ? request.getSetupInvalidationLevel() : null;

        if (sweepExtreme != null && Double.isFinite(sweepExtreme) && sweepExtreme > 0.0) {

            /*
             * JUDAS: the setup is wrong only once price trades back through the
             * sweep extreme, so the stop sits beyond it with a real buffer.
             * The OR-level stop clamped to 0.4% used to land within a tick or
             * two of the sweep high (COCHINSHIP 2026-10-26: sweep 1140.0, stop
             * 1140.1) and was taken out by an ordinary retest.
             *
             * The stop is never pulled inside the sweep to fit maxRiskPct; a
             * sweep too far away to risk means the trade is skipped.
             */
            // At least one tick, so tick alignment (which rounds stops toward
            // entry) can never put the stop back on the sweep extreme.
            final double buffer = Math.max(entry * sweepBufferPct / 100.0, priceTickSize);

            final double rawRisk = buy
                    ? entry - (sweepExtreme - buffer)
                    : (sweepExtreme + buffer) - entry;

            if (rawRisk <= 0.0 || rawRisk > maxRisk) {
                log.warn("EXECUTION PLAN REJECTED | Sweep stop outside allowed risk | Symbol={} | Signal={} | Entry={} | SweepExtreme={} | RiskPct={} | MaxRiskPct={}",
                        signal.getSymbol(), signal.getSignal(), entry, sweepExtreme,
                        round2(rawRisk / entry * 100.0), maxRiskPct);
                return false;
            }

            if (tooTight(signal, entry, rawRisk, "SWEEP_EXTREME")) {
                return false;
            }

            risk = Math.max(rawRisk, minRisk);
            source = (risk == rawRisk) ? "SWEEP_EXTREME" : "SWEEP_EXTREME_MIN_RISK";

        } else {

            final OpeningRange openingRange = openingRangeService.get(signal.getSymbol());
            final Double level = openingRange == null ? null
                    : (buy ? openingRange.getLow() : openingRange.getHigh());

            if (level != null && Double.isFinite(level) && level > 0.0) {

                final double buffer = entry * orBufferPct / 100.0;

                final double rawRisk = buy
                        ? entry - (level - buffer)
                        : (level + buffer) - entry;

                if (rawRisk > 0.0) {
                    if (tooTight(signal, entry, rawRisk, "OPENING_RANGE")) {
                        return false;
                    }
                    /*
                     * An OR level further than maxRiskPct used to be clamped,
                     * leaving the stop inside the range at no market level
                     * (2026-10-27: 10 of 13 plans; every ORB/MIDDAY trade).
                     * Like a far JUDAS sweep, such a setup is skipped.
                     */
                    if (rejectClampedOrStop && rawRisk > maxRisk) {
                        log.warn("EXECUTION PLAN REJECTED | Opening-range stop outside allowed risk | Symbol={} | Signal={} | Entry={} | Level={} | RiskPct={} | MaxRiskPct={}",
                                signal.getSymbol(), signal.getSignal(), entry, level,
                                round2(rawRisk / entry * 100.0), maxRiskPct);
                        return false;
                    }
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
            return true;
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

        return true;
    }

    private boolean tooTight(TradingSignal signal, double entry, double rawRisk, String source) {

        // Small tolerance so a stop at exactly minStopPct is not lost to floating point.
        if (minStopPct <= 0.0 || rawRisk >= entry * minStopPct / 100.0 - 1e-9) {
            return false;
        }

        log.warn("EXECUTION PLAN REJECTED | Structural stop tighter than minimum | Symbol={} | Signal={} | Source={} | Entry={} | RiskPct={} | MinStopPct={}",
                signal.getSymbol(), signal.getSignal(), source, entry,
                round2(rawRisk / entry * 100.0), minStopPct);

        return true;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
