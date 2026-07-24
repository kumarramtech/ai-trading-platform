package com.ram.trading.signal.engine.indicator.service;

import com.ram.trading.signal.engine.client.StockServiceClient;

import com.ram.trading.signal.engine.contant.IndicatorType;
import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import com.ram.trading.signal.engine.dto.history.HistoricalPriceResponse;
import com.ram.trading.signal.engine.dto.indicator.Candle;
import com.ram.trading.signal.engine.indicator.ema.EMACalculator;
import com.ram.trading.signal.engine.indicator.macd.MACDCalculator;
import com.ram.trading.signal.engine.indicator.macd.MACDIndicator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.time.ZoneOffset;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TechnicalIndicatorServiceImpl
        implements TechnicalIndicatorService {

    private final StockServiceClient stockServiceClient;

    private final IndicatorService indicatorService;


    @Override
    public Mono<TechnicalIndicatorResponse> calculate(String symbol) {

        log.info("==================================================");
        log.info("TECHNICAL INDICATOR CALCULATION STARTED");
        log.info("Symbol : {}", symbol);
        log.info("==================================================");

        return stockServiceClient
                .getHistoricalPrices(symbol)

                .doOnNext(price ->
                        log.info("Historical Candle : Date={}, Close={}",
                                price.getTradeDate(),
                                price.getClosePrice()))

                .collectList()

                .flatMap(prices -> {

                    log.info("Historical Candle Count : {}", prices.size());

                    if (prices.isEmpty()) {

                        log.warn("No historical candles found for {}", symbol);

                        return Mono.empty();
                    }

                    List<Candle> candles = toCandles(prices);

                    try {

                        TechnicalIndicatorResponse response =
                                buildResponse(symbol, candles);

                        log.info("Technical Indicators Calculated Successfully");
                        log.info("RSI      : {}", response.getRsi14());
                        log.info("EMA20    : {}", response.getEma20());
                        log.info("EMA50    : {}", response.getEma50());
                        log.info("SMA20    : {}", response.getSma20());
                        log.info("SMA50    : {}", response.getSma50());
                        log.info("MACD     : {}", response.getMacd());

                        return Mono.just(response);

                    } catch (IllegalArgumentException ex) {

                        log.error("Indicator Calculation Failed", ex);

                        return Mono.empty();
                    }

                })

                .doOnSuccess(response -> {

                    if (response == null) {

                        log.warn("TechnicalIndicatorService returned NULL/EMPTY");

                    } else {

                        log.info("TechnicalIndicatorService completed successfully.");
                    }

                })

                .doOnTerminate(() ->
                        log.info("TECHNICAL INDICATOR CALCULATION COMPLETED"));
    }

    private List<Candle> toCandles(
            List<HistoricalPriceResponse> prices) {

        return prices.stream()

                .map(price -> Candle.builder()

                        .dateTime(price.getTradeDate()
                                .atStartOfDay()
                                .atOffset(ZoneOffset.UTC))

                        .open(price.getOpenPrice())

                        .high(price.getHighPrice())

                        .low(price.getLowPrice())

                        .close(price.getClosePrice())

                        .volume(price.getVolume())

                        .openInterest(price.getOpenInterest())

                        .build())

                .toList();
    }

    private TechnicalIndicatorResponse buildResponse(
            String symbol,
            List<Candle> candles) {

        var rsi =
                indicatorService.calculate(
                        IndicatorType.RSI,
                        candles,
                        14);

        var ema20 =
                indicatorService.calculate(
                        IndicatorType.EMA,
                        candles,
                        20);

        var ema50 =
                indicatorService.calculate(
                        IndicatorType.EMA,
                        candles,
                        50);

        var sma20 =
                indicatorService.calculate(
                        IndicatorType.SMA,
                        candles,
                        20);

        var sma50 =
                indicatorService.calculate(
                        IndicatorType.SMA,
                        candles,
                        50);

        var macd =
                new MACDIndicator(
                        new MACDCalculator(
                                new EMACalculator()))
                        .calculate(candles);

        TechnicalIndicatorResponse response =
                new TechnicalIndicatorResponse();

        response.setSymbol(symbol);

        response.setRsi14(rsi.getValue().doubleValue());

        response.setEma20(ema20.getValue().doubleValue());

        response.setEma50(ema50.getValue().doubleValue());

        response.setSma20(sma20.getValue().doubleValue());

        response.setSma50(sma50.getValue().doubleValue());

        response.setMacd(macd.getMacd().doubleValue());

        // NEW
        response.setSignalLine(macd.getSignal().doubleValue());

        response.setClosePrice(
                candles.get(candles.size() - 1)
                        .getClose()
                        .doubleValue());

        log.info(
                "MACD Debug -> Symbol={}, MACD={}, Signal={}, Histogram={}",
                symbol,
                macd.getMacd(),
                macd.getSignal(),
                macd.getHistogram());

        return response;
    }
}