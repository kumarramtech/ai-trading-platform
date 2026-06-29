package com.ram.trading.notification.client;

import com.ram.trading.notification.dto.WhatsAppMessageRequest;
import com.ram.trading.notification.dto.WhatsAppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
@Slf4j
public class WhatsAppClient {

    private final WebClient webClient;
    private final WhatsAppProperties properties;

    public Mono<String> sendMessage(
            WhatsAppMessageRequest request) {

        log.info("WhatsApp Request={}", request);

        log.info("Token Prefix={}",
                properties.getAccessToken().substring(0,20));

        return webClient.post()
                .uri("https://graph.facebook.com/v25.0/"
                        + properties.getPhoneNumberId()
                        + "/messages")
                .header(
                        "Authorization",
                        "Bearer " + properties.getAccessToken())
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class);
    }
}