
package com.ram.trading.ai.engine.provider.deepseek;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.ai.engine.exception.LLMProviderException;
import com.ram.trading.ai.engine.provider.LLMProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
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

        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();

        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));

        this.restClient = restClientBuilder
                .baseUrl("https://api.deepseek.com")
                .requestFactory(requestFactory)
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

        long start = System.nanoTime();

        try {
            log.info(
                    "AI_PROVIDER_REQUEST | provider={} | model={}",
                    getProviderName(),
                    model);

            Map<String, Object> request = Map.of(
                    "model", model,
                    "messages", new Object[]{
                            Map.of(
                                    "role", "user",
                                    "content", prompt)
                    },
                    "response_format", Map.of(
                            "type", "json_object"),
                    "thinking", Map.of(
                            "type", "disabled"),
                    "max_tokens", 4096,
                    "stream", false);

            String rawResponse = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(request)
                    .retrieve()
                    .body(String.class);

            if (rawResponse == null || rawResponse.isBlank()) {
                throw new IllegalStateException(
                        "DeepSeek returned an empty response");
            }

            JsonNode root = objectMapper.readTree(rawResponse);
            JsonNode choices = root.path("choices");
            JsonNode choice = choices.path(0);
            JsonNode message = choice.path("message");
            JsonNode contentNode = message.path("content");

            if (contentNode.isMissingNode()
                    || contentNode.isNull()
                    || !contentNode.isTextual()
                    || contentNode.asText().isBlank()) {

                // Log metadata only. Never log the prompt, API key,
                // or complete provider response.
                log.warn(
                        "DEEPSEEK_INVALID_RESPONSE | model={} | "
                                + "choicesCount={} | finishReason={} | "
                                + "errorType={} | contentPresent={}",
                        model,
                        choices.isArray() ? choices.size() : 0,
                        choice.path("finish_reason").asText("unknown"),
                        root.path("error").path("type").asText("none"),
                        !contentNode.isMissingNode()
                                && !contentNode.isNull());

                throw new IllegalStateException(
                        "DeepSeek response does not contain valid message.content");
            }

            log.info(
                    "DEEPSEEK_SUCCESS | elapsedMs={}",
                    elapsedMillis(start));

            return contentNode.asText();

        } catch (Exception ex) {

            log.error(
                    "DEEPSEEK_FAILED | elapsedMs={} | exceptionType={}",
                    elapsedMillis(start),
                    ex.getClass().getSimpleName());

            throw new LLMProviderException(
                    "DEEPSEEK failed",
                    ex);
        }
    }

    private long elapsedMillis(long startNanos) {
        return Duration.ofNanos(
                System.nanoTime() - startNanos).toMillis();
    }
}
