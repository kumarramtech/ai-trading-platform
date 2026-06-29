package com.ram.trading.market.data.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PriceResponse {

    private String symbol;

    private Double price;

    private String source;

    private LocalDateTime timestamp;
}