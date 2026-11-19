package com.ram.trading.stock.service.deals;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NseDealCollectorTest {

    private static String file(String name) throws Exception {
        try (InputStream in = NseDealCollectorTest.class.getResourceAsStream("/deals/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void readsTheRealBulkFile() throws Exception {
        List<NseDealCollector.Deal> d = NseDealCollector.parse("BULK", file("bulk_19112026.csv"));
        assertEquals(120, d.size());
        NseDealCollector.Deal first = d.get(0);
        assertEquals(LocalDate.of(2026, 11, 19), first.date());
        assertEquals("ARIS", first.symbol());
        assertEquals("SIDDHARTH BHASKAR SHAH", first.client());
        assertEquals("BUY", first.side());
        assertEquals(475000, first.quantity());
        assertEquals(158.03, first.price(), 1e-9);
        assertNull(first.remarks()); // "-" means none
        assertEquals(d.size(), d.stream().map(NseDealCollector.Deal::rowHash).collect(Collectors.toSet()).size());
    }

    @Test
    void readsTheRealBlockFileWithoutRemarksColumn() throws Exception {
        List<NseDealCollector.Deal> d = NseDealCollector.parse("BLOCK", file("block_19112026.csv"));
        assertEquals(5, d.size());
        assertEquals("IIFLCAPS", d.get(0).symbol());
        assertEquals(44_000_000L, d.get(0).quantity());
        assertTrue(d.stream().allMatch(x -> x.kind().equals("BLOCK")));
    }

    @Test
    void identicalRowsStaySeparateAndRereadsHashTheSame() throws Exception {
        String csv = "Date,Symbol,Security Name,Client Name,Buy/Sell,Quantity Traded,Trade Price / Wght. Avg. Price,Remarks\n"
                + "19-NOV-2026,ABC,\"Abc, Ltd\",X LLP,BUY,100,10.5,-\n"
                + "19-NOV-2026,ABC,\"Abc, Ltd\",X LLP,BUY,100,10.5,-\n"
                + "bad,row\n";
        List<NseDealCollector.Deal> a = NseDealCollector.parse("BULK", csv);
        assertEquals(2, a.size());
        assertEquals("Abc, Ltd", a.get(0).securityName());
        assertNotEquals(a.get(0).rowHash(), a.get(1).rowHash());
        assertEquals(a.get(1).rowHash(), NseDealCollector.parse("BULK", csv).get(1).rowHash());
        assertNotEquals(a.get(0).rowHash(), NseDealCollector.parse("BLOCK", csv).get(0).rowHash());
    }
}
