package com.ram.trading.ai.engine.serviceimpl;


import com.ram.trading.ai.engine.dto.AiDecisionResponse;
import com.ram.trading.ai.engine.dto.TradingDecisionRequest;
import com.ram.trading.ai.engine.parser.AiDecisionResponseParser;
import com.ram.trading.ai.engine.prompt.AiDecisionPromptBuilder;
import com.ram.trading.ai.engine.service.AiDecisionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiDecisionServiceImpl implements AiDecisionService {

    private final ChatClient chatClient;

    private final AiDecisionPromptBuilder promptBuilder;

    private final AiDecisionResponseParser parser;

    @Override
    public AiDecisionResponse evaluate(
            TradingDecisionRequest request) {

        log.info("Generating AI decision for {}",
                request.getSignalRequest().getSymbol());

        String prompt =
                promptBuilder.buildPrompt(request);

        log.debug("Prompt Generated Successfully.");

        String aiResponse = chatClient.prompt()

                .user(prompt)

                .call()

                .content();

        log.debug("AI Response : {}", aiResponse);

        AiDecisionResponse response =
                parser.parse(aiResponse);

        log.info("AI Decision_Recommendation : {}",
                response.getDecision().getRecommendation());


        log.info("========== Parsed News ==========");
        log.info("Summary   : {}", response.getNewsAnalysis().getSummary());
        log.info("Sentiment : {}", response.getNewsAnalysis().getSentiment());
        log.info("Score     : {}", response.getNewsAnalysis().getScore());
        log.info("================================");



        return response;
    }

}