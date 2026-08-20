package com.ram.trading.newsanalysis.prompt;

import com.ram.trading.newsanalysis.dto.NewsArticle;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MarketTrendPromptBuilder {

    public String build(List<NewsArticle> articles) {

        StringBuilder newsContent = new StringBuilder();

        for (NewsArticle article : articles) {

            newsContent.append("""
                    TITLE: %s
                    DESCRIPTION: %s
                    SOURCE: %s

                    """.formatted(
                    article.getTitle(),
                    article.getDescription(),
                    article.getSource()
            ));
        }

        return """
                You are an expert Indian stock market analyst.

                Analyze the following latest market, economic, global,
                geopolitical and sector-related news.

                Your task is to identify the possible impact on the
                Indian stock market.

                Determine:

                1. Overall market sentiment:
                   POSITIVE, NEGATIVE or NEUTRAL

                2. Market trend:
                   BULLISH, BEARISH, MIXED or VOLATILE

                3. Sectors that may benefit.

                4. Sectors that may be negatively impacted.

                5. Important trending themes or events that could
                   influence the Indian stock market.

                Consider possible impact from:
                - US and global markets
                - Indian government announcements
                - RBI and economic policy
                - Budget-related developments
                - War and geopolitical events
                - Crude oil and commodity movements
                - Interest rates and inflation
                - Sector-specific developments

                IMPORTANT:
                News analysis must only provide market context and
                potential areas of focus.

                Do NOT generate BUY or SELL recommendations.
                Do NOT guarantee stock movement.
                Technical and trading systems will make the final
                trading decision.

                Return ONLY valid JSON in exactly this format:

                {
                  "overallMarketSentiment": "POSITIVE",
                  "marketTrend": "BULLISH",
                  "positiveSectors": [
                    "IT",
                    "BANKING"
                  ],
                  "negativeSectors": [
                    "OIL_MARKETING"
                  ],
                  "trendingThemes": [
                    "US technology developments",
                    "RBI policy"
                  ],
                  "summary": "Brief summary of today's important market drivers"
                }

                NEWS TO ANALYZE:

                %s
                """.formatted(newsContent);
    }
}