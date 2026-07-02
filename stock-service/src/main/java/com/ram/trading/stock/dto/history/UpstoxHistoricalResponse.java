package com.ram.trading.stock.dto.history;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpstoxHistoricalResponse {

    private String status;

    private UpstoxHistoricalData data;

}