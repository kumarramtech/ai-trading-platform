package com.ram.trading.newsanalysis.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
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

        String url = rssUrl.formatted(symbol);

        log.info("Fetching Google News RSS for {}", symbol);

        return webClientBuilder.build()
                .get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parseHeadlines)
                .doOnSuccess(headlines ->
                        log.info("Fetched {} headlines for {}",
                                headlines.size(),
                                symbol))
                .onErrorResume(ex -> {
                    log.error("Unable to fetch Google News", ex);
                    return Mono.just(List.of(
                            "No latest market news available."
                    ));

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