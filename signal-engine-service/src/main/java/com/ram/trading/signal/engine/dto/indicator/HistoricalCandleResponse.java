package com.ram.trading.signal.engine.dto.indicator;

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