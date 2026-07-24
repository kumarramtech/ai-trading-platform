package com.ram.trading.newsanalysis.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    public Mono<List<String>> getLatestHeadlines(String companyName) {

        String encodedCompany =
                URLEncoder.encode(companyName, StandardCharsets.UTF_8);

        String url = String.format(
                "%s?ticker=%s&limit=5&apiKey=%s",
                baseUrl,
                companyName,
                apiKey);

        log.info("Searching news for {}", companyName);

        return webClientBuilder.build()
                .get()
                .uri(url)
                .header(HttpHeaders.ACCEPT, "application/json")
                .retrieve()
                .bodyToMono(String.class)
                .doOnNext(json -> log.info("NewsAPI Response : {}", json))
                .map(this::parseNews)
                .doOnSuccess(headlines ->
                        log.info("Fetched {} headlines",
                                headlines.size()))
                .onErrorResume(ex -> {

                    log.error("Unable to fetch NewsAPI", ex);

                    return Mono.just(List.of(
                            "No latest market news available."
                    ));
                });
    }

    private List<String> parseNews(String json) {

        List<String> headlines = new ArrayList<>();

        try {

            JsonNode root = objectMapper.readTree(json);

            JsonNode articles = root.path("results");

            for (JsonNode article : articles) {

                headlines.add(
                        article.path("title").asText());

                if (headlines.size() >= 5) {
                    break;
                }
            }

        } catch (Exception ex) {

            log.error("Unable to parse NewsAPI response", ex);

        }

        return headlines;
    }
}