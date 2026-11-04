package com.ram.trading.signal.engine.service.longterm;

import com.ram.trading.signal.engine.client.NotificationClient;
import com.ram.trading.signal.engine.client.StockServiceClient;
import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.service.longterm.LongTermMonitorService.Level;
import com.ram.trading.signal.engine.service.replay.MomentumPaperService;
import com.ram.trading.signal.engine.service.replay.MomentumPaperService.Holding;
import com.ram.trading.signal.engine.service.replay.MomentumSignalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LongTermMonitorServiceTest {

    private static final LocalDate WED = LocalDate.of(2026, 11, 4);
    private final Holding abc = new Holding(1, "ABC", 10, 100.0, 0.0);

    private MomentumPaperService paper;
    private MomentumSignalService signal;
    private StockServiceClient stocks;
    private NotificationClient notifications;
    private LongTermMonitorService monitor;

    @BeforeEach
    void setUp() {
        paper = mock(MomentumPaperService.class);
        signal = mock(MomentumSignalService.class);
        stocks = mock(StockServiceClient.class);
        notifications = mock(NotificationClient.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(notifications.sendNotification(any())).thenReturn(Mono.empty());
        when(jdbc.queryForList(anyString(), eq(Double.class), any(Object[].class))).thenReturn(List.of());
        when(paper.holdings()).thenReturn(List.of(abc));
        monitor = new LongTermMonitorService(paper, signal, stocks, notifications, jdbc);
        ReflectionTestUtils.setField(monitor, "stopPct", 15.0);
    }

    private void priceAbc(double price) {
        StockResponse r = new StockResponse();
        r.setSymbol("ABC");
        r.setPrice(price);
        when(stocks.getStockPrice("ABC")).thenReturn(Mono.just(r));
    }

    @Test
    void levels() {
        assertEquals(Level.OK, LongTermMonitorService.level(100, 95, 10, 15));
        assertEquals(Level.WARN, LongTermMonitorService.level(100, 89, 10, 15));
        assertEquals(Level.STOP, LongTermMonitorService.level(100, 85, 10, 15));
        assertEquals(Level.WARN, LongTermMonitorService.level(100, 70, 10, 0), "stop 0 = alerts only");
    }

    @Test
    void stopTouchedIntradayAlertsOnceButSellsOnlyAtTheStopCheck() {
        priceAbc(80);

        monitor.tick(WED.atTime(11, 0));
        monitor.tick(WED.atTime(11, 1));
        verify(paper, never()).sell(any(), anyDouble(), anyString());
        verify(notifications, times(1)).sendNotification(any());

        monitor.tick(WED.atTime(15, 20, 30));
        verify(paper, times(1)).sell(eq(abc), eq(80.0), anyString());
        monitor.tick(WED.atTime(15, 21, 30));
        verify(paper, times(1)).sell(any(), anyDouble(), anyString());
    }

    @Test
    void recoveredByTheStopCheckIsKept() {
        priceAbc(80);
        monitor.tick(WED.atTime(11, 0));
        priceAbc(90);
        monitor.tick(WED.atTime(15, 20, 30));

        verify(paper, never()).sell(any(), anyDouble(), anyString());
    }

    @Test
    void nothingOnWeekendsOrOutsideTheSession() {
        priceAbc(80);
        monitor.tick(LocalDateTime.of(2026, 11, 7, 15, 20, 30));   // Saturday
        monitor.tick(WED.atTime(15, 45));

        verify(stocks, never()).getStockPrice(anyString());
        verify(paper, never()).sell(any(), anyDouble(), anyString());
    }

    @Test
    void weeklyEntryListsBeforeTheOpenAndBuysAfterIt() {
        ReflectionTestUtils.setField(monitor, "weeklyEntry", true);
        when(paper.holdings()).thenReturn(List.of());
        when(signal.cashMonth(YearMonth.of(2026, 11))).thenReturn(true);
        when(signal.riskOnNow()).thenReturn(true);
        MomentumSignalService.MonthlySignal listed = new MomentumSignalService.MonthlySignal("2026-11", WED.minusDays(1),
                true, 24000, 23700, 30000, List.of(new MomentumSignalService.Pick(1, "ABC", 2.0, 100, 10, 1000)),
                List.of(), List.of("ABC"), List.of(), List.of());
        when(signal.publish(true)).thenReturn(Optional.of(listed));
        when(paper.rebalance(true)).thenReturn(Optional.of("bought"));

        monitor.tick(WED.atTime(8, 53));
        monitor.tick(WED.atTime(8, 54));
        verify(signal, times(1)).publish(true);
        verify(paper, never()).rebalance(anyBoolean());

        monitor.tick(WED.atTime(9, 20, 10));
        monitor.tick(WED.atTime(9, 21, 10));
        verify(paper, times(1)).rebalance(true);
    }

    @Test
    void weeklyEntryStaysInCashWhileNiftyIsBelowItsAverage() {
        ReflectionTestUtils.setField(monitor, "weeklyEntry", true);
        when(signal.cashMonth(YearMonth.of(2026, 11))).thenReturn(true);
        when(signal.riskOnNow()).thenReturn(false);

        monitor.tick(WED.atTime(8, 53));
        monitor.tick(WED.atTime(9, 20, 10));

        verify(signal, never()).publish(anyBoolean());
        verify(paper, never()).rebalance(anyBoolean());
    }
}
