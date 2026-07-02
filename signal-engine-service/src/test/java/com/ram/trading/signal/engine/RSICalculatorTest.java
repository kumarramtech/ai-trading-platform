package com.ram.trading.signal.engine;

import com.ram.trading.signal.engine.dto.indicator.Candle;
import com.ram.trading.signal.engine.indicator.rsi.RSICalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RSICalculatorTest {

    private RSICalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new RSICalculator();
    }

    @Test
    void shouldReturnHundredWhenMarketAlwaysGoesUp() {

        List<Candle> candles = createIncreasingCandles();

        BigDecimal rsi =
                calculator.calculate(candles, 14);

        assertEquals(
                0,
                rsi.compareTo(BigDecimal.valueOf(100)));
    }

    @Test
    void shouldReturnZeroWhenMarketAlwaysFalls() {

        List<Candle> candles = createDecreasingCandles();

        BigDecimal rsi =
                calculator.calculate(candles, 14);

        assertEquals(
                0,
                rsi.compareTo(BigDecimal.ZERO));
    }

    @Test
    void shouldReturnValidRSIForMixedMarket() {

        List<Candle> candles = createMixedCandles();

        BigDecimal rsi =
                calculator.calculate(candles, 14);

        assertTrue(
                rsi.compareTo(BigDecimal.ZERO) >= 0);

        assertTrue(
                rsi.compareTo(BigDecimal.valueOf(100)) <= 0);
    }

    @Test
    void shouldThrowExceptionForInsufficientCandles() {

        List<Candle> candles =
                createIncreasingCandles()
                        .subList(0, 10);

        assertThrows(
                IndexOutOfBoundsException.class,
                () -> calculator.calculate(candles, 14));
    }

    private List<Candle> createIncreasingCandles() {

        List<Candle> candles = new ArrayList<>();

        BigDecimal price = BigDecimal.valueOf(100);

        for (int i = 0; i < 20; i++) {

            candles.add(create(price));

            price = price.add(BigDecimal.ONE);
        }

        return candles;
    }

    private List<Candle> createDecreasingCandles() {

        List<Candle> candles = new ArrayList<>();

        BigDecimal price = BigDecimal.valueOf(100);

        for (int i = 0; i < 20; i++) {

            candles.add(create(price));

            price = price.subtract(BigDecimal.ONE);
        }

        return candles;
    }

    private List<Candle> createMixedCandles() {

        double[] prices = {
                100,101,102,101,100,
                99,100,101,103,102,
                101,102,103,104,103,
                102,101,102,103,104
        };

        List<Candle> candles = new ArrayList<>();

        for (double price : prices) {
            candles.add(create(BigDecimal.valueOf(price)));
        }

        return candles;
    }

    private Candle create(BigDecimal close) {

        return Candle.builder()
                .dateTime(OffsetDateTime.now())
                .open(close)
                .high(close)
                .low(close)
                .close(close)
                .volume(1000L)
                .openInterest(0L)
                .build();
    }

}