package com.ram.trading.notification.controller;

import com.ram.trading.notification.constant.NotificationChannel;
import com.ram.trading.notification.dto.NotificationRequest;
import com.ram.trading.notification.dto.NotificationResponse;
import com.ram.trading.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/slack")
@RequiredArgsConstructor
public class SlackNotificationController {

    private final NotificationService notificationService;

    @PostMapping("/test")
    public NotificationResponse sendTestNotification() {

        NotificationRequest request =
                NotificationRequest.builder()
                        .channel(NotificationChannel.SLACK)
                        .title("🚀 AI Trading Platform")
                        .message("Slack Integration Successful.")
                        .build();

        return notificationService.send(request);
    }

    @PostMapping("/trade")
    public NotificationResponse trade() {

        NotificationRequest request =
                NotificationRequest.builder()
                        .channel(NotificationChannel.SLACK)
                        .title("🚀 BUY SIGNAL")
                        .message("""
                            Symbol : TCS

                            Signal : BUY

                            Entry : 2200

                            Target : 2230

                            StopLoss : 2180

                            Confidence : 90%
                            """)
                        .build();

        return notificationService.send(request);
    }
}