package com.ram.trading.signal.engine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "trading.market")
public class MarketSessionProperties {

    private boolean enabled = true;

    private String timezone = "Asia/Kolkata";

    private String open;

    private String entryCutoff;

    private String close;

    private String end;
}