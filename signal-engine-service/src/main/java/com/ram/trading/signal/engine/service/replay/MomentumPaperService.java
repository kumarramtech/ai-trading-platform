package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.client.NotificationClient;
import com.ram.trading.signal.engine.client.StockServiceClient;
import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.dto.indicator.Candle;
import com.ram.trading.signal.engine.dto.indicator.HistoricalCandleResponse;
import com.ram.trading.signal.engine.dto.notification.NotificationChannel;
import com.ram.trading.signal.engine.dto.notification.NotificationRequest;
import com.ram.trading.signal.engine.service.replay.SwingBacktestService.DeliveryCharges;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Paper portfolio for the monthly momentum list, in its own tables (nothing shared
 * with the intraday paper_trade table or the intraday paper balance):
 *
 *   momentum_paper_account   one row: virtual capital, cash, last rebalance month
 *   momentum_paper_position  one row per holding, OPEN until sold at a rebalance
 *   momentum_paper_equity    one row per day: cash + holdings at the close, NIFTY
 *
 * On the day a month's list is published, sells the stocks that left it and buys
 * the new ones at the live price shortly after the open, slippage-pct worse, with
 * delivery charges at zero brokerage (as the backtest). Cash earns the liquid-fund
 * yield. Holdings kept from the previous month are not resized.
 */
