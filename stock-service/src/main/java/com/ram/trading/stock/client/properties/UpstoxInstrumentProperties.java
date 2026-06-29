package com.ram.trading.stock.client.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "upstox.instrument")
@Getter
@Setter
public class UpstoxInstrumentProperties {

    private String url;

    private int connectTimeout;

    private int readTimeout;

}