package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.service.replay.LowVolMomentumStudy.Result;
import com.ram.trading.signal.engine.service.replay.LowVolMomentumStudy.Scored;
import com.ram.trading.signal.engine.service.replay.LowVolMomentumStudy.Variant;
import com.ram.trading.signal.engine.service.replay.SwingBacktestService.Series;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LowVolMomentumStudyTest {

    private static List<Scored> stocks(int n) {
        // Momentum falls with the index; volatility rises with it except every 3rd stock is calm.
        List<Scored> out = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            out.add(new Scored("S" + k, 100 - k, k % 3 == 0 ? 0.001 * k : 0.01 + 0.001 * k));
        }
        return out;
    }

    @Test
    void variantsRankAsRegistered() {
        List<Scored> s = stocks(100);
        assertEquals("S0", Variant.LIVE.name().equals("LIVE") ? LowVolMomentumStudy.ranking(Variant.LIVE, s).get(0) : "");
        List<String> a = LowVolMomentumStudy.ranking(Variant.A, s);
        assertEquals(100, a.size());
        // A: the first 60 by momentum re-sorted calmest first, then momentum ranks 61+.
        assertEquals("S0", a.get(0));
        assertEquals("S3", a.get(1));
        assertTrue(a.subList(0, 60).stream().allMatch(x -> Integer.parseInt(x.substring(1)) < 60));
        assertEquals("S60", a.get(60));
        // CONTROL: calmest overall.
        assertEquals("S0", LowVolMomentumStudy.ranking(Variant.CONTROL, s).get(0));
        // B: a calm, strong stock beats a slightly stronger jumpy one.
        List<String> b = LowVolMomentumStudy.ranking(Variant.B, s);
        assertTrue(b.indexOf("S3") < b.indexOf("S1"), b.subList(0, 10).toString());
    }

    @Test
    void volatilityIsTheDailyReturnSpread() {
        int n = 300;
        LocalDate[] d = new LocalDate[n];
        double[] c = new double[n];
        for (int i = 0; i < n; i++) {
            d[i] = LocalDate.of(2020, 1, 1).plusDays(i);
            c[i] = i % 2 == 0 ? 100 : 101;
        }
        Series s = new Series("X", d, c, c, c, c, c);
        assertTrue(Double.isNaN(LowVolMomentumStudy.volatility(s, 100, 252)));
        double vol = LowVolMomentumStudy.volatility(s, 299, 252);
        assertEquals(0.00995, vol, 0.0002, "alternating +1% / -0.99%");
    }

    private static Result r(Variant v, double cagr, double dd) {
        return new Result(v, new TreeMap<>(), cagr, dd, 0, 0, 0, 0);
    }

    @Test
    void passRuleAndDecisionFollowThePreRegistration() {
        Result live = r(Variant.LIVE, 17, 9);
        assertTrue(LowVolMomentumStudy.passes(r(Variant.A, 15.5, 6), live), "3 points calmer, 1.5 less return");
        assertFalse(LowVolMomentumStudy.passes(r(Variant.A, 14.5, 5), live), "too much return lost");
        assertTrue(LowVolMomentumStudy.passes(r(Variant.A, 18, 11), live), "richer, DD 2 worse");
        assertFalse(LowVolMomentumStudy.passes(r(Variant.A, 18, 11.5), live), "DD too much worse");
        assertFalse(LowVolMomentumStudy.passes(r(Variant.A, 16.5, 7), live), "a bit calmer is not enough");

        Map<Variant, Result> recent = new HashMap<>(Map.of(Variant.LIVE, live,
                Variant.A, r(Variant.A, 16, 5), Variant.B, r(Variant.B, 18, 9)));
        Map<Variant, Result> older = new HashMap<>(Map.of(Variant.LIVE, r(Variant.LIVE, 26, 17),
                Variant.A, r(Variant.A, 25, 13), Variant.B, r(Variant.B, 25, 18)));
        String d = LowVolMomentumStudy.decision(recent, older);
        assertTrue(d.startsWith("DECISION  A:"), d);

        older.put(Variant.A, r(Variant.A, 22, 13));
        assertTrue(LowVolMomentumStudy.decision(recent, older).startsWith("DECISION  NO variant"));
    }

    @Test
    void simulationHoldsTheListAndSitsInCashBelowTheAverage() {
        // 40 stocks over ~2.2 years of weekdays, NIFTY rising (risk on), all stocks rising at different speeds.
        int n = 560;
        LocalDate[] d = new LocalDate[n];
        LocalDate day = LocalDate.of(2020, 1, 1);
        for (int i = 0; i < n; i++) {
            while (day.getDayOfWeek().getValue() > 5) {
                day = day.plusDays(1);
            }
            d[i] = day;
            day = day.plusDays(1);
        }
        Map<String, Series> data = new HashMap<>();
        for (int k = 0; k < 40; k++) {
            double[] c = new double[n];
            double[] v = new double[n];
            for (int i = 0; i < n; i++) {
                c[i] = 100 * Math.pow(1 + 0.0002 * (k + 1), i) * (1 + (i % 2 == 0 ? 0.001 * k : 0));
                v[i] = 1e6;
            }
            data.put("S" + k, new Series("S" + k, d, c, c, c, c, v));
        }
        double[] nc = new double[n];
        for (int i = 0; i < n; i++) {
            nc[i] = 10000 + i;
        }
        Series nifty = new Series("NIFTY", d, nc, nc, nc, nc, nc);
        LocalDate[] calendar = SwingBacktestService.since(nifty, d[300]).date();
        Result live = LowVolMomentumStudy.simulate(Variant.LIVE, data, calendar, nifty);
        assertTrue(live.months().size() >= 10, "months: " + live.months().size());
        assertTrue(live.cagrPct() > 0, "rising stocks: " + live.cagrPct());
        assertEquals(0, live.cashMonths());
        assertTrue(live.buysPerYear() < 40, "the buffer keeps the list: " + live.buysPerYear());

        // NIFTY falling: always below its average, all cash at the liquid-fund yield.
        double[] down = new double[n];
        for (int i = 0; i < n; i++) {
            down[i] = 20000 - i;
        }
        Series fall = new Series("NIFTY", d, down, down, down, down, down);
        Result cash = LowVolMomentumStudy.simulate(Variant.A, data, calendar, fall);
        assertEquals(cash.months().size(), cash.cashMonths());
        assertEquals(6.5, cash.cagrPct(), 0.3);
    }
}
