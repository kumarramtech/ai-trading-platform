package com.ram.trading.signal.engine.service.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDate;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountOverviewServiceTest {

    private static final LocalDate D1 = LocalDate.of(2026, 11, 4);
    private static final LocalDate D2 = LocalDate.of(2026, 11, 5);
    private static final LocalDate D3 = LocalDate.of(2026, 11, 6);

    private JdbcTemplate jdbc;
    private AccountOverviewService service;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:account" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table paper_trade (id bigint auto_increment primary key, symbol varchar(32), "
                + "paper_signal varchar(8), status varchar(20), entry_time timestamp, entry_price double, quantity int, "
                + "profit_loss double, charges double, rule_version varchar(16))");
        jdbc.execute("create table fade_paper_trade (id bigint auto_increment primary key, symbol varchar(32), "
                + "trade_date date, status varchar(20), entry_price double, quantity int, last_price double, "
                + "net_pnl double, charges double)");
        jdbc.execute("create table momentum_paper_account (id int primary key, start_capital double, cash double, "
                + "started_on date)");
        jdbc.execute("create table momentum_paper_position (id bigint auto_increment primary key, symbol varchar(32), "
                + "quantity int, entry_day date, entry_price double, buy_charges double, exit_day date, "
                + "sell_charges double, net_pnl double, status varchar(8))");
        jdbc.execute("create table momentum_paper_equity (equity_day date primary key, equity double)");
        service = new AccountOverviewService(jdbc);
    }

    private void intraday(LocalDate d, String side, String status, double price, int qty, Double pnl, double chg,
                          String version) {
        jdbc.update("insert into paper_trade (symbol, paper_signal, status, entry_time, entry_price, quantity, "
                        + "profit_loss, charges, rule_version) values ('X', ?, ?, ?, ?, ?, ?, ?, ?)",
                side, status, d.atTime(10, 0), price, qty, pnl, chg, version);
    }

    private void fade(LocalDate d, String status, double price, int qty, Double last, Double pnl, double chg) {
        jdbc.update("insert into fade_paper_trade (symbol, trade_date, status, entry_price, quantity, last_price, "
                + "net_pnl, charges) values ('F', ?, ?, ?, ?, ?, ?, ?)", d, status, price, qty, last, pnl, chg);
    }

    @Test
    void addsTheThreeBooksTodayTotalExposureAndWorstFall() {
        // Intraday: D1 +300, D2 -500 (two trades), D3 +100; an old-rules trade and an open long/short.
        intraday(D1, "BUY", "CLOSED", 100, 10, 300.0, 30, "2026-11-03");
        intraday(D2, "SELL", "STOP_LOSS_HIT", 100, 10, -200.0, 30, "2026-11-03");
        intraday(D2, "BUY", "MARKET_CLOSED", 100, 10, -300.0, 30, "2026-11-03");
        intraday(D3, "BUY", "TARGET_HIT", 100, 10, 100.0, 30, "2026-11-03");
        intraday(D1, "BUY", "CLOSED", 100, 10, -9999.0, 30, "2026-10-01");
        intraday(D3, "BUY", "OPEN", 200, 5, null, 0, "2026-11-03");
        intraday(D3, "SELL", "OPEN", 300, 2, null, 0, "2026-11-03");

        // FADE: D1 -1000, D3 +400, and one open short 10 x 50 now at 48 (+20 unrealised).
        fade(D1, "CLOSED", 100, 10, null, -1000.0, 50);
        fade(D3, "CLOSED", 100, 10, null, 400.0, 50);
        fade(D3, "OPEN", 50, 10, 48.0, null, 0);

        // Momentum: start 100,000; equity D1 100,000, D2 99,000, D3 99,500; cash 30,000; 2 holdings.
        jdbc.update("insert into momentum_paper_account values (1, 100000, 30000, ?)", D1);
        jdbc.update("insert into momentum_paper_equity values (?, 100000)", D1);
        jdbc.update("insert into momentum_paper_equity values (?, 99000)", D2);
        jdbc.update("insert into momentum_paper_equity values (?, 99500)", D3);
        jdbc.update("insert into momentum_paper_position (symbol, quantity, entry_day, entry_price, buy_charges, status) "
                + "values ('A', 100, ?, 350, 40, 'OPEN'), ('B', 70, ?, 500, 40, 'OPEN')", D2, D2);

        AccountOverviewService.Overview o = service.overview(D3);
        AccountOverviewService.BookLine in = o.books().get(0), fd = o.books().get(1), mo = o.books().get(2);

        assertEquals(-100, in.totalNet(), 0.01, "old rule version left out");
        assertEquals(4, in.trades());
        assertEquals(2, in.winners());
        assertEquals(100, in.todayNet(), 0.01);
        assertEquals(-500, in.worstDay(), 0.01);
        assertEquals(-500, in.worstFall(), 0.01, "peak +300 after D1, down to -200 after D2");
        assertEquals(1000, in.longExposure(), 0.01);
        assertEquals(600, in.shortExposure(), 0.01);
        assertEquals(120, in.totalCharges(), 0.01);

        assertEquals(-600, fd.totalNet(), 0.01);
        assertEquals(400, fd.todayNet(), 0.01);
        assertEquals(500, fd.shortExposure(), 0.01);
        assertEquals(20, fd.unrealised(), 0.01);
        assertEquals(-1000, fd.worstFall(), 0.01);

        assertEquals(-500, mo.totalNet(), 0.01);
        assertEquals(500, mo.todayNet(), 0.01);
        assertEquals(-1000, mo.worstDay(), 0.01);
        assertEquals(-1000, mo.worstFall(), 0.01);
        assertEquals(70000, mo.longExposure(), 0.01);
        assertEquals(-500, mo.unrealised(), 0.01, "holdings 69,500 at the snapshot vs 70,000 cost");
        assertEquals(2, mo.openPositions());
        assertTrue(mo.realMoneyCandidate());

        assertEquals(1000, o.todayNet(), 0.01);
        assertEquals(-1200, o.totalNet(), 0.01);
        assertEquals(71000, o.longExposure(), 0.01);
        assertEquals(1100, o.shortExposure(), 0.01);
        // Combined by day: D1 300-1000+0 = -700, D2 -500-1000 = -1500, D3 100+400+500 = +1000.
        assertEquals(-2200, o.worstFallCombined(), 0.01);

        String text = AccountOverviewService.toText(o);
        assertTrue(text.contains("WHOLE ACCOUNT 2026-11-06"), text);
        assertTrue(text.contains("MONTHLY MOMENTUM - main bet"), text);
    }

    @Test
    void emptyBooksGiveZerosNotErrors() {
        AccountOverviewService.Overview o = service.overview(D1);
        assertEquals(0, o.totalNet(), 0.01);
        assertEquals("not started", o.books().get(2).note());
    }

    @Test
    void worstFallIsTheLargestDropFromARunningPeak() {
        TreeMap<LocalDate, Double> days = new TreeMap<>();
        days.put(D1, 1000.0);
        days.put(D2, -1500.0);
        days.put(D3, 200.0);
        assertEquals(-1500, AccountOverviewService.worstFall(days), 0.01);
        assertEquals(0, AccountOverviewService.worstFall(new TreeMap<>()), 0.01);
    }
}
