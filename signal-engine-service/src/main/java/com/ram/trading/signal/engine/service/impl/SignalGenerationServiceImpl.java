package com.ram.trading.signal.engine.service.impl;

import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.client.StockServiceClient;
import com.ram.trading.signal.engine.client.TradeExecutionClient;

import com.ram.trading.signal.engine.dto.EntryQualityResult;
import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.ai.AiDecisionResponse;
import com.ram.trading.signal.engine.dto.market.Tick;
import com.ram.trading.signal.engine.dto.portfolio.PortfolioContextResponse;
import com.ram.trading.signal.engine.dto.premarket.JudasSwingResult;
import com.ram.trading.signal.engine.dto.premarket.MinuteCandle;
import com.ram.trading.signal.engine.dto.premarket.ORBResult;
import com.ram.trading.signal.engine.dto.premarket.OpeningRange;
import com.ram.trading.signal.engine.risk.PositionSizingResult;
import com.ram.trading.signal.engine.risk.PositionSizingService;
import com.ram.trading.signal.engine.util.TradingSessionService;
import com.ram.trading.signal.engine.exit.TradeExitService;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import com.ram.trading.signal.engine.dto.rules.MarketContext;
import com.ram.trading.signal.engine.contant.Trend;
import com.ram.trading.signal.engine.indicator.service.TechnicalIndicatorService;
import com.ram.trading.signal.engine.risk.RiskEvaluation;
import com.ram.trading.signal.engine.risk.RiskGuardResult;
import com.ram.trading.signal.engine.risk.RiskGuardService;
import com.ram.trading.signal.engine.service.*;
import com.ram.trading.signal.engine.service.ai.TradingFunnelStatisticsService;
import com.ram.trading.signal.engine.service.ai.TradingOrchestratorService;
import com.ram.trading.signal.engine.service.ai.mapper.TradingSignalMapper;
import com.ram.trading.signal.engine.service.ExecutionPlanCalculator;
import com.ram.trading.signal.engine.service.context.TradingContext;
import com.ram.trading.signal.engine.service.interfac.MarketDataProvider;
import com.ram.trading.signal.engine.dto.ai.decision.TradingPipelineResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import com.ram.trading.signal.engine.service.performance.SetupEdgeGate;
import com.ram.trading.signal.engine.service.performance.SetupQualityScorer;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
@Slf4j
public class SignalGenerationServiceImpl implements SignalGenerationService {

    private final MarketDataProvider marketDataProvider;

    private final StockServiceClient stockServiceClient;

    private final TradeExecutionClient tradeExecutionClient;

    private final TradingOrchestratorService tradingOrchestratorService;

    private final TradingSignalMapper tradingSignalMapper;
    private final ExecutionPlanCalculator executionPlanCalculator;

    private final TradingSignalService tradingSignalService;

    private final TechnicalIndicatorService technicalIndicatorService;

    private final PaperTradingService paperTradingService;

    private final RiskGuardService riskGuardService;

    private final TradeExitService tradeExitService;

    private final OpportunityService opportunityService;

    private final TradingFunnelStatisticsService tradingFunnelStatisticsService;

    private final EntryQualityService entryQualityService;

    private final MinuteCandleAggregator minuteCandleAggregator;

    private final TradingSessionService tradingSessionService;

    private final OpeningRangeService openingRangeService;

    private final JudasSwingDetector judasSwingDetector;

    private final ORBValidator orbValidator;

    private final PositionSizingService positionSizingService;

    private final SetupEdgeGate setupEdgeGate;

    private final SetupQualityScorer setupQualityScorer;

    private final RiskManagementService riskManagementService;

    /** Follows filter-rejected setups as if traded, to measure each filter. */
    private final com.ram.trading.signal.engine.service.shadow.ShadowTradeService shadowTradeService;

    /** Only stocks that can be exited near the planned price (min price, min daily turnover). */
    private final com.ram.trading.signal.engine.risk.LiquidityFilter liquidityFilter;

    /** Setups scoring below this are not traded at all (0 = off). */
    @Value("${trading.quality.min-score:40}")
    private int minQualityScore = 40;

    /**
     * When only one open-trade slot is left, it is kept for a setup scoring at
     * least this much, so a mediocre early signal cannot take the last slot
     * from a better one a few minutes later (0 = off).
     */
    @Value("${trading.quality.last-slot-min-score:60}")
    private int lastSlotMinScore = 60;

    /**
     * Selects the entry execution path. PAPER remains the default;
     * LIVE stays fail-closed until the Trade Execution client is wired.
     */
    @Value("${execution.trade:paper}")
    private String executionTradeMode;

    /** Independent Signal Service safety gate; defaults to disabled. */
    @Value("${execution.live-enabled:false}")
    private boolean liveExecutionEnabled;

    @Value("${trading.market.strategy.entry.start:09:30}")
    private LocalTime strategyEntryStart = LocalTime.of(9, 30);

    /** ORB entries run until this time (JUDAS stays an opening-hour setup, 09:30-10:30). */
    @Value("${trading.strategy.orb.end:${trading.market.entry.cut:14:30}}")
    private LocalTime orbEntryEnd = LocalTime.of(14, 30);

    /**
     * The 12:00 MIDDAY path enters on daily indicators and the discovery move,
     * without an intraday JUDAS/ORB trigger or a market-regime check.
     */
    @Value("${trading.strategy.midday.enabled:true}")
    private boolean middayEntriesEnabled = true;

    /**
     * Upper bound for one detached entry pipeline (AI call + risk guard +
     * position sizing + paper-trade creation). It is independent of the
     * market-stream tick HTTP timeout (5s), which previously cancelled the
     * pipeline while the AI service was still answering (median ~7s).
     */
    @Value("${trading.pipeline.detached-timeout-seconds:90}")
    private long detachedPipelineTimeoutSeconds = 90L;

    /**
     * Reject entries whose entry-quality status is EXTENDED (price already far
     * from EMA20 with RSI stretched in the trade direction, or moved >= 1% since
     * the signal). RENUKA 2026-10-27 shorted at -13.6% vs EMA20, RSI 24.6.
     */
    @Value("${trading.entry-quality.reject-extended:true}")
    private boolean rejectExtendedEntries = true;

    private final AtomicInteger detachedPipelinesInFlight = new AtomicInteger();

    /**
     * Index regime from the latest live tick, lent to the MIDDAY path (which has no tick).
     * changedAt is when this trend was first seen after a different one.
     */
    private record RegimeSnapshot(Trend trend, LocalDateTime seenAt, LocalDateTime changedAt) {}

    /**
     * No entry in the direction of a regime flip younger than this: a SELL right
     * after the index turns BEARISH, or a BUY right after it turns BULLISH, enters
     * at the extreme of the move that caused the flip (2026-10-27: BEARISH at 14:05,
     * back to SIDEWAYS at 14:31; three shorts at 14:08-14:19 lost Rs 632). 0 = off.
     */
    @Value("${trading.regime.fresh-flip-minutes:15}")
    private long freshRegimeFlipMinutes = 15;

    /**
     * MIDDAY follows the daily movers, which a range-bound index rarely carries
     * further; while the live regime is SIDEWAYS no MIDDAY entry is taken.
     * An unknown or stale regime does not block (no live tick to judge by).
     */
    @Value("${trading.strategy.midday.skip-sideways:true}")
    private boolean skipMiddayInSideways = true;

    private static final Duration REGIME_MAX_AGE = Duration.ofMinutes(5);

    private final AtomicReference<RegimeSnapshot> latestMarketRegime = new AtomicReference<>();

    /**
     * The regime seen on a live tick in the last 5 minutes, so the market-regime
     * guard can veto counter-trend MIDDAY entries; null (no veto, as before) if stale.
     */
    private Trend freshMarketTrend() {
        RegimeSnapshot snapshot = latestMarketRegime.get();
        if (snapshot == null
                || snapshot.seenAt().isBefore(LocalDateTime.now().minus(REGIME_MAX_AGE))) {
            return null;
        }
        return snapshot.trend();
    }

    private void recordMarketRegime(Trend trend) {
        LocalDateTime now = LocalDateTime.now();
        latestMarketRegime.updateAndGet(previous ->
                new RegimeSnapshot(
                        trend,
                        now,
                        previous != null && previous.trend() == trend
                                ? previous.changedAt()
                                : now));
    }

    /**
     * True when the entry would follow a regime flip in its own direction that is
     * younger than trading.regime.fresh-flip-minutes.
     */
    private boolean chasesFreshRegimeFlip(String symbol, String strategy, String direction) {
        RegimeSnapshot snapshot = latestMarketRegime.get();
        if (freshRegimeFlipMinutes <= 0 || snapshot == null || direction == null
                || snapshot.seenAt().isBefore(LocalDateTime.now().minus(REGIME_MAX_AGE))) {
            return false;
        }

        boolean sameDirection =
                (snapshot.trend() == Trend.BULLISH && "BUY".equalsIgnoreCase(direction))
                        || (snapshot.trend() == Trend.BEARISH && "SELL".equalsIgnoreCase(direction));

        LocalDateTime settledAt = snapshot.changedAt().plusMinutes(freshRegimeFlipMinutes);

        if (!sameDirection || !LocalDateTime.now().isBefore(settledAt)) {
            return false;
        }

        log.warn(
                "FRESH REGIME FLIP | Entry skipped | Symbol={} | Strategy={} | Direction={} | Regime={} | Since={} | SettlesAt={}",
                symbol,
                strategy,
                direction,
                snapshot.trend(),
                snapshot.changedAt().toLocalTime(),
                settledAt.toLocalTime());
        return true;
    }

