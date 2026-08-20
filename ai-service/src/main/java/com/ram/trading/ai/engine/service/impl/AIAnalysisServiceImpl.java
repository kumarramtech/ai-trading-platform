package com.ram.trading.ai.engine.service.impl;

import com.ram.trading.ai.engine.constant.PromptConstants;
import com.ram.trading.ai.engine.dto.*;
import com.ram.trading.ai.engine.dto.news.AiPromptRequest;
import com.ram.trading.ai.engine.dto.news.AiPromptResponse;
import com.ram.trading.ai.engine.gateway.AIGatewayService;
import com.ram.trading.ai.engine.service.AIAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AIAnalysisServiceImpl implements AIAnalysisService {



    private final AIGatewayService aiGatewayService;
    @Override
    public SignalExplanationResponse explainSignal(
            SignalExplanationRequest request) {

        String prompt =
                PromptConstants.STOCK_ANALYSIS
                        .formatted(
                                request.getSymbol(),
                                request.getSignal(),
                                request.getConfidence(),
                                request.getRsi(),
                                request.getEma20(),
                                request.getEma50(),
                                request.getMacd());


        String explanation = executeAiOrFallback(
                prompt,
                """
                Technical explanation generated because all AI providers are unavailable.
        
                Signal is based on RSI, EMA and MACD analysis.
                Please follow standard risk management.
                """);

        return SignalExplanationResponse.builder()
                .symbol(request.getSymbol())
                .signal(request.getSignal())
                .confidence(request.getConfidence())
                .explanation(explanation)
                .build();
    }

    @Override
    public TradeReviewResponse reviewTrade(
            TradeReviewRequest request) {

        String prompt =
                PromptConstants.INTRA_DAY_TRADE_PROMPT
                        .formatted(
                                request.getTradeId(),
                                request.getSymbol(),
                                request.getSignal(),
                                request.getEntryPrice(),
                                request.getExitPrice(),
                                request.getProfitLoss(),
                                request.getConfidence(),
                                request.getRsi(),
                                request.getEma20(),
                                request.getEma50(),
                                request.getMacd());

        String review = executeAiOrFallback(
                prompt,
                "Trade review unavailable. AI providers are currently unavailable.");

        return TradeReviewResponse.builder()
                .tradeId(request.getTradeId())
                .review(review)
                .build();
    }

    @Override
    public StrategyReviewResponse reviewStrategy(
            List<TradeReviewRequest> trades) {

        long winningTrades =
                trades.stream()
                        .filter(t ->
                        t.getProfitLoss() != null
                        && t.getProfitLoss() > 0).count();

        long losingTrades =
                trades.stream()
                        .filter(t ->
                        t.getProfitLoss() != null
                        && t.getProfitLoss() < 0)
                        .count();

        StringBuilder tradeData = new StringBuilder();

        for (TradeReviewRequest trade : trades) {

            tradeData.append(
                    String.format(
                            """
                            TradeId=%d,
                            Signal=%s,
                            Confidence=%d,
                            ProfitLoss=%.2f,
                            RSI=%.2f,
                            EMA20=%.2f,
                            EMA50=%.2f,
                            MACD=%.2f
    
                            """,
                            trade.getTradeId(),
                            trade.getSignal(),
                            trade.getConfidence(),
                            trade.getProfitLoss(),
                            trade.getRsi(),
                            trade.getEma20(),
                            trade.getEma50(),
                            trade.getMacd()));
        }

        String prompt = PromptConstants.STRATEGY_RESPONSE_PROMPT.formatted(tradeData);
        String review = executeAiOrFallback(
                prompt,
                "Strategy review unavailable. AI providers are currently unavailable.");

        return StrategyReviewResponse.builder()
                .totalTrades(trades.size())
                .winningTrades((int) winningTrades)
                .losingTrades((int) losingTrades)
                .review(review)
                .build();
    }

    @Override
    public RiskAnalysisResponse analyzeRisk(
            RiskAnalysisRequest request) {

        String prompt =
                String.format(
                        PromptConstants.RISK_ANALYSIS_PROMPT,
                        request.getSymbol(),
                        request.getSignal(),
                        request.getConfidence(),
                        request.getRsi(),
                        request.getEma20(),
                        request.getEma50(),
                        request.getMacd());

        String analysis = executeAiOrFallback(
                prompt,
                "Risk analysis generated using technical indicators only.");

        String riskLevel =
                request.getConfidence() >= 70
                        ? "LOW"
                        : request.getConfidence() >= 40
                        ? "MEDIUM"
                        : "HIGH";

        return RiskAnalysisResponse.builder()
                .symbol(request.getSymbol())
                .riskLevel(riskLevel)
                .analysis(analysis)
                .build();
    }

    @Override
    public AiPromptResponse analyze(AiPromptRequest request) {

        log.info("Processing generic AI analysis request");

        try {

            String response =
                    aiGatewayService.analyze(
                            request.getPrompt());

            return AiPromptResponse.builder()
                    .response(response)
                    .build();

        } catch (Exception ex) {

            log.error(
                    "AI analysis failed",
                    ex);

            return AiPromptResponse.builder()
                    .response(
                            "AI analysis unavailable. All AI providers failed.")
                    .build();
        }
    }

    private String executeAiOrFallback(
            String prompt,
            String fallbackMessage) {

        try {

            return aiGatewayService.analyze(prompt);

        } catch (Exception ex) {

            log.warn(
                    "AI unavailable. Using fallback response. Reason: {}",
                    ex.getMessage()
            );
            log.debug("AI provider failure", ex);

            return fallbackMessage;
        }
    }
}