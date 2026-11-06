package com.ram.trading.auth.service.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Slack messages about the Upstox login. Sent in the background: a failure here is only
 * logged and can never fail a login or a token request. Never pass a token or an
 * authorization code to these methods.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BrokerAuthNotifier {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM HH:mm");
    private static final String HOW_TO_LOGIN =
            "Log in: open the SSH tunnel to port 9090, then go to http://localhost:9090/upstox/login";

    private final WebClient webClient;

    @Value("${notification.service.url:http://localhost:8087}")
    private String notificationUrl = "http://localhost:8087";

    @Value("${broker.auth.notifications.enabled:true}")
    private boolean enabled = true;

    public void loginSucceeded(LocalDateTime expiresAt) {
        send("Upstox login successful",
                expiresAt == null ? "The Upstox token is stored." : "Token valid until " + TIME.format(expiresAt) + " IST.");
    }

    public void loginFailed(String reason) {
        send("Upstox login FAILED", reason + "\nThe token was not updated. Try again. " + HOW_TO_LOGIN);
    }

    public void loginNeeded(String reason) {
        send("Upstox login needed", reason + "\nLog in now; the morning jobs need it by 07:30 IST. " + HOW_TO_LOGIN);
    }

    public void tokenRequestFailed(String reason) {
        send("Upstox token unavailable", "A service could not get the Upstox token: " + reason + "\n" + HOW_TO_LOGIN);
    }

    void send(String title, String message) {
        if (!enabled) {
            return;
        }
        try {
            webClient.post()
                    .uri(notificationUrl + "/notifications/send")
                    .bodyValue(Map.of("channel", "SLACK", "title", title, "message", message))
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(Duration.ofSeconds(10))
                    .doOnError(ex -> log.warn("Broker auth Slack message failed | {} | {}", title, ex.getMessage()))
                    .onErrorResume(ex -> Mono.empty())
                    .subscribe();
        } catch (RuntimeException ex) {
            log.warn("Broker auth Slack message failed | {} | {}", title, ex.getMessage());
        }
    }
}
