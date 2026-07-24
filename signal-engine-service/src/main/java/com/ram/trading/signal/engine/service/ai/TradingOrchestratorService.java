package com.ram.trading.signal.engine.service.ai;

import com.ram.trading.signal.engine.audit.dto.TradingAuditReport;
import com.ram.trading.signal.engine.audit.service.StrategyStatisticsService;
import com.ram.trading.signal.engine.audit.service.TradingAuditService;
import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.rules.RuleResult;
import com.ram.trading.signal.engine.service.EngineeringFilterService;
import com.ram.trading.signal.engine.service.ai.mapper.TradingDecisionMapper;
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

    public Mono<AiDecisionResponse> executeTrade(
            SignalGenerationRequest signalRequest) {

        log.info("======================================================");
        log.info("Starting AI Trading Pipeline for {}",
                signalRequest.getSymbol());
        log.info("======================================================");

        TradingDecision technicalDecision =
                generateTechnicalDecision(signalRequest);

        log.info("Technical Decision Generated");
        log.info("Symbol      : {}", signalRequest.getSymbol());
        log.info("Signal      : {}", technicalDecision.getSignal());
        log.info("Confidence  : {}", technicalDecision.getConfidence());
        log.info("Reasons     : {}", technicalDecision.getReasons());

        boolean eligible =
                engineeringFilterService.isEligibleForAI(technicalDecision);

        SignalType emaSignal =
                getRuleSignal(technicalDecision, "EMA");

        SignalType macdSignal =
                getRuleSignal(technicalDecision, "MACD");

        SignalType rsiSignal =
                getRuleSignal(technicalDecision, "RSI");

        TradingAuditReport auditReport =
                TradingAuditReport.builder()
                        .symbol(signalRequest.getSymbol())
                        .currentPrice(signalRequest.getCurrentPrice())

                        .ema20(signalRequest.getEma20())
                        .ema50(signalRequest.getEma50())

                        .sma20(signalRequest.getSma20())
                        .sma50(signalRequest.getSma50())

                        .macd(signalRequest.getMacd())
                        .signalLine(signalRequest.getSignalLine())

                        .rsi(signalRequest.getRsi())

                        .emaSignal(emaSignal)

                        .macdSignal(macdSignal)

                        .rsiSignal(rsiSignal)

                        .finalSignal(technicalDecision.getSignal())

                        .confidence(technicalDecision.getConfidence())

                        .engineeringFilterPassed(eligible)

                        .rejectionReason(
                                eligible
                                        ? "PASSED"
                                        : "Engineering Filter Rejected")

                        .scanTime(LocalDateTime.now())

                        .build();

        tradingAuditService.audit(auditReport);

        strategyStatisticsService.recordAudit(auditReport);

        log.info("Engineering Filter Result");
        log.info("Eligible For AI : {}", eligible);

        if (!eligible) {

            log.info("Engineering Filter Rejected {}",
                    signalRequest.getSymbol());

            strategyStatisticsService.printStatistics();

            return Mono.empty();
        }

        return tradingContextService
                .buildTradingContext(signalRequest.getSymbol())
                .flatMap(context -> {

                    log.info("Trading Context Built Successfully");

                    TradingDecisionRequest aiRequest =
                            tradingDecisionMapper.map(
                                    signalRequest,
                                    technicalDecision,
                                    context);

                    log.info("AI Request Created");

                    return callAI(aiRequest);

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