package com.ram.trading.ai.engine.dto;

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

    private Double currentPrice;

    private Double rsi;

    private Double ema20;

    private Double ema50;

    private Double macd;

    private Double signalLine;

    private Long volume;

    private String technicalSignal;

    private String confidenceLevel;

    private long evaluatedAt;

    /*
     * Last successful AI response.
     *
     * This allows us to reuse the previous AI decision
     * when the market has not changed meaningfully,
     * without calling the LLM again.
     */
    private AiDecisionResponse aiDecisionResponse;
}