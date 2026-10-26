package com.ram.trading.signal.engine.service.performance;

import com.ram.trading.signal.engine.entity.PaperTrade;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import com.ram.trading.signal.engine.service.performance.SetupEdgeGate.EdgeDecision;
import com.ram.trading.signal.engine.service.performance.StrategyPerformanceService.GroupStats;
import com.ram.trading.signal.engine.service.performance.StrategyPerformanceService.StrategyPerformanceReport;
import com.ram.trading.signal.engine.service.performance.StrategyPerformanceService.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StrategyPerformanceServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final List<PaperTrade> trades = new ArrayList<>();
    private StrategyPerformanceService service;
    private SetupEdgeGate gate;

    @BeforeEach
    void setUp() {
        PaperTradeRepository repository = mock(PaperTradeRepository.class);
        when(repository.findByExitTimeBetweenOrderByExitTimeDesc(any(), any())).thenAnswer(i -> trades);
        service = new StrategyPerformanceService(repository);
        ReflectionTestUtils.setField(service, "minTrades", 20);
        gate = new SetupEdgeGate(service);
    }

    /** A trade entered at the given IST time today, stamped in the JVM zone as the service does. */
    private void trade(String strategy, String side, int hour, int minute, double gross, double charges) {
        LocalDateTime entry = ZonedDateTime.of(LocalDate.now(IST), LocalTime.of(hour, minute), IST)
                .withZoneSameInstant(ZoneId.systemDefault())
                .toLocalDateTime();
        trades.add(PaperTrade.builder()
                .strategy(strategy)
                .signal(side)
                .entryTime(entry)
                .exitTime(entry.plusMinutes(20))
                .grossProfitLoss(gross)
                .charges(charges)
                .profitLoss(gross - charges)
                .plannedRisk(250.0)
                .rMultiple((gross - charges) / 250.0)
                .build());
    }

    @Test
    void reportsNetResultsPerStrategyAndTimeOfDay() {
        trade("ORB", "BUY", 9, 40, 400, 65);     // +335
        trade("ORB", "BUY", 9, 50, -250, 65);    // -315
        trade("ORB", "SELL", 13, 20, -250, 65);  // -315
        trade("JUDAS", "SELL", 10, 5, 500, 65);  // +435

        StrategyPerformanceReport report = service.report(LocalDate.now().minusDays(1), LocalDate.now());

        GroupStats overall = report.overall();
        assertEquals(4, overall.trades());
        assertEquals(400.0, overall.grossPnl(), 0.001);
        assertEquals(260.0, overall.charges(), 0.001);
        assertEquals(140.0, overall.netPnl(), 0.001);
        assertEquals(50.0, overall.winRatePct(), 0.001);
        assertEquals(Verdict.INSUFFICIENT_DATA, overall.verdict());
        // Equity in exit order: +335, +20, +455 (JUDAS 10:25), +140; deepest fall is 315.
        assertEquals(315.0, overall.maxDrawdown(), 0.001);
        assertEquals(770.0 / 630.0, overall.profitFactor(), 0.01);

        GroupStats orb = report.byStrategy().stream().filter(g -> g.group().equals("ORB")).findFirst().orElseThrow();
        assertEquals(3, orb.trades());
        assertEquals(-295.0, orb.netPnl(), 0.001);

        assertTrue(report.byStrategyAndEntryTime().stream()
                .anyMatch(g -> g.group().startsWith("ORB AFTERNOON") && g.trades() == 1));
        assertTrue(report.byStrategyAndEntryTime().stream()
                .anyMatch(g -> g.group().startsWith("ORB OPENING") && g.trades() == 2));
    }

    @Test
    void edgeGateBlocksAProvenLosingSetupOnly() {
        for (int i = 0; i < 20; i++) {
            trade("ORB", "BUY", 13, 10 + i, i % 3 == 0 ? 300 : -200, 65);   // loses on average
        }
        for (int i = 0; i < 5; i++) {
            trade("ORB", "BUY", 9, 35 + i, -200, 65);                       // too few to judge
        }

        EdgeDecision afternoon = gate.evaluate("ORB", LocalTime.of(13, 45));
        EdgeDecision opening = gate.evaluate("ORB", LocalTime.of(9, 45));

        assertFalse(afternoon.allowed());
        assertEquals(20, afternoon.trades());
        assertEquals(Verdict.NEGATIVE_EDGE, afternoon.verdict());
        assertTrue(opening.allowed(), "a bucket still being sampled stays open");
    }

    @Test
    void edgeGateKeepsAProfitableSetupOpen() {
        for (int i = 0; i < 25; i++) {
            trade("JUDAS", "SELL", 9, 31 + i, i % 2 == 0 ? 450 : -200, 65);
        }

        EdgeDecision judas = gate.evaluate("JUDAS", LocalTime.of(10, 0));

        assertTrue(judas.allowed());
        assertEquals(Verdict.POSITIVE_EDGE, judas.verdict());
    }
}
