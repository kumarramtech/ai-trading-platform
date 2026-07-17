package com.ram.trading.notification.dto;

import com.ram.trading.notification.constant.NotificationChannel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationRequest {

    private NotificationChannel channel;

    private String title;

    private String message;

    private String symbol;

    private String signal;

    private Double entryPrice;

    private Double exitPrice;

    private Double targetPrice;

    private Double stopLoss;

    private Double profitLoss;

    private Integer confidence;

    private String reason;

    private String recipient;
}