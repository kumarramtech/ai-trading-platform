package com.ram.trading.ai.engine.dto;


import com.ram.trading.ai.engine.constant.enums.Sentiment;
import com.ram.trading.ai.engine.constant.enums.TradingSession;
import com.ram.trading.ai.engine.constant.enums.Trend;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketContext {

    /**
     * Historical averages
     */
    private Long averageVolume;

    private Double averageTrueRange;

    /**
     * Index movement
     */
    private Double niftyChange;

    private Double bankNiftyChange;

    /**
     * Market volatility
     */
    private Double volatilityIndex;

    /**
     * Market trends
     */
    private Trend marketTrend;

    private Trend sectorTrend;

    /**
     * AI/News sentiment
     */
    private Sentiment newsSentiment;

    /**
     * Trading session
     */
    private TradingSession tradingSession;

    /**
     * Current market time
     */
    private LocalDateTime marketTime;

}