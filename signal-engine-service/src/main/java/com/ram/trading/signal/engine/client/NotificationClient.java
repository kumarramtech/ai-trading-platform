package com.ram.trading.signal.engine.client;

import com.ram.trading.signal.engine.dto.notification.NotificationRequest;
import com.ram.trading.signal.engine.dto.notification.NotificationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class NotificationClient {

    private final WebClient webClient;

    public Mono<NotificationResponse> sendNotification(
            NotificationRequest request) {

        return webClient
                .post()
                .uri("http://localhost:8087/notifications/send")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(NotificationResponse.class);
    }
}