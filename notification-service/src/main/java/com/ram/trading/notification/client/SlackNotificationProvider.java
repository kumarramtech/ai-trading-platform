package com.ram.trading.notification.client;

import com.ram.trading.notification.constant.NotificationChannel;
import com.ram.trading.notification.dto.NotificationRequest;
import com.ram.trading.notification.dto.NotificationResponse;
import com.ram.trading.notification.provider.NotificationProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class SlackNotificationProvider implements NotificationProvider {

    private final WebClient webClient;

    @Value("${slack.webhook.url}")
    private String webhook;

    @Override
    public NotificationResponse send(
            NotificationRequest request) {

        Map<String,String> payload =
                Map.of(
                        "text",
                        request.getTitle()
                        + "\n"
                        + request.getMessage());

        webClient.post()
                .uri(webhook)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        return NotificationResponse.builder()
                .status("SUCCESS")
                .message("Slack notification sent")
                .build();
    }

    @Override
    public boolean supports(
            NotificationChannel channel) {

        return channel == NotificationChannel.SLACK;
    }
}