package com.ram.trading.trade.execution.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrokerOrderResponse {

    private boolean success;

    private String orderId;

    private BigDecimal executedPrice;

    private String status;

    private String message;
}