    @PostConstruct
    private void validateExecutionTradeMode() {
        if (executionTradeMode == null
                || (!"paper".equalsIgnoreCase(executionTradeMode.trim())
                && !"live".equalsIgnoreCase(executionTradeMode.trim()))) {
            throw new IllegalStateException("Invalid execution.trade value: "
                    + executionTradeMode + ". Allowed values are paper or live.");
        }
        log.info("ENTRY EXECUTION MODE CONFIGURED | execution.trade={}",
                executionTradeMode.trim().toLowerCase());
    }

    private final Map<String, String> processedStrategySetups = new ConcurrentHashMap<>();

    private record EntrySetup(
            String strategy,
            String direction,
            LocalDateTime confirmationTime,
            Double invalidationLevel,
            Double volumeRatio
    ) {}

    @Override
    public Mono<TradingSignal> generateSignal(String symbol) {

        log.debug("GENERIC SIGNAL ANALYSIS STARTED | Symbol={}", symbol);

        /*
         * ============================================================
         * IMPORTANT SAFETY GATE
         * ============================================================
         *
         * This String-based signal generation path is used by the
         * Market Scanner / revalidation flow.
         *
         * It does NOT have a JUDAS / ORB EntrySetup.
         *
         * Therefore it MUST NOT create a trading signal that can
         * reach PaperTradingService and open a trade.
         *
         * All automatic entries must come through:
         *
         * Tick
         *   ↓
         * Opening Range
         *   ↓
         * JUDAS / ORB
         *   ↓
         * EntrySetup
         *   ↓
         * Engineering
         *   ↓
         * AI
         *   ↓
         * Risk Guard
         *   ↓
         * Trade
         *
         * The Tick-based generateSignal(Tick) method is responsible
         * for the actual strategy-entry pipeline.
         * ============================================================
         */

        if (!tradingSessionService.canCreateStrategyEntry()) {

            log.debug(
                    "GENERIC SIGNAL PATH BLOCKED | " +
                            "Symbol={} | Strategy entry window is closed",
                    symbol);

            return Mono.just(
                    TradingSignal.builder()
                            .symbol(symbol)
                            .signal(SignalType.HOLD.name())
                            .build());
        }

        /*
         * ============================================================
         * GENERIC SCANNER PATH
         * ============================================================
         *
         * We can still calculate technical indicators for analysis,
         * but we deliberately DO NOT call generateTradingSignal().
         *
         * Calling generateTradingSignal() would eventually execute
         * postProcessSignal(), which can create a paper trade.
         *
         * Therefore this path remains analysis-only.
         * ============================================================
         */

        return marketDataProvider
                .getStockPrice(symbol)

                .doOnNext(stock ->
                        log.debug(
                                "Scanner Analysis Price | Symbol={} | Price={}",
                                stock.getSymbol(),
                                stock.getPrice()))

                .zipWhen(stock ->
                        technicalIndicatorService
                                .calculate(symbol)
                                .switchIfEmpty(
                                        Mono.defer(() -> {

                                            log.warn(
                                                    "Scanner Analysis skipped | " +
                                                            "Technical indicators unavailable | " +
                                                            "Symbol={}",
                                                    symbol);

                                            return Mono.empty();
                                        }))
                )

                .map(tuple -> {

                    StockResponse stock = tuple.getT1();
                    TechnicalIndicatorResponse indicator = tuple.getT2();

                    log.debug(
                            "Scanner Analysis Completed | " +
                                    "Symbol={} | RSI={} | EMA20={} | EMA50={} | MACD={}",
                            symbol,
                            indicator.getRsi14(),
                            indicator.getEma20(),
                            indicator.getEma50(),
                            indicator.getMacd());

                    /*
                     * IMPORTANT:
                     *
                     * Do NOT call:
                     *
                     * generateTradingSignal(request, indicator)
                     *
                     * here.
                     *
                     * This method has no JUDAS / ORB EntrySetup and
                     * therefore must never produce an executable BUY
                     * or SELL signal.
                     */

                    return TradingSignal.builder()
                            .symbol(stock.getSymbol())
                            .signal(SignalType.HOLD.name())
                            .build();
                })

                .doOnSuccess(signal -> {

                    if (signal != null) {

                        log.debug(
                                "Scanner Analysis Completed | " +
                                        "Symbol={} | Result={}",
                                signal.getSymbol(),
                                signal.getSignal());
                    }

                    log.debug(
                            "========== GENERIC SIGNAL ANALYSIS COMPLETED ==========");
                })

                .doOnError(error ->
                        log.debug(
                                "Scanner analysis failed for {}",
                                symbol,
                                error));
    }

    @Override
    public Mono<TradingSignal> generateMiddaySignal(
            com.ram.trading.signal.engine.dto.watchlist.MiddayRevalidationResult result) {

        if (result == null) {
            log.warn("MIDDAY PIPELINE SKIPPED | Result is null");
            return Mono.empty();
        }

        String symbol = result.getTradingSymbol();

        if (symbol == null || symbol.isBlank()) {
            log.warn("MIDDAY PIPELINE SKIPPED | Invalid symbol");
            return Mono.empty();
        }

        if (!middayEntriesEnabled) {
            log.info("MIDDAY PIPELINE SKIPPED | Symbol={} | trading.strategy.midday.enabled=false", symbol);
            return Mono.empty();
        }

        if (!"QUALIFIED".equalsIgnoreCase(result.getStatus())) {
            log.info(
                    "MIDDAY PIPELINE SKIPPED | Symbol={} | Status={}",
                    symbol,
                    result.getStatus());
            return Mono.empty();
        }

        if (!tradingSessionService.canCreateStrategyEntry()) {
            log.info(
                    "MIDDAY PIPELINE SKIPPED | Symbol={} | Trading session does not allow new trades",
                    symbol);
            return Mono.empty();
        }

        String direction = normalizeStrategyDirection(result.getDirection());

        if (!"BUY".equals(direction) && !"SELL".equals(direction)) {
            log.warn(
                    "MIDDAY PIPELINE SKIPPED | Symbol={} | Invalid direction={} | Status={}",
                    symbol,
                    result.getDirection(),
                    result.getStatus());
            return Mono.empty();
        }

        if (result.getCurrentPrice() == null || result.getCurrentPrice() <= 0) {
            log.warn(
                    "MIDDAY PIPELINE SKIPPED | Symbol={} | Invalid current price={}",
                    symbol,
                    result.getCurrentPrice());
            return Mono.empty();
        }

        java.util.Optional<String> illiquid =
                liquidityFilter.rejection(symbol, result.getCurrentPrice(), null);

        if (illiquid.isPresent()) {
            log.info("LIQUIDITY FILTER | Entry skipped | Symbol={} | Strategy=MIDDAY | Reason={}",
                    symbol, illiquid.get());
            return Mono.empty();
        }

        String middayRejection = null;

        if (skipMiddayInSideways && freshMarketTrend() == Trend.SIDEWAYS) {
            log.info(
                    "MIDDAY PIPELINE SKIPPED | Symbol={} | Direction={} | Reason=Index regime is SIDEWAYS",
                    symbol,
                    direction);
            middayRejection = "MIDDAY_SIDEWAYS";
        } else if (chasesFreshRegimeFlip(symbol, "MIDDAY", direction)) {
            middayRejection = "FRESH_REGIME_FLIP";
        }

        if (middayRejection != null) {
            Trend regime = freshMarketTrend();
            shadowTradeService.recordRejectedSetup(
                    SignalGenerationRequest.builder()
                            .symbol(symbol)
                            .currentPrice(result.getCurrentPrice())
                            .setupStrategy("MIDDAY")
                            .setupDirection(SignalType.valueOf(direction))
                            .build(),
                    direction,
                    middayRejection,
                    regime != null ? regime.name() : null);
            return Mono.empty();
        }

        try {
            TechnicalIndicatorResponse indicator = new TechnicalIndicatorResponse();
            indicator.setSymbol(symbol);
            indicator.setClosePrice(result.getCurrentPrice());
            indicator.setRsi14(result.getRsi());
            indicator.setEma20(result.getEma20());
            indicator.setEma50(result.getEma50());
            indicator.setMacd(result.getMacd());
            indicator.setSignalLine(result.getSignalLine());

            SignalGenerationRequest request = SignalGenerationRequest.builder()
                    .symbol(symbol)
                    .currentPrice(result.getCurrentPrice())
                    .rsi(result.getRsi())
                    .ema20(result.getEma20())
                    .ema50(result.getEma50())
                    .macd(result.getMacd())
                    .signalLine(result.getSignalLine())
                    .setupStrategy("MIDDAY")
                    .setupDirection(SignalType.valueOf(direction))
                    .marketContext(MarketContext.builder()
                            .marketTrend(freshMarketTrend())
                            .marketTime(LocalDateTime.now())
                            .build())
                    .build();

            log.info(
                    "MIDDAY ENTRY SETUP PASSED | Symbol={} | Strategy=MIDDAY | Direction={} | Score={} | Price={} | RSI={} | EMA20={} | EMA50={} | MACD={}",
                    symbol,
                    direction,
                    result.getScore(),
                    result.getCurrentPrice(),
                    result.getRsi(),
                    result.getEma20(),
                    result.getEma50(),
                    result.getMacd());

            return generateTradingSignal(request, indicator)
                    .doOnSuccess(signal ->
                            log.info(
                                    "MIDDAY PIPELINE COMPLETED | Symbol={} | Result={}",
                                    symbol,
                                    signal != null ? signal.getSignal() : "NULL"))
                    .doOnError(error ->
                            log.error(
                                    "MIDDAY PIPELINE FAILED | Symbol={} | Error={}",
                                    symbol,
                                    error.getMessage(),
                                    error));

        } catch (Exception ex) {
            log.error(
                    "MIDDAY PIPELINE BUILD FAILED | Symbol={} | Direction={}",
                    symbol,
                    direction,
                    ex);
            return Mono.error(ex);
        }
    }

