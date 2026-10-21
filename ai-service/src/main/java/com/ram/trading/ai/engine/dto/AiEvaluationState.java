
package com.ram.trading.ai.engine.dto;

import com.ram.trading.ai.engine.constant.enums.Sentiment;
import com.ram.trading.ai.engine.constant.enums.TradingSession;
import com.ram.trading.ai.engine.constant.enums.Trend;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiEvaluationState implements Serializable {

    private String symbol;

    // Technical indicator snapshot
    private Double currentPrice;
    private Double rsi;
    private Double ema20;
    private Double ema50;
    private Double macd;
    private Double signalLine;
    private Long volume;

    private String technicalSignal;
    private String confidenceLevel;

    // Market context snapshot used during the previous AI evaluation
    private Double niftyChange;
    private Double bankNiftyChange;
    private Double volatilityIndex;
    private Trend marketTrend;
    private Trend sectorTrend;
    private Sentiment newsSentiment;
    private TradingSession tradingSession;
    private Long averageVolume;
    private Double averageTrueRange;

    private long evaluatedAt;
    private String cacheKey;

// Existing technical and market-context fields remain unchanged.

    /*
     * Last successful AI response.
     *
     * Reuse this decision only when the relevant market and
     * technical context remains valid.
     */
    private AiDecisionResponse aiDecisionResponse;
}