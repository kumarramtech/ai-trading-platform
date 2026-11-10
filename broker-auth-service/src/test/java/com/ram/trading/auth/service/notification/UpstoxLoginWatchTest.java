package com.ram.trading.auth.service.notification;

import com.ram.trading.auth.service.entity.BrokerSession;
import com.ram.trading.auth.service.exception.BrokerSessionNotFoundException;
import com.ram.trading.auth.service.service.BrokerSessionService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpstoxLoginWatchTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 9);

    private final BrokerSessionService sessions = mock(BrokerSessionService.class);
    private final BrokerAuthNotifier notifier = mock(BrokerAuthNotifier.class);
    private final UpstoxLoginWatch watch = new UpstoxLoginWatch(sessions, notifier);

    private void sessionExpiring(LocalDateTime expiresAt) {
        BrokerSession session = new BrokerSession();
        session.setExpiresAt(expiresAt);
        when(sessions.getBrokerSession("UPSTOX")).thenReturn(session);
    }

    @Test
    void remindsWhenTheTokenExpiredOvernight() {
        sessionExpiring(MONDAY.atTime(3, 30));

        watch.check(MONDAY.atTime(7, 0));

        verify(notifier).loginNeeded(contains("expired"));
    }

    @Test
    void remindsWhenNoSessionIsStored() {
        when(sessions.getBrokerSession("UPSTOX")).thenThrow(new BrokerSessionNotFoundException("UPSTOX"));

        watch.check(MONDAY.atTime(7, 0));

        verify(notifier).loginNeeded(contains("No Upstox session"));
    }

    @Test
    void staysQuietOnceLoggedIn() {
        sessionExpiring(MONDAY.plusDays(1).atTime(3, 30));

        watch.check(MONDAY.atTime(7, 0));

        verify(notifier, never()).loginNeeded(anyString());
    }

    @Test
    void morningCheckStillWarnsWhenTheCheckItselfFails() {
        when(sessions.getBrokerSession("UPSTOX")).thenThrow(new IllegalStateException("database down"));

        watch.morningCheck();

        verify(notifier).loginNeeded(contains("IllegalStateException"));
    }

    @Test
    void tokenFailuresAlertAtMostEveryThirtyMinutes() {
        RuntimeException ex = new BrokerSessionNotFoundException("UPSTOX");

        watch.tokenRequestFailed(ex, MONDAY.atTime(10, 0));
        watch.tokenRequestFailed(ex, MONDAY.atTime(10, 29));
        verify(notifier, times(1)).tokenRequestFailed(anyString());

        watch.tokenRequestFailed(ex, MONDAY.atTime(10, 30));
        verify(notifier, times(2)).tokenRequestFailed(anyString());
    }

    @Test
    void tokenFailuresAreQuietOvernightAndAtWeekends() {
        RuntimeException ex = new BrokerSessionNotFoundException("UPSTOX");

        watch.tokenRequestFailed(ex, MONDAY.atTime(3, 45));
        watch.tokenRequestFailed(ex, MONDAY.atTime(15, 30));
        watch.tokenRequestFailed(ex, MONDAY.minusDays(1).atTime(11, 0));

        verify(notifier, never()).tokenRequestFailed(anyString());
    }

    @Test
    void onAMarketHolidaySendsOneClosedNoteAndNoLoginReminder() {
        com.ram.trading.auth.service.calendar.TradingCalendar calendar =
                mock(com.ram.trading.auth.service.calendar.TradingCalendar.class);
        when(calendar.isTradingDay()).thenReturn(false);
        when(calendar.reason()).thenReturn("NSE holiday: Diwali-Balipratipada");
        watch.setCalendar(calendar);

        watch.morningCheck();
        watch.morningCheck();

        verify(notifier, times(1)).marketHoliday(contains("Diwali"));
        verify(notifier, never()).loginNeeded(anyString());
    }
}