    @Override
    public Mono<TradingSignal> generateSignal(Tick tick) {

        log.debug(
                "LIVE SIGNAL GENERATION STARTED | Symbol={}",
                tick != null ? tick.getSymbol() : "UNKNOWN");

        if (tick == null) {

            log.warn(
                    "Live Signal Generation skipped | Tick is null");

            return Mono.empty();
        }

        if (!tradingSessionService.isMarketOpen()) {

            log.debug(
                    "LIVE SIGNAL GATE | Symbol={} | Market session closed | Skipping signal pipeline",
                    tick.getSymbol());

            return Mono.empty();
        }

        if (toTrend(tick.getMarketRegime()) != null) {
            recordMarketRegime(toTrend(tick.getMarketRegime()));
        }

        shadowTradeService.onTick(tick.getSymbol(), tick.getLastTradedPrice());

        log.debug(
                "Symbol : {}",
                tick.getSymbol());

        log.debug(
                "LTP    : {}",
                tick.getLastTradedPrice());

        /*
         * ============================================================
         * LIVE 1-MINUTE CANDLE AGGREGATION
         * ============================================================
         *
         * Every incoming live tick is first passed to the
         * MinuteCandleAggregator.
         *
         * This builds the 1-minute OHLC + volume candles that
         * will later be used by:
         *
         *     OpeningRangeService
         *     JudasSwingDetector
         *     ORBValidator
         *
         * IMPORTANT:
         *
         * Candle aggregation does NOT make a trading decision.
         * It only maintains market structure data.
         */

        MinuteCandle completedCandle = null;

        try {

            completedCandle =
                    minuteCandleAggregator.process(tick);

            if (completedCandle != null) {

                log.debug(
                        "1-MIN CANDLE COMPLETED | Symbol={} | Minute={} | O={} | H={} | L={} | C={} | V={}",
                        completedCandle.getSymbol(),
                        completedCandle.getMinute(),
                        completedCandle.getOpen(),
                        completedCandle.getHigh(),
                        completedCandle.getLow(),
                        completedCandle.getClose(),
                        completedCandle.getVolume()
                );
            }

        } catch (Exception ex) {

            /*
             * Candle aggregation must NEVER break
             * the existing signal-generation pipeline.
             */
            log.error(
                    "1-MIN CANDLE AGGREGATION FAILED | " +
                            "Symbol={} | Continuing signal pipeline",
                    tick.getSymbol(),
                    ex);
        }

        final MinuteCandle finalCompletedCandle =
                completedCandle;

        return tradeExitService
                .evaluateExit(tick)
                .then(
                        Mono.defer(() -> {

                            if (!tradingSessionService.canCreateStrategyEntry()) {

                                log.debug(
                                        "ENTRY SESSION CLOSED | Symbol={} | Entry cutoff reached | Skipping setup/AI",
                                        tick.getSymbol());

                                return Mono.empty();
                            }

                            /*
                             * ====================================================
                             * ENTRY STRATEGY GATE
                             * ====================================================
                             *
                             * Entry strategies are evaluated ONLY when a
                             * completed 1-minute candle is available.
                             *
                             * Trade exits remain evaluated on EVERY tick.
                             */

                            if (finalCompletedCandle == null) {

                                log.debug(
                                        "ENTRY STRATEGY GATE | {} | " +
                                                "No completed candle | " +
                                                "Skipping entry evaluation",
                                        tick.getSymbol());

                                return Mono.empty();
                            }

                            if (technicalIndicatorService.hasInsufficientHistory(
                                    tick.getSymbol())) {

                                log.debug(
                                        "ENTRY STRATEGY GATE | {} | " +
                                                "Insufficient price history | " +
                                                "Skipping entry evaluation",
                                        tick.getSymbol());

                                return Mono.empty();
                            }

                            EntrySetup entrySetup =
                                    findEntrySetup(
                                            finalCompletedCandle);

                            if (entrySetup == null) {

                                log.debug(
                                        "ENTRY STRATEGY GATE | {} | " +
                                                "No valid Judas/ORB setup | " +
                                                "Skipping AI",
                                        tick.getSymbol());

                                return Mono.empty();
                            }

                            log.info(
                                    "ENTRY STRATEGY GATE PASSED | " +
                                            "Symbol={} | Strategy={} | Direction={} | Confirmation={}",
                                    tick.getSymbol(),
                                    entrySetup.strategy(),
                                    entrySetup.direction(),
                                    entrySetup.confirmationTime());

                            if (chasesFreshRegimeFlip(
                                    tick.getSymbol(),
                                    entrySetup.strategy(),
                                    entrySetup.direction())) {

                                shadowTradeService.recordRejectedSetup(
                                        SignalGenerationRequest.builder()
                                                .symbol(tick.getSymbol())
                                                .currentPrice(tick.getLastTradedPrice())
                                                .setupStrategy(entrySetup.strategy())
                                                .setupDirection(SignalType.valueOf(
                                                        entrySetup.direction().toUpperCase()))
                                                .setupInvalidationLevel(entrySetup.invalidationLevel())
                                                .build(),
                                        entrySetup.direction().toUpperCase(),
                                        "FRESH_REGIME_FLIP",
                                        tick.getMarketRegime());

                                return Mono.empty();
                            }

                            return technicalIndicatorService
                                    .calculate(
                                            tick.getSymbol())
                                    .onErrorResume(error -> {
                                        log.error(
                                                "Technical indicator calculation failed; releasing setup for retry | Symbol={}",
                                                tick.getSymbol(),
                                                error);

                                        releaseProcessedStrategySetup(
                                                tick.getSymbol(),
                                                entrySetup,
                                                "TECHNICAL_INDICATOR_ERROR");

                                        return Mono.empty();
                                    })

                                    .switchIfEmpty(
                                            Mono.defer(() -> {

                                                log.warn(
                                                        "Skipping {} because technical indicators are unavailable.",
                                                        tick.getSymbol());

                                                releaseProcessedStrategySetup(
                                                        tick.getSymbol(),
                                                        entrySetup,
                                                        "TECHNICAL_INDICATORS_UNAVAILABLE");

                                                return Mono.empty();
                                            })
                                    )

                                    .flatMap(indicator -> {

                                        recordStrategySetup(entrySetup);

                                        java.util.Optional<String> illiquid =
                                                liquidityFilter.rejection(
                                                        tick.getSymbol(),
                                                        tick.getLastTradedPrice(),
                                                        indicator);

                                        if (illiquid.isPresent()) {

                                            log.info(
                                                    "LIQUIDITY FILTER | Entry skipped | Symbol={} | Strategy={} | Reason={}",
                                                    tick.getSymbol(),
                                                    entrySetup.strategy(),
                                                    illiquid.get());

                                            return Mono.empty();
                                        }

                                        log.debug(
                                                "Technical Indicators Loaded");

                                        log.debug(
                                                "RSI      : {}",
                                                indicator.getRsi14());

                                        log.debug(
                                                "EMA20    : {}",
                                                indicator.getEma20());

                                        log.debug(
                                                "EMA50    : {}",
                                                indicator.getEma50());

                                        log.debug(
                                                "SMA20    : {}",
                                                indicator.getSma20());

                                        log.debug(
                                                "SMA50    : {}",
                                                indicator.getSma50());

                                        log.debug(
                                                "MACD     : {}",
                                                indicator.getMacd());

                                        SignalGenerationRequest request =
                                                buildSignalRequest(
                                                        tick,
                                                        indicator,
                                                        entrySetup);

                                        /*
                                         * The AI -> risk -> sizing -> trade chain
                                         * takes 5-15s. It must NOT be owned by the
                                         * tick HTTP request: the market-stream
                                         * cancels that request after 5s, which
                                         * silently dropped otherwise valid trades.
                                         */
                                        return detachTradingPipeline(
                                                request,
                                                indicator);
                                    });
                        })
                );
    }

    /**
     * Runs the trading pipeline independently of the inbound tick request.
     *
     * Subscribing here (instead of returning the Mono to the controller)
     * means a client timeout/disconnect can no longer cancel the AI call,
     * Risk Guard, position sizing or paper-trade creation half-way. The tick
     * request is acknowledged immediately with an empty response.
     *
     * Behaviour of the pipeline itself (gates, thresholds, sizing, risk) is
     * unchanged; only its lifecycle is decoupled from the HTTP request.
     */
    private Mono<TradingSignal> detachTradingPipeline(
            SignalGenerationRequest request,
            TechnicalIndicatorResponse indicator) {

        final String symbol = request.getSymbol();
        final long startNanos = System.nanoTime();
        final int inFlight = detachedPipelinesInFlight.incrementAndGet();

        log.info(
                "DETACHED TRADING PIPELINE STARTED | Symbol={} | InFlight={}",
                symbol,
                inFlight);

        Mono.defer(() -> generateTradingSignal(request, indicator))
                .timeout(Duration.ofSeconds(
                        Math.max(1L, detachedPipelineTimeoutSeconds)))
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(signalType ->
                        detachedPipelinesInFlight.decrementAndGet())
                .doOnSuccess(signal -> log.info(
                        "DETACHED TRADING PIPELINE FINISHED | Symbol={} | ElapsedMs={} | SignalProduced={}",
                        symbol,
                        (System.nanoTime() - startNanos) / 1_000_000L,
                        signal != null))
                .subscribe(
                        ignored -> { },
                        error -> log.error(
                                "DETACHED TRADING PIPELINE FAILED | Symbol={} | ElapsedMs={}",
                                symbol,
                                (System.nanoTime() - startNanos) / 1_000_000L,
                                error));

        return Mono.empty();
    }

