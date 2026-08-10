package com.ram.trading.newsanalysis.prompt;

import com.ram.trading.newsanalysis.dto.NewsArticle;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class NewsAnalysisPromptBuilder {

    public String build(String symbol, List<NewsArticle> newsArticles) {

        StringBuilder newsContent = new StringBuilder();

        if (newsArticles != null && !newsArticles.isEmpty()) {

            newsArticles.stream()
                    .limit(5)
                    .forEach(article -> {

                        newsContent.append("\nTitle: ")
                                .append(article.getTitle());

                        newsContent.append("\nDescription: ")
                                .append(article.getDescription());

                        newsContent.append("\nPublished At: ")
                                .append(article.getPublishedAt());

                        newsContent.append("\nSource: ")
                                .append(article.getSource());

                        newsContent.append("\n");
                    });

        } else {

            newsContent.append("No latest market news available.");
        }

        return """
                Analyze the latest market news for %s.

                News:
                %s

                Return ONLY JSON.

                {
                  "sentiment":"POSITIVE|NEGATIVE|NEUTRAL",
                  "score":0-100,
                  "summary":"one line summary"
                }
                """.formatted(
                symbol,
                newsContent);
    }
}