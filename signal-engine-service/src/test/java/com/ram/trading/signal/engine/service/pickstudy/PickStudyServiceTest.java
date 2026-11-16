package com.ram.trading.signal.engine.service.pickstudy;

import com.ram.trading.signal.engine.service.pickstudy.PickStudyService.Baselines;
import com.ram.trading.signal.engine.service.pickstudy.PickStudyService.Group;
import com.ram.trading.signal.engine.service.pickstudy.PickStudyService.Row;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PickStudyServiceTest {

    @Test
    void directionComesFromTheLabelElseTheMove() {
        assertEquals("LONG", PickStudyService.direction("BULLISH", -3.0));
        assertEquals("SHORT", PickStudyService.direction("bearish", 2.0));
        assertEquals("SHORT", PickStudyService.direction("NEUTRAL", -0.5));
        assertEquals("LONG", PickStudyService.direction(null, null));
        assertEquals(2.0, PickStudyService.signed(0.02, "LONG"), 1e-9);
        assertEquals(-2.0, PickStudyService.signed(0.02, "SHORT"), 1e-9);
    }

    @Test
    void baselinesLeaveOutPicksCheapStocksAndUseTheBiggestSameWayMovers() {
        Map<String, Double> open = new HashMap<>(), ref = new HashMap<>(), exit = new HashMap<>();
        // 20 risers: S0 rose most since the open; each falls 1% from ref to exit, S0 falls 3%.
        for (int i = 0; i < 20; i++) {
            open.put("U" + i, 100.0);
            ref.put("U" + i, 120.0 - i);
            exit.put("U" + i, (120.0 - i) * (i == 0 ? 0.97 : 0.99));
        }
        // 10 fallers: each rises 2% from ref to exit, the biggest faller (D0) rises 4%.
        for (int i = 0; i < 10; i++) {
            open.put("D" + i, 100.0);
            ref.put("D" + i, 80.0 + i);
            exit.put("D" + i, (80.0 + i) * (i == 0 ? 1.04 : 1.02));
        }
        ref.put("CHEAP", 20.0);
        exit.put("CHEAP", 40.0);
        ref.put("PICKED", 100.0);
        exit.put("PICKED", 200.0);
        Baselines b = PickStudyService.baselines(open, ref, exit, Set.of("PICKED"));
        // All 30 liquid: (19 * -1 + -3 + 9 * 2 + 4) / 30 = 0
        assertEquals(0.0, b.b1Long(), 1e-6);
        assertEquals(0.0, b.b1("SHORT"), 1e-6);
        // Top 10% of 20 risers = U0, U1: (-3 + -1) / 2 = -2 for a long
        assertEquals(-2.0, b.b2("LONG"), 1e-6);
        // Top 10% of 10 fallers = D0 alone, rising 4%: -4 for a short
        assertEquals(-4.0, b.b2("SHORT"), 1e-6);
    }

    @Test
    void groupsUseOneValuePerDayForT() {
        LocalDate d1 = LocalDate.of(2026, 11, 17), d2 = d1.plusDays(1), d3 = d1.plusDays(2);
        List<Row> rows = List.of(
                new Row(d1, "MIDDAY", true, 1.0, 0.0, 0.5),
                new Row(d1, "MIDDAY", false, 3.0, 0.0, 0.5),
                new Row(d2, "MIDDAY", false, -1.0, 0.0, 0.0),
                new Row(d3, "MIDDAY", false, 1.0, 0.0, null),
                new Row(d1, "PREMARKET", false, 0.5, 0.5, 0.5));
        List<Group> g = PickStudyService.groups(rows);
        Group all = g.stream().filter(x -> x.label().equals("MIDDAY all")).findFirst().orElseThrow();
        assertEquals(4, all.picks());
        assertEquals(3, all.days());
        assertEquals(1.0, all.movePct(), 1e-9);
        // daily vs-all excess: d1 2.0, d2 -1.0, d3 1.0 -> mean 0.667
        assertEquals(2.0 / 3, all.vsAllPct(), 1e-9);
        // vs movers: d1 (0.5 + 2.5) / 2 = 1.5, d2 -1.0 (d3 has no movers value)
        assertEquals(0.25, all.vsMoversPct(), 1e-9);
        assertEquals(75.0, all.hitPct(), 1e-9);
        Group pre = g.stream().filter(x -> x.label().equals("PREMARKET all")).findFirst().orElseThrow();
        assertNull(pre.vsAllT(), "one day: no t");
        assertEquals(3, g.stream().filter(x -> x.label().startsWith("MIDDAY")).count());
    }
}
