package com.ram.trading.ai.engine.provider.openai;

import com.ram.trading.ai.engine.exception.LLMProviderException;
import com.ram.trading.ai.engine.provider.LLMProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class OpenAIProvider implements LLMProvider {

    private final ChatClient chatClient;

    public OpenAIProvider(
            @Qualifier("openAiChatClient")
            ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String getProviderName() {
        return "OPENAI";
    }

    @Value("${ai.provider.openai.priority}")
    private int priority;

    @Override
    public int getPriority() {
        return priority;
    }

    @Value("${ai.provider.openai.enabled:true}")
    private boolean enabled;

    @Override
    public boolean isAvailable() {
        return enabled;
    }

    @Override
    public String analyze(String prompt) {

        long start = System.currentTimeMillis();
        try {

            log.info("Calling Provider={} Enabled={} Priority={}",
                    getProviderName(),
                    enabled,
                    priority);

            String response = chatClient
                    .prompt(prompt)
                    .options(
                            org.springframework.ai.openai.OpenAiChatOptions.builder()
                                    .model("gpt-5.6-luna")
                                    .temperature(1.0)
                                    .build()
                    )
                    .call()
                    .content();

            long elapsed = System.currentTimeMillis() - start;

            log.info("{} completed successfully in {} ms",
                    getProviderName(),
                    elapsed);

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