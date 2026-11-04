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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
