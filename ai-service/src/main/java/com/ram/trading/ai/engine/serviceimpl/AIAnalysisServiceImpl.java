package com.ram.trading.ai.engine.serviceimpl;

import com.ram.trading.ai.engine.constant.PromptConstant;
import com.ram.trading.ai.engine.dto.SignalExplanationRequest;
import com.ram.trading.ai.engine.dto.SignalExplanationResponse;
import com.ram.trading.ai.engine.dto.TradeReviewRequest;
import com.ram.trading.ai.engine.dto.TradeReviewResponse;
import com.ram.trading.ai.engine.service.AIAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.SignalType;

@Service
@RequiredArgsConstructor
@Slf4j
public class AIAnalysisServiceImpl implements AIAnalysisService {



    private final ChatClient chatClient;
    @Override
    public SignalExplanationResponse explainSignal(
            SignalExplanationRequest request) {

        String prompt =
                PromptConstant.STOCK_ANALYSIS
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
                PromptConstant.INTRA_DAY_TRADE_PROMPT
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
}