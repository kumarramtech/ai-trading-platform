package com.ram.trading.stock.service.momexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.stock.service.momexec.MomentumExecPlanner.BookHolding;
import com.ram.trading.stock.service.momexec.MomentumExecPlanner.Limits;
import com.ram.trading.stock.service.momexec.MomentumExecPlanner.Order;
import com.ram.trading.stock.service.momexec.MomentumExecPlanner.Plan;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MomentumExecPlannerTest {

    private static final Limits COPY = new Limits(0, 0, 0.5, 40);
    private static final Map<String, Double> TICK = Map.of("AAA", 0.05, "BBB", 0.10, "CCC", 0.01, "DDD", 0.10);
    private static final Map<String, Double> LTP = Map.of("AAA", 500.0, "BBB", 2000.0, "CCC", 120.0, "DDD", 4500.0);

    private static Map<String, BookHolding> book(BookHolding... h) {
        Map<String, BookHolding> m = new HashMap<>();
        for (BookHolding x : h) {
            m.put(x.symbol(), x);
        }
        return m;
    }

    private static Order find(Plan p, String symbol) {
        return p.orders().stream().filter(o -> o.symbol().equals(symbol)).findFirst().orElseThrow();
    }

    @Test
    void anExactCopySellsLeaversAndBuysNewNamesAtLimitPricesOnTheTick() {
        // Paper holds AAA 6 and BBB 2 now; the book still holds CCC (the paper book sold it) and AAA.
        Plan p = MomentumExecPlanner.plan(Map.of("AAA", 6, "BBB", 2), 100_000,
                book(new BookHolding("AAA", 6, 6), new BookHolding("CCC", 40, 40)), Map.of(), Set.of(), LTP, TICK, COPY);
        assertEquals(1.0, p.scale(), 1e-9);
        assertEquals(2, p.orders().size());
        Order sell = find(p, "CCC");
        assertEquals("SELL", sell.side());
        assertEquals(40, sell.qty());
        assertEquals(119.40, sell.limit(), 1e-9); // 120 x 0.995 on a 0.01 tick
        Order buy = find(p, "BBB");
        assertEquals("BUY", buy.side());
        assertEquals(2, buy.qty());
        assertEquals(2010.00, buy.limit(), 1e-9); // 2000 x 1.005 on a 0.10 tick
        assertEquals("SELL", p.orders().get(0).side()); // sells go first
    }

    @Test
    void aSmallerCapitalScalesQuantitiesAndSkipsSharesTooDearForTheScale() {
        // Rs 25,000 against a Rs 1,00,000 paper book: scale 0.25.
        Plan p = MomentumExecPlanner.plan(Map.of("AAA", 8, "DDD", 1), 100_000, book(), Map.of(), Set.of(), LTP, TICK,
                new Limits(25_000, 10_000, 0.5, 40));
        assertEquals(0.25, p.scale(), 1e-9);
        assertEquals(1, p.orders().size());
        assertEquals(2, find(p, "AAA").qty());
        assertTrue(p.skipped().stream().anyMatch(s -> s.startsWith("DDD buy") && s.contains("rounds to 0")));
    }

    @Test
    void capsCutOrdersAndCountPendingBuys() {
        Map<String, Integer> paper = Map.of("AAA", 40, "BBB", 10);
        // Per-order cap Rs 10,000: AAA 40 x 502.50 is cut to 19 shares; one BBB share 2010 fits 4 times.
        Plan p = MomentumExecPlanner.plan(paper, 100_000, book(), Map.of(), Set.of(), LTP, TICK, new Limits(100_000, 10_000, 0.5, 40));
        assertEquals(19, find(p, "AAA").qty());
        assertEquals(4, find(p, "BBB").qty());
        // Capital cap Rs 12,000 with Rs 5,000 already in a pending AAA buy (10 shares): only 3 BBB shares fit.
        Plan q = MomentumExecPlanner.plan(Map.of("BBB", 10), 12_000, book(), Map.of("AAA", 10), Set.of(), LTP, TICK,
                new Limits(12_000, 0, 0.5, 40));
        assertEquals(3, find(q, "BBB").qty());
    }

    @Test
    void pendingOrdersAndUnchangedHoldingsCreateNothing() {
        Plan p = MomentumExecPlanner.plan(Map.of("AAA", 6, "BBB", 2), 100_000,
                book(new BookHolding("AAA", 6, 6), new BookHolding("CCC", 40, 40)), Map.of("BBB", 2), Set.of("CCC"),
                LTP, TICK, COPY);
        assertTrue(p.orders().isEmpty(), p.orders().toString());
    }

    @Test
    void aPaperTopUpBuysTheDifference() {
        Plan p = MomentumExecPlanner.plan(Map.of("AAA", 9), 100_000, book(new BookHolding("AAA", 6, 6)), Map.of(), Set.of(),
                LTP, TICK, COPY);
        Order o = find(p, "AAA");
        assertEquals(3, o.qty());
        assertEquals(9, o.paperQty());
        // At scale 0.25 a top-up of 1 share rounds to nothing: no order, but the new paper quantity is remembered.
        Plan q = MomentumExecPlanner.plan(Map.of("AAA", 9), 100_000, book(new BookHolding("AAA", 2, 8)), Map.of(), Set.of(),
                LTP, TICK, new Limits(25_000, 0, 0.5, 40));
        assertTrue(q.orders().isEmpty());
        assertEquals(9, q.seenUpdates().get("AAA"));
    }

    @Test
    void noPriceOrTickMeansNoOrderAndTheOrderCapHolds() {
        Plan p = MomentumExecPlanner.plan(Map.of("ZZZ", 5), 100_000, book(), Map.of(), Set.of(), LTP, TICK, COPY);
        assertTrue(p.orders().isEmpty());
        assertTrue(p.skipped().get(0).contains("no price or tick"));
        Plan q = MomentumExecPlanner.plan(Map.of("AAA", 1, "BBB", 1, "CCC", 1), 100_000, book(), Map.of(), Set.of(), LTP, TICK,
                new Limits(0, 0, 0.5, 2));
        assertEquals(2, q.orders().size());
        assertTrue(q.skipped().stream().anyMatch(s -> s.contains("orders-per-run cap")));
    }

    @Test
    void tickAlignmentNeverLoosensTheLimit() {
        assertEquals(1234.50, MomentumExecPlanner.align(1234.57, 0.10, true), 1e-9);
        assertEquals(1234.60, MomentumExecPlanner.align(1234.51, 0.10, false), 1e-9);
        assertEquals(99.35, MomentumExecPlanner.align(99.35000000000001, 0.05, false), 1e-9);
    }

    @Test
    void parsesUpstoxReplies() throws Exception {
        ObjectMapper m = new ObjectMapper();
        assertEquals("250120000123", UpstoxDeliveryBroker.parsePlaced(200,
                "{\"status\":\"success\",\"data\":{\"order_id\":\"250120000123\"}}"));
        assertThrows(IllegalStateException.class, () -> UpstoxDeliveryBroker.parsePlaced(400,
                "{\"status\":\"error\",\"errors\":[{\"message\":\"insufficient funds\"}]}"));

        UpstoxDeliveryBroker.OrderState st = UpstoxDeliveryBroker.parseOrder(m.readTree(
                "{\"status\":\"success\",\"data\":{\"status\":\"complete\",\"filled_quantity\":7,\"average_price\":501.2,"
                        + "\"status_message\":\"\"}}"));
        assertTrue(st.finished());
        assertEquals(7, st.filled());
        assertEquals(501.2, st.average(), 1e-9);
        assertTrue(!UpstoxDeliveryBroker.parseOrder(m.readTree("{\"data\":{\"status\":\"open\"}}")).finished());

        Map<String, Integer> shares = UpstoxDeliveryBroker.parseShares(
                m.readTree("{\"data\":[{\"instrument_token\":\"NSE_EQ|A\",\"quantity\":10,\"t1_quantity\":2}]}"),
                m.readTree("{\"data\":[{\"instrument_token\":\"NSE_EQ|A\",\"product\":\"D\",\"quantity\":-4},"
                        + "{\"instrument_token\":\"NSE_EQ|B\",\"product\":\"D\",\"quantity\":5},"
                        + "{\"instrument_token\":\"NSE_EQ|C\",\"product\":\"I\",\"quantity\":50}]}"));
        assertEquals(8, shares.get("NSE_EQ|A")); // 10 settled + 2 T1 - 4 sold today
        assertEquals(5, shares.get("NSE_EQ|B")); // bought today
        assertTrue(!shares.containsKey("NSE_EQ|C")); // intraday positions are not delivery shares

        Map<String, Double> ltp = UpstoxDeliveryBroker.parseLtp(m.readTree(
                "{\"data\":{\"NSE_EQ:AAA\":{\"instrument_token\":\"NSE_EQ|A\",\"last_price\":500.5}}}"));
        assertEquals(List.of(500.5), List.copyOf(ltp.values()));
    }
}
