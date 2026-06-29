package com.ram.trading.notification.dto;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "meta.whatsapp")
public class WhatsAppProperties {

    private String accessToken;

    private String phoneNumberId;
}