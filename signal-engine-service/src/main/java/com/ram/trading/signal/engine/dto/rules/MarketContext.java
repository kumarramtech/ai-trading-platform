package com.ram.trading.signal.engine.dto.rules;

import com.ram.trading.signal.engine.contant.Sentiment;
import com.ram.trading.signal.engine.contant.TradingSession;
import com.ram.trading.signal.engine.contant.Trend;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketContext {

    private Long averageVolume;

    private Double averageTrueRange;

    private Double niftyChange;

    private Double bankNiftyChange;

    private Double volatilityIndex;

    private Trend marketTrend;

    private Trend sectorTrend;

    private Sentiment newsSentiment;

    private LocalDateTime marketTime;

    private TradingSession tradingSession;

}