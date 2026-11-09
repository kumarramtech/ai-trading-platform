package com.ram.trading.signal.engine.service.fadebook;

import com.ram.trading.signal.engine.dto.ai.NewsArticle;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FadeNewsStudyTest {

    /** Monday 2026-11-09, the 09:46 entry: the window starts Friday 09:00 IST. */
    private static final LocalDateTime ENTRY = LocalDateTime.of(2026, 11, 9, 9, 46);

    private static NewsArticle a(String title, String published) {
        return NewsArticle.builder().title(title).publishedAt(published).build();
    }

    @Test
    void keepsOnlyFreshHeadlinesAboutTheCompanyAndSpotsEvents() {
        // Real headlines the news service returned for INDSWFTLAB on 2026-11-09.
        List<NewsArticle> articles = List.of(
                a("Ind-Swift Laboratories secures 99.99% shareholder approval for ₹137.20 crore warrant issue - scanx.trade",
                        "Sun, 08 Nov 2026 05:00:52 GMT"),
                a("EVONILE Stock Price and Chart — NSE:EVONILE - TradingView", "Wed, 04 Nov 2026 10:42:38 GMT"),
                a("Ind-Swift Laboratories Q2 FY27 Results: Key Highlights - Kalkine India", "Wed, 04 Nov 2026 06:11:00 GMT"));
        FadeNewsTagger.Tag tag = FadeNewsTagger.classify(articles, "IND SWIFT LABORATORIES LT", "INDSWFTLAB", ENTRY);
        assertEquals(FadeNewsTagger.CATALYST, tag.tag());
        assertEquals(1, tag.kept(), "the Nov 4 results are before Friday 09:00; EVONILE is another company");
        assertEquals(3, tag.returned());
    }

    @Test
    void quotePagesAndOtherCompaniesAreNotNews() {
        // MOMSBELIEF on 2026-11-09: only quote/profile pages and an unrelated listing story.
        List<NewsArticle> articles = List.of(
                a("RAYS OF BELIEF LIMITED (MOMSBELIEF.NS) stock price, news, quote and history - Yahoo Finance Australia",
                        "Sun, 08 Nov 2026 10:00:02 GMT"),
                a("RAYS OF BELIEF LIMITED (MOMSBELIEF.NS) company profile and facts - Yahoo Finance Singapore",
                        "Sun, 08 Nov 2026 17:47:39 GMT"),
                a("Bright Outdoor Media Limited Marks Its Transition to NSE Main Board with IPO Listing",
                        "Sun, 08 Nov 2026 12:00:00 GMT"));
        assertEquals(FadeNewsTagger.NO_NEWS,
                FadeNewsTagger.classify(articles, "RAYS OF BELIEF LIMITED", "MOMSBELIEF", ENTRY).tag());
    }

    @Test
    void newsWithoutAnEventAndArticlesAfterTheEntry() {
        List<NewsArticle> articles = List.of(
                a("Wheels India's (NSE:WHEELS) Earnings Are Of Questionable Quality - Simply Wall St",
                        "Fri, 06 Nov 2026 00:49:00 GMT"),       // Fri 06:19 IST: before Friday 09:00
                a("Why Wheels India is in focus today", "Mon, 09 Nov 2026 02:00:00 GMT"),       // Mon 07:30 IST
                a("Wheels India results beat estimates", "Mon, 09 Nov 2026 06:00:00 GMT"));     // Mon 11:30 IST: after entry
        FadeNewsTagger.Tag tag = FadeNewsTagger.classify(articles, "WHEELS INDIA LTD", "WHEELS", ENTRY);
        assertEquals(FadeNewsTagger.NEWS, tag.tag());
        assertEquals("Why Wheels India is in focus today", tag.headlines());
    }

    @Test
    void anEmptyAnswerIsNotNoNews() {
        assertEquals(FadeNewsTagger.EMPTY, FadeNewsTagger.classify(List.of(), "HUBTOWN LIMITED", "HUBTOWN", ENTRY).tag());
        assertEquals(FadeNewsTagger.ERROR, FadeNewsTagger.classify(null, "HUBTOWN LIMITED", "HUBTOWN", ENTRY).tag());
    }

    @Test
    void nameKeysDatesAndWeekdays() {
        assertEquals("ind swift", FadeNewsTagger.nameKey("IND SWIFT LABORATORIES LT"));
        assertEquals("sms pharmaceuticals", FadeNewsTagger.nameKey("SMS PHARMACEUTICALS LTD."));
        assertEquals("hubtown", FadeNewsTagger.nameKey("HUBTOWN LIMITED"));
        assertEquals("", FadeNewsTagger.nameKey("ABC LTD"), "too short to match safely; the symbol still matches");
        assertEquals(LocalDate.of(2026, 11, 6), FadeNewsTagger.previousWeekday(LocalDate.of(2026, 11, 9)));
        assertEquals(LocalDateTime.of(2026, 11, 5, 23, 43),
                FadeNewsTagger.published("Thu, 05 Nov 2026 18:13:00 GMT"));
    }

    private static List<FadeNewsStudyService.Row> rows(int sessions, double noNewsBps, double newsBps, boolean bothDays) {
        List<FadeNewsStudyService.Row> out = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 11, 10);
        for (int i = 0; i < sessions; i++) {
            boolean up = bothDays ? i % 2 == 0 : true;
            double wobble = (i % 3 - 1) * 10;
            out.add(new FadeNewsStudyService.Row(d.plusDays(i), "A", FadeNewsTagger.NO_NEWS, noNewsBps + wobble, 100, up));
            out.add(new FadeNewsStudyService.Row(d.plusDays(i), "B", FadeNewsTagger.CATALYST, newsBps + wobble, -50, up));
            out.add(new FadeNewsStudyService.Row(d.plusDays(i), "C", FadeNewsTagger.EMPTY, 500, 999, up));
        }
        return out;
    }

    @Test
    void summarySplitsTodayAndTheStudyByTagWithOpenShortsEstimated() {
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        "jdbc:h2:mem:fadenews" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table fade_paper_trade (id bigint primary key, trade_date date, symbol varchar(64), "
                + "status varchar(8), net_pnl double, entry_price double, last_price double, quantity int)");
        jdbc.execute("create table minute_candle (id bigint auto_increment primary key, symbol varchar(64), "
                + "candle_minute timestamp, close_price double)");
        FadeNewsTagger tagger = new FadeNewsTagger(null, jdbc);
        tagger.createTable();
        LocalDate d1 = LocalDate.of(2026, 11, 10), d2 = LocalDate.of(2026, 11, 11);
        Object[][] trades = {
                // id, day, symbol, status, net, entry, last, qty, tag (null = untagged)
                {1L, d1, "A", "CLOSED", 500.0, 100.0, 95.0, 100, FadeNewsTagger.NO_NEWS},
                {2L, d1, "B", "CLOSED", -300.0, 100.0, 103.0, 100, FadeNewsTagger.CATALYST},
                {3L, d2, "C", "CLOSED", 200.0, 100.0, 98.0, 100, FadeNewsTagger.NO_NEWS},
                {4L, d2, "D", "OPEN", null, 200.0, 190.0, 10, FadeNewsTagger.NEWS},
                {5L, d2, "E", "CLOSED", -50.0, 100.0, 100.5, 100, null}};
        for (Object[] t : trades) {
            jdbc.update("insert into fade_paper_trade values (?, ?, ?, ?, ?, ?, ?, ?)", t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7]);
            if (t[8] != null) {
                jdbc.update("insert into fade_news_tag values (?, ?, ?, ?, 1, 3, 'h', ?)", t[0], t[1], t[2], t[8],
                        ((LocalDate) t[1]).atTime(9, 47));
            }
        }
        FadeNewsStudyService study = new FadeNewsStudyService(jdbc);
        FadeNewsStudyService.Summary s = study.summary(d2);

        assertEquals(3, s.today().all().trades());
        assertEquals(200, s.today().noNews().pnl(), 0.01);
        assertEquals(100, s.today().withNews().pnl(), 0.01, "open short D marked (200 - 190) x 10");
        assertEquals(-50, s.today().untagged().pnl(), 0.01);
        assertTrue(s.trades().get(1).estimated());
        assertEquals("UNTAGGED", s.trades().get(2).tag());

        assertEquals(d1, s.studySince());
        assertEquals(2, s.studySessions());
        assertEquals(700, s.sinceStart().noNews().pnl(), 0.01, "closed shorts only since the study start");
        assertEquals(-300, s.sinceStart().withNews().pnl(), 0.01);
        assertTrue(s.verdict().startsWith("COLLECTING"));

        String text = study.splitText(d2);
        assertTrue(text.contains("today       NO_NEWS Rs +200 (1) | with news Rs +100 (1) | untagged Rs -50 (1)"), text);
    }

    @Test
    void verdictWaitsForSixtySessionsThenAppliesThePassMark() {
        assertTrue(FadeNewsStudyService.evaluate(rows(59, 40, -20, true)).verdict().startsWith("COLLECTING 59/60"));

        FadeNewsStudyService.Report pass = FadeNewsStudyService.evaluate(rows(60, 40, -20, true));
        assertEquals("PASSED", pass.verdict(), String.join("; ", pass.reasons()));
        assertEquals(60, pass.excluded(), "EMPTY tags are left out");
        assertEquals(40, pass.noNews().meanBps(), 0.5);

        assertEquals("FAILED", FadeNewsStudyService.evaluate(rows(60, 10, -20, true)).verdict(), "below +15 bps");
        assertEquals("FAILED", FadeNewsStudyService.evaluate(rows(60, 40, 60, true)).verdict(), "news shorts did better");
        assertEquals("FAILED", FadeNewsStudyService.evaluate(rows(60, 40, -20, false)).verdict(), "no down days seen");

        String text = FadeNewsStudyService.toText(pass);
        assertTrue(text.contains("VERDICT: PASSED"), text);
    }
}
