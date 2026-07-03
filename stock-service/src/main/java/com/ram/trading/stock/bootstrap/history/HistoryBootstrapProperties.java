package com.ram.trading.stock.bootstrap.history;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "bootstrap.history")
public class HistoryBootstrapProperties {
    private Integer lookbackDays;
    private Integer maxSymbolsPerRun;
    private Integer threadPoolSize;
    private String interval;

}