package com.ram.trading.stock.service.announcements;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NseAnnouncementCollectorTest {

    private static final String RSS = "<rss version=\"2.0\"><channel><title>NSE</title>"
            + "<item><title>State Bank of India</title>"
            + "<link>https://nsearchives.nseindia.com/corporate/SBIN1_18112026162054_MeetOutcome18112026_dsc.pdf</link>"
            + "<description>State Bank of India has informed the Exchange about Outcome |SUBJECT: Outcome of Board Meeting</description>"
            + "<pubDate>18-Nov-2026 16:20:54</pubDate></item>"
            + "<item><title>AYM Syntex Limited</title>"
            + "<link>https://nsearchives.nseindia.com/corporate/AYMSYNTEX_18112026162041_Intimation.pdf</link>"
            + "<description>no subject here</description><pubDate>bad date</pubDate></item>"
            + "<item><title>No link</title></item></channel></rss>";

    @Test
    void parsesItemsSubjectsAndTimes() throws Exception {
        List<NseAnnouncementCollector.Item> items = NseAnnouncementCollector.parse(RSS.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, items.size());
        assertEquals("State Bank of India", items.get(0).company());
        assertEquals("Outcome of Board Meeting", items.get(0).subject());
        assertEquals(LocalDateTime.of(2026, 11, 18, 16, 20, 54), items.get(0).pubAt());
        assertNull(items.get(1).subject());
        assertNull(items.get(1).pubAt());
    }

    @Test
    void resolvesTheSymbolFromTheFileName() {
        Set<String> known = Set.of("SBIN", "AYMSYNTEX", "M&M");
        assertEquals("SBIN1", NseAnnouncementCollector.rawSymbol(
                "https://nsearchives.nseindia.com/corporate/SBIN1_18112026162054_MeetOutcome.pdf"));
        assertEquals("SBIN", NseAnnouncementCollector.resolve("SBIN1", known));
        assertEquals("AYMSYNTEX", NseAnnouncementCollector.resolve("AYMSYNTEX", known));
        assertEquals("M&M", NseAnnouncementCollector.resolve(NseAnnouncementCollector.rawSymbol(
                "https://nsearchives.nseindia.com/corporate/M&M_18112026101500_x.pdf"), known));
        assertNull(NseAnnouncementCollector.resolve("UNKNOWN2", known));
        assertNull(NseAnnouncementCollector.rawSymbol("https://example.com/other.pdf"));
    }

    @Test
    void refusesADoctype() {
        String evil = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><rss>&x;</rss>";
        assertThrows(Exception.class, () -> NseAnnouncementCollector.parse(evil.getBytes(StandardCharsets.UTF_8)));
    }
}
