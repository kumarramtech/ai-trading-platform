package com.ram.trading.notification.service;

import com.ram.trading.notification.client.WhatsAppClient;
import com.ram.trading.notification.dto.*;
import com.ram.trading.notification.provider.NotificationProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final List<NotificationProvider> providers;

    public NotificationResponse send(
            NotificationRequest request) {

        NotificationProvider provider =
                providers.stream()
                        .filter(p -> p.supports(request.getChannel()))
                        .findFirst()
                        .orElseThrow(() ->
                                new RuntimeException("Provider not found"));

        return provider.send(request);
    }
}