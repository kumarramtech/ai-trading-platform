package com.ram.trading.stock.service.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.stock.client.UpstoxHistoricalCandleClient;
import com.ram.trading.stock.dto.history.Candle;
import com.ram.trading.stock.dto.history.UpstoxHistoricalResponse;
import com.ram.trading.stock.entity.Instrument;
import com.ram.trading.stock.mapper.CandleMapper;
import com.ram.trading.stock.service.auth.UpstoxTokenService;
import com.ram.trading.stock.service.instument.InstrumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.ram.trading.stock.dto.history.HistoricalCandleResponse;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoricalCandleServiceImpl implements HistoricalCandleService {

    private final InstrumentService instrumentService;
    private final UpstoxHistoricalCandleClient client;
    private final UpstoxTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final CandleMapper candleMapper;

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
                    ObjectMapper mapper = new ObjectMapper();

                    UpstoxHistoricalResponse upstoxResponse =  null;
                    try {
                        upstoxResponse = mapper.readValue(response,  UpstoxHistoricalResponse.class);
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }

                    List<Candle> candles = candleMapper.map(upstoxResponse.getData().getCandles());
                    List<Candle> sortedCandles = new ArrayList<>(candles);

                    sortedCandles.sort(
                            Comparator.comparing(Candle::getDateTime));

                    return HistoricalCandleResponse.builder()
                            .symbol(symbol)
                            .candles(sortedCandles)
                            .build();

                });
    }
}