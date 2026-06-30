package com.ram.trading.stock.dto;

import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoricalCandleResponse {

    private String symbol;

    private List<Candle> candles;

}