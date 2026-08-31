package com.ram.trading.signal.engine.scheduler;

import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.dto.watchlist.MiddayRevalidationResult;
import com.ram.trading.signal.engine.service.*;
import com.ram.trading.signal.engine.util.TradingSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketScannerScheduler {

    private final MarketScannerService marketScannerService;

    private final TradingSessionService tradingSessionService;

    private final MiddayCandidateService middayCandidateService;

    private final MiddayRevalidationService middayRevalidationService;

    private final SignalGenerationService signalGenerationService;

    private final PreMarketAnalysisService preMarketAnalysisService;


    /**
     * Existing market scanner.
     *
     * Runs every 5 minutes during the trading session.
     *
     * IMPORTANT:
     * This existing flow remains unchanged.
     */
    @Scheduled(fixedRate = 300000)
    public void scanMarket() {

        if (!tradingSessionService.canCreateTrade()) {
            return;
        }

        log.info("Starting Market Scan");

        marketScannerService.scanMarket();
    }


    /**
     * V2.2 MIDDAY DISCOVERY
     *
     * Runs once at 11:30 AM IST on weekdays.
     *
     * Flow:
     *
     * Watchlist Snapshot
     *        ↓
     * Top Gainers
     * Top Losers
     * Trending Stocks
     *        ↓
     * Unique Candidate Pool
     *        ↓
     * MiddayCandidateStore
     *
     * This stage does NOT:
     * - generate BUY/SELL signals
     * - call AI
     * - call Risk Guard
     * - create paper trades
     */
    @Scheduled(
            cron = "0 30 11 * * MON-FRI",
            zone = "Asia/Kolkata"
    )
    public void discoverMiddayCandidates() {

        if (!tradingSessionService.canCreateTrade()) {

            log.info(
                    "MIDDAY DISCOVERY SKIPPED | " +
                            "Trading session does not allow processing"
            );

            return;
        }

        log.info("======================================================");
        log.info("V2.2 MIDDAY DISCOVERY STARTED");
        log.info("Discovery Time : 11:30 AM IST");
        log.info("======================================================");

        middayCandidateService
                .discoverCandidates()

                .doOnSuccess(candidates ->
                        log.info(
                                "V2.2 MIDDAY DISCOVERY FINISHED | " +
                                        "Candidates={}",
                                candidates == null
                                        ? 0
                                        : candidates.size()
                        )
                )

                .doOnError(error ->
                        log.error(
                                "V2.2 MIDDAY DISCOVERY FAILED",
                                error
                        )
                )

                .subscribe();
    }


    /**
     * V2.2 MIDDAY REVALIDATION + SIGNAL HANDOFF
     *
     * Runs once at 12:00 PM IST on weekdays.
     *
     * Flow:
     *
     * MiddayCandidateStore
     *        ↓
     * Revalidation
     *        ↓
     * ┌───────────────┐
     * │   QUALIFIED   │ ─────→ SignalGenerationService
     * ├───────────────┤
     * │   WEAKENED    │ ─────→ STOP
     * ├───────────────┤
     * │   REJECTED    │ ─────→ STOP
     * └───────────────┘
     *
     * Only QUALIFIED candidates enter the
     * existing signal-generation pipeline.
     */
    @Scheduled(
            cron = "0 0 12 * * MON-FRI",
            zone = "Asia/Kolkata"
    )
    public void revalidateAndGenerateSignals() {

        if (!tradingSessionService.canCreateTrade()) {

            log.info(
                    "MIDDAY REVALIDATION SKIPPED | " +
                            "Trading session does not allow processing"
            );

            return;
        }

        log.info("======================================================");
        log.info("V2.2 MIDDAY REVALIDATION STARTED");
        log.info("Revalidation Time : 12:00 PM IST");
        log.info("======================================================");

        middayRevalidationService
                .revalidateCandidates()

                .flatMapMany(Flux::fromIterable)

                /*
                 * Only QUALIFIED candidates are allowed
                 * to enter the existing signal service.
                 */
                .filter(result ->
                        result != null
                                && "QUALIFIED".equals(
                                result.getStatus()
                        )
                )

                /*
                 * Process candidates sequentially.
                 *
                 * This prevents multiple AI/trading pipelines
                 * from being launched simultaneously.
                 */
                .concatMap(this::generateSignalForQualifiedCandidate)

                .collectList()

                .doOnSuccess(signals ->
                        log.info(
                                "======================================================"
                        )
                )

                .doOnSuccess(signals ->
                        log.info(
                                "V2.2 MIDDAY TRADING COMPLETED | " +
                                        "SignalsGenerated={}",
                                signals.size()
                        )
                )

                .doOnSuccess(signals ->
                        log.info(
                                "======================================================"
                        )
                )

                .doOnError(error ->
                        log.error(
                                "V2.2 MIDDAY REVALIDATION / " +
                                        "SIGNAL HANDOFF FAILED",
                                error
                        )
                )

                .subscribe();
    }


    /**
     * Sends one QUALIFIED candidate into the
     * existing SignalGenerationService.
     *
     * IMPORTANT:
     * We do NOT duplicate signal-generation logic here.
     *
     * SignalGenerationService remains the single
     * entry point for the existing trading pipeline.
     */
    private Mono<TradingSignal> generateSignalForQualifiedCandidate(
            MiddayRevalidationResult result) {

        String symbol = result.getTradingSymbol();

        log.info(
                "MIDDAY SIGNAL HANDOFF | " +
                        "Symbol={} | " +
                        "Score={} | " +
                        "Status={}",
                symbol,
                result.getScore(),
                result.getStatus()
        );

        if (symbol == null || symbol.isBlank()) {

            log.warn(
                    "MIDDAY SIGNAL HANDOFF SKIPPED | " +
                            "Invalid trading symbol"
            );

            return Mono.empty();
        }

        return signalGenerationService
                .generateSignal(symbol)

                .doOnSuccess(signal -> {

                    if (signal == null) {

                        log.warn(
                                "MIDDAY SIGNAL RESULT | " +
                                        "Symbol={} | Signal=NULL",
                                symbol
                        );

                        return;
                    }

                    log.info(
                            "MIDDAY SIGNAL RESULT | " +
                                    "Symbol={} | " +
                                    "Signal={} | " +
                                    "Confidence={}",
                            symbol,
                            signal.getSignal(),
                            signal.getConfidence()
                    );
                })

                .doOnError(error ->
                        log.error(
                                "MIDDAY SIGNAL GENERATION FAILED | " +
                                        "Symbol={} | Error={}",
                                symbol,
                                error.getMessage(),
                                error
                        )
                )

                /*
                 * If one qualified candidate fails,
                 * continue with the next candidate.
                 */
                .onErrorResume(error -> Mono.empty());
    }

    @Scheduled(
            cron = "0 0 9 * * MON-FRI",
            zone = "Asia/Kolkata"
    )
    public void analyzePreMarket() {

        log.info("======================================================");
        log.info("V2.2 PRE-MARKET ANALYSIS STARTED");
        log.info("Analysis Time : 09:00 AM IST");
        log.info("======================================================");

        preMarketAnalysisService
                .analyze()

                .doOnSuccess(candidates ->
                        log.info(
                                "V2.2 PRE-MARKET ANALYSIS COMPLETED | " +
                                        "Candidates={}",
                                candidates == null
                                        ? 0
                                        : candidates.size()
                        )
                )

                .doOnError(error ->
                        log.error("V2.2 PRE-MARKET ANALYSIS FAILED", error)
                )

                .subscribe();
    }
}