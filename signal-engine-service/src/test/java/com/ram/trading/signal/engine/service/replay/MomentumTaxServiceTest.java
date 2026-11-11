package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.service.replay.MomentumTaxService.Lot;
import com.ram.trading.signal.engine.service.replay.MomentumTaxService.Rates;
import com.ram.trading.signal.engine.service.replay.MomentumTaxService.YearTax;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MomentumTaxServiceTest {

    private static final Rates RATES = new Rates(20, 12.5, 125000, 4, 0);
    private static final LocalDate NOV = LocalDate.of(2026, 11, 2);

    @Test
    void financialYearRunsAprilToMarch() {
        assertEquals("FY 2026-27", MomentumTaxService.financialYear(LocalDate.of(2026, 11, 11)));
        assertEquals("FY 2026-27", MomentumTaxService.financialYear(LocalDate.of(2027, 3, 31)));
        assertEquals("FY 2027-28", MomentumTaxService.financialYear(LocalDate.of(2027, 4, 1)));
    }

    @Test
    void shortTermGainIsTaxedAtTwentyPercentPlusCessAfterLosses() {
        // Rs 10,000 gain and Rs 4,000 loss in the same year: 6,000 x 20% x 1.04 = 1,248.
        List<YearTax> y = MomentumTaxService.taxByYear(List.of(
                new Lot(NOV, NOV.plusMonths(1), 10000), new Lot(NOV, NOV.plusMonths(2), -4000)), RATES);
        assertEquals(1, y.size());
        assertEquals(1248, y.get(0).tax(), 0.01);
    }

    @Test
    void longTermGainsAboveTheExemptionAt12Point5AndHeldOverTwelveMonths() {
        Lot exactlyTwelve = new Lot(NOV, NOV.plusMonths(12), 1);
        Lot overTwelve = new Lot(NOV, NOV.plusMonths(12).plusDays(1), 1);
        assertFalse(exactlyTwelve.longTerm());
        assertTrue(overTwelve.longTerm());
        // Rs 2,25,000 long-term: 1,00,000 above the exemption x 12.5% x 1.04 = 13,000.
        List<YearTax> y = MomentumTaxService.taxByYear(List.of(
                new Lot(NOV, LocalDate.of(2028, 1, 10), 225000)), RATES);
        assertEquals(13000, y.get(0).tax(), 0.01);
        assertEquals(100000, y.get(0).taxedLongTerm(), 0.01);
    }

    @Test
    void lossesCarryForwardAndShortTermLossOffsetsLongTermGain() {
        List<YearTax> y = MomentumTaxService.taxByYear(List.of(
                new Lot(NOV, LocalDate.of(2027, 2, 1), -30000),          // FY 2026-27 loss
                new Lot(NOV, LocalDate.of(2027, 6, 1), 50000)), RATES);  // FY 2027-28 short-term gain
        assertEquals(0, y.get(0).tax(), 0.01);
        assertEquals(30000, y.get(0).shortLossCarried(), 0.01);
        assertEquals(20000 * 0.2 * 1.04, y.get(1).tax(), 0.01);
        assertEquals(0, y.get(1).shortLossCarried(), 0.01);

        // A long-term loss never offsets a short-term gain.
        List<YearTax> z = MomentumTaxService.taxByYear(List.of(
                new Lot(NOV, LocalDate.of(2028, 1, 5), -10000), new Lot(LocalDate.of(2027, 12, 1),
                        LocalDate.of(2028, 1, 5), 10000)), RATES);
        assertEquals(10000 * 0.2 * 1.04, z.get(0).tax(), 0.01);
        assertEquals(10000, z.get(0).longLossCarried(), 0.01);
    }

    @Test
    void unusedBasicExemptionAbsorbsShortTermGainFirst() {
        Rates withExemption = new Rates(20, 12.5, 125000, 4, 4000);
        List<YearTax> y = MomentumTaxService.taxByYear(List.of(new Lot(NOV, NOV.plusMonths(1), 10000)), withExemption);
        assertEquals(6000 * 0.2 * 1.04, y.get(0).tax(), 0.01);
    }

    @Test
    void sttIsAddedBackToTheTaxableGain() {
        // Rs 1,00,000 bought, Rs 1,10,000 sold, net Rs 9,700: STT 0.1% of each side (Rs 210) is not deductible.
        assertEquals(9910, MomentumTaxService.taxableGain(9700, 100000, 110000), 0.001);
    }

    @Test
    void afterTaxViewTaxesSoldAndOpenLotsAndComparesTheIndexOnTheSameDay() {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:tax" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        var notifications = org.mockito.Mockito.mock(com.ram.trading.signal.engine.client.NotificationClient.class);
        var stocks = org.mockito.Mockito.mock(com.ram.trading.signal.engine.client.StockServiceClient.class);
        org.mockito.Mockito.when(stocks.getStockPrice(org.mockito.ArgumentMatchers.anyString())).thenReturn(
                reactor.core.publisher.Mono.just(com.ram.trading.signal.engine.dto.StockResponse.builder().price(120.0).build()));
        java.time.OffsetDateTime d1 = LocalDate.of(2026, 11, 2).atStartOfDay().atOffset(java.time.ZoneOffset.ofHoursMinutes(5, 30));
        org.mockito.Mockito.when(stocks.getHistoricalCandles(org.mockito.ArgumentMatchers.eq("NIFTY200MOMENTM30"),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    LocalDate from = inv.getArgument(2);
                    // As the real history: daily candles stamped 00:00 IST, from the requested day.
                    return reactor.core.publisher.Mono.just(new com.ram.trading.signal.engine.dto.indicator.HistoricalCandleResponse(
                            "NIFTY200MOMENTM30", java.util.stream.Stream.of(candle(d1, 1000, 1010), candle(d1.plusDays(1), 1010, 1020))
                            .filter(c -> !c.getDateTime().toLocalDate().isBefore(from)).toList()));
                });
        MomentumPaperService paper = new MomentumPaperService(jdbc, stocks, notifications);
        paper.createTables();
        jdbc.execute("create table minute_candle (id bigint auto_increment primary key, symbol varchar(64), "
                + "candle_minute timestamp, close_price double)");
        jdbc.update("insert into momentum_paper_account (id, start_capital, cash, started_on, interest_until) "
                + "values (1, 100000, 90000, '2026-11-02', '2099-01-01')");
        // Sold: 100 @ 100 -> 110, net Rs 950 after charges.
        jdbc.update("insert into momentum_paper_position (list_month, symbol, quantity, entry_day, entry_price, buy_charges, "
                + "exit_day, exit_price, sell_charges, net_pnl, status) values ('2026-11', 'SOLD', 100, '2026-11-02', 100, 20, "
                + "'2026-11-03', 110, 30, 950, 'CLOSED')");
        // Open: 100 @ 100, marked at 120.
        jdbc.update("insert into momentum_paper_position (list_month, symbol, quantity, entry_day, entry_price, buy_charges, "
                + "status) values ('2026-11', 'HELD', 100, '2026-11-02', 100, 20, 'OPEN')");
        jdbc.update("insert into momentum_paper_equity (equity_day, cash, holdings_value, equity, open_positions) "
                + "values ('2026-11-03', 90000, 11000, 101000, 1)");

        var v = new MomentumTaxService(jdbc, stocks, paper).afterTax();

        assertEquals(950 + 21, v.realisedTaxableGain(), 0.01);              // STT on 10,000 + 11,000 added back
        assertEquals(971 * 0.2 * 1.04, v.taxOnRealised(), 0.01);
        assertEquals(1, v.open().size());
        assertTrue(v.unrealisedTaxableGain() > 1900 && v.unrealisedTaxableGain() < 2000, "" + v.unrealisedTaxableGain());
        assertEquals((971 + v.unrealisedTaxableGain()) * 0.2 * 1.04, v.taxOnRealised() + v.taxIfSoldNow(), 0.01);
        assertEquals(v.equity() - v.sellCostsIfSoldNow() - v.taxOnRealised() - v.taxIfSoldNow(), v.afterTaxEquity(), 0.01);
        assertTrue(v.afterTaxPnl() < v.preTaxPnl());

        var idx = v.indexSinceStart();
        assertEquals("SINCE_START", idx.basis());
        assertEquals(null, v.indexSinceFirstBuy(), "bought on the first day: one comparison is enough");
        assertEquals(LocalDate.of(2026, 11, 2), idx.baseDay());                // 00:00 IST is still Nov 2
        assertEquals(1000, idx.baseLevel(), 0.001);                          // the open on the first day
        assertEquals(LocalDate.of(2026, 11, 3), idx.lastDay());
        assertEquals(101000, idx.bookEquityThatDay(), 0.001);               // the book on the same day
        assertTrue(idx.preTaxPct() > 1.99 && idx.preTaxPct() < 2.0, "" + idx.preTaxPct());
        assertEquals(1.0 - idx.preTaxPct(), idx.bookAheadPctPoints(), 0.0001);

        // A book in cash for its first day: the second comparison starts at the first buy,
        // from the equity of the evening before.
        jdbc.update("update momentum_paper_position set entry_day = '2026-11-03'");
        jdbc.update("insert into momentum_paper_equity (equity_day, cash, holdings_value, equity, open_positions) "
                + "values ('2026-11-02', 100500, 0, 100500, 0)");
        var w = new MomentumTaxService(jdbc, stocks, paper).afterTax().indexSinceFirstBuy();
        assertEquals("SINCE_FIRST_BUY", w.basis());
        assertEquals(LocalDate.of(2026, 11, 3), w.baseDay());
        assertEquals((101000 / 100500.0 - 1) * 100, w.bookPctThatDay(), 0.0001);
    }

    private static com.ram.trading.signal.engine.dto.indicator.Candle candle(java.time.OffsetDateTime t, double open, double close) {
        return com.ram.trading.signal.engine.dto.indicator.Candle.builder().dateTime(t)
                .open(java.math.BigDecimal.valueOf(open)).close(java.math.BigDecimal.valueOf(close)).build();
    }

    @Test
    void indexFundPaysExpensesDailyAndShortTermTaxWhenSoldInsideAYear() {
        double[] fund = MomentumTaxService.indexFund(100000, 100, 110, NOV, NOV.plusDays(365), 0.3, RATES);
        assertEquals(110000 * 0.997, fund[0], 0.01);
        assertEquals((110000 * 0.997 - 100000) * 0.2 * 1.04, fund[1], 0.01);
    }
}
