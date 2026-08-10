package com.ram.trading.ai.engine.provider.openrouter;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ai.openrouter")
@Data
public class OpenRouterProperties {

    private boolean enabled;

    private String apiKey;

    private String model;

    private int priority;

    private long timeout;
}