    /**
     * Execution plan and structural entry quality, judged on the setup
     * direction at the current price before any AI call.
     *
     * @return a HOLD signal when the setup must be skipped, null to continue
     */
    private TradingSignal preAiRejection(
            SignalGenerationRequest request,
            TechnicalIndicatorResponse indicator,
            Integer qualityScore) {

        SignalType direction = request.getSetupDirection();
        Double price = request.getCurrentPrice();

        if ((direction != SignalType.BUY && direction != SignalType.SELL)
                || price == null || price <= 0) {
            return null;
        }

        TradingSignal plan = TradingSignal.builder()
                .symbol(request.getSymbol())
                .signal(direction.name())
                .entryPrice(price)
                .strategy(request.getSetupStrategy())
                .qualityScore(qualityScore)
                .marketRegime(request.getMarketContext() != null
                        && request.getMarketContext().getMarketTrend() != null
                        ? request.getMarketContext().getMarketTrend().name()
                        : null)
                .build();

        TradingSignal hold = TradingSignal.builder()
                .symbol(request.getSymbol())
                .signal(SignalType.HOLD.name())
                .strategy(request.getSetupStrategy())
                .qualityScore(qualityScore)
                .build();

        if (!executionPlanCalculator.apply(plan, request)) {
            log.info("PRE-AI CHECK | Execution plan rejected, AI not called | Symbol={} | Strategy={} | Direction={}",
                    request.getSymbol(), request.getSetupStrategy(), direction);
            return hold;
        }

        if (rejectExtendedEntries && indicator != null) {

            EntryQualityResult quality =
                    entryQualityService.evaluate(request.getSymbol(), direction, price, price, indicator);

            if (quality != null && "EXTENDED".equals(quality.getStatus())) {
                log.info("PRE-AI CHECK | Entry EXTENDED, AI not called | Symbol={} | Direction={} | PriceVsEMA20={}% | Reason={}",
                        request.getSymbol(), direction, quality.getPriceVsEma20Pct(), quality.getReason());

                if (plan.getStopLoss() != null && plan.getTargetPrice() != null) {
                    shadowTradeService.recordRejectedSignal(plan, "EXTENDED");
                }
                return hold;
            }
        }

        return null;
    }

    private Mono<TradingSignal> generateTradingSignal(
            SignalGenerationRequest request,
            TechnicalIndicatorResponse indicator) {

        log.debug(
                "======================================================");

        log.debug(
                "Trading Decision Pipeline Started");

        log.debug(
                "Symbol : {}",
                request.getSymbol());

        log.debug(
                "======================================================");

        /*
         * EDGE GATE: skip setups whose strategy and time of day have lost
         * money net of charges over enough recent trades (before spending
         * an AI call on them).
         */
        SetupEdgeGate.EdgeDecision edge =
                setupEdgeGate.evaluate(
                        request.getSetupStrategy(),
                        LocalTime.now(ZoneId.of("Asia/Kolkata")));

        if (edge != null && !edge.allowed()) {

            log.info(
                    "EDGE GATE BLOCKED | Symbol={} | Strategy={} | EntryTime={} | {}",
                    request.getSymbol(),
                    edge.strategy(),
                    edge.entryTime(),
                    edge.reason());

            return Mono.just(
                    TradingSignal.builder()
                            .symbol(request.getSymbol())
                            .signal(SignalType.HOLD.name())
                            .strategy(request.getSetupStrategy())
                            .build());
        }

        /*
         * SETUP QUALITY: rank the setup 0-100 on evidence that differs between
         * setups. Weak setups are skipped, and the last open slot is kept for a
         * strong one instead of going to whichever signal arrives first.
         */
        SetupQualityScorer.QualityScore quality =
                setupQualityScorer.score(request, edge);

        Integer qualityScore = quality != null ? quality.score() : null;

        // The regime guard's counter-trend exception needs it.
        request.setSetupQualityScore(qualityScore);

        if (quality != null) {

            log.info(
                    "SETUP QUALITY | Symbol={} | Strategy={} | Direction={} | Score={} | {}",
                    request.getSymbol(),
                    request.getSetupStrategy(),
                    request.getSetupDirection(),
                    quality.score(),
                    quality.breakdown());

            String rejection = null;

            if (minQualityScore > 0 && quality.score() < minQualityScore) {
                rejection = "score below trading.quality.min-score=" + minQualityScore;
            } else if (lastSlotMinScore > 0
                    && quality.score() < lastSlotMinScore
                    && riskManagementService.openSlotsLeft() == 1) {
                rejection = "last open slot is reserved for score >= " + lastSlotMinScore;
            }

            if (rejection != null) {

                log.info(
                        "SETUP QUALITY BLOCKED | Symbol={} | Strategy={} | Score={} | Reason={}",
                        request.getSymbol(),
                        request.getSetupStrategy(),
                        quality.score(),
                        rejection);

                return Mono.just(
                        TradingSignal.builder()
                                .symbol(request.getSymbol())
                                .signal(SignalType.HOLD.name())
                                .strategy(request.getSetupStrategy())
                                .qualityScore(qualityScore)
                                .build());
            }
        }

        /*
         * PRE-AI CHECKS: rules that need no AI answer run before the AI call,
         * so a setup they reject never costs a call or its 5-40s wait. The
         * same checks run again after the AI on the actual entry price.
         */
        TradingSignal preAiRejection = preAiRejection(request, indicator, qualityScore);

        if (preAiRejection != null) {
            return Mono.just(preAiRejection);
        }

        return tradingOrchestratorService
                .executeTrade(request)

                .flatMap(pipelineResult -> {

                    log.debug(
                            "******** TRADING PIPELINE RESULT RECEIVED ********");

                    /*
                     * =====================================================
                     * PIPELINE RESULT VALIDATION
                     * =====================================================
                     */

                    if (pipelineResult == null) {

                        log.warn(
                                "Trading pipeline result is null for {}",
                                request.getSymbol());

                        return Mono.empty();
                    }

                    /*
                     * =====================================================
                     * AI DECISION VALIDATION
                     * =====================================================
                     */

                    if (pipelineResult.getAiDecision() == null) {

                        log.info(
                                "No AI decision generated for {}. Reason: {}",
                                request.getSymbol(),
                                pipelineResult.getSkipReason());

                        return Mono.empty();
                    }

                    /*
                     * =====================================================
                     * GET AI RESPONSE
                     * =====================================================
                     */


                    AiDecisionResponse aiResponse = pipelineResult.getAiDecision();

                    log.debug("******** AI RESPONSE RECEIVED ********");

                    if (aiResponse.getDecision() == null) {
                        log.warn(
                                "AI decision is null for {}",
                                request.getSymbol());

                        return Mono.just(
                                TradingSignal.builder()
                                        .symbol(request.getSymbol())
                                        .signal(SignalType.HOLD.name())
                                        .build());
                    }

                    if (aiResponse.getDecision().getRecommendation() == null) {
                        log.warn(
                                "AI recommendation is null. Returning HOLD | Symbol={}",
                                request.getSymbol());

                        return Mono.just(
                                TradingSignal.builder()
                                        .symbol(request.getSymbol())
                                        .signal(SignalType.HOLD.name())
                                        .build());
                    }

                    /*
                     * FUNNEL: AI DECISION
                     */
                    String recommendation =
                            aiResponse.getDecision()
                                    .getRecommendation()
                                    .name();

                    Boolean tradeAllowed =
                            aiResponse.getDecision()
                                    .getTradeAllowed();


                    if (SignalType.BUY.name()
                            .equalsIgnoreCase(recommendation)) {

                        tradingFunnelStatisticsService
                                .recordAiBuy();

                    } else if (SignalType.SELL.name()
                            .equalsIgnoreCase(recommendation)) {

                        tradingFunnelStatisticsService
                                .recordAiSell();

                    } else {

                        tradingFunnelStatisticsService
                                .recordAiHold();
                    }

                    if (!Boolean.TRUE.equals(tradeAllowed)) {

                        tradingFunnelStatisticsService
                                .recordAiTradeNotAllowed();
                    }

                    log.debug(
                            "Trade Allowed : {}",
                            tradeAllowed);

                    log.debug(
                            "Recommendation : {}",
                            recommendation);

                    logAiDecision(aiResponse);

                    /*
                     * =====================================================
                     * AI TRADE-ALLOWED GUARD
                     * =====================================================
                     */

                    if (!Boolean.TRUE.equals(tradeAllowed)) {

                        log.info(
                                "AI Trade Not Allowed. Returning HOLD | Symbol={} | Recommendation={}",
                                request.getSymbol(),
                                recommendation);

                        return Mono.just(
                                TradingSignal.builder()
                                        .symbol(request.getSymbol())
                                        .signal(SignalType.HOLD.name())
                                        .build());
                    }

                    /*
                     * =====================================================
                     * ENGINEERING ↔ AI DIRECTION GUARD
                     * =====================================================
                     *
                     * BUY  + BUY  -> continue
                     * SELL + SELL -> continue
                     * BUY  + SELL -> HOLD
                     * SELL + BUY  -> HOLD
                     */

                    TradingSignal directionGuardResult =
                            validateAiDirection(
                                    pipelineResult,
                                    aiResponse,
                                    request);

                    if (directionGuardResult != null) {
                        return Mono.just(directionGuardResult);
                    }

                    /*
                     * =====================================================
                     * GET EXISTING TRADING CONTEXT
                     * =====================================================
                     */

                    TradingContext context =
                            pipelineResult.getTradingContext();

                    if (context == null) {

                        log.error(
                                "TradingContext is missing for eligible AI decision. Symbol : {}",
                                request.getSymbol());

                        return Mono.empty();
                    }

                    logTradingContext(context);

                    /*
                     * =====================================================
                     * RISK GUARD
                     * =====================================================
                     */

                    RiskEvaluation evaluation =
                            RiskEvaluation.builder()
                                    .context(context)
                                    .decision(
                                            aiResponse.getDecision())
                                    .aiResponse(aiResponse)
                                    .signal(
                                            TradingSignal.builder()
                                                    .symbol(
                                                            request.getSymbol())
                                                    .build())
                                    .build();

                    try {

                        log.debug(
                                "STEP-1 Before Risk Guard");

                        /*
                         * =================================================
                         * FUNNEL : RISK EVALUATED
                         * =================================================
                         */

                        tradingFunnelStatisticsService
                                .recordRiskEvaluated();

                        RiskGuardResult result =
                                riskGuardService.evaluate(
                                        evaluation);

                        log.debug(
                                "STEP-2 Risk Guard Completed");

                        logRiskEvaluation(result);

                        /*
                         * =================================================
                         * FUNNEL : RISK RESULT
                         * =================================================
                         */

                        if (result.isApproved()) {

                            tradingFunnelStatisticsService
                                    .recordRiskApproved();

                        } else {

                            tradingFunnelStatisticsService
                                    .recordRiskRejected();
                        }

                        /*
                         * =================================================
                         * MAP AI RESPONSE TO TRADING SIGNAL
                         * =================================================
                         */

                        log.debug(
                                "STEP-3 Before Mapper");

                        TradingSignal signal =
                                tradingSignalMapper.map(
                                        aiResponse,
                                        request);

                        // A counter-regime ORB let through on relative strength is reported
                        // apart from ordinary ORB, so its results can be judged on their own.
                        signal.setStrategy(
                                pipelineResult.getTechnicalDecision() != null
                                        && pipelineResult.getTechnicalDecision().isCounterTrendException()
                                        ? "ORB_RS"
                                        : request.getSetupStrategy());
                        signal.setQualityScore(qualityScore);
                        signal.setMarketRegime(
                                request.getMarketContext() != null
                                        && request.getMarketContext().getMarketTrend() != null
                                        ? request.getMarketContext().getMarketTrend().name()
                                        : null);

                        /*
                         * The AI only confirms the direction. Stop-loss and
                         * target are owned by the engine (Opening Range based).
                         */
                        boolean planUsable =
                                executionPlanCalculator.apply(signal, request);

                        log.debug(
                                "STEP-4 Mapper Completed");

                        if (!planUsable) {

                            signal.setSignal(
                                    SignalType.HOLD.name());

                            log.warn(
                                    "Trade skipped: setup stop is outside allowed risk. Returning HOLD | Symbol={}",
                                    request.getSymbol());

                            return Mono.just(signal);
                        }

                        /*
                         * =================================================
                         * RISK REJECTED
                         * =================================================
                         */

                        if (!result.isApproved()) {

                            signal.setSignal(
                                    SignalType.HOLD.name());

                            log.warn(
                                    "Trade rejected by Risk Guard. Returning HOLD.");

                            return Mono.just(signal);
                        }

                        /*
                         * =================================================
                         * POST PROCESSING
                         * =================================================
                         */

                        tradingFunnelStatisticsService
                                .recordPostProcessingEntered();

                        /*
                         * IMPORTANT:
                         *
                         * Pass the original market price from the
                         * SignalGenerationRequest into post processing.
                         *
                         * This becomes our reference price for the
                         * Entry Quality measurement.
                         */

                        return postProcessSignal(
                                signal,
                                indicator,
                                request.getCurrentPrice());

                    } catch (Exception ex) {

                        log.error(
                                "FAILED INSIDE generateTradingSignal()",
                                ex);

                        return Mono.error(ex);
                    }
                })

                .doOnSuccess(signal -> {

                    if (signal != null) {

                        log.info(
                                "Final Decision : {} {}",
                                signal.getSymbol(),
                                signal.getSignal());
                    }

                    log.debug(
                            "Trading Decision Pipeline Completed");
                })

                .doOnError(error ->
                        log.error(
                                "Trading Decision Pipeline Failed",
                                error));
    }

