package com.ram.trading.margin.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BalanceMarginResponse {

    private Double availableBalance;

    private Double cashAvailable;

    private Double pledgedMargin;

    private Double unsettledProfitToday;

    private Double unsettledProfitPreviousDays;
}