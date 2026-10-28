package com.ram.trading.signal.engine.service.replay;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwingBacktestServiceTest {

    @Test
    void deliveryRoundTripOnOneLakhCostsAboutPointTwoNinePercent() {
        double cost = SwingBacktestService.DeliveryCharges.roundTrip(100.0, 100.0, 1000);   // Rs 1 lakh each way

        // STT 200 + stamp 15 + brokerage 40 + DP 18.5 + exchange ~6 + GST ~11
        assertEquals(290.0, cost, 5.0);
    }

    @Test
    void rsiAndAverages() {
        double[] up = {1, 2, 3, 4, 5};
        assertEquals(100.0, SwingBacktestService.rsi(up, 4, 2), 1e-9);
        assertEquals(4.0, SwingBacktestService.sma(up, 4, 3), 1e-9);
        assertEquals(5.0, SwingBacktestService.highest(up, 0, 4), 1e-9);
        assertEquals(1.0, SwingBacktestService.lowest(up, 0, 4), 1e-9);
    }

    /** A stock in a steady uptrend that breaks out on volume, then reverses below its 20-day low. */
    @Test
    void breakoutIsBoughtAtTheNextOpenAndExitedOnTheTrailingLow() {
        int n = 200;
        LocalDate[] d = new LocalDate[n];
        double[] o = new double[n], h = new double[n], l = new double[n], c = new double[n], v = new double[n];
        LocalDate day = LocalDate.of(2025, 1, 1);
        for (int i = 0; i < n; i++) {
            d[i] = day.plusDays(i);
            double p = i < 150 ? 100 + i * 0.1 : i < 170 ? 117 + (i - 150) * 1.0 : 137 - (i - 170) * 2.0;
            o[i] = p;
            h[i] = p + 0.5;
            l[i] = p - 0.5;
            c[i] = p;
            v[i] = i == 150 ? 3_000_000 : 1_000_000;
        }
        SwingBacktestService.Series s = new SwingBacktestService.Series("ABC", d, o, h, l, c, v);
        SwingBacktestService svc = new SwingBacktestService(null, org.springframework.web.reactive.function.client.WebClient.builder(), "http://x");

        List<SwingBacktestService.Trade> trades = svc.simulateSignals(Map.of("ABC", s), d, SwingBacktestService.Strategy.BREAKOUT);

        assertFalse(trades.isEmpty());
        SwingBacktestService.Trade t = trades.get(0);
        assertEquals(d[151], t.entryDay());              // signal on the 150 close, bought at the 151 open
        assertTrue(t.net() > 0, "rode the trend: " + t);
    }
}
