package com.ram.trading.newsanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.newsanalysis.dto.NewsAnalysisResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class NewsAnalysisService {

    private final ChatClient.Builder chatClientBuilder;

    public NewsAnalysisResponse analyze(
            String symbol,
            String headline) {

        String prompt = """
                Analyze the following stock market news.

                Return ONLY JSON.

                Format:
                {
                  "sentiment":"POSITIVE|NEGATIVE|NEUTRAL",
                  "score":0-100,
                  "summary":"one line summary"
                }

                News:
                %s
                """.formatted(headline);

        String response =
                chatClientBuilder.build()
                        .prompt(prompt)
                        .call()
                        .content();

        log.info("AI Response={}", response);

        try {

            ObjectMapper mapper =
                    new ObjectMapper();

            JsonNode node =
                    mapper.readTree(response);

            return NewsAnalysisResponse.builder()
                    .symbol(symbol)
                    .sentiment(
                            node.get("sentiment").asText())
                    .score(
                            node.get("score").asInt())
                    .summary(
                            node.get("summary").asText())
                    .build();

        } catch (Exception e) {

            log.error(
                    "Failed to parse AI response",
                    e);

            return NewsAnalysisResponse.builder()
                    .symbol(symbol)
                    .sentiment("NEUTRAL")
                    .score(50)
                    .summary("Unable to analyze")
                    .build();
        }
    }
}