    /**
     * Validates that the AI recommendation confirms the technical
     * direction generated by the engineering pipeline.
     *
     * AI acts as a confirmation / veto layer.
     *
     * BUY  + BUY  -> CONTINUE
     * SELL + SELL  -> CONTINUE
     *
     * BUY  + SELL  -> HOLD
     * SELL + BUY   -> HOLD
     *
     * BUY  + HOLD  -> HOLD
     * SELL + HOLD  -> HOLD
     *
     * HOLD + BUY   -> HOLD
     * HOLD + SELL  -> HOLD
     * HOLD + HOLD  -> HOLD
     *
     * Any invalid / unknown direction -> HOLD
     *
     * @return HOLD signal when the AI does not confirm the engineering
     *         direction; otherwise null so the normal pipeline continues.
     */
    private TradingSignal validateAiDirection(
            TradingPipelineResult pipelineResult,
            AiDecisionResponse aiResponse,
            SignalGenerationRequest request) {

        /*
         * ============================================================
         * VALIDATION
         * ============================================================
         */

        if (pipelineResult == null
                || pipelineResult.getTechnicalDecision() == null
                || aiResponse == null
                || aiResponse.getDecision() == null
                || aiResponse.getDecision().getRecommendation() == null) {

            log.warn(
                    "AI Direction Guard -> HOLD | Incomplete decision data | Symbol={}",
                    request.getSymbol());

            return TradingSignal.builder()
                    .symbol(request.getSymbol())
                    .signal(SignalType.HOLD.name())
                    .build();
        }

        /*
         * ============================================================
         * ENGINEERING / TECHNICAL SIGNAL
         * ============================================================
         */

        SignalType technicalSignal =
                pipelineResult
                        .getTechnicalDecision()
                        .getSignal();

        /*
         * ============================================================
         * AI SIGNAL
         * ============================================================
         */

        SignalType aiSignal;

        try {

            aiSignal = SignalType.valueOf(
                    aiResponse
                            .getDecision()
                            .getRecommendation()
                            .name()
                            .toUpperCase());

        } catch (IllegalArgumentException ex) {

            log.warn(
                    "AI Direction Guard -> HOLD | Unknown AI recommendation | Symbol={} | Recommendation={}",
                    request.getSymbol(),
                    aiResponse.getDecision().getRecommendation());

            return TradingSignal.builder()
                    .symbol(request.getSymbol())
                    .signal(SignalType.HOLD.name())
                    .build();
        }

        /*
         * ============================================================
         * ENGINEERING HOLD
         * ============================================================
         *
         * Engineering is the primary direction anchor.
         *
         * If Engineering itself says HOLD, no trade should proceed.
         */

        if (technicalSignal == null
                || SignalType.HOLD.equals(technicalSignal)) {

            log.debug(
                    "AI Direction Guard -> HOLD | Engineering signal is HOLD | Symbol={} | AI={}",
                    request.getSymbol(),
                    aiSignal);

            return TradingSignal.builder()
                    .symbol(request.getSymbol())
                    .signal(SignalType.HOLD.name())
                    .build();
        }

        /*
         * ============================================================
         * AI HOLD
         * ============================================================
         *
         * AI did not confirm the Engineering direction.
         * Therefore, do not trade.
         */

        if (SignalType.HOLD.equals(aiSignal)) {

            log.debug(
                    "AI Direction Guard -> HOLD | AI did not confirm Engineering direction | Symbol={} | Engineering={}",
                    request.getSymbol(),
                    technicalSignal);

            recordAiVeto(request, technicalSignal);

            return TradingSignal.builder()
                    .symbol(request.getSymbol())
                    .signal(SignalType.HOLD.name())
                    .build();
        }

        /*
         * ============================================================
         * DIRECTION MATCH
         * ============================================================
         *
         * Engineering BUY  + AI BUY  -> Continue
         * Engineering SELL + AI SELL -> Continue
         */

        if (technicalSignal == aiSignal) {

            tradingFunnelStatisticsService.recordAiDirectionMatch();

            log.debug(
                    "AI DIRECTION CONFIRMED | Symbol={} | Direction={}",
                    request.getSymbol(),
                    technicalSignal);

            return null;
        }

        /*
         * ============================================================
         * DIRECTION MISMATCH
         * ============================================================
         *
         * Engineering BUY  + AI SELL -> HOLD
         * Engineering SELL + AI BUY  -> HOLD
         *
         * AI is NOT allowed to reverse Engineering direction.
         */

        tradingFunnelStatisticsService.recordAiDirectionMismatch();

        log.warn(
                "AI DIRECTION MISMATCH -> HOLD | Symbol={} | Engineering={} | AI={}",
                request.getSymbol(),
                technicalSignal,
                aiSignal);

        recordAiVeto(request, technicalSignal);

        return TradingSignal.builder()
                .symbol(request.getSymbol())
                .signal(SignalType.HOLD.name())
                .build();
    }

    /**
     * Follows a setup the AI turned down as a shadow trade, so the AI layer is
     * judged like every other filter: if the setups it vetoes would have made
     * money, it costs more (calls, ~5-40s latency, missed trades) than it saves.
     */
    private void recordAiVeto(SignalGenerationRequest request, SignalType technicalSignal) {
        shadowTradeService.recordRejectedSetup(
                request,
                technicalSignal.name(),
                "AI_VETO",
                request.getMarketContext() != null
                        && request.getMarketContext().getMarketTrend() != null
                        ? request.getMarketContext().getMarketTrend().name()
                        : null);
    }

    private void logTradingContext(TradingContext context) {

        log.debug("========== Trading Context ==========");

        log.debug("News      : {}", context.getNewsSummary());
        log.debug("Sector    : {}", context.getSectorSummary());

        PortfolioContextResponse portfolio =
                context.getPortfolioContext();

        if (portfolio != null) {

            if (portfolio.getSummary() != null) {
                log.debug("Portfolio Summary : {}",
                        portfolio.getSummary());
            }

            if (portfolio.getRecommendations() != null) {
                log.debug("Portfolio Recommendations : {}",
                        portfolio.getRecommendations());
            }

            if (portfolio.getRisk() != null) {
                log.debug("Portfolio Risk : {}",
                        portfolio.getRisk().getRiskLevel());
            }

            if (portfolio.getHealth() != null) {
                log.debug("Portfolio Health : {}",
                        portfolio.getHealth().getStatus());
            }

        } else {

            log.debug("Portfolio Context : Not Available");
        }

        log.debug("News Score : {}",
                context.getNewsScore());

        log.debug("News Sentiment : {}",
                context.getNewsSentiment());

        if (context.getOpenPositionContext() != null) {

            log.debug("Open Position Exists : {}",
                    context.getOpenPositionContext().isPositionExists());

            if (context.getOpenPositionContext().isPositionExists()) {

                log.debug("Open Position Signal : {}",
                        context.getOpenPositionContext().getSignal());

                log.debug("Current PnL : {}",
                        context.getOpenPositionContext().getCurrentPnL());
            }

        } else {

            log.debug("Open Position Context : Not Available");
        }

        log.debug("====================================");
    }

