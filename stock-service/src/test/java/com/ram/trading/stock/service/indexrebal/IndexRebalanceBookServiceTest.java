package com.ram.trading.stock.service.indexrebal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexRebalanceBookServiceTest {

    private static IndexRebalanceBookService.Release release(String file) throws Exception {
        try (InputStream in = IndexRebalanceBookServiceTest.class.getResourceAsStream("/indexrebal/" + file)) {
            return IndexRebalanceBookService.parse(IndexRebalanceBookService.pdfText(in.readAllBytes()));
        }
    }

    private static Map<String, IndexRebalanceBookService.Event> bySymbol(IndexRebalanceBookService.Release r) {
        return r.events().stream().collect(Collectors.toMap(IndexRebalanceBookService.Event::symbol, e -> e));
    }

    @Test
    void readsTheAugust2025ReviewLikeTheStudy() throws Exception {
        IndexRebalanceBookService.Release r = release("ind_prs22082025.pdf");
        assertNotNull(r);
        assertEquals(LocalDate.of(2025, 9, 29), r.changeDate());
        assertTrue(r.problems().isEmpty(), r.problems().toString());
        Map<String, IndexRebalanceBookService.Event> m = bySymbol(r);
        assertEquals(28, m.size()); // same as research/index_events.tsv after the largest-index rule
        assertEquals(14, r.events().stream().filter(e -> e.side().equals("ADD")).count());
        assertEquals("ADD", m.get("INDIGO").side());
        assertEquals("NIFTY 50", m.get("INDIGO").index());
        // dropped from NIFTY 50 and added to Midcap 150: the larger index decides
        assertEquals("DROP", m.get("HEROMOTOCO").side());
        assertEquals("NIFTY 50", m.get("HEROMOTOCO").index());
        assertEquals("DROP", m.get("DABUR").side());
        assertEquals("NIFTY NEXT 50", m.get("DABUR").index());
    }

    @Test
    void handlesACompanyNameWrappedOverTwoLines() throws Exception {
        IndexRebalanceBookService.Release r = release("ind_prs23022026.pdf");
        assertNotNull(r);
        assertEquals(LocalDate.of(2026, 3, 27), r.changeDate());
        assertTrue(r.problems().isEmpty(), r.problems().toString());
        Map<String, IndexRebalanceBookService.Event> m = bySymbol(r);
        assertEquals(34, m.size());
        assertEquals("ADD", m.get("ICICIAMC").side());
        assertEquals("NIFTY MIDCAP 150", m.get("ICICIAMC").index());
    }

    private static IndexRebalanceBookService.OtherRelease other(String file) throws Exception {
        try (InputStream in = IndexRebalanceBookServiceTest.class.getResourceAsStream("/indexrebal/" + file)) {
            return IndexRebalanceBookService.parseOther(IndexRebalanceBookService.pdfText(in.readAllBytes()));
        }
    }

    @Test
    void readsTheOtherIndicesLikeTheStudy() throws Exception {
        // Expected counts from research/index_events_other.tsv (pdftotext parse) with the study's rules:
        // main-3 stocks left out, added-and-dropped stocks left out, O1 = has a Smallcap 250 change.
        for (Object[] c : new Object[][]{{"ind_prs22082025.pdf", 195, 87, 9, LocalDate.of(2025, 9, 29)},
                {"ind_prs23022026.pdf", 246, 107, 17, LocalDate.of(2026, 3, 27)}}) {
            IndexRebalanceBookService.OtherRelease r = other((String) c[0]);
            assertNotNull(r);
            assertEquals(c[4], r.changeDate());
            long adds = r.events().stream().filter(e -> e.side().equals("ADD")).count();
            long o1 = r.events().stream().filter(e -> e.group().equals("O1")).count();
            assertEquals(List.of(c[1], c[2], c[3]), List.of(r.events().size(), (int) adds, (int) o1), (String) c[0]);
        }
        Map<String, IndexRebalanceBookService.OtherEvent> m = other("ind_prs22082025.pdf").events().stream()
                .collect(Collectors.toMap(IndexRebalanceBookService.OtherEvent::symbol, e -> e));
        assertEquals("DROP", m.get("FINCABLES").side());
        assertEquals(List.of("NIFTY CORE HOUSING"), m.get("FINCABLES").indices());
        assertNull(m.get("INDIGO"), "a NIFTY 50 change: traded by the main book only");
        assertNull(m.get("HEROMOTOCO"));
    }

    @Test
    void otherIndicesPayTheHigherStudyCost() {
        assertEquals(0.13, IndexRebalanceBookService.costPct("MAIN"), 1e-12);
        assertEquals(0.13, IndexRebalanceBookService.costPct(null), 1e-12);
        assertEquals(0.18, IndexRebalanceBookService.costPct("O2"), 1e-12);
        assertEquals(0.18, IndexRebalanceBookService.costPct("O1"), 1e-12);
        double[] r = IndexRebalanceBookService.result(1, 100, 500, 505, 25000, 25000, 0.18);
        assertEquals(90, r[1], 1e-9); // 0.18% of Rs 50,000
        assertEquals(0.82, r[3], 1e-9);
    }

    @Test
    void ignoresReleasesThatAreNotAStockReview() throws Exception {
        assertNull(release("ind_prs02012018.pdf")); // NIFTY 10 Year SDL index replacement
        assertNull(IndexRebalanceBookService.parse("Some other press release about a new index launch"));
    }

    @Test
    void skipsAnUnbalancedSection() {
        String text = String.join("\n", "The Index Maintenance Sub-Committee (Equity) decided as part of its periodic review.",
                "These changes shall become effective from March 30, 2027 (close of March 29, 2027).",
                "1) Nifty 50", "The following companies are being excluded:", "Sr. No. Company Name Symbol",
                "1 Alpha Ltd. ALPHA", "The following companies are being included:", "Sr. No. Company Name Symbol",
                "1 Beta Ltd. BETA", "2 Gamma Ltd. GAMMA", "2) Nifty Next 50", "The following companies are being excluded:",
                "1 Beta Ltd. BETA", "The following companies are being included:", "1 Alpha Ltd. ALPHA");
        IndexRebalanceBookService.Release r = IndexRebalanceBookService.parse(text);
        assertEquals(LocalDate.of(2027, 3, 29), r.changeDate());
        assertEquals(1, r.problems().size());
        Map<String, IndexRebalanceBookService.Event> m = bySymbol(r);
        assertEquals(2, m.size()); // only the balanced Next 50 section
        assertEquals("DROP", m.get("BETA").side());
    }

    @Test
    void paperResultAndTurnover() throws Exception {
        double[] longWin = IndexRebalanceBookService.result(1, 100, 500, 505, 25000, 25000);
        assertEquals(500, longWin[0], 1e-9);
        assertEquals(65, longWin[1], 1e-9); // 0.13% of Rs 50,000
        assertEquals(435, longWin[2], 1e-9);
        assertEquals(0.87, longWin[3], 1e-9);
        double[] shortVsMarket = IndexRebalanceBookService.result(-1, 100, 500, 500, 25000, 25250);
        assertEquals(0, shortVsMarket[0], 1e-9);
        assertEquals(1.0 - 0.13, shortVsMarket[3], 1e-9); // flat while NIFTY rose 1%: a short gains 1% vs NIFTY

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 20; i++) {
            sb.append(i == 0 ? "" : ",").append("[\"t\",0,0,0,200,500000]");
        }
        assertEquals(1e8, IndexRebalanceBookService.avgTurnover(new ObjectMapper().readTree(sb.append("]").toString())), 1e-3);
        assertEquals(0, IndexRebalanceBookService.avgTurnover(new ObjectMapper().readTree("[[\"t\",0,0,0,200,5]]")), 1e-9);
    }
}
