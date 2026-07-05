package com.ram.trading.stock.bootstrap.properties;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "bootstrap.history")
@NoArgsConstructor
public class BootStrapProperties {

    private String interval;
    private String exchange;
    private String segment;
    private String instrumentType;

}