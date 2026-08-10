package com.ram.trading.newsanalysis.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.newsanalysis.dto.NewsArticle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class NewsApiClientImpl implements NewsApiClient {

    private final WebClient.Builder webClientBuilder;

    private final ObjectMapper objectMapper;

    @Value("${newsapi.base-url}")
    private String baseUrl;

    @Value("${newsapi.api-key}")
    private String apiKey;

    @Override
    public Mono<List<NewsArticle>> getLatestHeadlines(
            String symbol,
            String companyName) {

        String encodedSymbol =
                URLEncoder.encode(symbol, StandardCharsets.UTF_8);

        String url = String.format(
                "%s?ticker=%s&limit=5&apiKey=%s",
                baseUrl,
                encodedSymbol,
                apiKey
        );

        log.info(
                "Searching news for symbol [{}] company [{}]",
                symbol,
                companyName
        );

        return webClientBuilder.build()
                .get()
                .uri(url)
                .header(HttpHeaders.ACCEPT, "application/json")
                .retrieve()
                .bodyToMono(String.class)
                .doOnNext(json ->
                        log.info("NewsAPI Response : {}", json))
                .map(this::parseNews)
                .doOnSuccess(newsArticles ->
                        log.info(
                                "Fetched {} news articles for symbol [{}]",
                                newsArticles.size(),
                                symbol
                        ))
                .onErrorResume(ex -> {

                    log.error(
                            "Unable to fetch NewsAPI for symbol [{}] company [{}]. Continuing without news.",
                            symbol,
                            companyName,
                            ex
                    );

                    return Mono.just(List.of());
                });
    }

    private List<NewsArticle> parseNews(String json) {

        List<NewsArticle> newsArticles = new ArrayList<>();

        try {

            JsonNode root = objectMapper.readTree(json);

            JsonNode articles = root.path("results");

            if (!articles.isArray()) {
                log.warn("No news results found in NewsAPI response");
                return newsArticles;
            }

            for (JsonNode article : articles) {

                JsonNode publisher = article.path("publisher");

                List<String> tickers = new ArrayList<>();

                JsonNode tickerNode = article.path("tickers");

                if (tickerNode.isArray()) {

                    for (JsonNode ticker : tickerNode) {
                        tickers.add(ticker.asText());
                    }
                }

                NewsArticle newsArticle = NewsArticle.builder()
                        .title(article.path("title").asText(""))
                        .description(article.path("description").asText(""))
                        .publishedAt(article.path("published_utc").asText(""))
                        .source(publisher.path("name").asText(""))
                        .url(article.path("article_url").asText(""))
                        .tickers(tickers)
                        .build();

                newsArticles.add(newsArticle);

                if (newsArticles.size() >= 5) {
                    break;
                }
            }

        } catch (Exception ex) {

            log.error(
                    "Unable to parse NewsAPI response",
                    ex
            );
        }

        return newsArticles;
    }
}