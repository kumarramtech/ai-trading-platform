package com.ram.trading.ai.engine.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test")
public class GeminiTestController {

    private final ChatClient chatClient;

    public GeminiTestController(
            @Qualifier("geminiChatClient")
            ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @GetMapping("/gemini")
    public String test() {

        return chatClient.prompt()
                .user("Say Hello from Gemini")
                .call()
                .content();
    }
}