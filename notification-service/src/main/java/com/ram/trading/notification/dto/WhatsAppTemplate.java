package com.ram.trading.notification.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WhatsAppTemplate {

    private String name;

    private WhatsAppLanguage language;
}