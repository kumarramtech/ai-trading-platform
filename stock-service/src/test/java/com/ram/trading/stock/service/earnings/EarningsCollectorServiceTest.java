package com.ram.trading.stock.service.earnings;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EarningsCollectorServiceTest {

    // Shape of the real Upstox quarterly response (Reliance, checked 2026-11-11), cut to two quarters.
    private static final String BODY = """
            {"status":"success","data":{"type":"consolidated","time_period":"quarterly","units_in":"crore",
             "income_statement":[
              {"category":"revenue","history":[{"value":336394.0,"period":"Sep 2026","change":"+6.45%"},
                                               {"value":316018.0,"period":"Jun 2026"}]},
              {"category":"operating_profit","history":[{"value":38613.0,"period":"Sep 2026"},
                                                        {"value":30630.0,"period":"Jun 2026"}]},
              {"category":"net_profit","history":[{"value":29250.0,"period":"Sep 2026"},
                                                  {"value":null,"period":"Jun 2026"}]}],
             "full_statement":null}}""";

    @Test
    void parsesEachQuarterIntoRevenueOperatingAndNetProfit() throws Exception {
        Map<String, double[]> q = new EarningsCollectorService(null, null).parse(BODY);
        assertEquals(List.of("Sep 2026", "Jun 2026"), List.copyOf(q.keySet()));
        assertEquals(336394.0, q.get("Sep 2026")[0], 0.001);
        assertEquals(38613.0, q.get("Sep 2026")[1], 0.001);
        assertEquals(29250.0, q.get("Sep 2026")[2], 0.001);
        assertTrue(Double.isNaN(q.get("Jun 2026")[2]), "a missing value stays NaN, not 0");
    }

    @Test
    void emptyOrErrorResponsesGiveNoQuarters() throws Exception {
        EarningsCollectorService c = new EarningsCollectorService(null, null);
        assertTrue(c.parse("{\"status\":\"error\",\"errors\":[{\"errorCode\":\"UDAPI1206\"}]}").isEmpty());
        assertTrue(c.parse("{\"status\":\"success\",\"data\":{\"income_statement\":[]}}").isEmpty());
    }

    @Test
    void aManualPollIsLabelledLikeTheScheduledOneAtThatTime() {
        assertEquals("MORNING", EarningsCollectorService.slotAt(LocalTime.of(8, 30)));
        assertEquals("MARKET", EarningsCollectorService.slotAt(LocalTime.of(12, 0)));
        assertEquals("EVENING", EarningsCollectorService.slotAt(LocalTime.of(21, 0)));
    }
}