@Service
@Slf4j
public class MomentumPaperService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String CASH = "CASH";

    private final JdbcTemplate jdbc;
    private final StockServiceClient stockServiceClient;
    private final NotificationClient notificationClient;

    @Value("${trading.momentum.paper.capital:100000}")
    private double startCapital = 100000;

    @Value("${trading.momentum.top:30}")
    private int top = 30;

    @Value("${trading.momentum.paper.brokerage:0}")
    private double brokerage = 0;

    @Value("${trading.momentum.paper.slippage-pct:0.10}")
    private double slippagePct = 0.10;

    @Value("${trading.momentum.paper.cash-yield-pct:6.5}")
    private double cashYieldPct = 6.5;

    public MomentumPaperService(JdbcTemplate jdbc, StockServiceClient stockServiceClient,
                                NotificationClient notificationClient) {
        this.jdbc = jdbc;
        this.stockServiceClient = stockServiceClient;
        this.notificationClient = notificationClient;
    }

    @PostConstruct
    void createTables() {
        jdbc.execute("create table if not exists momentum_paper_account ("
                + "id int primary key, start_capital double not null, cash double not null, started_on date not null, "
                + "interest_until date not null, last_rebalance_month varchar(7))");
        jdbc.execute("create table if not exists momentum_paper_position ("
                + "id bigint auto_increment primary key, list_month varchar(7) not null, symbol varchar(32) not null, "
                + "quantity int not null, entry_day date not null, entry_price double not null, buy_charges double not null, "
                + "exit_day date, exit_price double, sell_charges double, net_pnl double, "
                + "status varchar(8) not null, index idx_momentum_paper_status (status))");
        jdbc.execute("create table if not exists momentum_paper_equity ("
                + "equity_day date primary key, cash double not null, holdings_value double not null, equity double not null, "
                + "open_positions int not null, nifty_close double)");
    }

    public record Holding(long id, String symbol, int quantity, double entryPrice, double buyCharges) {
    }

    /** The open paper holdings (for the long-term monitor). */
    public List<Holding> holdings() {
        return open();
    }

    /**
     * Sells one holding at the live price (slippage worse, delivery charges) outside a
     * rebalance: the long-term monitor's stop. The cash waits for the next rebalance.
     * Returns the realised P&L, or null when the holding is no longer open.
     */
    public synchronized Double sell(Holding h, double ltp, String reason) {
        LocalDate today = LocalDate.now(IST);
        accrueInterest(today);
        double fill = ltp * (1 - slippagePct / 100);
        double value = fill * h.quantity();
        double charges = DeliveryCharges.sellSide(value, brokerage);
        double pnl = value - charges - h.entryPrice() * h.quantity() - h.buyCharges();
        int updated = jdbc.update("update momentum_paper_position set exit_day = ?, exit_price = ?, sell_charges = ?, "
                + "net_pnl = ?, status = 'CLOSED' where id = ? and status = 'OPEN'", today, fill, charges, pnl, h.id());
        if (updated == 0) {
            return null;
        }
        jdbc.update("update momentum_paper_account set cash = cash + ? where id = 1", value - charges);
        String text = String.format("PAPER MOMENTUM SELL %s (%s): %d @ %.2f, bought %.2f, P&L Rs %+.0f%n%s",
                h.symbol(), reason, h.quantity(), fill, h.entryPrice(), pnl, report());
        log.info("{}", text);
        send("Paper momentum stop: " + h.symbol(), text);
        return pnl;
    }

    /** Creates the account with the virtual capital on first use. */
    private double cash(LocalDate today) {
        List<Double> cash = jdbc.queryForList("select cash from momentum_paper_account where id = 1", Double.class);
        if (cash.isEmpty()) {
            jdbc.update("insert into momentum_paper_account (id, start_capital, cash, started_on, interest_until) "
                    + "values (1, ?, ?, ?, ?)", startCapital, startCapital, today, today);
            return startCapital;
        }
        return cash.get(0);
    }

    /** Liquid-fund interest on idle cash since the last accrual. */
    private void accrueInterest(LocalDate today) {
        double cash = cash(today);
        LocalDate until = jdbc.queryForObject("select interest_until from momentum_paper_account where id = 1",
                LocalDate.class);
        long days = until == null ? 0 : ChronoUnit.DAYS.between(until, today);
        if (days > 0) {
            jdbc.update("update momentum_paper_account set cash = ?, interest_until = ? where id = 1",
                    cash + cash * cashYieldPct / 100 * days / 365, today);
        }
    }

    private List<Holding> open() {
        return jdbc.query("select id, symbol, quantity, entry_price, buy_charges from momentum_paper_position "
                        + "where status = 'OPEN' order by id",
                (rs, n) -> new Holding(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getDouble(4), rs.getDouble(5)));
    }

    private Double price(String symbol) {
        try {
            StockResponse r = stockServiceClient.getStockPrice(symbol).block(Duration.ofSeconds(10));
            return r == null || r.getPrice() == null || r.getPrice() <= 0 ? null : r.getPrice();
        } catch (Exception ex) {
            log.warn("Momentum paper: no price for {}: {}", symbol, ex.getMessage());
            return null;
        }
    }

    /**
     * Trades this month's published list once: sells what left it (everything when
     * the list is CASH), then buys the new names at an equal share of equity.
     * Returns the Slack text, or empty when there is no list yet or it was traded.
     */
    public java.util.Optional<String> rebalance() {
        return rebalance(false);
    }

    /** {@code force}: trade the list again although this month was traded (the weekly entry after a cash month). */
    public synchronized java.util.Optional<String> rebalance(boolean force) {
        LocalDate today = LocalDate.now(IST);
        String month = YearMonth.from(today).toString();
        List<String> list = jdbc.queryForList(
                "select symbol from momentum_signal where list_month = ? order by rank_no", String.class, month);
        cash(today);
        String done = jdbc.queryForObject("select last_rebalance_month from momentum_paper_account where id = 1",
                String.class);
        if (list.isEmpty() || (month.equals(done) && !force)) {
            return java.util.Optional.empty();
        }
        accrueInterest(today);
        List<String> target = list.contains(CASH) ? List.of() : list;
        StringBuilder out = new StringBuilder();
        out.append("PAPER MOMENTUM REBALANCE ").append(month).append(" (virtual Rs ")
                .append(Math.round(startCapital)).append(", live prices after the open, ")
                .append(slippagePct).append("% slippage, delivery charges)\n");

        List<String> missing = new ArrayList<>();
        double realised = 0;
        for (Holding h : open()) {
            if (target.contains(h.symbol())) {
                continue;
            }
            Double ltp = price(h.symbol());
            if (ltp == null) {
                missing.add(h.symbol() + " (sell)");
                continue;
            }
            double fill = ltp * (1 - slippagePct / 100);
            double value = fill * h.quantity();
            double charges = DeliveryCharges.sellSide(value, brokerage);
            double pnl = value - charges - h.entryPrice() * h.quantity() - h.buyCharges();
            realised += pnl;
            jdbc.update("update momentum_paper_position set exit_day = ?, exit_price = ?, sell_charges = ?, net_pnl = ?, "
                    + "status = 'CLOSED' where id = ?", today, fill, charges, pnl, h.id());
            jdbc.update("update momentum_paper_account set cash = cash + ? where id = 1", value - charges);
            out.append(String.format("  SELL %-12s %4d @ %9.2f  P&L Rs %+8.0f%n", h.symbol(), h.quantity(), fill, pnl));
        }

        List<Holding> kept = open();
        double equity = cash(today);
        java.util.Map<String, Double> prices = new java.util.HashMap<>();
        for (Holding h : kept) {
            Double ltp = price(h.symbol());
            prices.put(h.symbol(), ltp);
            equity += (ltp != null ? ltp : h.entryPrice()) * h.quantity();
        }
        double slot = equity / top;
        List<String> held = kept.stream().map(Holding::symbol).toList();
        for (String symbol : target) {
            if (held.contains(symbol)) {
                continue;
            }
            Double ltp = price(symbol);
            if (ltp == null) {
                missing.add(symbol + " (buy)");
                continue;
            }
            double fill = ltp * (1 + slippagePct / 100);
            double available = cash(today);
            int quantity = (int) Math.floor(Math.min(slot, available) / fill);
            while (quantity > 0 && quantity * fill + DeliveryCharges.buySide(quantity * fill, brokerage) > available) {
                quantity--;
            }
            if (quantity == 0) {
                missing.add(symbol + " (one share > slot)");
                continue;
            }
            double value = quantity * fill;
            double charges = DeliveryCharges.buySide(value, brokerage);
            jdbc.update("insert into momentum_paper_position (list_month, symbol, quantity, entry_day, entry_price, buy_charges, status) "
                    + "values (?, ?, ?, ?, ?, ?, 'OPEN')", month, symbol, quantity, today, fill, charges);
            jdbc.update("update momentum_paper_account set cash = cash - ? where id = 1", value + charges);
            out.append(String.format("  BUY  %-12s %4d @ %9.2f  = Rs %8.0f%n", symbol, quantity, fill, value));
        }
        jdbc.update("update momentum_paper_account set last_rebalance_month = ? where id = 1", month);

        if (target.isEmpty()) {
            out.append("  NIFTY below its average: all cash (liquid fund ").append(cashYieldPct).append("%)\n");
        }
        if (!missing.isEmpty()) {
            out.append("  NOT DONE (no price / too dear): ").append(String.join(", ", missing)).append("\n");
        }
        out.append(String.format("  realised this rebalance Rs %+.0f%n%n", realised));
        out.append(report());
        String text = out.toString();
        log.info("{}", text);
        send("Paper momentum rebalance " + month, text);
        return java.util.Optional.of(text);
    }

    /** Values the holdings at today's prices and stores the day's equity (after the close). */
    public synchronized void snapshot() {
        LocalDate today = LocalDate.now(IST);
        Integer started = jdbc.queryForObject("select count(*) from momentum_paper_account", Integer.class);
        if (started == null || started == 0) {
            return;
        }
        accrueInterest(today);
        double holdings = 0;
        List<Holding> open = open();
        List<Mark> marks = new ArrayList<>();
        for (Holding h : open) {
            Double ltp = price(h.symbol());
            marks.add(new Mark(h.symbol(), h.quantity(), h.entryPrice(), ltp));
            holdings += (ltp != null ? ltp : h.entryPrice()) * h.quantity();
        }
        double cash = cash(today);
        jdbc.update("replace into momentum_paper_equity (equity_day, cash, holdings_value, equity, open_positions, nifty_close) "
                + "values (?, ?, ?, ?, ?, ?)", today, cash, holdings, cash + holdings, open.size(), niftyClose(today));
        if (dailySummary) {
            dailySummary(today, marks).ifPresent(text -> send("Paper momentum daily " + today, text));
        }
    }

    @Value("${trading.momentum.paper.daily-summary:true}")
    private boolean dailySummary = true;

    /** One holding valued at the close; ltp null when no price came back. */
    public record Mark(String symbol, int quantity, double entryPrice, Double ltp) {
        Double pct() {
            return ltp == null ? null : (ltp / entryPrice - 1) * 100;
        }
    }

    /**
     * The day's Slack summary, from the equity row just stored. Empty on a day with no
     * holdings and nothing sold (an all-cash book has nothing to report every evening).
     */
    java.util.Optional<String> dailySummary(LocalDate today, List<Mark> marks) {
        Double realisedToday = jdbc.queryForObject("select sum(net_pnl) from momentum_paper_position "
                + "where status = 'CLOSED' and exit_day = ?", Double.class, today);
        if (marks.isEmpty() && realisedToday == null) {
            return java.util.Optional.empty();
        }
        List<Map<String, Object>> days = jdbc.queryForList(
                "select equity_day, cash, equity, nifty_close from momentum_paper_equity where equity_day <= ? "
                        + "order by equity_day", today);
        if (days.isEmpty()) {
            return java.util.Optional.empty();
        }
        double start = jdbc.queryForObject("select start_capital from momentum_paper_account where id = 1", Double.class);
        List<Double> equity = days.stream().map(d -> ((Number) d.get("equity")).doubleValue()).toList();
        List<Double> nifty = days.stream()
                .map(d -> d.get("nifty_close") == null ? null : ((Number) d.get("nifty_close")).doubleValue()).toList();
        double cash = ((Number) days.get(days.size() - 1).get("cash")).doubleValue();
        return java.util.Optional.of(formatDaily(today, start, cash, equity, nifty,
                realisedToday == null ? 0 : realisedToday, marks));
    }

    /**
     * equity and nifty: one value per stored day up to today, oldest first (a NIFTY value
     * may be null). The NIFTY return runs from the first day it is known, as report() does.
     */
    static String formatDaily(LocalDate today, double startCapital, double cash, List<Double> equity,
                              List<Double> nifty, double realisedToday, List<Mark> marks) {
        StringBuilder out = new StringBuilder();
        int n = equity.size();
        double now = equity.get(n - 1);
        double peak = startCapital, maxDd = 0;
        for (double e : equity) {
            peak = Math.max(peak, e);
            maxDd = Math.max(maxDd, (peak - e) / peak * 100);
        }
        double fromPeak = (peak - now) / peak * 100;
        out.append(String.format("PAPER MOMENTUM %s (paper only, no real orders)%n", today));
        if (n >= 2) {
            double prev = equity.get(n - 2);
            out.append(String.format("  today      Rs %+,.0f (%+.2f%%)", now - prev, (now / prev - 1) * 100));
            Double nt = nifty.get(n - 1), np = nifty.get(n - 2);
            if (nt != null && np != null) {
                out.append(String.format(", NIFTY %+.2f%%", (nt / np - 1) * 100));
            }
            out.append("\n");
        }
        out.append(String.format("  since start Rs %+,.0f (%+.2f%%)", now - startCapital, (now / startCapital - 1) * 100));
        Double first = nifty.stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        Double last = nifty.get(n - 1);
        if (first != null && last != null) {
            out.append(String.format(", NIFTY %+.2f%%", (last / first - 1) * 100));
        }
        out.append("\n");
        out.append(String.format("  equity Rs %,.0f = holdings Rs %,.0f + cash Rs %,.0f%n", now, now - cash, cash));
        out.append(String.format("  below peak %.1f%%, worst fall so far %.1f%%%n", fromPeak, maxDd));
        if (realisedToday != 0) {
            out.append(String.format("  sold today: realised Rs %+,.0f%n", realisedToday));
        }

        List<Mark> priced = marks.stream().filter(m -> m.ltp() != null)
                .sorted(java.util.Comparator.comparing(Mark::pct).reversed()).toList();
        long up = priced.stream().filter(m -> m.pct() > 0).count();
        out.append(String.format("  holdings %d: %d up, %d down vs the buy price%n", marks.size(), up, priced.size() - up));
        int k = Math.min(3, priced.size());
        if (k > 0) {
            out.append("  best:  ").append(marksText(priced.subList(0, k))).append("\n");
            if (priced.size() > 3) {
                out.append("  worst: ").append(marksText(priced.subList(priced.size() - k, priced.size()).reversed())).append("\n");
            }
        }
        List<String> noPrice = marks.stream().filter(m -> m.ltp() == null).map(Mark::symbol).toList();
        if (!noPrice.isEmpty()) {
            out.append("  no price (valued at the buy price): ").append(String.join(", ", noPrice)).append("\n");
        }
        out.append("  Rules: hold to the monthly review; no daily action needed.\n");
        return out.toString();
    }

    private static String marksText(List<Mark> marks) {
        return String.join(", ", marks.stream().map(m -> String.format("%s %+.1f%%", m.symbol(), m.pct())).toList());
    }

    private Double niftyClose(LocalDate today) {
        try {
            HistoricalCandleResponse r = stockServiceClient.getHistoricalCandles("NIFTY", "day", today.minusDays(10), today)
                    .block(Duration.ofSeconds(20));
            if (r == null || r.getCandles() == null) {
                return null;
            }
            return r.getCandles().stream().filter(c -> c.getDateTime() != null && c.getClose() != null)
                    .max(java.util.Comparator.comparing(Candle::getDateTime))
                    .map(c -> c.getClose().doubleValue()).orElse(null);
        } catch (Exception ex) {
            log.warn("Momentum paper: no NIFTY close: {}", ex.getMessage());
            return null;
        }
    }

    /** Performance since the start against NIFTY over the same days, and the holdings. */
    public String report() {
        StringBuilder out = new StringBuilder();
        List<Map<String, Object>> account = jdbc.queryForList("select * from momentum_paper_account where id = 1");
        if (account.isEmpty()) {
            return "PAPER MOMENTUM: not started (the first list starts it)\n";
        }
        List<Map<String, Object>> days = jdbc.queryForList(
                "select equity_day, equity, nifty_close from momentum_paper_equity order by equity_day");
        double start = ((Number) account.get(0).get("start_capital")).doubleValue();
        out.append("PAPER MOMENTUM since ").append(account.get(0).get("started_on")).append("\n");
        if (!days.isEmpty()) {
            Map<String, Object> last = days.get(days.size() - 1);
            double equity = ((Number) last.get("equity")).doubleValue();
            double peak = start, maxDd = 0;
            Double niftyFirst = null, niftyLast = null;
            for (Map<String, Object> d : days) {
                double e = ((Number) d.get("equity")).doubleValue();
                peak = Math.max(peak, e);
                maxDd = Math.max(maxDd, (peak - e) / peak * 100);
                if (d.get("nifty_close") != null) {
                    double nc = ((Number) d.get("nifty_close")).doubleValue();
                    niftyFirst = niftyFirst == null ? nc : niftyFirst;
                    niftyLast = nc;
                }
            }
            out.append(String.format("  equity Rs %.0f on %s: %+.2f%% (Rs %+.0f), max drawdown %.1f%%%n",
                    equity, last.get("equity_day"), (equity / start - 1) * 100, equity - start, maxDd));
            if (niftyFirst != null) {
                out.append(String.format("  NIFTY over the same days: %+.2f%%%n", (niftyLast / niftyFirst - 1) * 100));
            }
        }
        Double realised = jdbc.queryForObject(
                "select coalesce(sum(net_pnl), 0) from momentum_paper_position where status = 'CLOSED'", Double.class);
        Double charges = jdbc.queryForObject("select coalesce(sum(buy_charges + coalesce(sell_charges, 0)), 0) "
                + "from momentum_paper_position", Double.class);
        out.append(String.format("  cash Rs %.0f, realised P&L Rs %+.0f, charges paid Rs %.0f%n",
                ((Number) account.get(0).get("cash")).doubleValue(), realised, charges));
        List<Holding> open = open();
        out.append("  holding ").append(open.size()).append(": ")
                .append(String.join(", ", open.stream().map(Holding::symbol).toList())).append("\n");
        return out.toString();
    }

    private void send(String title, String text) {
        notificationClient.sendNotification(NotificationRequest.builder()
                        .channel(NotificationChannel.SLACK).title(title).message(text).build())
                .doOnError(ex -> log.error("Momentum paper: Slack send failed", ex))
                .onErrorResume(ex -> Mono.empty())
                .subscribe();
    }
}
