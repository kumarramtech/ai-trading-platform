package com.ram.trading.signal.engine.dto.market;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketTick {

    private String symbol;

    private BigDecimal lastPrice;

    private long volume;

    private LocalDateTime timestamp;

}