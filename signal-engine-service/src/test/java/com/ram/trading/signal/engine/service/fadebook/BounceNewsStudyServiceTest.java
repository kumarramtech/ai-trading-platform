package com.ram.trading.signal.engine.service.fadebook;

import com.ram.trading.signal.engine.service.replay.OpeningScreen;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BounceNewsStudyServiceTest {

    private static OpeningScreen.Ranking ranking(int size, double[] relByRank) {
        List<OpeningScreen.Ranked> ranked = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ranked.add(new OpeningScreen.Ranked("S" + i, List.of(), i, relByRank[i], 100));
        }
        return new OpeningScreen.Ranking(0.1, ranked);
    }

    @Test
    void picksTheFiveWeakestOfRanks501To600AtMinusTwoOrWorse() {
        double[] rel = new double[700];
        rel[10] = -9;    // rank 11: outside the pool
        rel[500] = -2;   // rank 501: exactly -2 counts
        rel[520] = -6;
        rel[530] = -3;
        rel[540] = -4;
        rel[550] = -5;
        rel[560] = -2.5;
        rel[570] = -1.99; // not enough
        rel[600] = -8;   // rank 601: outside
        List<OpeningScreen.Ranked> picks = BounceNewsStudyService.select(ranking(700, rel));
        assertEquals(List.of("S520", "S550", "S540", "S530", "S560"),
                picks.stream().map(OpeningScreen.Ranked::symbol).toList());
    }

    @Test
    void noScreenWhenTheLiquidListIsShort() {
        assertNull(BounceNewsStudyService.select(ranking(540, new double[540])));
        assertNull(BounceNewsStudyService.select(null));
    }

    private static BounceNewsStudyService.Row row(LocalDate d, String tag, double bps, Double hedged, double net) {
        return new BounceNewsStudyService.Row(d, "X", tag, bps, hedged, net);
    }

    @Test
    void collectsUntilSixtySessionsAndIgnoresUntaggedRows() {
        LocalDate d = BounceNewsStudyService.TEST_SINCE;
        var r = BounceNewsStudyService.evaluate(List.of(
                row(d, FadeNewsTagger.NO_NEWS, 50, 40.0, 200),
                row(d, FadeNewsTagger.EMPTY, -500, -500.0, -2000),
                row(d, null, -500, -500.0, -2000)));
        assertEquals("COLLECTING 1/60 sessions", r.verdict());
        assertEquals(1, r.tagged());
        assertEquals(2, r.excluded());
    }

    @Test
    void passesOnlyWhenEveryCheckHolds() {
        List<BounceNewsStudyService.Row> rows = new ArrayList<>();
        LocalDate d = BounceNewsStudyService.TEST_SINCE;
        for (int i = 0; i < 60; i++) {
            LocalDate day = d.plusDays(i);
            rows.add(row(day, FadeNewsTagger.NO_NEWS, 30 + (i % 3) * 10, 25.0, 160));
            rows.add(row(day, FadeNewsTagger.NO_NEWS, 20, 15.0, 80));
            rows.add(row(day, FadeNewsTagger.CATALYST, -10, -10.0, -40));
        }
        var pass = BounceNewsStudyService.evaluate(rows);
        assertEquals("PASSED", pass.verdict(), String.join("\n", pass.checks()));

        // One terrible NO_NEWS day fails the worst-day check, whatever the average.
        rows.add(row(d.plusDays(5), FadeNewsTagger.NO_NEWS, 1, 1.0, -6000));
        var fail = BounceNewsStudyService.evaluate(rows);
        assertEquals("FAILED", fail.verdict());
        assertTrue(fail.checks().stream().anyMatch(c -> c.startsWith("FAIL worst")));
    }

    @Test
    void failsWhenNewsBuysDoBetter() {
        List<BounceNewsStudyService.Row> rows = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            LocalDate day = BounceNewsStudyService.TEST_SINCE.plusDays(i);
            rows.add(row(day, FadeNewsTagger.NO_NEWS, 20 + (i % 2) * 10, 20.0, 100));
            rows.add(row(day, FadeNewsTagger.NEWS, 60 + (i % 2) * 10, 60.0, 260));
        }
        var r = BounceNewsStudyService.evaluate(rows);
        assertEquals("FAILED", r.verdict());
        assertTrue(r.checks().stream().anyMatch(c -> c.startsWith("FAIL NO_NEWS") && c.contains("with news")));
    }
}
