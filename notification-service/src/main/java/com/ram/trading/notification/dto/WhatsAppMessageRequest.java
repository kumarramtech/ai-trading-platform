package com.ram.trading.notification.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WhatsAppMessageRequest {

    private String messaging_product;

    private String to;

    private String type;

    private WhatsAppTemplate template;
}