package com.ram.trading.newsanalysis.service.impl;


import com.ram.trading.newsanalysis.entity.Instrument;
import com.ram.trading.newsanalysis.repo.InstrumentMasterRepository;
import com.ram.trading.newsanalysis.service.InstrumentMetadataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class InstrumentMetadataServiceImpl
        implements InstrumentMetadataService {

    private final InstrumentMasterRepository repository;

    @Override
    public Mono<Instrument> getInstrument(String symbol) {

        return Mono.fromCallable(() ->
                repository.findByTradingSymbol(symbol))
                .flatMap(instrument -> {

                    if (instrument == null) {
                        return Mono.error(
                                new RuntimeException(
                                        "Instrument not found : " + symbol));
                    }

                    return Mono.just(instrument);
                });
    }

    @Override
    public Mono<String> getCompanyName(String symbol) {

        return getInstrument(symbol)
                .map(Instrument::getCompanyName);
    }
}