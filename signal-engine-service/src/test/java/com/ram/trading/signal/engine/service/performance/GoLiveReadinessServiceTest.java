package com.ram.trading.signal.engine.service.performance;

import com.ram.trading.signal.engine.entity.PaperTrade;
import com.ram.trading.signal.engine.entity.ShadowTrade;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import com.ram.trading.signal.engine.repo.ShadowTradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GoLiveReadinessServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 28);

    private PaperTradeRepository paperTradeRepository;
    private ShadowTradeRepository shadowTradeRepository;
    private GoLiveReadinessService service;

    @BeforeEach
    void setUp() {
        paperTradeRepository = mock(PaperTradeRepository.class);
        shadowTradeRepository = mock(ShadowTradeRepository.class);
        when(shadowTradeRepository.findByEntryTimeBetween(any(), any())).thenReturn(List.of());
        service = new GoLiveReadinessService(
                new StrategyPerformanceService(paperTradeRepository), shadowTradeRepository);
    }

    private static PaperTrade trade(int day, double net, double r, String strategy, String regime) {
        LocalDateTime exit = FROM.plusDays(day).atTime(11, 0);
        return PaperTrade.builder()
                .symbol("T" + day).signal("BUY").strategy(strategy).marketRegime(regime)
                .entryTime(exit.minusMinutes(30)).exitTime(exit)
                .profitLoss(net).grossProfitLoss(net + 57).charges(57.0).rMultiple(r)
                .build();
    }

    private void givenTrades(List<PaperTrade> trades) {
        when(paperTradeRepository.findByExitTimeBetweenOrderByExitTimeDesc(any(), any()))
                .thenReturn(trades);
    }

    @Test
    void notReadyWithTooFewTradesEvenWhenProfitable() {
        givenTrades(List.of(
                trade(0, 500, 2.0, "JUDAS", "SIDEWAYS"),
                trade(1, -250, -1.0, "ORB", "BULLISH")));

        GoLiveReadinessService.ReadinessReport report = service.report(FROM.plusDays(6));

        assertFalse(report.readyForLive());
        assertEquals(GoLiveReadinessService.CriterionStatus.FAIL, report.criteria().get(0).status());
        assertEquals(2, report.byRegime().size());
    }

    @Test
    void readyWhenEveryCriterionPasses() {
        // 60 trades: 3 wins of +2R for every 2 losses of -1R -> avg R +0.8, PF 3.
        List<PaperTrade> trades = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            boolean win = i % 5 < 3;
            trades.add(trade(i / 3, win ? 500 : -250, win ? 2.0 : -1.0, "JUDAS", "SIDEWAYS"));
        }
        givenTrades(trades);

        GoLiveReadinessService.ReadinessReport report = service.report(FROM.plusDays(27));

        assertTrue(report.readyForLive(), service.toText(report));
        report.criteria().forEach(c ->
                assertEquals(GoLiveReadinessService.CriterionStatus.PASS, c.status(), c.name()));
    }

    @Test
    void drawdownBeyondTenRFailsTheGate() {
        // 70 trades, profitable overall, but a 12-loss streak (12R) in the middle.
        List<PaperTrade> trades = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            boolean streak = i >= 20 && i < 32;
            boolean win = !streak && i % 2 == 0;
            trades.add(trade(i / 3, win ? 750 : -250, win ? 3.0 : -1.0, "ORB", "BULLISH"));
        }
        givenTrades(trades);

        GoLiveReadinessService.Criterion drawdown =
                service.report(FROM.plusDays(30)).criteria().get(3);

        assertEquals("Max drawdown", drawdown.name());
        assertEquals(GoLiveReadinessService.CriterionStatus.FAIL, drawdown.status(), drawdown.actual());
    }

    @Test
    void projectionScalesTheAverageRToOnePercentRisk() {
        // 10 trades over 10 weekdays (1/day) at +0.25R and Rs 50 net each.
        List<PaperTrade> trades = new ArrayList<>();
        LocalDate day = FROM;
        while (trades.size() < 10) {
            if (day.getDayOfWeek().getValue() <= 5) {
                PaperTrade t = trade(0, 50, 0.25, "JUDAS", "SIDEWAYS");
                t.setExitTime(day.atTime(11, 0));
                t.setEntryTime(day.atTime(10, 0));
                trades.add(t);
            }
            day = day.plusDays(1);
        }
        givenTrades(trades);

        GoLiveReadinessService.Projection p = service.report(day.minusDays(1)).projection();

        assertEquals(1.0, p.tradesPerDay(), 0.01);
        assertEquals(21 * 50.0, p.monthlyAtCurrentRisk(), 0.01);
        assertEquals(0.25 * 1000 * 21, p.monthlyAtScaledRisk(), 0.01);
        // Rs 10,000 at Rs 1,000 risk and 21 trades a month needs ~0.48R per trade.
        assertEquals(0.48, p.averageRNeededForTarget(), 0.01);
    }

    @Test
    void shadowFilterVerdictsCompareBlockedSetupsWithTheCostOfTrading() {
        givenTrades(List.of());
        List<ShadowTrade> shadows = new ArrayList<>();
        shadows.addAll(Collections.nCopies(10, shadow("EXTENDED", -0.4)));       // blocked losers
        shadows.addAll(Collections.nCopies(10, shadow("REGIME_GUARD", 0.9)));    // blocked winners
        shadows.addAll(Collections.nCopies(3, shadow("MIDDAY_SIDEWAYS", 1.0)));  // too few to judge
        when(shadowTradeRepository.findByEntryTimeBetween(any(), any())).thenReturn(shadows);

        List<GoLiveReadinessService.ShadowFilterStats> filters =
                service.report(FROM.plusDays(10)).shadowFilters();

        assertEquals(GoLiveReadinessService.ShadowVerdict.SAVING_MONEY, verdictOf(filters, "EXTENDED"));
        assertEquals(GoLiveReadinessService.ShadowVerdict.COSTING_MONEY, verdictOf(filters, "REGIME_GUARD"));
        assertEquals(GoLiveReadinessService.ShadowVerdict.INSUFFICIENT_DATA, verdictOf(filters, "MIDDAY_SIDEWAYS"));
    }

    @Test
    void textReportRendersWithoutAnyData() {
        givenTrades(List.of());

        String text = service.toText(service.report(FROM));

        assertTrue(text.contains("VERDICT: NOT READY"), text);
        assertTrue(text.contains("no closed shadow trades yet"), text);
    }

    private static ShadowTrade shadow(String filter, double r) {
        return ShadowTrade.builder().rejectedBy(filter).rMultiple(r).status("STOP").build();
    }

    private static GoLiveReadinessService.ShadowVerdict verdictOf(
            List<GoLiveReadinessService.ShadowFilterStats> filters, String name) {
        return filters.stream().filter(f -> f.filter().equals(name)).findFirst().orElseThrow().verdict();
    }
}
