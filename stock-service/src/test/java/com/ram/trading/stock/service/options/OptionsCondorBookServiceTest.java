package com.ram.trading.stock.service.options;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.stock.service.calendar.MarketCalendarService;
import com.ram.trading.stock.service.options.OptionsCondorBookService.Legs;
import com.ram.trading.stock.service.options.OptionsCondorBookService.Quote;
import com.ram.trading.stock.service.options.OptionsCondorBookService.Strike;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OptionsCondorBookServiceTest {

    /** Strikes every 50 from 23000 to 27000; call bid = ask - 1 etc. */
    private static List<Strike> chain() {
        List<Strike> s = new ArrayList<>();
        for (double k = 23000; k <= 27000; k += 50) {
            double callPx = Math.max(1, (27000 - k) / 50);
            double putPx = Math.max(1, (k - 23000) / 50);
            s.add(new Strike(k, new Quote("C" + (int) k, callPx, callPx - 0.5, callPx + 0.5),
                    new Quote("P" + (int) k, putPx, putPx - 0.5, putPx + 0.5)));
        }
        return s;
    }

    @Test
    void strikesAreTheFirstOnesBeyondTwoAndThreeAndAHalfPercent() {
        Legs l = OptionsCondorBookService.pickStrikes(25387.4, chain());
        // 2% up = 25895.1 -> 25900; 3.5% up = 26276.0 -> 26300; 2% down = 24879.7 -> 24850; 3.5% down = 24498.8 -> 24450
        assertEquals(25900, l.shortCall().strike());
        assertEquals(26300, l.longCall().strike());
        assertEquals(24850, l.shortPut().strike());
        assertEquals(24450, l.longPut().strike());
        assertNull(OptionsCondorBookService.pickStrikes(26900, chain()), "no call wing above the chain");
    }

    @Test
    void creditSellsAtTheBidAndBuysAtTheAsk() {
        Legs l = OptionsCondorBookService.pickStrikes(25387.4, chain());
        // call 25900: px 22 -> bid 21.5; call 26300: px 14 -> ask 14.5; put 24850: px 37 -> bid 36.5; put 24450: px 29 -> ask 29.5
        assertEquals(21.5 + 36.5 - 14.5 - 29.5, OptionsCondorBookService.entryCredit(l), 1e-9);
        assertTrue(OptionsCondorBookService.quotesUsable(l));
        assertEquals(400 - 14, OptionsCondorBookService.maxLossPerUnit(25900, 26300, 24850, 24450, 14), 1e-9);
    }

    @Test
    void closePricesFallBackToLtpThenIntrinsic() {
        assertEquals(3.0, OptionsCondorBookService.closePrice(new Quote("x", 2.5, 2.0, 3.0), true, 0), 1e-9);
        assertEquals(2.0, OptionsCondorBookService.closePrice(new Quote("x", 2.5, 2.0, 3.0), false, 0), 1e-9);
        assertEquals(2.5, OptionsCondorBookService.closePrice(new Quote("x", 2.5, 0, 0), true, 0), 1e-9);
        assertEquals(40, OptionsCondorBookService.closePrice(new Quote("x", 0, 0, 0), true, 40), 1e-9);
        assertEquals(0, OptionsCondorBookService.closePrice(new Quote("x", 0.05, 0, 0.05), false, 0), 1e-9);
        assertEquals(100, OptionsCondorBookService.callIntrinsic(26000, 25900), 1e-9);
        assertEquals(0, OptionsCondorBookService.putIntrinsic(26000, 25900), 1e-9);
    }

    @Test
    void chargesForOneLotRoundTrip() {
        // sold 60/unit, bought 45/unit, 65 qty, 8 orders at Rs 30, STT 0.15%
        double c = OptionsCondorBookService.charges(60, 45, 65, 8, 30, 0.15);
        double sell = 3900, buy = 2925, turn = 6825;
        double expected = 240 + sell * 0.0015 + turn * 0.0003503 + turn * 1e-6 + buy * 0.00003
                + 0.18 * (240 + turn * 0.0003503 + turn * 1e-6);
        assertEquals(expected, c, 1e-6);
        assertTrue(c > 290 && c < 300, "about Rs 290-300: " + c);
    }

    @Test
    void previousTradingDaySkipsTheHoliday() {
        MarketCalendarService cal = mock(MarketCalendarService.class);
        when(cal.check(any())).thenAnswer(inv -> {
            LocalDate d = inv.getArgument(0);
            boolean trading = d.getDayOfWeek().getValue() <= 5 && !d.equals(LocalDate.of(2026, 11, 24));
            return new MarketCalendarService.TradingDay(d, trading, "");
        });
        OptionsCondorBookService s = new OptionsCondorBookService(null, null, cal);
        assertEquals(LocalDate.of(2026, 11, 23), s.previousTradingDay(LocalDate.of(2026, 11, 25)));
        assertEquals(LocalDate.of(2026, 11, 17), s.previousTradingDay(LocalDate.of(2026, 11, 18)));
        assertEquals(LocalDate.of(2026, 11, 20), s.previousTradingDay(LocalDate.of(2026, 11, 23)));
    }

    @Test
    void parsesTheUpstoxChainAndContracts() throws Exception {
        ObjectMapper m = new ObjectMapper();
        var chain = OptionsCondorBookService.parseChain(m.readTree("[{\"strike_price\":24400,\"underlying_spot_price\":25387.4,"
                + "\"call_options\":{\"instrument_key\":\"NSE_FO|45350\",\"market_data\":{\"ltp\":989.55,\"bid_price\":985.15,\"ask_price\":989.7}},"
                + "\"put_options\":{\"instrument_key\":\"NSE_FO|45351\",\"market_data\":{\"ltp\":0.05,\"bid_price\":0,\"ask_price\":0.05}}}]"));
        assertEquals(25387.4, chain.spot(), 1e-9);
        assertEquals("NSE_FO|45350", chain.strikes().get(0).call().key());
        assertEquals(0.05, chain.strikes().get(0).put().ask(), 1e-9);
        var c = OptionsCondorBookService.parseContracts(m.readTree("[{\"expiry\":\"2026-11-23\",\"lot_size\":65},"
                + "{\"expiry\":\"2026-11-17\",\"lot_size\":65},{\"expiry\":\"2026-11-23\",\"lot_size\":65}]"));
        assertEquals(List.of(LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 23)), c.expiries());
        assertEquals(65, c.lotSize());
        assertFalse(c.expiries().isEmpty());
    }
}
