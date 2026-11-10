package com.ram.trading.stock.service.calendar;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketCalendarServiceTest {

    // Shape of the real Upstox response (2026): a closure, a special session, an MCX-only day.
    private static final String BODY = """
            {"status":"success","data":[
             {"date":"2026-11-10","description":"Diwali-Balipratipada","holiday_type":"TRADING_HOLIDAY",
              "closed_exchanges":["NSE","NFO","CDS","BSE","BFO","BCD"],"open_exchanges":[{"exchange":"MCX"}]},
             {"date":"2026-02-01","description":"Budget Day Session","holiday_type":"SPECIAL_TIMING",
              "closed_exchanges":[],"open_exchanges":[{"exchange":"NSE"}]},
             {"date":"2026-03-03","description":"MCX only","holiday_type":"TRADING_HOLIDAY",
              "closed_exchanges":["MCX"],"open_exchanges":[{"exchange":"NSE"}]}
            ]}""";

    @Test
    void onlyNseClosedTradingHolidaysCount() throws Exception {
        MarketCalendarService calendar = new MarketCalendarService(null);
        Map<LocalDate, String> parsed = calendar.parse(BODY);
        assertEquals(Map.of(LocalDate.of(2026, 11, 10), "Diwali-Balipratipada"), parsed);
    }

    @Test
    void weekendsAndHolidaysAreClosedWeekdaysOpen() throws Exception {
        MarketCalendarService calendar = new MarketCalendarService(null);
        calendar.holidaysForTest(calendar.parse(BODY));
        assertFalse(calendar.check(LocalDate.of(2026, 11, 10)).tradingDay());
        assertEquals("NSE holiday: Diwali-Balipratipada", calendar.check(LocalDate.of(2026, 11, 10)).reason());
        assertTrue(calendar.check(LocalDate.of(2026, 11, 11)).tradingDay());
        assertFalse(calendar.check(LocalDate.of(2026, 11, 14)).tradingDay(), "Saturday");
        assertTrue(calendar.check(LocalDate.of(2026, 2, 2)).tradingDay(), "special session days are not closures");
    }
}
