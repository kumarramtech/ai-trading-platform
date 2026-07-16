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

    private String signal;

    private TradeType tradeType;

    private int quantity;

    private Double entryPrice;

    private Double currentPrice;

    private Double stopLoss;

    private Double targetPrice;

    private Double highestPrice;

    private Double lowestPrice;

    private LocalDateTime entryTime;

}