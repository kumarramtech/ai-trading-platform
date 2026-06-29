package com.ram.trading.notification.provider;

import com.ram.trading.notification.dto.NotificationRequest;
import com.ram.trading.notification.dto.NotificationResponse;

public interface NotificationProvider {

    NotificationResponse sendTestMessage();
}