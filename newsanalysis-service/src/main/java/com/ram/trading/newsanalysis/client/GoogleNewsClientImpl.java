package com.ram.trading.newsanalysis.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

@Component
@RequiredArgsConstructor
@Slf4j
public class GoogleNewsClientImpl implements GoogleNewsClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${google.news.rss-url}")
    private String rssUrl;

    @Override
    public Mono<List<String>> getLatestHeadlines(String symbol) {

        String encodedSymbol =
                URLEncoder.encode(symbol + " NSE India", StandardCharsets.UTF_8);

        String url = "https://news.google.com/rss/search?q=" + encodedSymbol
                + "&hl=en-IN&gl=IN&ceid=IN:en";

        log.info("Fetching Google News RSS");
        log.info("URL : {}", url);

        return webClientBuilder
                .defaultHeader(HttpHeaders.USER_AGENT,
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
                .defaultHeader(HttpHeaders.ACCEPT,
                        "application/rss+xml, application/xml, text/xml")
                .build()
                .get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parseHeadlines)
                .doOnSuccess(headlines ->
                        log.info("Fetched {} headlines for {}", headlines.size(), symbol))
                .onErrorResume(ex -> {
                    log.error("Unable to fetch Google News", ex);
                    return Mono.just(List.of("No latest market news available."));
                });

    }

    private List<String> parseHeadlines(String xml) {

        List<String> headlines = new ArrayList<>();

        try {

            Document document =
                    DocumentBuilderFactory
                            .newInstance()
                            .newDocumentBuilder()
                            .parse(
                                    new ByteArrayInputStream(
                                            xml.getBytes(StandardCharsets.UTF_8)));

            NodeList titles =
                    document.getElementsByTagName("title");

            for (int i = 1; i < titles.getLength(); i++) {

                String title =
                        titles.item(i)
                                .getTextContent();

                headlines.add(title);

                if (headlines.size() >= 5) {
                    break;
                }

            }

        } catch (Exception e) {

            log.error("Unable to parse Google RSS", e);

        }

        return headlines;

    }

}