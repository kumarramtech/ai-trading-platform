package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.client.NotificationClient;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class TrailingStopServiceLiveCalculationTest {

    private TrailingStopService service;

    @BeforeEach
    void setUp() {
        service = new TrailingStopService(
                mock(PaperTradeRepository.class), mock(NotificationClient.class));
    }

    @Test
    void buyAtHalfRMovesStopToBreakEven() {
        Optional<BigDecimal> result = service.calculateLiveTrailingStop(
                "BUY", bd("100"), bd("90"), bd("90"), 105.0d);
        assertEquals(bd("100.00"), result.orElseThrow());
    }

    @Test
    void sellAtHalfRMovesStopToBreakEven() {
        Optional<BigDecimal> result = service.calculateLiveTrailingStop(
                "SELL", bd("100"), bd("110"), bd("110"), 95.0d);
        assertEquals(bd("100.00"), result.orElseThrow());
    }

    @Test
    void buyAtPointEightRLocksQuarterR() {
        Optional<BigDecimal> result = service.calculateLiveTrailingStop(
                "BUY", bd("100"), bd("90"), bd("90"), 108.0d);
        assertEquals(bd("102.50"), result.orElseThrow());
    }

    @Test
    void sellAtPointEightRLocksQuarterR() {
        Optional<BigDecimal> result = service.calculateLiveTrailingStop(
                "SELL", bd("100"), bd("110"), bd("110"), 92.0d);
        assertEquals(bd("97.50"), result.orElseThrow());
    }

    @Test
    void doesNotWeakenAnExistingStop() {
        assertTrue(service.calculateLiveTrailingStop(
                "BUY", bd("100"), bd("90"), bd("103"), 108.0d).isEmpty());
        assertTrue(service.calculateLiveTrailingStop(
                "SELL", bd("100"), bd("110"), bd("97"), 92.0d).isEmpty());
    }

    @Test
    void doesNotTrailBeforeHalfROrWithInvalidRiskAndSide() {
        assertTrue(service.calculateLiveTrailingStop(
                "BUY", bd("100"), bd("90"), bd("90"), 104.99d).isEmpty());
        assertTrue(service.calculateLiveTrailingStop(
                "BUY", bd("100"), bd("101"), bd("90"), 110.0d).isEmpty());
        assertTrue(service.calculateLiveTrailingStop(
                "HOLD", bd("100"), bd("90"), bd("90"), 110.0d).isEmpty());
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
