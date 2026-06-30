package com.ram.trading.stock.service.history;

import com.ram.trading.stock.client.UpstoxHistoricalCandleClient;
import com.ram.trading.stock.entity.Instrument;
import com.ram.trading.stock.service.auth.UpstoxTokenService;
import com.ram.trading.stock.service.instument.InstrumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.ram.trading.stock.dto.HistoricalCandleResponse;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoricalCandleServiceImpl implements HistoricalCandleService {

    private final InstrumentService instrumentService;
    private final UpstoxHistoricalCandleClient client;
    private final UpstoxTokenService tokenService;

    @Override
    public Mono<HistoricalCandleResponse> getHistoricalCandles(
            String symbol,
            String interval,
            LocalDate from,
            LocalDate to) {

        Instrument instrument = instrumentService.getActiveInstrument(symbol);

        log.info("Trading Symbol : {}", symbol);
        log.info("Instrument Key : {}", instrument.getInstrumentKey());

        return client.getHistoricalCandles(
                        tokenService.getAccessToken(),
                        instrument.getInstrumentKey(),
                        interval,
                        from.toString(),
                        to.toString())
                .map(response -> {
                    log.info("Upstox Response : {}", response);

                    return HistoricalCandleResponse.builder()
                            .symbol(symbol)
                            .build();

                });
    }
}