    private void logAiDecision(AiDecisionResponse aiResponse) {

        log.debug("========== AI RESPONSE ==========");

        if (aiResponse.getDecision() != null) {

            log.debug("Trade Allowed      : {}",
                    aiResponse.getDecision().getTradeAllowed());

            log.debug("Recommendation     : {}",
                    aiResponse.getDecision().getRecommendation());

            log.debug("Confidence         : {}",
                    aiResponse.getDecision().getConfidence());

            log.debug("Decision Strength  : {}",
                    aiResponse.getDecision().getDecisionStrength());

        } else {

            log.warn("Decision : NULL");
        }

        log.debug("AI Reasoning       : {}",
                aiResponse.getAiReasoning());

        log.debug("Technical Analysis : {}",
                aiResponse.getTechnicalAnalysis());

        log.debug("Risk Analysis      : {}",
                aiResponse.getRiskAnalysis());

        log.debug("News Analysis      : {}",
                aiResponse.getNewsAnalysis());

        log.debug("Portfolio Analysis : {}",
                aiResponse.getPortfolioAnalysis());

        log.debug("Execution Plan     : {}",
                aiResponse.getExecutionPlan());

        log.debug("=================================");
    }

    private void logRiskEvaluation(
            RiskGuardResult result) {

        log.debug("========== RISK GUARD ==========");

        log.debug("Approved : {}",
                result.isApproved());

        if (!result.isApproved()) {

            result.getViolations()
                    .forEach(v ->
                            log.warn("{} -> {}",
                                    v.getRule(),
                                    v.getReason()));
        }

        log.debug("================================");
    }

