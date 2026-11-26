package com.ram.trading.stock.service.shortexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.stock.service.shortexec.ShortExecPlanner.Paper;
import com.ram.trading.stock.service.shortexec.ShortExecPlanner.Plan;
import com.ram.trading.stock.service.shortexec.ShortExecPlanner.Position;
import com.ram.trading.stock.service.shortexec.ShortExecPlanner.Rules;
import org.junit.jupiter.api.Test;

import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortExecPlannerTest {

    static final Rules FADE = new Rules(2, 40_000, 5, 0.5, LocalTime.of(10, 0), LocalTime.of(15, 5));
    static final Map<String, Double> TICK = Map.of("AAA", 0.05, "BBB", 0.05, "CCC", 0.01, "DDD", 0.05);

    static Paper open(long id, String s) {
        return new Paper(id, s, "OPEN", null);
    }

    @Test
    void copiesTheFirstPaperShortsUpToThePositionCapInPaperOrder() {
        List<Paper> paper = List.of(open(1, "AAA"), open(2, "BBB"), open(3, "CCC"));
        Plan p = ShortExecPlanner.plan(paper, List.of(), Set.of(), Map.of("AAA", 400.0, "BBB", 123.4, "CCC", 50.0),
                TICK, LocalTime.of(9, 47), FADE, null, true);
        assertEquals(2, p.enters().size());
        assertEquals("AAA", p.enters().get(0).paper().symbol());
        assertEquals(100, p.enters().get(0).qty());
        assertEquals(398.0, p.enters().get(0).limit());          // 0.5% below, rounded up to the tick
        assertEquals(420.0, p.enters().get(0).stop());           // 5% above
        assertEquals(324, p.enters().get(1).qty());              // floor(40000 / 123.4)
        assertEquals(1, p.skips().size());
        assertEquals("CCC", p.skips().get(0).paper().symbol());
    }

    @Test
    void noEntriesWhenPausedLateOrAlreadyCopied() {
        List<Paper> paper = List.of(open(1, "AAA"), open(2, "BBB"));
        Map<String, Double> ltp = Map.of("AAA", 400.0, "BBB", 100.0);
        Plan paused = ShortExecPlanner.plan(paper, List.of(), Set.of(), ltp, TICK, LocalTime.of(9, 47), FADE, "lost", true);
        assertEquals(0, paused.enters().size());
        assertEquals(2, paused.skips().size());
        Plan late = ShortExecPlanner.plan(paper, List.of(), Set.of(), ltp, TICK, LocalTime.of(10, 0), FADE, null, true);
        assertEquals(0, late.enters().size());
        Plan copied = ShortExecPlanner.plan(paper, List.of(), Set.of(1L, 2L), ltp, TICK, LocalTime.of(9, 48), FADE, null, true);
        assertEquals(0, copied.enters().size());
        assertEquals(0, copied.skips().size());
    }

    @Test
    void missingPriceIsRetriedNotSkipped() {
        Plan p = ShortExecPlanner.plan(List.of(open(1, "AAA")), List.of(), Set.of(), Map.of(), TICK, LocalTime.of(9, 47),
                FADE, null, true);
        assertEquals(0, p.enters().size());
        assertEquals(0, p.skips().size());
        assertEquals(1, p.notes().size());
    }

    @Test
    void coversWhenPaperCoversAtForceTimeAndOnDryRunStop() {
        List<Paper> paper = List.of(new Paper(1, "AAA", "CLOSED", "DAY_STOP"), open(2, "BBB"), open(3, "DDD"));
        List<Position> mine = List.of(new Position(10, "AAA", 1, 100, 420.0, "OPEN"),
                new Position(11, "BBB", 2, 300, 105.0, "OPEN"), new Position(12, "DDD", 3, 50, 900.0, "OPEN"));
        Map<String, Double> ltp = Map.of("AAA", 410.0, "BBB", 106.0, "DDD", 850.0);
        Plan p = ShortExecPlanner.plan(paper, mine, Set.of(1L, 2L, 3L), ltp, TICK, LocalTime.of(11, 0), FADE, null, true);
        assertEquals(2, p.covers().size());
        assertEquals("PAPER DAY_STOP", p.covers().get(0).reason());
        assertEquals("STOP", p.covers().get(1).reason());
        Plan live = ShortExecPlanner.plan(paper, mine, Set.of(1L, 2L, 3L), ltp, TICK, LocalTime.of(11, 0), FADE, null, false);
        assertEquals(1, live.covers().size());                   // in LIVE the broker's stop order covers BBB
        Plan time = ShortExecPlanner.plan(paper, mine, Set.of(1L, 2L, 3L), ltp, TICK, LocalTime.of(15, 5), FADE, null, false);
        assertEquals(3, time.covers().size());
        assertTrue(time.covers().stream().allMatch(c -> c.reason().startsWith("TIME")));
    }

    @Test
    void aFreedSlotIsNotRefilledByACoverInTheSameMinuteButOpenOnesCount() {
        List<Paper> paper = List.of(new Paper(1, "AAA", "CLOSED", "STOP"), open(2, "BBB"), open(3, "CCC"));
        List<Position> mine = List.of(new Position(10, "AAA", 1, 100, null, "OPEN"),
                new Position(11, "BBB", 2, 300, null, "ENTERING"));
        Plan p = ShortExecPlanner.plan(paper, mine, Set.of(1L, 2L), Map.of("AAA", 400.0, "BBB", 100.0, "CCC", 50.0), TICK,
                LocalTime.of(9, 50), FADE, null, true);
        assertEquals(1, p.covers().size());
        assertEquals(1, p.enters().size());                      // AAA covered -> one slot of two is free
        assertEquals("CCC", p.enters().get(0).paper().symbol());
    }

    @Test
    void bookStopAndLosingStreak() {
        assertNull(ShortExecPlanner.pauseReason(List.of(-2000.0, 1000.0, -3000.0), 5000, 4));
        assertTrue(ShortExecPlanner.pauseReason(List.of(-2000.0, 1000.0, -4000.0), 5000, 4).contains("book stop"));
        assertTrue(ShortExecPlanner.pauseReason(List.of(500.0, -10.0, -10.0, -10.0, -10.0), 5000, 4).contains("4 losing"));
        assertNull(ShortExecPlanner.pauseReason(List.of(-10.0, -10.0, -10.0, -10.0), 5000, 0));
    }

    @Test
    void netAndTicks() {
        // short 100 @ 400, cover @ 390: sell Rs 40,000, buy Rs 39,000
        ShortExecPlanner.Charges c = ShortExecPlanner.shortCharges(100, 400, 390);
        assertEquals(40.0, c.brokerage());   // Rs 20 cap on each order
        assertEquals(10.0, c.stt());         // 0.025% of the sell
        assertEquals(2.35, c.exchangeTxn()); // 0.00297% of Rs 79,000
        assertEquals(0.08, c.sebiFee());
        assertEquals(1.17, c.stampDuty());   // 0.003% of the buy
        assertEquals(7.64, c.gst());         // 18% of brokerage + exchange + SEBI
        assertEquals(61.23, c.total());
        double[] r = ShortExecPlanner.shortNet(100, 400, 390);
        assertEquals(1000.0, r[0]);
        assertEquals(61.23, r[1]);
        assertEquals(938.77, r[2]);          // profit after every charge
        assertEquals(100.05, ShortExecPlanner.align(100.01, 0.05, RoundingMode.CEILING));
        assertEquals(100.0, ShortExecPlanner.align(100.0, 0.05, RoundingMode.CEILING));
    }

    @Test
    void orderBodiesAreIntradayAndParsePositions() throws Exception {
        Map<String, Object> stop = UpstoxIntradayBroker.body("NSE_EQ|X", "BUY", 10, "SL-M", 0, 105.5);
        assertEquals("I", stop.get("product"));
        assertEquals(105.5, stop.get("trigger_price"));
        assertEquals(0.0, stop.get("price"));
        assertEquals("SHX", stop.get("tag"));
        Map<String, Object> lim = UpstoxIntradayBroker.body("NSE_EQ|X", "SELL", 10, "LIMIT", 99.5, 0);
        assertEquals(99.5, lim.get("price"));
        assertEquals(0.0, lim.get("trigger_price"));
        Map<String, Integer> net = UpstoxIntradayBroker.parseIntraday(new ObjectMapper().readTree(
                "{\"data\":[{\"product\":\"I\",\"instrument_token\":\"NSE_EQ|X\",\"quantity\":-10},"
                        + "{\"product\":\"D\",\"instrument_token\":\"NSE_EQ|Y\",\"quantity\":5}]}"));
        assertEquals(Map.of("NSE_EQ|X", -10), net);
    }
}
