package com.ram.trading.newsanalysis.client;

import com.ram.trading.newsanalysis.dto.NewsArticle;
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
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
@Slf4j
public class GoogleNewsClientImpl implements GoogleNewsClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${google.news.rss-url}")
    private String rssUrl;

    @Override
    public Mono<List<NewsArticle>> getLatestHeadlines(String symbol) {

        String query =
                symbol + " when:7d NSE India";

        String url =
                rssUrl.formatted(query)
                        + "&hl=en-IN"
                        + "&gl=IN"
                        + "&ceid=IN:en";

        log.info("=========================================");
        log.info("Fetching Google News RSS");
        log.info("Symbol : {}", symbol);
        log.info("URL    : {}", url);
        log.info("=========================================");

        return webClientBuilder
                .defaultHeader(
                        HttpHeaders.USER_AGENT,
                        "Mozilla/5.0"
                )
                .defaultHeader(
                        HttpHeaders.ACCEPT,
                        "application/rss+xml, application/xml, text/xml"
                )
                .build()
                .get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .doOnNext(xml -> {
                    log.info("========== GOOGLE NEWS RAW RESPONSE ==========");
                    log.info("{}", xml);
                    log.info("==============================================");
                })
                .map(this::parseNews)
                .doOnSuccess(news ->
                        log.info(
                                "Google News returned {} valid articles for {}",
                                news.size(),
                                symbol
                        )
                )
                .onErrorResume(ex -> {

                    log.error(
                            "Unable to fetch Google News for {}",
                            symbol,
                            ex
                    );

                    return Mono.just(List.of());
                });
    }

    private List<NewsArticle> parseNews(String xml) {

        List<NewsArticle> articles = new ArrayList<>();

        try {

            DocumentBuilderFactory factory =
                    DocumentBuilderFactory.newInstance();

            factory.setNamespaceAware(true);

            Document document =
                    factory
                            .newDocumentBuilder()
                            .parse(
                                    new ByteArrayInputStream(
                                            xml.getBytes(StandardCharsets.UTF_8)
                                    )
                            );

            NodeList items =
                    document.getElementsByTagNameNS("*", "item");
            log.info(
                    "Google News RSS item count: {}",
                    items.getLength()
            );

            for (int i = 0; i < items.getLength(); i++) {

                Element item = (Element) items.item(i);
                log.info(
                        "Processing Google News RSS item {}",
                        i
                );

                String title =
                        getElementText(item, "title");

                String description =
                        getElementText(item, "description");

                String publishedAt =
                        getElementText(item, "pubDate");

                log.info(
                        "Google News candidate: [{}] | PublishedAt: [{}]",
                        title,
                        publishedAt
                );

                String source =
                        getElementText(item, "source");

                String url =
                        getElementText(item, "link");

                // Ignore invalid articles
                if (title == null || title.isBlank()) {
                    continue;
                }

                // Ignore Google RSS feed title
                if ("Google News".equalsIgnoreCase(title.trim())) {
                    continue;
                }

                // Publication date is mandatory for trading news
                if (publishedAt == null || publishedAt.isBlank()) {

                    log.warn(
                            "Skipping article without publication date: {}",
                            title
                    );

                    continue;
                }

                // -------------------------------------------------
                // Freshness validation
                // Keep only news from the last 7 days
                // -------------------------------------------------

                try {

                    ZonedDateTime publishedTime =
                            ZonedDateTime.parse(
                                    publishedAt,
                                    DateTimeFormatter.RFC_1123_DATE_TIME
                            );

                    ZonedDateTime now = ZonedDateTime.now();

                    ZonedDateTime cutoff = now.minusDays(7);

                    if (publishedTime.isBefore(cutoff)) {

                        log.info(
                                "Skipping stale news: {} | Published: {} | Cutoff: {}",
                                title,
                                publishedAt,
                                cutoff
                        );

                        continue;
                    }

                    if (publishedTime.isAfter(now)) {

                        log.warn(
                                "Skipping future-dated news: {} | Published: {}",
                                title,
                                publishedAt
                        );

                        continue;
                    }

                    // Prevent future-dated articles
                    if (publishedTime.isAfter(ZonedDateTime.now())) {

                        log.warn(
                                "Skipping future-dated news: {} | Published: {}",
                                title,
                                publishedAt
                        );

                        continue;
                    }

                } catch (Exception ex) {

                    log.warn(
                            "Unable to parse publication date [{}] for article [{}]. Skipping.",
                            publishedAt,
                            title
                    );

                    continue;
                }

                // -------------------------------------------------
                // Clean RSS HTML description
                // -------------------------------------------------

                String cleanDescription =
                        cleanHtml(description);

                // -------------------------------------------------
                // Build NewsArticle
                // -------------------------------------------------

                NewsArticle article =
                        NewsArticle.builder()
                                .title(title.trim())
                                .description(cleanDescription)
                                .publishedAt(publishedAt.trim())
                                .source(
                                        source != null && !source.isBlank()
                                                ? source.trim()
                                                : "Google News"
                                )
                                .url(
                                        url != null
                                                ? url.trim()
                                                : ""
                                )
                                .tickers(List.of())
                                .build();

                articles.add(article);

                log.info(
                        "Accepted news: {} | Source: {} | Published: {}",
                        article.getTitle(),
                        article.getSource(),
                        article.getPublishedAt()
                );

                // Maximum 5 valid articles
                if (articles.size() >= 5) {
                    break;
                }
            }

        } catch (Exception ex) {

            log.error(
                    "Unable to parse Google News RSS",
                    ex
            );
        }

        return articles;
    }

    private String cleanHtml(String value) {

        if (value == null || value.isBlank()) {
            return "";
        }

        return value
                .replaceAll("<[^>]*>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .trim();
    }

    private String getElementText(
            Element parent,
            String tagName) {

        NodeList nodes =
                parent.getElementsByTagNameNS("*", tagName);

        if (nodes.getLength() == 0) {

            // Fallback for non-namespaced XML
            nodes = parent.getElementsByTagName(tagName);
        }

        if (nodes.getLength() == 0) {
            return "";
        }

        return nodes.item(0)
                .getTextContent()
                .trim();
    }
}