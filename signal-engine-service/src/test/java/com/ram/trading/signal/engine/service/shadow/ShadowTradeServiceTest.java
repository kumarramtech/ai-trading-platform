package com.ram.trading.signal.engine.service.shadow;

import com.ram.trading.signal.engine.dto.TradingSignal;
import com.ram.trading.signal.engine.entity.ShadowTrade;
import com.ram.trading.signal.engine.repo.ShadowTradeRepository;
import com.ram.trading.signal.engine.service.ExecutionPlanCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ShadowTradeServiceTest {

    private ShadowTradeRepository repository;
    private ShadowTradeService service;

    @BeforeEach
    void setUp() {
        repository = mock(ShadowTradeRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findByEntryTimeGreaterThanEqual(any())).thenReturn(List.of());
        service = new ShadowTradeService(repository, mock(ExecutionPlanCalculator.class));
    }

    private TradingSignal extendedShort() {
        return TradingSignal.builder()
                .symbol("RENUKA").signal("SELL").strategy("JUDAS").marketRegime("SIDEWAYS")
                .entryPrice(100.0).stopLoss(101.0).targetPrice(98.0)
                .build();
    }

    @Test
    void shadowClosesAtTargetWithItsRMultipleAndExcursions() {
        service.recordRejectedSignal(extendedShort(), "EXTENDED");

        service.onTick("RENUKA", 100.5);   // -0.5R adverse
        service.onTick("RENUKA", 97.9);    // beyond target

        ShadowTrade closed = lastSaved(2);
        assertEquals("TARGET", closed.getStatus());
        assertEquals(98.0, closed.getExitPrice());
        assertEquals(2.0, closed.getRMultiple());
        assertEquals(0.5, closed.getMaeR());
        assertEquals("EXTENDED", closed.getRejectedBy());
    }

    @Test
    void shadowClosesAtItsStop() {
        service.recordRejectedSignal(extendedShort(), "EXTENDED");

        service.onTick("RENUKA", 101.2);

        ShadowTrade closed = lastSaved(2);
        assertEquals("STOP", closed.getStatus());
        assertEquals(-1.0, closed.getRMultiple());
    }

    @Test
    void sameSetupIsRecordedOncePerDayAndOtherSymbolsAreIgnored() {
        service.recordRejectedSignal(extendedShort(), "EXTENDED");
        service.recordRejectedSignal(extendedShort(), "EXTENDED");
        service.onTick("TCS", 50.0);

        verify(repository, timeout(1000).times(1)).save(any());
    }

    @Test
    void openShadowsCloseAtTheLastTickPriceAtMarketClose() {
        service.recordRejectedSignal(extendedShort(), "EXTENDED");
        service.onTick("RENUKA", 99.5);

        service.closeAllAtMarketClose();

        ShadowTrade closed = lastSaved(2);
        assertEquals("MARKET_CLOSE", closed.getStatus());
        assertEquals(0.5, closed.getRMultiple());

        // A second pass (the scheduler runs every minute) does nothing.
        service.closeAllAtMarketClose();
        verify(repository, after(300).times(2)).save(any());
    }

    private ShadowTrade lastSaved(int expectedSaves) {
        ArgumentCaptor<ShadowTrade> captor = ArgumentCaptor.forClass(ShadowTrade.class);
        verify(repository, timeout(1000).times(expectedSaves)).save(captor.capture());
        return captor.getValue();
    }
}
