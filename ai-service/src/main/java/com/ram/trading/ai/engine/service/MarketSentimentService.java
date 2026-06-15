package com.ram.trading.ai.engine.service;

import com.ram.trading.ai.engine.dto.MarketSentimentResponse;
import org.springframework.stereotype.Service;

@Service
public class MarketSentimentService {

    public MarketSentimentResponse
            getSentiment(
                    String symbol) {

        if ("INFY".equals(symbol)) {

            return MarketSentimentResponse
                    .builder()
                    .symbol(symbol)
                    .sentiment("POSITIVE")
                    .sentimentScore(85)
                    .reason("Strong IT sector momentum")
                    .build();
        }

        return MarketSentimentResponse
                .builder()
                .symbol(symbol)
                .sentiment("NEUTRAL")
                .sentimentScore(50)
                .reason("No significant market events")
                .build();
    }
}