package com.ram.trading.signal.engine.service.rules;

import com.ram.trading.signal.engine.contant.ConfidenceLevel;
import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.contant.Trend;
import com.ram.trading.signal.engine.dto.rules.RuleResult;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import com.ram.trading.signal.engine.dto.rules.TradingDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradingDecisionEngine {

    private static final double MINIMUM_DOMINANCE_RATIO = 0.20;

    /**
     * For ORB and JUDAS in a regime aligned with the setup, the intraday trigger
     * decides the direction; the daily EMA/MACD/RSI rules no longer force HOLD
     * when they disagree or are mixed.
     * A daily-trend conflict is still priced in by the setup-quality trend score
     * (0/20 against the trend), and the trade still needs the regime guard, the
     * AI and risk checks. 2026-10-27: 142 of 156 BUY setups were turned to HOLD
     * by daily rules while most SELL setups passed, so only exhausted shorts traded.
     */
    @Value("${trading.decision.intraday-overrides-daily:true}")
    private boolean intradayOverridesDaily = true;

    /**
     * Rule confidence is direction-agnostic (SELL=50 against a BUY setup still
     * reads 71), so an overridden decision carries this fixed, deliberately low
     * confidence instead: by default the engineering minimum for reaching the AI.
     */
    @Value("${trading.decision.override-confidence:${trading.engineering.min-ai-confidence:60}}")
    private int overrideConfidence = 60;

    /*
     * Counter-regime ORB exception: a stock breaking against the index on its
     * own news/flow (relative strength) is a real setup that the regime guard
     * otherwise always blocks. Off in live unless ...live=true, so going live
     * never inherits a paper experiment. A counter-regime setup scores at most
     * 70 on quality (regime and NIFTY momentum both 0), hence the 55 floor.
     */
    @Value("${trading.regime.counter-trend-exception.enabled:true}")
    private boolean counterTrendExceptionEnabled = true;

    @Value("${trading.regime.counter-trend-exception.live:false}")
    private boolean counterTrendExceptionLive = false;

    @Value("${trading.regime.counter-trend-exception.min-relative-pct:1.5}")
    private double counterTrendMinRelativePct = 1.5;

    @Value("${trading.regime.counter-trend-exception.min-volume-ratio:2.0}")
    private double counterTrendMinVolumeRatio = 2.0;

    @Value("${trading.regime.counter-trend-exception.min-quality:55}")
    private int counterTrendMinQuality = 55;

    @Value("${execution.trade:paper}")
    private String executionTradeMode = "paper";

    private final RuleEngine ruleEngine;
    private final RuleConfidenceCalculator confidenceCalculator;


    public TradingDecision generateDecision(SignalGenerationRequest request) {

        if (request == null) {
            log.warn("V2.2 TECHNICAL DECISION | Request is null | Decision=HOLD");
            return holdDecision("Null technical request");
        }

        List<RuleResult> results = ruleEngine.executeRules(request);

        if (results == null || results.isEmpty()) {
            log.warn("V2.2 TECHNICAL DECISION | No rule results | Symbol={} | Decision=HOLD",
                    request.getSymbol());
            return holdDecision("No technical rule results");
        }

        int buyScore = score(results, SignalType.BUY);
        int sellScore = score(results, SignalType.SELL);

        int totalPossibleScore = results.stream()
                .filter(Objects::nonNull)
                .mapToInt(RuleResult::getMaxScore)
                .sum();

        SignalType setupDirection = request.getSetupDirection();

        // Determine the technical signal and preserve setup-direction integrity.
        SignalType technicalSignal = determineSignal(results, setupDirection);

        boolean dailyOverridden = false;
        if (technicalSignal == SignalType.HOLD && intradaySetupDecides(request)) {
            log.info("V2.2 DAILY CONFLICT OVERRIDDEN | Symbol={} | Strategy={} | Setup={} | BUY={} | SELL={} | Decision={}",
                    request.getSymbol(), request.getSetupStrategy(), setupDirection,
                    buyScore, sellScore, setupDirection);
            technicalSignal = setupDirection;
            dailyOverridden = true;
        }

        SignalType signal = applyMarketRegimeGuard(request, technicalSignal);

        List<String> reasons = new ArrayList<>(collectReasons(results));
        if (dailyOverridden) {
            reasons.add("Daily indicators do not confirm the intraday " + request.getSetupStrategy()
                    + " setup; direction taken from the intraday trigger.");
        }
        if (technicalSignal != signal && signal == SignalType.HOLD) {
            reasons.add("Market regime / strategy alignment guard rejected the entry.");
        }

        int confidence = dailyOverridden
                ? overrideConfidence
                : confidenceCalculator.calculateScore(results);
        ConfidenceLevel level = confidenceCalculator.determineLevel(confidence);

        log.info(
                "V2.2 TECHNICAL DECISION | Symbol={} | Setup={} | MarketRegime={} | BUY={} | SELL={} | Confidence={} | Signal={}",
                request.getSymbol(),
                setupDirection,
                request.getMarketContext() != null
                        ? request.getMarketContext().getMarketTrend()
                        : null,
                buyScore,
                sellScore,
                confidence,
                signal
        );

        if (setupDirection != null
                && (signal == SignalType.BUY || signal == SignalType.SELL)) {

            log.info(
                    "V2.2 DIRECTION INTEGRITY | Symbol={} | Setup={} | Technical={} | Result=CONFIRMED",
                    request.getSymbol(),
                    setupDirection,
                    signal
            );

        } else if (setupDirection != null && signal == SignalType.HOLD) {

            log.debug(
                    "V2.2 DIRECTION INTEGRITY | Symbol={} | Setup={} | TechnicalEvidence=BUY:{} SELL:{} | Result=HOLD",
                    request.getSymbol(),
                    setupDirection,
                    buyScore,
                    sellScore
            );
        }

        if (log.isDebugEnabled()) {
            results.stream()
                    .filter(Objects::nonNull)
                    .forEach(result -> log.debug(
                            "V2.2 RULE | Symbol={} | Rule={} | Signal={} | Score={} | Max={} | Reason={}",
                            request.getSymbol(),
                            result.getRuleName(),
                            result.getSignal(),
                            result.getScore(),
                            result.getMaxScore(),
                            result.getReason()
                    ));
        }

        boolean technicallyValid =
                technicalSignal == SignalType.BUY || technicalSignal == SignalType.SELL;

        boolean regimeRejected = technicallyValid
                && signal == SignalType.HOLD
                && isCounterTrend(request, technicalSignal);

        boolean counterTrendException = technicallyValid
                && signal == technicalSignal
                && isCounterTrend(request, signal)
                && "ORB".equalsIgnoreCase(
                        request.getSetupStrategy() == null ? "" : request.getSetupStrategy().trim());

        if (counterTrendException) {
            reasons.add("Counter-regime ORB allowed on the stock's own relative strength and volume.");
        }

        return TradingDecision.builder()
                .signal(signal)
                .confidence(confidence)
                .confidenceLevel(level)
                .reasons(reasons)
                .ruleResults(results)
                .filterRejection(regimeRejected ? "REGIME_GUARD" : null)
                .counterTrendException(counterTrendException)
                .build();
    }


    private SignalType determineSignal(List<RuleResult> results, SignalType setupDirection) {
        int buyScore = score(results, SignalType.BUY);
        int sellScore = score(results, SignalType.SELL);
        int totalPossibleScore = results.stream()
                .filter(Objects::nonNull)
                .mapToInt(RuleResult::getMaxScore)
                .sum();

        if (buyScore == 0 && sellScore == 0) {
            return SignalType.HOLD;
        }
        if (totalPossibleScore <= 0) {
            log.warn("V2.2 SIGNAL DECISION | Invalid total possible score={} | Decision=HOLD",
                    totalPossibleScore);
            return SignalType.HOLD;
        }

        int netScore = buyScore - sellScore;
        double dominanceRatio = Math.abs(netScore) / (double) totalPossibleScore;

        if (dominanceRatio < MINIMUM_DOMINANCE_RATIO) {
            return SignalType.HOLD;
        }

        SignalType technicalDirection;
        if (buyScore > sellScore) {
            technicalDirection = SignalType.BUY;
        } else if (sellScore > buyScore) {
            technicalDirection = SignalType.SELL;
        } else {
            return SignalType.HOLD;
        }

        // Strategy setup is a directional constraint. Technical rules confirm it;
        // they must not reverse it. This preserves JUDAS/ORB direction symmetry.
        if (setupDirection == SignalType.BUY || setupDirection == SignalType.SELL) {
            if (technicalDirection != setupDirection) {
                log.info("V2.2 DIRECTION CONFLICT | Setup={} | Technical={} | BUY={} | SELL={} | Decision=HOLD",
                        setupDirection, technicalDirection, buyScore, sellScore);
                return SignalType.HOLD;
            }
        }

        return technicalDirection;
    }

    private boolean intradaySetupDecides(SignalGenerationRequest request) {
        if (!intradayOverridesDaily) {
            return false;
        }
        SignalType setupDirection = request.getSetupDirection();
        if (setupDirection != SignalType.BUY && setupDirection != SignalType.SELL) {
            return false;
        }
        String strategy = request.getSetupStrategy() == null
                ? ""
                : request.getSetupStrategy().trim().toUpperCase();
        if (!"ORB".equals(strategy) && !"JUDAS".equals(strategy)) {
            return false;
        }
        /*
         * Only with the index behind the trade (BUY in BULLISH, SELL in BEARISH).
         * In SIDEWAYS or an unknown regime the daily rules stay the filter:
         * range days are where counter-trend breakouts fail most.
         */
        Trend regime = request.getMarketContext() != null
                ? request.getMarketContext().getMarketTrend()
                : null;
        return (setupDirection == SignalType.BUY && regime == Trend.BULLISH)
                || (setupDirection == SignalType.SELL && regime == Trend.BEARISH);
    }

    /**
     * A counter-regime ORB is allowed only when the stock is clearly moving on
     * its own: its day change runs against the index by at least
     * counterTrendMinRelativePct, the setup volume is heavy, and the setup
     * scores well. Paper only unless explicitly enabled for live.
     */
    private boolean hasOwnRelativeStrength(SignalGenerationRequest request, SignalType signal) {
        if (!counterTrendExceptionEnabled
                || (!"paper".equalsIgnoreCase(executionTradeMode.trim()) && !counterTrendExceptionLive)) {
            return false;
        }

        Double stockChange = request.getStockChangePct();
        Double niftyChange = request.getMarketContext() != null
                ? request.getMarketContext().getNiftyChange()
                : null;
        Double volumeRatio = request.getSetupVolumeRatio();
        Integer quality = request.getSetupQualityScore();

        if (stockChange == null || niftyChange == null || volumeRatio == null || quality == null) {
            return false;
        }

        // SELL: stock below the index; BUY: stock above it.
        double relative = signal == SignalType.SELL
                ? niftyChange - stockChange
                : stockChange - niftyChange;

        return relative >= counterTrendMinRelativePct
                && volumeRatio >= counterTrendMinVolumeRatio
                && quality >= counterTrendMinQuality;
    }

    private static boolean isCounterTrend(SignalGenerationRequest request, SignalType signal) {
        Trend regime = request.getMarketContext() != null
                ? request.getMarketContext().getMarketTrend()
                : null;
        return (regime == Trend.BULLISH && signal == SignalType.SELL)
                || (regime == Trend.BEARISH && signal == SignalType.BUY);
    }

    private int score(List<RuleResult> results, SignalType signalType) {
        return results.stream()
                .filter(result -> result != null && result.getSignal() == signalType)
                .mapToInt(RuleResult::getScore)
                .sum();
    }

    private TradingDecision holdDecision(String reason) {
        return TradingDecision.builder()
                .signal(SignalType.HOLD)
                .confidence(0)
                .confidenceLevel(confidenceCalculator.determineLevel(0))
                .reasons(List.of(reason))
                .ruleResults(List.of())
                .build();
    }

    private List<String> collectReasons(List<RuleResult> results) {
        return results.stream()
                .filter(Objects::nonNull)
                .map(RuleResult::getReason)
                .toList();
    }

    private SignalType applyMarketRegimeGuard(
            SignalGenerationRequest request,
            SignalType signal) {

        if (signal == null || signal == SignalType.HOLD) {
            return SignalType.HOLD;
        }

        String strategy = request.getSetupStrategy() == null
                ? ""
                : request.getSetupStrategy().trim().toUpperCase();

        boolean judasSetup = "JUDAS".equals(strategy);
        boolean orbSetup = "ORB".equals(strategy);
        boolean strategyEntry = judasSetup || orbSetup;

        Trend marketTrend = request.getMarketContext() == null
                ? null
                : request.getMarketContext().getMarketTrend();

        if (marketTrend == null) {
            if (strategyEntry) {
                log.warn(
                        "MARKET REGIME GUARD REJECTED | Symbol={} | Strategy={} | Regime=UNKNOWN | Signal={} | Decision=HOLD",
                        request.getSymbol(),
                        strategy,
                        signal);
                return SignalType.HOLD;
            }

            // Preserve the established MIDDAY/non-JUDAS-ORB pipeline when its
            // DTO does not carry index-regime fields. This is not treated as
            // a positive regime confirmation.
            log.warn(
                    "MARKET REGIME UNAVAILABLE | Symbol={} | Strategy={} | "
                            + "No regime-based veto applied to this non-JUDAS/ORB path",
                    request.getSymbol(),
                    strategy);
            return signal;
        }

        if (marketTrend == Trend.SIDEWAYS) {
            // SIDEWAYS is a neutral/low-directional-confidence context, not an
            // automatic veto. Technical rules have already confirmed the setup
            // direction; AI and risk controls remain responsible for final approval.
            // Keep UNKNOWN fail-closed and keep strong counter-trend rejection below.
            log.info(
                    "MARKET REGIME NEUTRAL | Symbol={} | Strategy={} | "
                            + "Regime=SIDEWAYS | Signal={} | Decision=CONTINUE_TO_AI_AND_RISK",
                    request.getSymbol(),
                    strategy,
                    signal);
            return signal;
        }

        boolean counterTrend =
                (marketTrend == Trend.BULLISH && signal == SignalType.SELL)
                        || (marketTrend == Trend.BEARISH && signal == SignalType.BUY);

        if (counterTrend) {
            if (judasSetup) {
                // JUDAS is explicitly a sweep/reversal setup. Keep it eligible
                // for the final AI layer, which applies stricter evidence rules.
                log.warn(
                        "COUNTERTREND JUDAS | Symbol={} | Regime={} | Signal={} | "
                                + "Passed to AI for stricter reversal validation",
                        request.getSymbol(),
                        marketTrend,
                        signal);
                return signal;
            }

            if (orbSetup && hasOwnRelativeStrength(request, signal)) {
                log.warn(
                        "COUNTERTREND EXCEPTION | Symbol={} | Strategy=ORB | Regime={} | Signal={} | "
                                + "StockChange={}% | NIFTY={}% | VolumeRatio={} | Quality={} | Passed to AI as ORB_RS",
                        request.getSymbol(),
                        marketTrend,
                        signal,
                        request.getStockChangePct(),
                        request.getMarketContext().getNiftyChange(),
                        request.getSetupVolumeRatio(),
                        request.getSetupQualityScore());
                return signal;
            }

            log.warn(
                    "MARKET REGIME GUARD REJECTED | Symbol={} | Strategy={} | "
                            + "Regime={} | Signal={} | Decision=HOLD",
                    request.getSymbol(),
                    strategy,
                    marketTrend,
                    signal);
            return SignalType.HOLD;
        }

        log.debug(
                "MARKET REGIME GUARD PASSED | Symbol={} | Strategy={} | "
                        + "Regime={} | Signal={}",
                request.getSymbol(),
                strategy,
                marketTrend,
                signal);

        return signal;
    }

}
