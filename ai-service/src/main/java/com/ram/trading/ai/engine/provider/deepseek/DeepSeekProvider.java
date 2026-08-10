package com.ram.trading.ai.engine.provider.deepseek;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.ai.engine.exception.LLMProviderException;
import com.ram.trading.ai.engine.provider.LLMProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
@Slf4j
public class DeepSeekProvider implements LLMProvider {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${ai.provider.deepseek.api-key:}")
    private String apiKey;

    @Value("${ai.provider.deepseek.model:deepseek-v4-flash}")
    private String model;

    @Value("${ai.provider.deepseek.priority:2}")
    private int priority;

    @Value("${ai.provider.deepseek.enabled:false}")
    private boolean enabled;

    public DeepSeekProvider(
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder) {

        this.objectMapper = objectMapper;

        this.restClient = restClientBuilder
                .baseUrl("https://api.deepseek.com")
                .build();
    }

    @Override
    public String getProviderName() {
        return "DEEPSEEK";
    }

    @Override
    public int getPriority() {
        return priority;
    }

    @Override
    public boolean isAvailable() {
        return enabled
                && apiKey != null
                && !apiKey.isBlank();
    }

    @Override
    public String analyze(String prompt) {

        long start = System.currentTimeMillis();

        try {

            log.info("=========================================");
            log.info("Calling Provider={} Enabled={} Priority={}",
                    getProviderName(),
                    enabled,
                    priority);
            log.info("DeepSeek Model : {}", model);
            log.info("=========================================");

            Map<String, Object> request = Map.of(
                    "model", model,
                    "messages", new Object[]{
                            Map.of(
                                    "role", "user",
                                    "content", prompt
                            )
                    },
                    "response_format", Map.of(
                            "type", "json_object"
                    ),
                    "thinking", Map.of(
                            "type", "disabled"
                    ),
                    "max_tokens", 4096,
                    "stream", false
            );

            String rawResponse = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(request)
                    .retrieve()
                    .body(String.class);

            if (rawResponse == null || rawResponse.isBlank()) {
                throw new IllegalStateException(
                        "DeepSeek returned empty response");
            }

            JsonNode root = objectMapper.readTree(rawResponse);

            JsonNode contentNode = root
                    .path("choices")
                    .path(0)
                    .path("message")
                    .path("content");

            if (contentNode.isMissingNode()
                    || contentNode.isNull()
                    || contentNode.asText().isBlank()) {

                throw new IllegalStateException(
                        "DeepSeek response does not contain message.content");
            }

            String response = contentNode.asText();

            long elapsed = System.currentTimeMillis() - start;

            log.info("{} completed successfully in {} ms",
                    getProviderName(),
                    elapsed);

            log.debug("{} response received successfully",
                    getProviderName());

            return response;

        } catch (Exception ex) {

            long elapsed = System.currentTimeMillis() - start;

            log.error("{} failed after {} ms",
                    getProviderName(),
                    elapsed,
                    ex);

            throw new LLMProviderException(
                    getProviderName() + " Failed",
                    ex);
        }
    }
}