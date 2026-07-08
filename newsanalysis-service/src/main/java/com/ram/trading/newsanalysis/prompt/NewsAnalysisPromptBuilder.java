package com.ram.trading.newsanalysis.prompt;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class NewsAnalysisPromptBuilder {

    public String build(String symbol, List<String> headlines) {

        return """
        Analyze the latest market news for %s.

        Headlines:
        %s

        Return ONLY JSON.

        {
          "sentiment":"POSITIVE|NEGATIVE|NEUTRAL",
          "score":0-100,
          "summary":"one line summary"
        }
        """.formatted(
                symbol,
                String.join("\n", headlines));
    }
}