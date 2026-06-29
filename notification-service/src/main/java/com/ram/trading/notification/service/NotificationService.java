package com.ram.trading.notification.service;

import com.ram.trading.notification.client.WhatsAppClient;
import com.ram.trading.notification.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final WhatsAppClient whatsAppClient;

    public NotificationResponse send(
            NotificationRequest request) {

        WhatsAppMessageRequest whatsappRequest =
                WhatsAppMessageRequest.builder()
                        .messaging_product("whatsapp")
                        .to("919052979683")
                        .type("template")
                        .template(
                                WhatsAppTemplate.builder()
                                        .name("trading_signal_alert")
                                        .language(
                                                WhatsAppLanguage.builder()
                                                        .code("en_US")
                                                        .build())
                                        .build())
                        .build();
        String response =
                whatsAppClient.sendMessage(whatsappRequest)
                        .block();

        log.info("WhatsApp Response={}", response);

        return NotificationResponse.builder()
                .status("SUCCESS")
                .message(response)
                .build();
    }
}