package com.ram.trading.auth.service.notification;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BrokerAuthNotifierTest {

    @Test
    void postsToTheNotificationService() {
        AtomicReference<String> url = new AtomicReference<>();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            url.set(request.url().toString());
            return Mono.error(new ConnectException("stopped after capturing the request"));
        }).build();

        new BrokerAuthNotifier(client).send("title", "message");

        assertEquals("http://localhost:8087/notifications/send", url.get());
    }

    @Test
    void anUnreachableNotificationServiceDoesNotThrow() {
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new ConnectException("notification service down")))
                .build();
        BrokerAuthNotifier notifier = new BrokerAuthNotifier(client);

        assertDoesNotThrow(() -> notifier.loginFailed("reason"));
    }
}
