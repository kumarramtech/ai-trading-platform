package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.EntryQualityResult;
import com.ram.trading.signal.engine.dto.TechnicalIndicatorResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EntryQualityServiceTest {

    private final EntryQualityService service = new EntryQualityService();

    @Test
    void missingFreshPriceIsClassifiedUnavailable() {
        EntryQualityResult result = service.evaluate(
                "TCS",
                SignalType.BUY,
                100.0,
                null,
                new TechnicalIndicatorResponse());

        assertEquals("UNAVAILABLE", result.getStatus());
        assertEquals("Invalid signal/fresh market price", result.getReason());
    }

    @Test
    void nonPositiveFreshPriceIsClassifiedUnavailable() {
        EntryQualityResult result = service.evaluate(
                "TCS",
                SignalType.SELL,
                100.0,
                0.0,
                new TechnicalIndicatorResponse());

        assertEquals("UNAVAILABLE", result.getStatus());
        assertEquals("Invalid signal/fresh market price", result.getReason());
    }

    @Test
    void largeDirectionalMoveIsClassifiedExtendedForObservation() {
        EntryQualityResult result = service.evaluate(
                "TCS",
                SignalType.BUY,
                100.0,
                101.25,
                new TechnicalIndicatorResponse());

        assertEquals("EXTENDED", result.getStatus());
        assertTrue(result.getDirectionalMovementPct() >= 1.0);
        assertNotNull(result.getReason());
    }
}
