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
                mock(PaperTradeRepository.class), mock(NotificationClient.class),
                new com.ram.trading.signal.engine.service.charges.IntradayChargesCalculator());
    }

    @Test
    void buyAtOneRMovesStopToBreakEven() {
        Optional<BigDecimal> result = service.calculateLiveTrailingStop(
                "BUY", bd("100"), bd("90"), bd("90"), 110.0d);
        assertEquals(bd("100.00"), result.orElseThrow());
    }

    @Test
    void sellAtOneRMovesStopToBreakEven() {
        Optional<BigDecimal> result = service.calculateLiveTrailingStop(
                "SELL", bd("100"), bd("110"), bd("110"), 90.0d);
        assertEquals(bd("100.00"), result.orElseThrow());
    }

    @Test
    void buyAtOnePointThreeRLocksQuarterR() {
        Optional<BigDecimal> result = service.calculateLiveTrailingStop(
                "BUY", bd("100"), bd("90"), bd("90"), 113.0d);
        assertEquals(bd("102.50"), result.orElseThrow());
    }

    @Test
    void sellAtOnePointThreeRLocksQuarterR() {
        Optional<BigDecimal> result = service.calculateLiveTrailingStop(
                "SELL", bd("100"), bd("110"), bd("110"), 87.0d);
        assertEquals(bd("97.50"), result.orElseThrow());
    }

    @Test
    void doesNotWeakenAnExistingStop() {
        assertTrue(service.calculateLiveTrailingStop(
                "BUY", bd("100"), bd("90"), bd("103"), 113.0d).isEmpty());
        assertTrue(service.calculateLiveTrailingStop(
                "SELL", bd("100"), bd("110"), bd("97"), 87.0d).isEmpty());
    }

    @Test
    void doesNotTrailBeforeOneROrWithInvalidRiskAndSide() {
        assertTrue(service.calculateLiveTrailingStop(
                "BUY", bd("100"), bd("90"), bd("90"), 109.99d).isEmpty());
        assertTrue(service.calculateLiveTrailingStop(
                "BUY", bd("100"), bd("101"), bd("90"), 110.0d).isEmpty());
        assertTrue(service.calculateLiveTrailingStop(
                "HOLD", bd("100"), bd("90"), bd("90"), 110.0d).isEmpty());
    }

    @Test
    void halfRNoiseNoLongerArmsBreakEven() {
        // Replay of NBCC 2026-10-26: SELL 67.79, SL 68.05, moved to 67.65 (0.54R) then 67.56 (0.88R)
        assertTrue(service.calculateLiveTrailingStop(
                "SELL", bd("67.79"), bd("68.05"), bd("68.05"), 67.65d).isEmpty());
        assertTrue(service.calculateLiveTrailingStop(
                "SELL", bd("67.79"), bd("68.05"), bd("68.05"), 67.56d).isEmpty());
    }

    @Test
    void tightStopNeedsMinimumFavorableMoveBeforeTrailing() {
        // 1.33R but only a 0.40% move: below the 0.50% floor
        assertTrue(service.calculateLiveTrailingStop(
                "BUY", bd("100"), bd("99.70"), bd("99.70"), 100.40d).isEmpty());
        // 1.67R with a 0.50% move: trails, step 3 locks 0.50R
        assertEquals(bd("100.15"), service.calculateLiveTrailingStop(
                "BUY", bd("100"), bd("99.70"), bd("99.70"), 100.50d).orElseThrow());
    }

    @Test
    void breakEvenWithQuantityCoversRoundTripChargesAndExitSlippage() {
        // CRAFTSMAN 2026-10-27: 2 shares, 1.03R. Charges ~54.97 (27.49/share) + 0.03% slippage (3.31).
        BigDecimal stop = service.calculateLiveTrailingStop(
                "BUY", bd("11021.31"), bd("10907.85"), bd("10907.85"), 11138.0d, 2).orElseThrow();

        assertEquals(bd("11052.10"), stop);
    }

    @Test
    void sellBreakEvenWithQuantityIsBelowEntryByTheCosts() {
        // IFCI-like short: 520 shares at 60.62; costs ~0.13/share.
        BigDecimal stop = service.calculateLiveTrailingStop(
                "SELL", bd("60.62"), bd("61.05"), bd("61.05"), 60.10d, 520).orElseThrow();

        assertTrue(stop.compareTo(bd("60.62")) < 0, "short stop must be below entry: " + stop);
        assertTrue(stop.compareTo(bd("60.45")) > 0, "and only by the costs: " + stop);
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
