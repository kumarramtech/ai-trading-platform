package com.ram.trading.ai.engine.serviceimpl;

import com.ram.trading.ai.engine.constant.PromptConstants;
import com.ram.trading.ai.engine.dto.*;
import com.ram.trading.ai.engine.service.AIAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AIAnalysisServiceImpl implements AIAnalysisService {



    private final ChatClient chatClient;
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


        String explanation =
                chatClient.prompt()
                        .user(prompt)
                        .call()
                        .content();

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

        String review =
                chatClient.prompt()
                        .user(prompt)
                        .call()
                        .content();

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
        String review =
                chatClient.prompt()
                        .user(prompt)
                        .call()
                        .content();

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

        String analysis =
                chatClient.prompt()
                        .user(prompt)
                        .call()
                        .content();

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
}