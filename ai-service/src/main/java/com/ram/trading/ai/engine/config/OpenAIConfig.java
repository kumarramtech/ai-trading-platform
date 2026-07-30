package com.ram.trading.ai.engine.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenAIConfig {

    @Bean("openAiChatClient")
    public ChatClient openAiChatClient(OpenAiChatModel chatModel) {

        return ChatClient.builder(chatModel)
                .build();
    }

}