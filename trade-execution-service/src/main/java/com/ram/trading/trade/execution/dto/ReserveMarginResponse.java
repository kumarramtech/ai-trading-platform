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
public class ReserveMarginResponse {

    private Boolean sufficientBalance;

    private BigDecimal availableBalance;

    private BigDecimal reservedMargin;

    private String message;
}