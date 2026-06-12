package com.ram.trading.signal.engine.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TradeReviewResponse {

    private Long tradeId;

    private String review;
}