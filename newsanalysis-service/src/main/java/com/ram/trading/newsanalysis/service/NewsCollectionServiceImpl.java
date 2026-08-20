package com.ram.trading.newsanalysis.service;

import com.ram.trading.newsanalysis.client.GoogleNewsClient;
import com.ram.trading.newsanalysis.client.NewsApiClient;
import com.ram.trading.newsanalysis.dto.ExchangeConstants;
import com.ram.trading.newsanalysis.dto.NewsArticle;
import com.ram.trading.newsanalysis.repo.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import com.ram.trading.newsanalysis.entity.Instrument;
import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Schedulers;


@Service
@RequiredArgsConstructor
@Slf4j
public class NewsCollectionServiceImpl implements NewsCollectionService {

    private final NewsApiClient newsApiClient;
    private final InstrumentRepository instrumentRepository;
    private final GoogleNewsClient googleNewsClient;

    @Override
    public Mono<List<NewsArticle>> collectNews(String symbol) {

        return Mono.fromCallable(() ->
                        instrumentRepository
                                .findByTradingSymbolAndExchangeAndIsActive(
                                        symbol,
                                        ExchangeConstants.NSE,
                                        true
                                )
                )
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(optionalInstrument -> {

                    if (optionalInstrument.isEmpty()) {

                        log.warn(
                                "Instrument not found for {}",
                                symbol
                        );

                        return Mono.just(List.of());
                    }

                    Instrument instrument = optionalInstrument.get();

                    String companyName = instrument.getCompanyName();

                    if (companyName == null || companyName.isBlank()) {

                        log.warn(
                                "Company name missing for symbol [{}]. " +
                                        "Using symbol for Google News lookup.",
                                symbol
                        );

                        companyName = symbol;
                    }

                    log.info(
                            "Resolved Symbol [{}] -> [{}]",
                            symbol,
                            companyName
                    );

                    return googleNewsClient
                            .getLatestHeadlines(companyName);
                });
    }

    @Override
    public Mono<List<NewsArticle>> collectMarketNews() {

        List<String> queries = List.of(
                "Indian stock market news",
                "Indian stock market trends today",
                "US stock market overnight news",
                "global market news affecting India",
                "Indian economy government policy news",
                "RBI announcements economy",
                "India sector stock market news"
        );

        return Flux.fromIterable(queries)
                .flatMap(googleNewsClient::getLatestHeadlines)
                .flatMapIterable(articles -> articles)
                .distinct(NewsArticle::getTitle)
                .collectList()
                .doOnSuccess(articles ->
                        log.info(
                                "Collected {} market news articles",
                                articles != null ? articles.size() : 0
                        )
                )
                .onErrorResume(ex -> {

                    log.error(
                            "Unable to collect market news",
                            ex
                    );

                    return Mono.just(List.of());
                });
    }
}