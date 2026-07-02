package com.ram.trading.stock.dto.history;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpstoxHistoricalData {

    private List<List<Object>> candles;

}