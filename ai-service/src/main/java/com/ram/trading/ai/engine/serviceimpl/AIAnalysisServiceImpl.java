package com.ram.trading.ai.engine.serviceimpl;

import com.ram.trading.ai.engine.dto.SignalExplanationRequest;
import com.ram.trading.ai.engine.dto.SignalExplanationResponse;
import com.ram.trading.ai.engine.service.AIAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.SignalType;

@Service
@RequiredArgsConstructor
@Slf4j
public class AIAnalysisServiceImpl
        implements AIAnalysisService {

    public static final String STOCK_ANALYSIS =  """
            You are a professional stock market analyst.
            Analyze the trading signal and indicators.
             Provide:
                     1. Reason for the signal
                     2. Indicator interpretation
                     3. Risk assessment
                     Keep the response under 75 words.
                     Return plain text only.
                     Do not use bullet points or numbering.
                      """;

    private final ChatClient chatClient;
    @Override
    public SignalExplanationResponse explainSignal(
            SignalExplanationRequest request) {

        String prompt =
                STOCK_ANALYSIS
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

        if ("BUY".equalsIgnoreCase(
                request.getSignal())) {

          /*  explanation =
                    "Bullish conditions detected. "
                            + "Trend and momentum indicators support a BUY signal.";*/

        } else if ("SELL".equalsIgnoreCase(
                request.getSignal())) {

          /*  explanation =
                    "Bearish conditions detected. "
                            + "EMA trend and MACD momentum support a SELL signal.";*/

        } else {

           /* explanation =
                    "Market conditions are neutral. "
                            + "No strong directional signal detected.";*/
        }

        return SignalExplanationResponse.builder()
                .symbol(request.getSymbol())
                .signal(request.getSignal())
                .confidence(request.getConfidence())
                .explanation(explanation)
                .build();
    }
}