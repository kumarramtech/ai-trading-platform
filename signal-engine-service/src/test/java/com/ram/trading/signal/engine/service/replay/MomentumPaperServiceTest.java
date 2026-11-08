package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.client.NotificationClient;
import com.ram.trading.signal.engine.client.StockServiceClient;
import com.ram.trading.signal.engine.dto.StockResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MomentumPaperServiceTest {

    private JdbcTemplate jdbc;
    private MomentumPaperService paper;
    private final String month = YearMonth.now(ZoneId.of("Asia/Kolkata")).toString();

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:momentum" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        NotificationClient notifications = mock(NotificationClient.class);
        when(notifications.sendNotification(any())).thenReturn(Mono.empty());
        StockServiceClient prices = mock(StockServiceClient.class);
        when(prices.getStockPrice(anyString())).thenAnswer(inv -> Mono.just(StockResponse.builder()
                .symbol(inv.getArgument(0)).price("AAA".equals(inv.getArgument(0)) ? 100.0 : 200.0).build()));

        new MomentumSignalService(jdbc, WebClient.builder(), notifications, "http://localhost").createTable();
        paper = new MomentumPaperService(jdbc, prices, notifications);
        ReflectionTestUtils.setField(paper, "top", 2);
        paper.createTables();
    }

    private void list(String... symbols) {
        jdbc.update("delete from momentum_signal");
        for (int i = 0; i < symbols.length; i++) {
            jdbc.update("insert into momentum_signal (list_month, signal_day, symbol, rank_no) values (?, ?, ?, ?)",
                    month, LocalDate.now(), symbols[i], i + 1);
        }
    }

    private double cash() {
        return jdbc.queryForObject("select cash from momentum_paper_account where id = 1", Double.class);
    }

    @Test
    void buysTheListOnceInEqualSharesWithChargesAndSellsAllForACashMonth() {
        list("AAA", "BBB");

        assertTrue(paper.rebalance().isPresent());

        // Rs 1 lakh over 2: Rs 50,000 each at the price 0.10% worse.
        assertEquals(499, jdbc.queryForObject("select quantity from momentum_paper_position where symbol = 'AAA'", Integer.class));
        assertEquals(249, jdbc.queryForObject("select quantity from momentum_paper_position where symbol = 'BBB'", Integer.class));
        double afterBuy = cash();
        double bought = 499 * 100.1 + 249 * 200.2;
        assertTrue(afterBuy < 100000 - bought, "buy charges paid: " + afterBuy);
        assertTrue(afterBuy > 100000 - bought - 200, "charges about 0.12%: " + afterBuy);

        // Once per month.
        assertTrue(paper.rebalance().isEmpty());

        // NIFTY below its average: the month's list is CASH, everything is sold.
        jdbc.update("update momentum_paper_account set last_rebalance_month = null where id = 1");
        list("CASH");
        assertTrue(paper.rebalance().isPresent());
        assertEquals(0, jdbc.queryForObject("select count(*) from momentum_paper_position where status = 'OPEN'", Integer.class));
        double pnl = jdbc.queryForObject("select sum(net_pnl) from momentum_paper_position", Double.class);
        // Rs 1 lakh round trip: 0.10% slippage and 0.1% STT each way (~Rs 400), stamp, DP charge x 2.
        assertTrue(pnl < -400 && pnl > -500, "round trip at the same price costs slippage + charges: " + pnl);
        assertEquals(100000 + pnl, cash(), 0.01);
    }

    @Test
    void dailySummaryShowsTheDayTheTotalAgainstNiftyAndBestAndWorst() {
        List<MomentumPaperService.Mark> marks = List.of(
                new MomentumPaperService.Mark("AAA", 10, 100, 110.0),
                new MomentumPaperService.Mark("BBB", 10, 100, 95.0),
                new MomentumPaperService.Mark("CCC", 10, 100, 102.0),
                new MomentumPaperService.Mark("DDD", 10, 100, 90.0),
                new MomentumPaperService.Mark("EEE", 10, 100, null));

        String text = MomentumPaperService.formatDaily(LocalDate.of(2026, 11, 10), 100000, 2000,
                java.util.Arrays.asList(100000.0, 103000.0, 101970.0),
                java.util.Arrays.asList(null, 24000.0, 24240.0), 0, marks);

        assertTrue(text.contains("today      Rs -1,030 (-1.00%), NIFTY +1.00%"), text);
        // NIFTY from the first day it is known (24,000), equity from the start capital.
        assertTrue(text.contains("since start Rs +1,970 (+1.97%), NIFTY +1.00%"), text);
        assertTrue(text.contains("below peak 1.0%, worst fall so far 1.0%"), text);
        assertTrue(text.contains("holdings 5: 2 up, 2 down"), text);
        assertTrue(text.contains("best:  AAA +10.0%, CCC +2.0%, BBB -5.0%"), text);
        assertTrue(text.contains("worst: DDD -10.0%, BBB -5.0%, CCC +2.0%"), text);
        assertTrue(text.contains("no price (valued at the buy price): EEE"), text);
        assertFalse(text.contains("sold today"), text);
    }

    @Test
    void noDailySummaryForAnAllCashBookAndOneAfterTheBuys() {
        paper.snapshot();
        assertTrue(jdbc.queryForObject("select count(*) from momentum_paper_equity", Integer.class) == 0,
                "no account yet: nothing stored");

        list("CASH");
        paper.rebalance();
        paper.snapshot();
        assertTrue(paper.dailySummary(LocalDate.now(ZoneId.of("Asia/Kolkata")), List.of()).isEmpty(),
                "all cash, nothing sold: no message");

        jdbc.update("update momentum_paper_account set last_rebalance_month = null where id = 1");
        list("AAA", "BBB");
        paper.rebalance();
        List<MomentumPaperService.Mark> marks = List.of(
                new MomentumPaperService.Mark("AAA", 499, 100.1, 100.0),
                new MomentumPaperService.Mark("BBB", 249, 200.2, 200.0));
        paper.snapshot();
        String text = paper.dailySummary(LocalDate.now(ZoneId.of("Asia/Kolkata")), marks).orElseThrow();
        assertTrue(text.contains("holdings 2: 0 up, 2 down"), text);
        assertTrue(text.contains("since start Rs -"), "slippage and charges show as a small loss: " + text);
    }
}
