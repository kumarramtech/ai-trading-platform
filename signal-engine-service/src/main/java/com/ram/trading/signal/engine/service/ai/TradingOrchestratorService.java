package com.ram.trading.signal.engine.service.ai;

import com.ram.trading.signal.engine.audit.dto.TradingAuditReport;
import com.ram.trading.signal.engine.audit.service.StrategyStatisticsService;
import com.ram.trading.signal.engine.audit.service.TradingAuditService;
import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.ai.decision.TradingPipelineResult;
import com.ram.trading.signal.engine.dto.rules.RuleResult;
import com.ram.trading.signal.engine.service.EngineeringFilterService;
import com.ram.trading.signal.engine.service.ai.mapper.TradingDecisionMapper;
import com.ram.trading.signal.engine.service.context.TradingContext;
import com.ram.trading.signal.engine.service.context.TradingContextService;
import com.ram.trading.signal.engine.service.rules.TradingDecisionEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.ram.trading.signal.engine.dto.ai.AiDecisionResponse;
import com.ram.trading.signal.engine.dto.ai.TradingDecisionRequest;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import com.ram.trading.signal.engine.dto.rules.TradingDecision;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;


@Service
@RequiredArgsConstructor
@Slf4j
public class TradingOrchestratorService {

    private final TradingDecisionEngine tradingDecisionEngine;

    private final TradingDecisionMapper tradingDecisionMapper;

    private final AiDecisionIntegrationService aiDecisionIntegrationService;

    private final TradingContextService tradingContextService;

    private final EngineeringFilterService engineeringFilterService;

    private final TradingAuditService tradingAuditService;

    private final StrategyStatisticsService strategyStatisticsService;

    public Mono<TradingPipelineResult> executeTrade(
            SignalGenerationRequest signalRequest) {

        final long totalStart = System.currentTimeMillis();

        log.info("====================================================");
        log.info(
                "AI Trading Pipeline Started : {}",
                signalRequest.getSymbol());
        log.info("====================================================");

        /*
         * ============================================================
         * STEP 1 : TECHNICAL DECISION
         * ============================================================
         *
         * This is intentionally executed BEFORE TradingContext.
         *
         * TradingContext contains expensive downstream calls such as:
         * - News
         * - Portfolio
         * - Open Position
         *
         * We must not execute those for symbols that fail
         * the Engineering Filter.
         */

        long technicalStart = System.currentTimeMillis();

        TradingDecision technicalDecision =
                generateTechnicalDecision(signalRequest);

        log.info(
                "Technical Decision Time [{}] : {} ms",
                signalRequest.getSymbol(),
                System.currentTimeMillis() - technicalStart);

        log.info("Technical Decision Generated");
        log.info(
                "Symbol      : {}",
                signalRequest.getSymbol());

        log.info(
                "Signal      : {}",
                technicalDecision.getSignal());

        log.info(
                "Confidence  : {}",
                technicalDecision.getConfidence());

        /*
         * ============================================================
         * STEP 2 : ENGINEERING FILTER
         * ============================================================
         */

        long engineeringStart =
                System.currentTimeMillis();

        boolean eligible =
                engineeringFilterService
                        .isEligibleForAI(technicalDecision);

        log.info(
                "Engineering Filter Time [{}] : {} ms",
                signalRequest.getSymbol(),
                System.currentTimeMillis()
                        - engineeringStart);

        /*
         * ============================================================
         * STEP 3 : AUDIT
         * ============================================================
         */

        SignalType emaSignal =
                getRuleSignal(
                        technicalDecision,
                        "EMA");

        SignalType macdSignal =
                getRuleSignal(
                        technicalDecision,
                        "MACD");

        SignalType rsiSignal =
                getRuleSignal(
                        technicalDecision,
                        "RSI");

        TradingAuditReport auditReport =
                TradingAuditReport.builder()
                        .symbol(
                                signalRequest.getSymbol())
                        .currentPrice(
                                signalRequest.getCurrentPrice())
                        .ema20(
                                signalRequest.getEma20())
                        .ema50(
                                signalRequest.getEma50())
                        .sma20(
                                signalRequest.getSma20())
                        .sma50(
                                signalRequest.getSma50())
                        .macd(
                                signalRequest.getMacd())
                        .signalLine(
                                signalRequest.getSignalLine())
                        .rsi(
                                signalRequest.getRsi())
                        .emaSignal(
                                emaSignal)
                        .macdSignal(
                                macdSignal)
                        .rsiSignal(
                                rsiSignal)
                        .finalSignal(
                                technicalDecision.getSignal())
                        .confidence(
                                technicalDecision.getConfidence())
                        .engineeringFilterPassed(
                                eligible)
                        .rejectionReason(
                                eligible
                                        ? "PASSED"
                                        : "Engineering Filter Rejected")
                        .scanTime(
                                LocalDateTime.now())
                        .build();

        long auditStart =
                System.currentTimeMillis();

        tradingAuditService.audit(
                auditReport);

        strategyStatisticsService.recordAudit(
                auditReport);

        log.info(
                "Audit Time [{}] : {} ms",
                signalRequest.getSymbol(),
                System.currentTimeMillis()
                        - auditStart);

        /*
         * ============================================================
         * STEP 4 : ENGINEERING REJECTION
         * ============================================================
         *
         * VERY IMPORTANT:
         *
         * If rejected, we return immediately.
         *
         * NO:
         * - Trading Context
         * - News
         * - Portfolio
         * - Open Position
         * - AI
         *
         * should execute.
         */

        if (!eligible) {

            log.info(
                    "Engineering Filter Rejected {}",
                    signalRequest.getSymbol());

            strategyStatisticsService
                    .printStatistics();

            log.info(
                    "TOTAL AI Pipeline Time [{}] : {} ms",
                    signalRequest.getSymbol(),
                    System.currentTimeMillis()
                            - totalStart);

            return Mono.empty();
        }

        /*
         * ============================================================
         * STEP 5 : BUILD TRADING CONTEXT
         * ============================================================
         *
         * ONLY eligible symbols reach this point.
         */

        long contextStart =
                System.currentTimeMillis();

        log.info(
                "Building Trading Context for [{}]",
                signalRequest.getSymbol());

        return tradingContextService
                .buildTradingContext(signalRequest.getSymbol())
                .flatMap(context -> {

                    TradingDecisionRequest aiRequest =
                            tradingDecisionMapper.map(
                                    signalRequest,
                                    technicalDecision,
                                    context);

                    return callAI(aiRequest)
                            .map(aiResponse ->
                                    TradingPipelineResult.builder()
                                            .technicalDecision(technicalDecision)
                                            .tradingContext(context)
                                            .aiDecision(aiResponse)
                                            .build());
                });
    }

    private TradingDecision generateTechnicalDecision(
            SignalGenerationRequest request){
        return tradingDecisionEngine.generateDecision(request);

    }

    private Mono<AiDecisionResponse> callAI(
            TradingDecisionRequest request) {

        return aiDecisionIntegrationService.getDecision(request);
    }

    private SignalType getRuleSignal(
            TradingDecision decision,
            String ruleName) {

        if (decision == null ||
                decision.getRuleResults() == null) {
            return null;
        }

        return decision.getRuleResults()
                .stream()
                .filter(rule ->
                        ruleName.equalsIgnoreCase(rule.getRuleName()))
                .map(RuleResult::getSignal)
                .findFirst()
                .orElse(null);

    }

}