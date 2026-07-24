package com.ram.trading.newsanalysis.service;

import com.ram.trading.newsanalysis.entity.Instrument;
import reactor.core.publisher.Mono;

public interface InstrumentMetadataService {

    Mono<Instrument> getInstrument(String symbol);

    Mono<String> getCompanyName(String symbol);
}