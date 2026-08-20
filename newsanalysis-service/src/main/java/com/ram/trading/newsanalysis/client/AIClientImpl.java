package com.ram.trading.newsanalysis.client;

import com.ram.trading.newsanalysis.dto.AiPromptRequest;
import com.ram.trading.newsanalysis.dto.AiPromptResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
@Slf4j
public class AIClientImpl implements AIClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${ai.service.base-url}")
    private String aiServiceBaseUrl;

    @Override
    public Mono<String> analyze(String prompt) {

        log.info("Calling centralized AI Service");

        AiPromptRequest request =
                AiPromptRequest.builder()
                        .prompt(prompt)
                        .build();

        return webClientBuilder
                .baseUrl(aiServiceBaseUrl)
                .build()
                .post()
                .uri("/ai/news-analyze")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AiPromptResponse.class)
                .map(AiPromptResponse::getResponse)
                .doOnSuccess(response ->
                        log.info("AI response received from centralized AI Service"))
                .doOnError(error ->
                        log.error(
                                "Centralized AI Service call failed",
                                error
                        ));
    }
}