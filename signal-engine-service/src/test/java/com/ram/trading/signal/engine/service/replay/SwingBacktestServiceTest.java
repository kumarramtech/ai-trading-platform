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

    @Test
    void momentumPortfolioHoldsTheStrongerStockAndPaysCostsOnlyWhenItTrades() {
        int n = 300;
        LocalDate[] d = new LocalDate[n];
        LocalDate day = LocalDate.of(2025, 1, 1);
        for (int i = 0; i < n; i++) {
            d[i] = day.plusDays(i);
        }
        SwingBacktestService.Series up = trend("UP", d, 0.002);
        SwingBacktestService.Series down = trend("DOWN", d, -0.001);
        SwingBacktestService svc = new SwingBacktestService(null, org.springframework.web.reactive.function.client.WebClient.builder(), "http://x");

        var free = svc.momentumPortfolio(Map.of("UP", up, "DOWN", down), d,
                new SwingBacktestService.PortfolioRule("t", 1, true, 126, 21, false, 0, false));
        var costly = svc.momentumPortfolio(Map.of("UP", up, "DOWN", down), d,
                new SwingBacktestService.PortfolioRule("t", 1, true, 126, 21, false, 20, true));

        assertTrue(free.values().stream().allMatch(r -> r > 0), "always holds UP: " + free);
        // Costs only in the first month (buying UP once); later months hold without trading.
        assertTrue(costly.firstEntry().getValue() < free.firstEntry().getValue());
        assertEquals(free.lastEntry().getValue(), costly.lastEntry().getValue(), 1e-12);
    }

    @Test
    void marketFilterHoldsCashEarningTheYieldWhileTheIndexIsBelowItsAverage() {
        int n = 400;
        LocalDate[] d = new LocalDate[n];
        double[] c = new double[n], v = new double[n];
        LocalDate day = LocalDate.of(2025, 1, 1);
        for (int i = 0; i < n; i++) {
            d[i] = day.plusDays(i);
            c[i] = i < 250 ? 100 + i * 0.2 : 150 - (i - 250) * 1.0;   // up, then a bear market
        }
        SwingBacktestService.Series index = new SwingBacktestService.Series("NIFTY", d, c, c, c, c, v);
        SwingBacktestService.Series up = trend("UP", d, 0.002);
        SwingBacktestService svc = new SwingBacktestService(null, org.springframework.web.reactive.function.client.WebClient.builder(), "http://x");
        SwingBacktestService.PortfolioRule rule =
                new SwingBacktestService.PortfolioRule("f", 1, true, 126, 21, false, 0, false, 200, 6.5);

        var filtered = svc.momentumPortfolio(Map.of("UP", up), d, rule, index);

        assertTrue(SwingBacktestService.aboveAverage(index, d[249], 200));
        assertFalse(SwingBacktestService.aboveAverage(index, d[300], 200));
        // Invested while NIFTY is above its 200-day average: UP's monthly gain.
        assertTrue(filtered.firstEntry().getValue() > 0.03, "invested: " + filtered);
        // In the bear market it holds cash: one month of 6.5% a year, nothing more.
        double last = filtered.lastEntry().getValue();
        assertTrue(last > 0.004 && last < 0.007, "cash month earns ~0.55%: " + filtered);
        assertEquals(5, SwingBacktestService.since(index, d[395]).size());
    }

    @Test
    void weeklyExitSellsWhenTheIndexBreaksItsAverageMidMonth() {
        int n = 400;
        LocalDate[] d = new LocalDate[n];
        double[] c = new double[n], v = new double[n];
        LocalDate day = LocalDate.of(2025, 1, 1);
        for (int i = 0; i < n; i++) {
            d[i] = day.plusDays(i);
            c[i] = i < 250 ? 100 + i * 0.2 : 150 - (i - 250) * 1.0;
            v[i] = 1_000_000;
        }
        // The only stock moves with the index, so holding through the fall hurts.
        SwingBacktestService.Series index = new SwingBacktestService.Series("NIFTY", d, c, c, c, c, v);
        SwingBacktestService.Series stock = new SwingBacktestService.Series("S", d, c, c, c, c, v);
        SwingBacktestService svc = new SwingBacktestService(null, org.springframework.web.reactive.function.client.WebClient.builder(), "http://x");

        var monthly = svc.momentumPortfolio(Map.of("S", stock), d,
                new SwingBacktestService.PortfolioRule("m", 1, true, 126, 21, false, 0, false, 100, 0, false), index);
        var weekly = svc.momentumPortfolio(Map.of("S", stock), d,
                new SwingBacktestService.PortfolioRule("w", 1, true, 126, 21, false, 0, false, 100, 0, true), index);

        double worstMonthly = monthly.values().stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double worstWeekly = weekly.values().stream().mapToDouble(Double::doubleValue).min().orElse(0);
        assertTrue(worstWeekly > worstMonthly, "weekly " + weekly + " vs monthly " + monthly);
    }

    private static SwingBacktestService.Series trend(String symbol, LocalDate[] d, double dailyChange) {
        int n = d.length;
        double[] o = new double[n], h = new double[n], l = new double[n], c = new double[n], v = new double[n];
        for (int i = 0; i < n; i++) {
            double p = 100 * Math.pow(1 + dailyChange, i);
            o[i] = p;
            h[i] = p;
            l[i] = p;
            c[i] = p;
            v[i] = 1_000_000;
        }
        return new SwingBacktestService.Series(symbol, d, o, h, l, c, v);
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
