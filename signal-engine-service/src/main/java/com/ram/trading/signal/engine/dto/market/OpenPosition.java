package com.ram.trading.signal.engine.dto.market;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenPosition {

    private String tradeId;

    private String symbol;

    private TradeType tradeType;

    private int quantity;

    private BigDecimal entryPrice;

    private BigDecimal currentPrice;

    private BigDecimal stopLoss;

    private BigDecimal targetPrice;

    private BigDecimal highestPrice;

    private BigDecimal lowestPrice;

    private LocalDateTime entryTime;

}