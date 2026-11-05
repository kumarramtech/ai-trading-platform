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

    @Test
    void weeklyEntryBuysWhenTheIndexRecoversItsAverageMidMonth() {
        int n = 400;
        LocalDate[] d = new LocalDate[n];
        double[] c = new double[n], v = new double[n];
        LocalDate day = LocalDate.of(2025, 1, 1);
        int october = (int) java.time.temporal.ChronoUnit.DAYS.between(day, LocalDate.of(2025, 10, 1));
        for (int i = 0; i < n; i++) {
            d[i] = day.plusDays(i);
            // A rising index that dips below its average around 1 October (a Wednesday), back by Sunday the 5th.
            c[i] = i >= october - 3 && i <= october + 2 ? 50 : 100 + i * 0.2;
            v[i] = 1_000_000;
        }
        SwingBacktestService.Series index = new SwingBacktestService.Series("NIFTY", d, c, c, c, c, v);
        SwingBacktestService.Series up = trend("UP", d, 0.002);
        SwingBacktestService svc = new SwingBacktestService(null, org.springframework.web.reactive.function.client.WebClient.builder(), "http://x");

        var monthly = svc.momentumPortfolio(Map.of("UP", up), d,
                new SwingBacktestService.PortfolioRule("m", 1, true, 126, 21, false, 0, false, 100, 0, false, false, 0), index);
        var weekly = svc.momentumPortfolio(Map.of("UP", up), d,
                new SwingBacktestService.PortfolioRule("e", 1, true, 126, 21, false, 0, false, 100, 0, false, true, 0), index);

        LocalDate oct = LocalDate.of(2025, 10, 1);
        assertEquals(0.0, monthly.get(oct), 1e-12, "the locked rule sits out October in cash");
        // Bought at the 6 October open (Monday), held to 1 November: ~26 days of +0.2%.
        assertEquals(Math.pow(1.002, 26) - 1, weekly.get(oct), 1e-6);
        assertEquals(monthly.get(LocalDate.of(2025, 11, 1)), weekly.get(LocalDate.of(2025, 11, 1)), 1e-12);
    }

    @Test
    void stopSellsACollapsingStockAndKeepsTheSlotInCash() {
        int n = 400;
        LocalDate[] d = new LocalDate[n];
        double[] c = new double[n], v = new double[n];
        LocalDate day = LocalDate.of(2025, 1, 1);
        for (int i = 0; i < n; i++) {
            d[i] = day.plusDays(i);
            c[i] = i < 300 ? 100 + i * 0.2 : 160 * Math.pow(0.98, i - 300);   // up, then -2% a day
            v[i] = 1_000_000;
        }
        SwingBacktestService.Series stock = new SwingBacktestService.Series("S", d, c, c, c, c, v);
        SwingBacktestService svc = new SwingBacktestService(null, org.springframework.web.reactive.function.client.WebClient.builder(), "http://x");

        var none = svc.momentumPortfolio(Map.of("S", stock), d,
                new SwingBacktestService.PortfolioRule("n", 1, true, 126, 21, false, 0, false, 0, 0, false, false, 0));
        var stop = svc.momentumPortfolio(Map.of("S", stock), d,
                new SwingBacktestService.PortfolioRule("s", 1, true, 126, 21, false, 0, false, 0, 0, false, false, 15));

        double worstNone = none.values().stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double worstStop = stop.values().stream().mapToDouble(Double::doubleValue).min().orElse(0);
        assertTrue(worstNone < -0.30, "held the whole fall: " + none);
        // Sold at the open after the first close 15% under the buy price (2% a day lower): about -18%.
        assertTrue(worstStop > -0.20 && worstStop < -0.15, "stopped: " + stop);
        // Before the fall the stop never fires.
        assertEquals(none.firstEntry().getValue(), stop.firstEntry().getValue(), 1e-12);
    }

    private static SwingBacktestService newService() {
        return new SwingBacktestService(null, org.springframework.web.reactive.function.client.WebClient.builder(), "http://x");
    }

    private static LocalDate[] days(int n) {
        LocalDate[] d = new LocalDate[n];
        for (int i = 0; i < n; i++) {
            d[i] = LocalDate.of(2025, 1, 1).plusDays(i);
        }
        return d;
    }

    private static SwingBacktestService.ManagedRule managed(SwingBacktestService.EntryDay entry, double target, double trail,
                                                            boolean refill) {
        return new SwingBacktestService.ManagedRule("m", entry, target, trail, refill);
    }

    @Test
    void managedBaselineReproducesTheLockedRule() {
        LocalDate[] d = days(500);
        // Up, then a bear market below the average, then a recovery: invested and cash months both.
        double[] c = new double[500], v = new double[500];
        for (int i = 0; i < 500; i++) {
            c[i] = i < 250 ? 100 + i * 0.2 : i < 350 ? 150 - (i - 250) * 1.0 : 50 + (i - 350) * 1.0;
            v[i] = 1_000_000;
        }
        SwingBacktestService.Series index = new SwingBacktestService.Series("NIFTY", d, c, c, c, c, v);
        Map<String, SwingBacktestService.Series> data = Map.of("UP", trend("UP", d, 0.002), "SLOW", trend("SLOW", d, 0.001));
        SwingBacktestService svc = newService();

        var locked = svc.momentumPortfolio(data, d, SwingBacktestService.PortfolioRule.live("l", false, 0), index);
        var day = svc.managedPortfolio(data, d, managed(SwingBacktestService.EntryDay.MONTH_START, 0, 0, false), index);

        assertEquals(locked.keySet(), day.months().keySet());
        locked.forEach((month, r) -> assertEquals(r, day.months().get(month), 0.001, "month " + month));
        assertTrue(day.cashMonths() > 0, "the bear market is sat out in cash");
    }

    @Test
    void anyDayEntryBuysBeforeTheWeeklyEntryAfterTheIndexRecovers() {
        LocalDate[] d = days(400);
        double[] c = new double[400], v = new double[400];
        int october = (int) java.time.temporal.ChronoUnit.DAYS.between(d[0], LocalDate.of(2025, 10, 1));
        for (int i = 0; i < 400; i++) {
            // Below the average from before 1 October (a Wednesday) to the 3rd; back above from the 4th.
            c[i] = i >= october - 3 && i <= october + 2 ? 50 : 100 + i * 0.2;
            v[i] = 1_000_000;
        }
        SwingBacktestService.Series index = new SwingBacktestService.Series("NIFTY", d, c, c, c, c, v);
        Map<String, SwingBacktestService.Series> data = Map.of("UP", trend("UP", d, 0.002));
        SwingBacktestService svc = newService();
        LocalDate oct = LocalDate.of(2025, 10, 1);

        var locked = svc.managedPortfolio(data, d, managed(SwingBacktestService.EntryDay.MONTH_START, 0, 0, false), index);
        var weekly = svc.managedPortfolio(data, d, managed(SwingBacktestService.EntryDay.FIRST_OF_WEEK, 0, 0, false), index);
        var daily = svc.managedPortfolio(data, d, managed(SwingBacktestService.EntryDay.ANY_DAY, 0, 0, false), index);

        assertEquals(0, locked.midMonthEntries());
        assertEquals(1, weekly.midMonthEntries());
        assertEquals(1, daily.midMonthEntries());
        assertTrue(daily.months().get(oct) > weekly.months().get(oct) && weekly.months().get(oct) > locked.months().get(oct),
                "earlier entry earns more of the rise: " + daily.months().get(oct) + " " + weekly.months().get(oct));
    }

    @Test
    void trailingStopFollowsThePeakAcrossMonthsAndSellsAfterTheFall() {
        LocalDate[] d = days(400);
        double[] c = new double[400], v = new double[400];
        for (int i = 0; i < 400; i++) {
            c[i] = i < 300 ? 100 + i * 0.2 : 160 * Math.pow(0.98, i - 300);   // up, then -2% a day
            v[i] = 1_000_000;
        }
        SwingBacktestService.Series stock = new SwingBacktestService.Series("S", d, c, c, c, c, v);
        SwingBacktestService.Series index = trend("NIFTY", d, 0.001);
        Map<String, SwingBacktestService.Series> data = Map.of("S", stock);
        SwingBacktestService svc = newService();

        var hold = svc.managedPortfolio(data, d, managed(SwingBacktestService.EntryDay.MONTH_START, 0, 0, false), index);
        var trail = svc.managedPortfolio(data, d, managed(SwingBacktestService.EntryDay.MONTH_START, 0, 15, false), index);

        assertEquals(0, hold.trailExits());
        assertTrue(trail.trailExits() >= 1, "sold on the way down");
        assertTrue(trail.dailyMaxDdPct() < hold.dailyMaxDdPct(), trail.dailyMaxDdPct() + " vs " + hold.dailyMaxDdPct());
    }

    @Test
    void targetSellsOnTheRealBuyPriceAndRefillBuysTheNextBest() {
        LocalDate[] d = days(400);
        Map<String, SwingBacktestService.Series> data = new java.util.HashMap<>();
        for (int k = 0; k < 31; k++) {   // one more stock than the 30 slots
            data.put("S" + k, trend("S" + k, d, 0.002));
        }
        SwingBacktestService.Series index = trend("NIFTY", d, 0.001);
        SwingBacktestService svc = newService();

        // +10% takes about 48 days, so a target measured from each month's price could never fire.
        var cash = svc.managedPortfolio(data, d, managed(SwingBacktestService.EntryDay.MONTH_START, 10, 0, false), index);
        var refill = svc.managedPortfolio(data, d, managed(SwingBacktestService.EntryDay.MONTH_START, 10, 0, true), index);
        var hold = svc.managedPortfolio(data, d, managed(SwingBacktestService.EntryDay.MONTH_START, 0, 0, false), index);

        assertTrue(cash.targetExits() >= 30, "exits " + cash.targetExits());
        assertEquals(0, cash.refills());
        assertTrue(refill.refills() >= 1, "refills " + refill.refills());
        assertTrue(hold.costs() < cash.costs(), "selling early pays charges again");
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
