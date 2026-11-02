package com.ram.trading.signal.engine.risk;

import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.contant.SignalType;
import com.ram.trading.signal.engine.dto.rules.SignalGenerationRequest;
import com.ram.trading.signal.engine.entity.MinuteCandleEntity;
import com.ram.trading.signal.engine.entity.PaperTrade;
import com.ram.trading.signal.engine.repo.MinuteCandleRepository;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EntryExposureGuardTest {

    private final PaperTradeRepository paperTrades = mock(PaperTradeRepository.class);
    private final MinuteCandleRepository candles = mock(MinuteCandleRepository.class);
    private final LiveTradeSnapshotService snapshot = mock(LiveTradeSnapshotService.class);

    private EntryExposureGuard guard;
    private final List<PaperTrade> trades = new ArrayList<>();

    @BeforeEach
    void setUp() {
        guard = new EntryExposureGuard(paperTrades, candles, snapshot);
        when(paperTrades.findAll()).thenReturn(trades);
        when(paperTrades.findByStatus(SignalStatus.OPEN)).thenAnswer(inv ->
                trades.stream().filter(t -> t.getStatus() == SignalStatus.OPEN).toList());
        when(candles.findFirstBySymbolAndMinuteGreaterThanEqualOrderByMinuteAsc(eq("NIFTY"), any()))
                .thenReturn(Optional.empty());
        when(candles.findFirstBySymbolAndMinuteLessThanOrderByMinuteDesc(eq("NIFTY"), any()))
                .thenReturn(Optional.empty());
    }

    private static SignalGenerationRequest setup(String strategy, SignalType direction, Double moveFromOpen) {
        return SignalGenerationRequest.builder()
                .symbol("ABC")
                .setupStrategy(strategy)
                .setupDirection(direction)
                .stockChangePct(moveFromOpen)
                .build();
    }

    private PaperTrade open(String side, double entry, double stop, int qty, LocalDateTime entryTime) {
        PaperTrade t = PaperTrade.builder()
                .symbol("S" + trades.size())
                .signal(side)
                .entryPrice(entry)
                .stopLoss(stop)
                .currentStopLoss(stop)
                .quantity(qty)
                .entryTime(entryTime)
                .status(SignalStatus.OPEN)
                .build();
        trades.add(t);
        return t;
    }

    private void closedToday(double netPnl) {
        trades.add(PaperTrade.builder()
                .symbol("C" + trades.size())
                .signal("BUY")
                .entryTime(LocalDateTime.now().minusHours(3))
                .exitTime(LocalDateTime.now().minusHours(2))
                .profitLoss(netPnl)
                .status(SignalStatus.STOP_LOSS_HIT)
                .build());
    }

    @Test
    void orbLongAlreadyFarAboveTheOpenIsChased() {
        assertEquals("CHASED", guard.marketRejection(setup("ORB", SignalType.BUY, 2.4), LocalTime.of(11, 0)));
        assertNull(guard.marketRejection(setup("ORB", SignalType.BUY, 1.2), LocalTime.of(11, 0)));
    }

    @Test
    void chasedIsDirectional() {
        assertEquals("CHASED", guard.marketRejection(setup("ORB", SignalType.SELL, -2.0), LocalTime.of(11, 0)));
        // A short against a stock that ran up is a fade, not a chase.
        assertNull(guard.marketRejection(setup("ORB", SignalType.SELL, 5.9), LocalTime.of(11, 0)));
    }

    @Test
    void otherStrategiesAreNotJudgedOnTheirMoveFromOpen() {
        assertNull(guard.marketRejection(setup("JUDAS", SignalType.BUY, 3.0), LocalTime.of(9, 40)));
        assertNull(guard.marketRejection(setup("MIDDAY", SignalType.BUY, 3.0), LocalTime.of(11, 15)));
    }

    @Test
    void orbWaitsUntilTenOnANiftyGapDay() {
        MinuteCandleEntity firstToday = new MinuteCandleEntity();
        firstToday.setOpenPrice(23224.7);
        MinuteCandleEntity lastPrevious = new MinuteCandleEntity();
        lastPrevious.setClosePrice(23053.0);
        lastPrevious.setMinute(LocalDate.now().minusDays(3).atTime(15, 29));
        when(candles.findFirstBySymbolAndMinuteGreaterThanEqualOrderByMinuteAsc(eq("NIFTY"), any()))
                .thenReturn(Optional.of(firstToday));
        when(candles.findFirstBySymbolAndMinuteLessThanOrderByMinuteDesc(eq("NIFTY"), any()))
                .thenReturn(Optional.of(lastPrevious));

        assertEquals("GAP_DAY_WAIT", guard.marketRejection(setup("ORB", SignalType.BUY, 0.5), LocalTime.of(9, 35)));
        assertNull(guard.marketRejection(setup("ORB", SignalType.BUY, 0.5), LocalTime.of(10, 0)));
    }

    @Test
    void unknownGapDoesNotBlock() {
        assertNull(guard.marketRejection(setup("ORB", SignalType.BUY, 0.5), LocalTime.of(9, 35)));
    }

    @Test
    void fourthOpenLongIsRefusedButAShortIsNot() {
        LocalDateTime earlier = LocalDateTime.now().minusHours(1);
        open("BUY", 100, 99, 10, earlier);
        open("BUY", 100, 99, 10, earlier);
        open("BUY", 100, 99, 10, earlier);

        assertEquals("DIRECTION_CAP", guard.exposureRejection("ABC", "BUY"));
        assertNull(guard.exposureRejection("ABC", "SELL"));
    }

    @Test
    void thirdEntryInsideFifteenMinutesIsABurst() {
        open("BUY", 100, 99, 10, LocalDateTime.now().minusMinutes(5));
        open("SELL", 100, 101, 10, LocalDateTime.now().minusMinutes(2));

        assertEquals("ENTRY_BURST", guard.exposureRejection("ABC", "SELL"));
    }

    @Test
    void openRiskCountsTowardTheDailyLoss() {
        closedToday(-1000);
        LocalDateTime earlier = LocalDateTime.now().minusHours(1);
        open("BUY", 100, 96, 100, earlier);   // Rs 400 at risk
        open("SELL", 100, 104, 100, earlier); // Rs 400 at risk

        // -1000 - 800 - 250 = -2050 < -2000
        assertEquals("OPEN_RISK", guard.exposureRejection("ABC", "SELL"));
    }

    @Test
    void aStopTrailedIntoProfitCarriesNoRisk() {
        closedToday(-1000);
        LocalDateTime earlier = LocalDateTime.now().minusHours(1);
        open("BUY", 100, 101, 100, earlier);  // locked profit: 0 at risk
        open("SELL", 100, 104, 100, earlier); // Rs 400 at risk

        // -1000 - 400 - 250 = -1650
        assertNull(guard.exposureRejection("ABC", "BUY"));
        assertEquals(0.0, EntryExposureGuard.riskAtStop(true, 100.0, 101.0, 100));
    }

    @Test
    void holdIsNeverRefused() {
        assertNull(guard.exposureRejection("ABC", "HOLD"));
        assertNull(guard.exposureRejection("ABC", null));
    }
}
