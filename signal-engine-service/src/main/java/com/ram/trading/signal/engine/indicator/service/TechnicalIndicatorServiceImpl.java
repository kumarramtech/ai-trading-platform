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
    public Mono<TechnicalIndicatorResponse> calculate(
            String symbol) {

        return stockServiceClient

                .getHistoricalPrices(symbol)

                .collectList()
                .flatMap(prices -> {
                    if (prices.isEmpty()) {
                        return Mono.error(new IllegalStateException(
                                "No historical candles found for " + symbol));
                    }

                    List<Candle> candles = toCandles(prices);

                    return Mono.just(buildResponse(symbol, candles));
                });

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

        response.setClosePrice(
                candles.get(candles.size() - 1)
                        .getClose()
                        .doubleValue());

        return response;
    }
}