    private Mono<TradingSignal> postProcessSignal(
            TradingSignal signal,
            TechnicalIndicatorResponse indicator,
            Double signalPrice) {

        log.info("========== POST PROCESSING ==========");

        // Final entry-window check before any entry processing or persistence.
        if (!tradingSessionService.canCreateStrategyEntry()) {
            log.info(
                    "STRATEGY ENTRY WINDOW BLOCKED | Symbol={} | No new trade allowed",
                    signal.getSymbol());

            signal.setSignal(SignalType.HOLD.name());
            return Mono.just(signal);
        }

        /*
         * HOLD SIGNAL
         */
        if (SignalType.HOLD.name().equals(signal.getSignal())) {
            log.info("Signal is HOLD. Nothing to persist.");
            return Mono.just(signal);
        }

        /*
         * ENTRY QUALITY
         *
         * EXTENDED is rejected (trading.entry-quality.reject-extended) before
         * position sizing, signal persistence, opportunity persistence, or
         * paper-trade creation. CAUTION and UNAVAILABLE remain observable only;
         * this does not introduce unvalidated filters for those states or
         * alter JUDAS/ORB/AI/RiskGuard.
         */
        return marketDataProvider
                .getStockPrice(signal.getSymbol())
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn(
                            "ENTRY QUALITY | Fresh market price unavailable | "
                                    + "Symbol={} | Direction={} | SignalPrice={}",
                            signal.getSymbol(),
                            signal.getSignal(),
                            signalPrice);

                    return Mono.just(
                            StockResponse.builder()
                                    .symbol(signal.getSymbol())
                                    .price(signalPrice != null ? signalPrice : 0.0)
                                    .build());
                }))
                .flatMap(stock -> {

                    double freshMarketPrice = stock.getPrice();

                    EntryQualityResult entryQuality =
                            entryQualityService.evaluate(
                                    signal.getSymbol(),
                                    SignalType.valueOf(
                                            signal.getSignal().toUpperCase()),
                                    signalPrice,
                                    freshMarketPrice,
                                    indicator);

                    log.info(
                            "ENTRY QUALITY RESULT | Symbol={} | Direction={} | Status={} | "
                                    + "DirectionalMove={}% | PriceVsEMA20={}% | "
                                    + "PriceVsEMA50={}% | PriceVsHistoricalClose={}% | Reason={}",
                            entryQuality.getSymbol(),
                            entryQuality.getDirection(),
                            entryQuality.getStatus(),
                            entryQuality.getDirectionalMovementPct(),
                            entryQuality.getPriceVsEma20Pct(),
                            entryQuality.getPriceVsEma50Pct(),
                            entryQuality.getPriceVsHistoricalClosePct(),
                            entryQuality.getReason());

                    if (rejectExtendedEntries
                            && "EXTENDED".equals(entryQuality.getStatus())) {
                        log.warn(
                                "ENTRY QUALITY REJECTED | Symbol={} | Direction={} | "
                                        + "Status=EXTENDED | PriceVsEMA20={}% | Reason={}",
                                entryQuality.getSymbol(),
                                entryQuality.getDirection(),
                                entryQuality.getPriceVsEma20Pct(),
                                entryQuality.getReason());

                        shadowTradeService.recordRejectedSignal(signal, "EXTENDED");

                        return Mono.empty();
                    }

                    /*
                     * POSITION SIZING
                     */
                    return positionSizingService
                            .calculate(signal)
                            .flatMap(sizingResult -> {

                                if (!sizingResult.isValid()) {
                                    log.warn(
                                            "POSITION SIZING REJECTED | "
                                                    + "Symbol={} | Entry={} | "
                                                    + "StopLoss={} | Reason={}",
                                            signal.getSymbol(),
                                            signal.getEntryPrice(),
                                            signal.getStopLoss(),
                                            sizingResult.getRejectionReason());

                                    return Mono.empty();
                                }

                                int approvedQuantity =
                                        sizingResult.getMarginBasedQuantity();

                                if (approvedQuantity <= 0) {
                                    log.warn(
                                            "POSITION SIZING REJECTED | "
                                                    + "Quantity is zero | Symbol={}",
                                            signal.getSymbol());

                                    return Mono.empty();
                                }

                                log.info(
                                        "MARGIN BASED POSITION SIZING | "
                                                + "Symbol={} | Entry={} | StopLoss={} | "
                                                + "RiskPerShare={} | TargetMargin={} | "
                                                + "Quantity={} | RequiredMargin={} | "
                                                + "Exposure={} | Leverage={} | "
                                                + "StopLossRisk={} | MaxLossTrigger={}",
                                        signal.getSymbol(),
                                        sizingResult.getEntryPrice(),
                                        sizingResult.getStopLoss(),
                                        sizingResult.getRiskPerShare(),
                                        sizingResult.getTargetMargin(),
                                        approvedQuantity,
                                        sizingResult.getRequiredMargin(),
                                        sizingResult.getTradeValue(),
                                        sizingResult.getLeverage(),
                                        sizingResult.getExpectedRisk(),
                                        sizingResult.getMaxRiskPerTrade());

                                /*
                                 * Recheck immediately before signal persistence.
                                 */
                                if (!tradingSessionService.canCreateStrategyEntry()) {
                                    log.warn(
                                            "STRATEGY ENTRY WINDOW BLOCKED BEFORE PERSISTENCE | "
                                                    + "Symbol={}",
                                            signal.getSymbol());

                                    signal.setSignal(SignalType.HOLD.name());
                                    return Mono.just(signal);
                                }

                                log.info("Saving Trading Signal...");

                                return Mono.fromCallable(
                                                () -> tradingSignalService.save(signal))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .flatMap(entity -> {

                                            tradingFunnelStatisticsService
                                                    .recordSignalSaved();

                                            log.info(
                                                    "Trading Signal Saved : {}",
                                                    entity.getId());

                                            return Mono.fromRunnable(() -> {
                                                        log.info("Saving Opportunity...");

                                                        opportunityService.save(
                                                                signal,
                                                                entity.getId());

                                                        tradingFunnelStatisticsService
                                                                .recordOpportunitySaved();

                                                        log.info(
                                                                "Opportunity Saved Successfully.");
                                                    })
                                                    .subscribeOn(Schedulers.boundedElastic())
                                                    .thenReturn(entity);
                                        })
                                        .flatMap(entity -> {

                                            /*
                                             * Final check immediately before the
                                             * PAPER/LIVE execution branch.
                                             *
                                             * Note: signal/opportunity records may
                                             * already have been saved at this point.
                                             */
                                            if (!tradingSessionService.canCreateStrategyEntry()) {
                                                log.warn(
                                                        "STRATEGY ENTRY WINDOW BLOCKED BEFORE EXECUTION | "
                                                                + "Symbol={} | SignalId={}",
                                                        entity.getSymbol(),
                                                        entity.getId());

                                                signal.setSignal(
                                                        SignalType.HOLD.name());

                                                return Mono.just(signal);
                                            }

                                            String mode = executionTradeMode == null
                                                    ? ""
                                                    : executionTradeMode.trim();

                                            /*
                                             * PAPER EXECUTION
                                             */
                                            if ("paper".equalsIgnoreCase(mode)) {
                                                return Mono.fromRunnable(() -> {
                                                            log.info(
                                                                    "Creating Paper Trade | "
                                                                            + "ApprovedQuantity={}",
                                                                    approvedQuantity);

                                                            tradingFunnelStatisticsService
                                                                    .recordPaperTradeAttempted();

                                                            paperTradingService.createTrade(
                                                                    entity,
                                                                    indicator,
                                                                    sizingResult,
                                                                    freshMarketPrice);
                                                        })
                                                        .subscribeOn(
                                                                Schedulers.boundedElastic())
                                                        .thenReturn(signal);
                                            }

                                            /*
                                             * LIVE EXECUTION — fail closed.
                                             */
                                            if ("live".equalsIgnoreCase(mode)) {

                                                if (!liveExecutionEnabled) {
                                                    log.warn(
                                                            "LIVE ENTRY BLOCKED | Symbol={} | "
                                                                    + "execution.live-enabled=false | "
                                                                    + "No broker request sent",
                                                            entity.getSymbol());

                                                    return Mono.error(
                                                            new IllegalStateException(
                                                                    "LIVE execution is disabled "
                                                                            + "in Signal Service. "
                                                                            + "No broker request "
                                                                            + "or paper trade was created."));
                                                }

                                                String symbol = entity.getSymbol();
                                                String direction = entity.getSignal();

                                                if (symbol == null
                                                        || symbol.isBlank()
                                                        || direction == null
                                                        || !("BUY".equalsIgnoreCase(direction)
                                                        || "SELL".equalsIgnoreCase(direction))) {

                                                    return Mono.error(
                                                            new IllegalStateException(
                                                                    "LIVE entry rejected: symbol "
                                                                            + "or BUY/SELL direction "
                                                                            + "is invalid. No broker "
                                                                            + "request or paper trade "
                                                                            + "was created."));
                                                }

                                                return stockServiceClient
                                                        .getInstrument(symbol)
                                                        .switchIfEmpty(Mono.error(
                                                                new IllegalStateException(
                                                                        "LIVE entry rejected: Stock "
                                                                                + "Service returned no "
                                                                                + "instrument for "
                                                                                + symbol
                                                                                + ". No broker request "
                                                                                + "was sent.")))
                                                        .flatMap(instrument -> {

                                                            String instrumentKey =
                                                                    instrument.getInstrumentKey();

                                                            if (instrumentKey == null
                                                                    || instrumentKey.isBlank()) {
                                                                return Mono.error(
                                                                        new IllegalStateException(
                                                                                "LIVE entry rejected: "
                                                                                        + "instrumentKey "
                                                                                        + "is missing for "
                                                                                        + symbol
                                                                                        + ". No broker "
                                                                                        + "request was sent."));
                                                            }

                                                            BigDecimal requiredMargin =
                                                                    sizingResult.getRequiredMargin()
                                                                            != null
                                                                            ? BigDecimal.valueOf(
                                                                            sizingResult
                                                                                    .getRequiredMargin())
                                                                            : BigDecimal.valueOf(
                                                                            sizingResult
                                                                                    .getTargetMargin());

                                                            TradeExecutionClient.TradeExecutionRequest
                                                                    executionRequest =
                                                                    new TradeExecutionClient
                                                                            .TradeExecutionRequest(
                                                                            entity.getId(),
                                                                            symbol,
                                                                            instrumentKey,
                                                                            direction.toUpperCase(),
                                                                            BigDecimal.valueOf(
                                                                                    sizingResult
                                                                                            .getEntryPrice()),
                                                                            approvedQuantity,
                                                                            requiredMargin,
                                                                            BigDecimal.valueOf(
                                                                                    sizingResult
                                                                                            .getStopLoss()),
                                                                            entity.getTargetPrice() != null
                                                                                    ? BigDecimal.valueOf(
                                                                                    entity.getTargetPrice())
                                                                                    : null,
                                                                            "MARKET");

                                                            log.info(
                                                                    "LIVE ENTRY REQUEST | SignalId={} | "
                                                                            + "Symbol={} | Direction={} | "
                                                                            + "Quantity={} | "
                                                                            + "InstrumentKeyPresent=true",
                                                                    entity.getId(),
                                                                    symbol,
                                                                    direction,
                                                                    approvedQuantity);

                                                            return tradeExecutionClient
                                                                    .executeTrade(executionRequest)
                                                                    .switchIfEmpty(Mono.error(
                                                                            new IllegalStateException(
                                                                                    "Trade Execution "
                                                                                            + "Service returned "
                                                                                            + "an empty response "
                                                                                            + "for " + symbol
                                                                                            + ". Reconcile broker "
                                                                                            + "state before retrying.")))
                                                                    .flatMap(response -> {

                                                                        if (response == null) {
                                                                            return Mono.error(
                                                                                    new IllegalStateException(
                                                                                            "LIVE entry returned "
                                                                                                    + "a null response "
                                                                                                    + "for signalId="
                                                                                                    + entity.getId()
                                                                                                    + ". Reconcile broker "
                                                                                                    + "state before retrying."));
                                                                        }

                                                                        if (!entity.getId().equals(
                                                                                response.signalId())
                                                                                || response.symbol() == null
                                                                                || !symbol.equalsIgnoreCase(
                                                                                response.symbol())) {

                                                                            return Mono.error(
                                                                                    new IllegalStateException(
                                                                                            "LIVE entry response "
                                                                                                    + "identity mismatch. "
                                                                                                    + "Requested signalId="
                                                                                                    + entity.getId()
                                                                                                    + ", response signalId="
                                                                                                    + response.signalId()
                                                                                                    + ", requested symbol="
                                                                                                    + symbol
                                                                                                    + ", response symbol="
                                                                                                    + response.symbol()
                                                                                                    + ". Reconcile broker "
                                                                                                    + "state before retrying."));
                                                                        }

                                                                        String status =
                                                                                response.status() == null
                                                                                        ? ""
                                                                                        : response.status()
                                                                                        .trim()
                                                                                        .toUpperCase();

                                                                        if ("FILLED".equals(status)) {

                                                                            if (!response.success()) {
                                                                                return Mono.error(
                                                                                        new IllegalStateException(
                                                                                                "Trade Execution "
                                                                                                        + "Service returned "
                                                                                                        + "status FILLED "
                                                                                                        + "without success=true. "
                                                                                                        + "SignalId="
                                                                                                        + entity.getId()
                                                                                                        + ". Reconcile broker "
                                                                                                        + "and execution records."));
                                                                            }

                                                                            Integer filledQuantity =
                                                                                    response.filledQuantity();

                                                                            BigDecimal executedPrice =
                                                                                    response.executedPrice();

                                                                            if (response.orderId() == null
                                                                                    || response.orderId().isBlank()
                                                                                    || filledQuantity == null
                                                                                    || filledQuantity <= 0
                                                                                    || filledQuantity > approvedQuantity
                                                                                    || executedPrice == null
                                                                                    || executedPrice.compareTo(
                                                                                    BigDecimal.ZERO) <= 0
                                                                                    || filledQuantity
                                                                                    != approvedQuantity) {

                                                                                return Mono.error(
                                                                                        new IllegalStateException(
                                                                                                "Trade Execution "
                                                                                                        + "Service reported "
                                                                                                        + "FILLED with invalid "
                                                                                                        + "or incomplete fill "
                                                                                                        + "details. SignalId="
                                                                                                        + entity.getId()
                                                                                                        + ". Reconcile broker "
                                                                                                        + "and execution records."));
                                                                            }

                                                                            log.info(
                                                                                    "LIVE ENTRY CONFIRMED FILLED | "
                                                                                            + "SignalId={} | Symbol={} | "
                                                                                            + "Status={} | OrderId={} | "
                                                                                            + "FilledQuantity={} | "
                                                                                            + "ExecutedPrice={}",
                                                                                    response.signalId(),
                                                                                    response.symbol(),
                                                                                    status,
                                                                                    response.orderId(),
                                                                                    filledQuantity,
                                                                                    executedPrice);

                                                                            return Mono.just(signal);
                                                                        }

                                                                        String reason =
                                                                                response.rejectionReason() != null
                                                                                        ? response.rejectionReason()
                                                                                        : response.message();

                                                                        log.warn(
                                                                                "LIVE ENTRY NOT CONFIRMED FILLED | "
                                                                                        + "SignalId={} | Symbol={} | "
                                                                                        + "Status={} | OrderId={} | "
                                                                                        + "FilledQuantity={} | Reason={}",
                                                                                response.signalId(),
                                                                                response.symbol(),
                                                                                status,
                                                                                response.orderId(),
                                                                                response.filledQuantity(),
                                                                                reason);

                                                                        return Mono.error(
                                                                                new IllegalStateException(
                                                                                        "LIVE entry is not confirmed "
                                                                                                + "as fully FILLED. "
                                                                                                + "SignalId="
                                                                                                + entity.getId()
                                                                                                + ", status=" + status
                                                                                                + ", orderId="
                                                                                                + response.orderId()
                                                                                                + ", filledQuantity="
                                                                                                + response.filledQuantity()
                                                                                                + ", reason=" + reason
                                                                                                + ". Do not retry until "
                                                                                                + "broker state is reconciled."));
                                                                    });
                                                        });
                                            }

                                            return Mono.error(
                                                    new IllegalStateException(
                                                            "Unsupported execution.trade value: "
                                                                    + mode));
                                        });
                            });
                })
                .doOnSuccess(s ->
                        log.info("Post Processing Completed Successfully."))
                .doOnError(ex ->
                        log.error("Post Processing Failed", ex));
    }


    private SignalGenerationRequest buildSignalRequest(
            StockResponse stock,
            TechnicalIndicatorResponse indicator) {

        return SignalGenerationRequest.builder()
                .symbol(stock.getSymbol())
                .currentPrice(stock.getPrice())
                .rsi(indicator.getRsi14())
                .ema20(indicator.getEma20())
                .ema50(indicator.getEma50())
                .sma20(indicator.getSma20())
                .sma50(indicator.getSma50())
                .macd(indicator.getMacd())
                .signalLine(indicator.getSignalLine())
                .previousMacd(indicator.getPreviousMacd())
                .previousSignalLine(indicator.getPreviousSignalLine())
                .build();
    }

    private EntrySetup findEntrySetup(
            MinuteCandle completedCandle) {

        if (completedCandle == null
                || completedCandle.getSymbol() == null
                || completedCandle.getMinute() == null) {

            return null;
        }

        String symbol =
                completedCandle.getSymbol();

        LocalTime candleTime =
                completedCandle.getMinute().toLocalTime();

        /*
         * ============================================================
         * OPENING RANGE BUILDING
         * ============================================================
         *
         * 09:15 - 09:29
         *
         * No entry strategy is evaluated here.
         * These candles are used to build the Opening Range.
         */
        if (candleTime.isBefore(strategyEntryStart)) {

            log.debug(
                    "ENTRY STRATEGY GATE | {} | " +
                            "Opening range building | Candle={}",
                    symbol,
                    completedCandle.getMinute());

            return null;
        }

        /*
         * ============================================================
         * GET COMPLETED OPENING RANGE
         * ============================================================
         */

        OpeningRange openingRange =
                openingRangeService.get(symbol);

        if (openingRange == null) {

            openingRange =
                    openingRangeService.calculate(symbol);
        }

        if (openingRange == null
                || !openingRange.isComplete()) {

            log.debug(
                    "ENTRY STRATEGY GATE | {} | " +
                            "Opening range unavailable/incomplete",
                    symbol);

            return null;
        }

        /*
         * ============================================================
         * JUDAS
         * ============================================================
         *
         * 09:30 - 10:30. Evaluate only a setup confirmed by the
         * current completed candle; never enter on a stale historical setup.
         */
        if (!candleTime.isBefore(strategyEntryStart)
                && candleTime.isBefore(LocalTime.of(10, 30))) {

            JudasSwingResult judas =
                    judasSwingDetector.detect(openingRange, candleTime);

            if (judas != null
                    && judas.isDetected()
                    && judas.getConfirmationTime() != null
                    && judas.getConfirmationTime().equals(
                            completedCandle.getMinute())) {

                String setupKey =
                        symbol
                                + "|JUDAS|"
                                + String.valueOf(
                                judas.getConfirmationTime());

                if (processedStrategySetups.putIfAbsent(
                        setupKey,
                        setupKey) == null) {

                    String direction =
                            normalizeStrategyDirection(
                                    judas.getDirection());

                    log.info(
                            "ENTRY STRATEGY GATE | JUDAS VALID | " +
                                    "Symbol={} | Direction={} | " +
                                    "SweepSide={} | Confirmation={}",
                            symbol,
                            direction,
                            judas.getSweepSide(),
                            judas.getConfirmationTime());

                    return new EntrySetup(
                            "JUDAS",
                            direction,
                            judas.getConfirmationTime(),
                            judas.getSweepExtreme(),
                            judas.getConfirmationVolume() != null
                                    && judas.getAverageVolume() != null
                                    && judas.getAverageVolume() > 0
                                    ? judas.getConfirmationVolume() / judas.getAverageVolume()
                                    : null);
                }

                log.debug(
                        "ENTRY STRATEGY GATE | " +
                                "JUDAS ALREADY PROCESSED | " +
                                "Symbol={} | Confirmation={}",
                        symbol,
                        judas.getConfirmationTime());
            }
        }

        /*
         * ============================================================
         * ORB
         * ============================================================
         *
         * 09:30 - trading.strategy.orb.end (entry cut by default).
         * Confirmation must be from the current completed candle, not
         * an older historical setup.
         */
        if (!candleTime.isBefore(strategyEntryStart)
                && candleTime.isBefore(orbEntryEnd)) {

            ORBResult orb =
                    orbValidator.validate(openingRange, candleTime);

            if (orb != null
                    && orb.isValid()
                    && orb.getConfirmationTime() != null
                    && orb.getConfirmationTime().equals(
                            completedCandle.getMinute())) {

                String setupKey =
                        symbol
                                + "|ORB|"
                                + String.valueOf(
                                orb.getConfirmationTime());

                if (processedStrategySetups.putIfAbsent(
                        setupKey,
                        setupKey) == null) {

                    String direction =
                            normalizeStrategyDirection(
                                    orb.getDirection());

                    log.info(
                            "ENTRY STRATEGY GATE | ORB VALID | " +
                                    "Symbol={} | Direction={} | " +
                                    "BreakoutSide={} | Confirmation={}",
                            symbol,
                            direction,
                            orb.getBreakoutSide(),
                            orb.getConfirmationTime());

                    return new EntrySetup(
                            "ORB",
                            direction,
                            orb.getConfirmationTime(),
                            null,
                            orb.getVolumeRatio());
                }

                log.debug(
                        "ENTRY STRATEGY GATE | " +
                                "ORB ALREADY PROCESSED | " +
                                "Symbol={} | Confirmation={}",
                        symbol,
                        orb.getConfirmationTime());
            }
        }

        log.debug(
                "ENTRY STRATEGY GATE | NO VALID SETUP | " +
                        "Symbol={} | Candle={}",
                symbol,
                completedCandle.getMinute());

        return null;
    }

    private void releaseProcessedStrategySetup(
            String symbol,
            EntrySetup entrySetup,
            String reason) {

        if (symbol == null || entrySetup == null
                || entrySetup.strategy() == null
                || entrySetup.confirmationTime() == null) {
            return;
        }

        String setupKey = symbol
                + "|"
                + entrySetup.strategy()
                + "|"
                + entrySetup.confirmationTime();

        if (processedStrategySetups.remove(setupKey, setupKey)) {
            log.warn(
                    "STRATEGY SETUP RELEASED FOR RETRY | Symbol={} | Strategy={} | Confirmation={} | Reason={}",
                    symbol,
                    entrySetup.strategy(),
                    entrySetup.confirmationTime(),
                    reason);
        }
    }

    private void recordStrategySetup(EntrySetup entrySetup) {

        if (entrySetup == null || entrySetup.direction() == null) {
            return;
        }

        String strategy = entrySetup.strategy();
        String direction = entrySetup.direction().toUpperCase();

        if ("JUDAS".equalsIgnoreCase(strategy)) {
            if ("BUY".equals(direction)) {
                tradingFunnelStatisticsService.recordJudasBuy();
            } else if ("SELL".equals(direction)) {
                tradingFunnelStatisticsService.recordJudasSell();
            }
        } else if ("ORB".equalsIgnoreCase(strategy)) {
            if ("BUY".equals(direction)) {
                tradingFunnelStatisticsService.recordOrbBuy();
            } else if ("SELL".equals(direction)) {
                tradingFunnelStatisticsService.recordOrbSell();
            }
        }
    }

    private String normalizeStrategyDirection(
            String direction) {

        if (direction == null) {
            return "NONE";
        }

        return switch (direction.toUpperCase()) {

            case "BULLISH",
                 "BULLISH_REVERSAL" ->
                    "BUY";

            case "BEARISH",
                 "BEARISH_REVERSAL" ->
                    "SELL";

            default ->
                    "NONE";
        };
    }

    private SignalGenerationRequest buildSignalRequest(
            Tick tick,
            TechnicalIndicatorResponse indicator,
            EntrySetup entrySetup) {

        log.debug(
                "ENTRY QUALITY CONTEXT | Symbol={} | Strategy={} | Direction={} | EntryPrice={} | CandleTime={} | NIFTY={} | BANKNIFTY={} | Regime={}",
                tick.getSymbol(),
                entrySetup.strategy(),
                entrySetup.direction(),
                tick.getLastTradedPrice(),
                tick.getTradeTime(),
                tick.getNiftyChange(),
                tick.getBankNiftyChange(),
                tick.getMarketRegime());

        return SignalGenerationRequest.builder()
                .symbol(tick.getSymbol())
                .currentPrice(tick.getLastTradedPrice())
                .rsi(indicator.getRsi14())
                .ema20(indicator.getEma20())
                .ema50(indicator.getEma50())
                .sma20(indicator.getSma20())
                .sma50(indicator.getSma50())
                .macd(indicator.getMacd())
                .signalLine(indicator.getSignalLine())
                .previousMacd(indicator.getPreviousMacd())
                .previousSignalLine(indicator.getPreviousSignalLine())

                // Original strategy setup
                .setupStrategy(entrySetup.strategy())
                .setupDirection(
                        SignalType.valueOf(
                                entrySetup.direction()
                                        .toUpperCase()))
                .setupInvalidationLevel(entrySetup.invalidationLevel())
                .setupVolumeRatio(entrySetup.volumeRatio())
                .stockChangePct(changeFromDayOpen(tick))
                .marketContext(buildMarketContext(tick))
                .build();
    }

    /**
     * The stock's % move since today's open, on the same basis as the NIFTY
     * change market-stream publishes (also from today's open), so the two can
     * be compared; null when the day open is unknown.
     */
    private static Double changeFromDayOpen(Tick tick) {
        Double open = tick.getDayOpenPrice();
        Double last = tick.getLastTradedPrice();
        if (open == null || last == null || open <= 0 || last <= 0) {
            return null;
        }
        return (last - open) / open * 100.0;
    }

    private MarketContext buildMarketContext(Tick tick) {
        if (tick == null) {
            return null;
        }

        return MarketContext.builder()
                .niftyChange(tick.getNiftyChange())
                .bankNiftyChange(tick.getBankNiftyChange())
                .marketTrend(toTrend(tick.getMarketRegime()))
                .marketTime(tick.getTimestamp() != null ? tick.getTradeTime() : null)
                .build();
    }

    private Trend toTrend(String regime) {
        if (regime == null) return null;
        return switch (regime.toUpperCase()) {
            case "BULLISH" -> Trend.BULLISH;
            case "BEARISH" -> Trend.BEARISH;
            case "SIDEWAYS" -> Trend.SIDEWAYS;
            default -> null;
        };
    }

}