package com.ram.trading.ai.engine.provider.gemini;

import com.ram.trading.ai.engine.exception.LLMProviderException;
import com.ram.trading.ai.engine.provider.LLMProvider;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class GeminiProvider implements LLMProvider {

    private final ChatClient chatClient;

    public GeminiProvider(
            @Qualifier("geminiChatClient")
            ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Value("${ai.provider.gemini.priority}")
    private int priority;

    @Override
    public String getProviderName() {
        return "GEMINI";
    }

    @Override
    public int getPriority() {
        return priority;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String analyze(String prompt) {

        try {

            log.info("=========================================");
            log.info("Calling {}", getProviderName());
            log.info("=========================================");

            String response =
                    chatClient.prompt()
                            .user(prompt)
                            .call()
                            .content();

            log.info("{} Response Received Successfully",
                    getProviderName());

            return response;

        } catch (Exception ex) {

            log.error("{} Failed",
                    getProviderName(),
                    ex);

            throw new LLMProviderException(
                    getProviderName() + " Failed",
                    ex);

        }

    }

}