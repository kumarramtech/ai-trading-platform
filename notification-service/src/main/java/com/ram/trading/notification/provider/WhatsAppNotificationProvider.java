/*
package com.ram.trading.notification.provider;

import com.ram.trading.notification.client.WhatsAppClient;
import com.ram.trading.notification.dto.NotificationRequest;
import com.ram.trading.notification.dto.NotificationResponse;
import com.ram.trading.notification.dto.Text;
import com.ram.trading.notification.dto.WhatsAppMessageRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WhatsAppNotificationProvider implements NotificationProvider {

    private final WhatsAppClient whatsAppClient;


    @Override
    public NotificationResponse sendTestMessage() {
        WhatsAppMessageRequest request =
                WhatsAppMessageRequest.builder()
                        .messaging_product("whatsapp")
                        .to("919052979683")
                        .type("text")
                        .text(Text.builder()
                                .body(
                                        "🚀 AI Trading Platform Connected")
                                .build())
                        .build();

        whatsAppClient.sendMessage(request)
                .subscribe();
    }
}*/
