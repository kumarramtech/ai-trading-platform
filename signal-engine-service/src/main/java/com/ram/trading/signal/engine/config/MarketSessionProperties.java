package com.ram.trading.signal.engine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "market")
public class MarketSessionProperties {

    private boolean enabled;

    private String timezone;

    private String openTime;

    private String closeTime;
}