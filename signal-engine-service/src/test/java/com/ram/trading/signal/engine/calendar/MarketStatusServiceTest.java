package com.ram.trading.signal.engine.calendar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketStatusServiceTest {

    private static final LocalDate MON = LocalDate.of(2026, 11, 9);
    private static final LocalDate HOLIDAY = LocalDate.of(2026, 11, 10);

    private JdbcTemplate jdbc;
    private TradingCalendar calendar;
    private MarketStatusService service;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:status" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table minute_candle (id bigint auto_increment primary key, symbol varchar(64), "
                + "candle_minute timestamp, close_price double)");
        calendar = mock(TradingCalendar.class);
        when(calendar.check(any())).thenAnswer(inv -> {
            LocalDate d = inv.getArgument(0);
            if (d.equals(HOLIDAY)) {
                return Map.entry(false, "NSE holiday: Diwali-Balipratipada");
            }
            int dow = d.getDayOfWeek().getValue();
            return dow >= 6 ? Map.entry(false, "weekend") : Map.entry(true, "trading day");
        });
        when(calendar.upcomingHolidays(any())).thenReturn(Map.of(HOLIDAY, "Diwali-Balipratipada"));
        service = new MarketStatusService(calendar, jdbc);
    }

    @Test
    void liveInTheSessionWithAFreshCandle() {
        jdbc.update("insert into minute_candle (symbol, candle_minute, close_price) values ('NIFTY', ?, 25000)",
                MON.atTime(11, 58));
        MarketStatusService.MarketStatus s = service.status(MON.atTime(12, 0));
        assertEquals("OPEN", s.status());
        assertTrue(s.live());
        assertTrue(s.feedLive());
        assertNull(s.nextOpen());

        MarketStatusService.MarketStatus stale = service.status(MON.atTime(12, 10));
        assertTrue(stale.live());
        assertFalse(stale.feedLive(), "last candle 12 minutes old");
        assertEquals("Market is open, but our data feed is not live", stale.message());
    }

    @Test
    void holidayAndAfterCloseNameTheNextOpen() {
        MarketStatusService.MarketStatus h = service.status(HOLIDAY.atTime(10, 0));
        assertEquals("HOLIDAY", h.status());
        assertFalse(h.live());
        assertEquals("Market closed: Diwali-Balipratipada", h.message());
        assertEquals(LocalDate.of(2026, 11, 11).atTime(9, 15), h.nextOpen());

        MarketStatusService.MarketStatus after = service.status(MON.atTime(16, 0));
        assertEquals("AFTER_CLOSE", after.status());
        assertEquals(LocalDate.of(2026, 11, 11).atTime(9, 15), after.nextOpen(), "skips the Tuesday holiday");

        assertEquals("PRE_OPEN", service.status(MON.atTime(9, 5)).status());
        assertEquals("BEFORE_OPEN", service.status(MON.atTime(8, 0)).status());
        assertEquals(MON.atTime(9, 15), service.status(MON.atTime(8, 0)).nextOpen());
        assertEquals("WEEKEND", service.status(LocalDate.of(2026, 11, 14).atTime(11, 0)).status());
    }
}
