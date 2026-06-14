package com.ram.trading.notification.service;

import com.ram.trading.notification.dto.NotificationRequest;
import com.ram.trading.notification.dto.NotificationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class NotificationService {

    public NotificationResponse send(
            NotificationRequest request) {

        log.info(
                "Channel: {}",
                request.getChannel());

        log.info(
                "Title: {}",
                request.getTitle());

        log.info(
                "Message: {}",
                request.getMessage());

        return NotificationResponse
                .builder()
                .status("SUCCESS")
                .message(
                        "Notification sent")
                .build();
    }
}