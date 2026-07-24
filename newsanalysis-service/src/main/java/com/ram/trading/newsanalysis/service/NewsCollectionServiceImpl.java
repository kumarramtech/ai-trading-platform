package com.ram.trading.newsanalysis.service;

import com.ram.trading.newsanalysis.client.NewsApiClient;
import com.ram.trading.newsanalysis.dto.ExchangeConstants;
import com.ram.trading.newsanalysis.repo.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
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

    @Override
    public Mono<List<String>> collectNews(String symbol) {

        return Mono.fromCallable(() ->
                        instrumentRepository
                                .findByTradingSymbolAndExchangeAndIsActive(
                                        symbol,
                                        ExchangeConstants.NSE,
                                        true))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(optionalInstrument -> {

                    if (optionalInstrument.isEmpty()) {

                        log.warn("Instrument not found for {}", symbol);

                        return Mono.just(List.of());
                    }

                    Instrument instrument = optionalInstrument.get();

                    String companyName = instrument.getCompanyName();

                    if (companyName == null || companyName.isBlank()) {

                        log.warn("Company name missing for symbol [{}]. Falling back to symbol.", symbol);

                        companyName = symbol;
                    }

                    log.info("Resolved Symbol [{}] -> [{}]", symbol, companyName);

                    return newsApiClient.getLatestHeadlines(companyName);

                });
    }
}