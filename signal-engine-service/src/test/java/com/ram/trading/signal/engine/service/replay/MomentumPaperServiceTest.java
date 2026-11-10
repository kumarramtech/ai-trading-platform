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
    private StockServiceClient prices;
    private final String month = YearMonth.now(ZoneId.of("Asia/Kolkata")).toString();

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:momentum" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        NotificationClient notifications = mock(NotificationClient.class);
        when(notifications.sendNotification(any())).thenReturn(Mono.empty());
        prices = mock(StockServiceClient.class);
        when(prices.getStockPrice(anyString())).thenAnswer(inv -> Mono.just(StockResponse.builder()
                .symbol(inv.getArgument(0)).price(switch ((String) inv.getArgument(0)) {
                    case "AAA" -> 100.0;
                    case "BIG" -> 30000.0;
                    case "HUGE" -> 60000.0;
                    default -> 200.0;
                }).build()));

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

    @Test
    void planFillGivesOneShareToNearMissesThenTopsUpTheMostUnderweightUpToTheCap() {
        // Slot 1,000. X: one share 1,400 (<= 1.5 slots) unheld; Y: 2,000 (> 1.5 slots); A and B held at 900 and 500.
        List<MomentumPaperService.FillSlot> slots = List.of(
                new MomentumPaperService.FillSlot("A", 100, 9),
                new MomentumPaperService.FillSlot("B", 250, 2),
                new MomentumPaperService.FillSlot("X", 1400, 0),
                new MomentumPaperService.FillSlot("Y", 2000, 0));
        java.util.Map<String, Integer> plan = MomentumPaperService.planFill(slots, 3000, 1000, 1.5, 1.3, 0);
        assertEquals(1, plan.get("X"));
        assertEquals(null, plan.get("Y"), "one share above 1.5 slots stays skipped");
        // After X (1,400) there is 1,600: B to 1,250 (3 more = 750), A to 1,300 (4 more = 400); 450 left.
        assertEquals(3, plan.get("B"));
        assertEquals(4, plan.get("A"));

        assertTrue(MomentumPaperService.planFill(slots, 50, 1000, 1.5, 1.3, 0).isEmpty(), "not enough cash");
    }

    @Test
    void rebalanceSpendsTheIdleCashOfWholeSharesOnTheList() {
        ReflectionTestUtils.setField(paper, "top", 4);
        // Slot 25,000: BIG (30,000) gets one share, HUGE (60,000) stays out, AAA and BBB top up to 1.3 slots.
        list("AAA", "BBB", "BIG", "HUGE");
        String text = paper.rebalance().orElseThrow();

        assertEquals(1, jdbc.queryForObject("select quantity from momentum_paper_position where symbol = 'BIG'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from momentum_paper_position where symbol = 'HUGE'", Integer.class));
        assertEquals(324, jdbc.queryForObject("select quantity from momentum_paper_position where symbol = 'AAA'", Integer.class));
        assertEquals(162, jdbc.queryForObject("select quantity from momentum_paper_position where symbol = 'BBB'", Integer.class));
        assertTrue(cash() < 6000, "most of the idle cash is invested: " + cash());
        assertTrue(text.contains("WHOLE-SHARE TOP-UP"), text);
        double charges = jdbc.queryForObject("select sum(buy_charges) from momentum_paper_position", Double.class);
        double cost = jdbc.queryForObject("select sum(quantity * entry_price) from momentum_paper_position", Double.class);
        assertEquals(100000, cost + charges + cash(), 0.01, "every rupee accounted for");

        // The 09:25 check does nothing now: idle cash is below 5% of equity.
        assertTrue(paper.fillIdleCash().isEmpty());
    }

    @Test
    void marketTradedOnlyWhenNiftyHasACandleThatDay() {
        LocalDate monday = LocalDate.of(2026, 11, 9), holiday = LocalDate.of(2026, 11, 10);
        assertEquals(false, paper.marketTraded(monday), "no minute_candle table: treated as not traded");
        jdbc.execute("create table minute_candle (id bigint auto_increment primary key, symbol varchar(64), "
                + "candle_minute timestamp, close_price double)");
        jdbc.update("insert into minute_candle (symbol, candle_minute, close_price) values ('NIFTY', ?, 25000), "
                + "('AAA', ?, 100)", monday.atTime(9, 15), holiday.atTime(9, 30));
        assertEquals(true, paper.marketTraded(monday));
        assertEquals(false, paper.marketTraded(holiday), "another stock's candle does not count, only NIFTY");
    }

    @Test
    void holdingsArePricedLiveInTheSessionAndFromTheLastStoredMinuteAfterIt() {
        jdbc.execute("create table minute_candle (id bigint auto_increment primary key, symbol varchar(64), "
                + "candle_minute timestamp, close_price double)");
        list("AAA", "BBB");
        paper.rebalance();
        java.time.LocalDateTime t1 = LocalDate.now().atTime(15, 27), t2 = LocalDate.now().atTime(15, 28);
        jdbc.update("insert into minute_candle (symbol, candle_minute, close_price) values ('AAA', ?, 109), ('AAA', ?, 110)",
                t1, t2);

        // Monday 2026-11-09 11:00: in the session the market stream price wins (mock: AAA 100, BBB 200).
        MomentumPaperService.PortfolioView live = paper.portfolioView(LocalDate.of(2026, 11, 9).atTime(11, 0));
        assertEquals("LIVE", live.positions().get(0).priceSource());
        assertEquals(100, live.positions().get(0).lastPrice(), 0.001);
        assertEquals(2, live.priced());

        // 18:00, market closed: still the stream's last price (as the 15:40 snapshot), labelled CLOSE.
        MomentumPaperService.PortfolioView close = paper.portfolioView(LocalDate.of(2026, 11, 9).atTime(18, 0));
        assertEquals("CLOSE", close.positions().get(0).priceSource());
        assertEquals(100, close.positions().get(0).lastPrice(), 0.001);
        assertEquals(2, close.priced());

        // Stream has no prices (e.g. after a restart): AAA from its last minute (110), BBB has none.
        when(prices.getStockPrice(anyString())).thenReturn(Mono.empty());
        MomentumPaperService.PortfolioView after = paper.portfolioView(LocalDate.of(2026, 11, 9).atTime(18, 0));
        MomentumPaperService.HoldingView a = after.positions().get(0), b = after.positions().get(1);
        assertEquals("AAA", a.symbol());
        assertEquals("LAST_MINUTE", a.priceSource());
        assertEquals(110, a.lastPrice(), 0.001);
        assertEquals(t2, a.priceTime());
        assertEquals(499 * 110 - 499 * 100.1, a.pnl(), 0.01);
        assertEquals("NONE", b.priceSource());
        assertEquals(null, b.pnl());
        assertEquals(1, after.priced());
        assertEquals(after.cash() + after.marketValue(), after.equity(), 0.01);
        assertEquals(after.equity() - 100000, after.totalPnl(), 0.01);

        // The 15:40 snapshot stores each price (105 here); after a restart the view uses it (newer than 15:28).
        when(prices.getStockPrice(anyString())).thenAnswer(inv -> Mono.just(StockResponse.builder()
                .symbol(inv.getArgument(0)).price(105.0).build()));
        paper.snapshot();
        when(prices.getStockPrice(anyString())).thenReturn(Mono.empty());
        MomentumPaperService.PortfolioView restarted = paper.portfolioView(LocalDate.of(2026, 11, 9).atTime(18, 0));
        assertEquals("CLOSE", restarted.positions().get(0).priceSource());
        assertEquals(105, restarted.positions().get(0).lastPrice(), 0.001);
        assertEquals("CLOSE", restarted.positions().get(1).priceSource());
        assertEquals(2, restarted.priced());
        Double snapEquity = jdbc.queryForObject(
                "select equity from momentum_paper_equity order by equity_day desc limit 1", Double.class);
        assertEquals(snapEquity, restarted.equity(), 0.01);
    }
}
