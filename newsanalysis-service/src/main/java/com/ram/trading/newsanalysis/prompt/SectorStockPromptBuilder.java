package com.ram.trading.newsanalysis.prompt;

import com.ram.trading.newsanalysis.dto.MarketTrendAnalysisResponse;
import org.springframework.stereotype.Component;

@Component
public class SectorStockPromptBuilder {

    public String build(MarketTrendAnalysisResponse marketTrend) {

        return """
                You are an expert analyst of the Indian stock market.

                Based on the identified positive sectors and current
                market trends, identify relevant NSE-listed equity stocks
                that may deserve additional attention.

                MARKET SENTIMENT:
                %s

                MARKET TREND:
                %s

                POSITIVE SECTORS:
                %s

                TRENDING THEMES:
                %s

                IMPORTANT RULES:

                1. Consider only NSE-listed equity stocks.

                2. Return stock trading symbols where possible.
                   Example: TCS, INFY, HDFCBANK, ICICIBANK.

                3. Select stocks that are relevant to the positive
                   sectors and current market themes.

                4. Do NOT provide BUY, SELL, TARGET, STOP LOSS,
                   or price predictions.

                5. These are only stocks that deserve additional
                   attention.

                6. The final system will validate every symbol against
                   the actual tradable instrument universe.

                7. Prefer relevant and actively traded NSE equities.

                Return ONLY valid JSON in exactly this format:

                {
                  "sectorStocks": [
                    {
                      "sector": "IT",
                      "stocks": [
                        {
                          "symbol": "TCS",
                          "reason": "Relevant to the positive IT sector trend"
                        },
                        {
                          "symbol": "INFY",
                          "reason": "Relevant to the positive IT sector trend"
                        }
                      ]
                    }
                  ]
                }
                """.formatted(
                marketTrend.getOverallMarketSentiment(),
                marketTrend.getMarketTrend(),
                marketTrend.getPositiveSectors(),
                marketTrend.getTrendingThemes()
        );
    }
}