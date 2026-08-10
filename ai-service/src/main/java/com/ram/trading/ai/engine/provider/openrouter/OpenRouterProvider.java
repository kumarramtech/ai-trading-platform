package com.ram.trading.ai.engine.provider.openrouter;

import com.ram.trading.ai.engine.dto.openrouter.Message;
import com.ram.trading.ai.engine.dto.openrouter.OpenRouterRequest;
import com.ram.trading.ai.engine.dto.openrouter.OpenRouterResponse;
import com.ram.trading.ai.engine.exception.LLMProviderException;
import com.ram.trading.ai.engine.provider.LLMProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

@Component
@Slf4j
public class OpenRouterProvider implements LLMProvider {

    private final WebClient webClient;
    private final OpenRouterProperties properties;

    public OpenRouterProvider(WebClient.Builder builder,
                              OpenRouterProperties properties) {

        this.properties = properties;

        this.webClient = builder
                .baseUrl("https://openrouter.ai")
                .build();
    }

    @Override
    public String getProviderName() {
        return "OPENROUTER";
    }

    @Override
    public int getPriority() {
        return properties.getPriority();
    }

    @Override
    public boolean isAvailable() {
        return properties.isEnabled();
    }

    @Override
    public String analyze(String prompt) {

        long start = System.currentTimeMillis();

        try {

            log.info("=========================================");
            log.info("Calling Provider : {}", getProviderName());
            log.info("Model            : {}", properties.getModel());
            log.info("=========================================");

            OpenRouterRequest request =
                    OpenRouterRequest.builder()
                            .model(properties.getModel())
                            .messages(List.of(
                                    Message.builder()
                                            .role("user")
                                            .content(prompt)
                                            .build()))
                            .build();

            OpenRouterResponse response =
                    webClient
                            .post()
                            .uri("/api/v1/chat/completions")
                            .header("Authorization",
                                    "Bearer " + properties.getApiKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .bodyValue(request)
                            .retrieve()
                            .onStatus(
                                    status -> status.is4xxClientError(),
                                    clientResponse -> clientResponse
                                            .bodyToMono(String.class)
                                            .map(body -> new LLMProviderException(
                                                    "OpenRouter Client Error : "
                                                            + clientResponse.statusCode()
                                                            + " Response : " + body)))
                            .onStatus(
                                    status -> status.is5xxServerError(),
                                    clientResponse -> clientResponse
                                            .bodyToMono(String.class)
                                            .map(body -> new LLMProviderException(
                                                    "OpenRouter Server Error : "
                                                            + clientResponse.statusCode()
                                                            + " Response : " + body)))
                            .bodyToMono(OpenRouterResponse.class)
                            .timeout(Duration.ofSeconds(properties.getTimeout()))
                            .block();

            if (response == null
                    || response.getChoices() == null
                    || response.getChoices().isEmpty()) {

                throw new LLMProviderException("No response received from OpenRouter");
            }

            long elapsed = System.currentTimeMillis() - start;

            log.info("{} completed successfully in {} ms",
                    getProviderName(),
                    elapsed);

            return response.getChoices()
                    .get(0)
                    .getMessage()
                    .getContent();

        } catch (Exception ex) {

            long elapsed = System.currentTimeMillis() - start;

            Throwable root = ex.getCause();

            if (root instanceof java.util.concurrent.TimeoutException) {

                log.warn("{} timed out after {} ms",
                        getProviderName(),
                        elapsed);

                throw new LLMProviderException(
                        "OpenRouter Timeout",
                        ex);
            }

            log.error("{} failed after {} ms",
                    getProviderName(),
                    elapsed,
                    ex);

            throw new LLMProviderException(
                    "OpenRouter Failed",
                    ex);
        }
    }
}