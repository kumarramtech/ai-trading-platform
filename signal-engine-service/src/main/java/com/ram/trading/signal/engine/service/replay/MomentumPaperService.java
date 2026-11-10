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
        // Each holding's price at the 15:40 snapshot, so the holdings view matches it even after a restart.
        jdbc.execute("create table if not exists momentum_paper_close ("
                + "equity_day date not null, symbol varchar(32) not null, price double not null, "
                + "primary key (equity_day, symbol))");
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
        if (fillEnabled && !target.isEmpty()) {
            StringBuilder topUp = new StringBuilder();
            if (fill(today, month, open(), 0, topUp)) {
                out.append("  WHOLE-SHARE TOP-UP (idle cash to the list, equal weight as backtested)\n").append(topUp);
            }
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
            if (ltp != null) {
                jdbc.update("replace into momentum_paper_close (equity_day, symbol, price) values (?, ?, ?)",
                        today, h.symbol(), ltp);
            }
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

    // ------------------------------------------------------------ idle cash (whole shares)

    @Value("${trading.momentum.paper.fill.enabled:true}")
    private boolean fillEnabled = true;

    /** A listed stock whose one share costs up to this many slots still gets one share. */
    @Value("${trading.momentum.paper.fill.one-share-max-slots:1.5}")
    private double oneShareMaxSlots = 1.5;

    /** Top-ups never take a holding above this many slots. */
    @Value("${trading.momentum.paper.fill.max-slots:1.3}")
    private double maxSlots = 1.3;

    /** The 09:25 check tops up only when idle cash is above this share of equity. */
    @Value("${trading.momentum.paper.fill.min-idle-pct:5}")
    private double minIdlePct = 5;

    /** A stock in the plan: fill price (slippage included), shares held now, and whether it is on this month's list. */
    public record FillSlot(String symbol, double price, int held) {
    }

    /**
     * Pure: extra shares to buy with {@code cash}. First one share for each unheld stock
     * whose share costs up to oneShareMaxSlots x slot; then one share at a time for the
     * most underweight stock, never above maxSlots x slot. {@code costPct}: charges allowance.
     */
    static Map<String, Integer> planFill(List<FillSlot> slots, double cash, double slot, double oneShareMaxSlots,
                                        double maxSlots, double costPct) {
        Map<String, Integer> add = new java.util.LinkedHashMap<>();
        double left = cash;
        double k = 1 + costPct / 100;
        for (FillSlot s : slots) {
            if (s.held() == 0 && s.price() <= oneShareMaxSlots * slot && s.price() * k <= left) {
                add.put(s.symbol(), 1);
                left -= s.price() * k;
            }
        }
        while (true) {
            FillSlot best = null;
            double bestValue = Double.MAX_VALUE;
            for (FillSlot s : slots) {
                int q = s.held() + add.getOrDefault(s.symbol(), 0);
                if (q == 0 || (q + 1) * s.price() > maxSlots * slot || s.price() * k > left) {
                    continue;
                }
                double value = q * s.price();
                if (value < bestValue) {
                    bestValue = value;
                    best = s;
                }
            }
            if (best == null) {
                return add;
            }
            add.merge(best.symbol(), 1, Integer::sum);
            left -= best.price() * k;
        }
    }

    /**
     * 09:25 weekdays: when whole shares left more than min-idle-pct of equity in cash,
     * spends it on this month's list by {@link #planFill}. Empty when nothing to do.
     */
    public synchronized java.util.Optional<String> fillIdleCash() {
        if (!fillEnabled) {
            return java.util.Optional.empty();
        }
        LocalDate today = LocalDate.now(IST);
        List<Holding> open = open();
        Integer started = jdbc.queryForObject("select count(*) from momentum_paper_account", Integer.class);
        if (open.isEmpty() || started == null || started == 0) {
            return java.util.Optional.empty();
        }
        accrueInterest(today);
        StringBuilder out = new StringBuilder();
        boolean done = fill(today, YearMonth.from(today).toString(), open, minIdlePct, out);
        if (!done) {
            return java.util.Optional.empty();
        }
        out.append("\n").append(report());
        String text = "PAPER MOMENTUM IDLE CASH TOP-UP " + today + "\n" + out;
        log.info("{}", text);
        send("Paper momentum idle cash top-up", text);
        return java.util.Optional.of(text);
    }

    /** Prices the list, plans and books the top-up; false when idle cash is below {@code minIdle} % or nothing fits. */
    private boolean fill(LocalDate today, String month, List<Holding> open, double minIdle, StringBuilder out) {
        List<String> list = jdbc.queryForList(
                "select symbol from momentum_signal where list_month = ? and symbol <> 'CASH' order by rank_no",
                String.class, month);
        Map<String, Holding> held = new java.util.HashMap<>();
        open.forEach(h -> held.put(h.symbol(), h));
        double cash = cash(today);
        Map<String, Double> prices = new java.util.HashMap<>();
        double equity = cash;
        for (Holding h : open) {
            Double ltp = price(h.symbol());
            prices.put(h.symbol(), ltp);
            equity += (ltp != null ? ltp : h.entryPrice()) * h.quantity();
        }
        if (cash < equity * minIdle / 100) {
            return false;
        }
        double slot = equity / top;
        List<FillSlot> slots = new ArrayList<>();
        java.util.LinkedHashSet<String> universe = new java.util.LinkedHashSet<>(list);
        universe.addAll(held.keySet());
        for (String symbol : universe) {
            Double ltp = prices.containsKey(symbol) ? prices.get(symbol) : price(symbol);
            if (ltp == null) {
                continue;
            }
            Holding h = held.get(symbol);
            // Only stocks on this month's list: a holding that left the list is not topped up.
            if (!list.contains(symbol)) {
                continue;
            }
            slots.add(new FillSlot(symbol, ltp * (1 + slippagePct / 100), h == null ? 0 : h.quantity()));
        }
        Map<String, Integer> plan = planFill(slots, cash, slot, oneShareMaxSlots, maxSlots, 0.2);
        if (plan.isEmpty()) {
            return false;
        }
        out.append(String.format("  idle cash Rs %.0f of equity Rs %.0f (slot Rs %.0f; one share up to %.1f slots, "
                + "top-ups up to %.1f slots)%n", cash, equity, slot, oneShareMaxSlots, maxSlots));
        double spent = 0;
        for (Map.Entry<String, Integer> e : plan.entrySet()) {
            FillSlot s = slots.stream().filter(x -> x.symbol().equals(e.getKey())).findFirst().orElseThrow();
            int qty = e.getValue();
            double value = qty * s.price();
            double charges = DeliveryCharges.buySide(value, brokerage);
            double available = cash(today);
            if (value + charges > available) {
                continue;
            }
            Holding h = held.get(s.symbol());
            if (h == null) {
                jdbc.update("insert into momentum_paper_position (list_month, symbol, quantity, entry_day, entry_price, "
                        + "buy_charges, status) values (?, ?, ?, ?, ?, ?, 'OPEN')", month, s.symbol(), qty, today,
                        s.price(), charges);
            } else {
                int total = h.quantity() + qty;
                double avg = (h.entryPrice() * h.quantity() + value) / total;
                jdbc.update("update momentum_paper_position set quantity = ?, entry_price = ?, buy_charges = buy_charges + ? "
                        + "where id = ?", total, avg, charges, h.id());
            }
            jdbc.update("update momentum_paper_account set cash = cash - ? where id = 1", value + charges);
            spent += value + charges;
            out.append(String.format("  %s %-12s +%3d @ %9.2f = Rs %7.0f%n", h == null ? "BUY " : "ADD ",
                    s.symbol(), qty, s.price(), value));
        }
        out.append(String.format("  spent Rs %.0f, cash now Rs %.0f%n", spent, cash(today)));
        return spent > 0;
    }

    /**
     * One holding priced now. source: LIVE (market stream in the session), CLOSE (market stream's last
     * price outside the session, as the 15:40 snapshot uses; or, when the stream has none, e.g. after a
     * restart, the stored snapshot price), LAST_MINUTE (stored candle, when newer than the snapshot)
     * or NONE (buy price).
     */
    public record HoldingView(String symbol, int quantity, double buyPrice, double cost, Double lastPrice,
                              String priceSource, java.time.LocalDateTime priceTime, double value,
                              Double pnl, Double pnlPct) {
    }

    public record PortfolioView(java.time.LocalDateTime asOf, boolean paperOnly, LocalDate since, double startCapital,
                                double cash, double cost, double marketValue, double unrealised, double unrealisedPct,
                                double realised, double charges, double equity, double totalPnl, double totalPnlPct,
                                int holdings, int priced, List<HoldingView> positions) {
    }

    /**
     * The holdings at the latest price from the market stream (LIVE in the session, CLOSE outside it),
     * otherwise the last stored 1-minute close (about 15:29 that day), otherwise the buy price
     * (flagged NONE). Read-only.
     */
    public PortfolioView portfolioView() {
        return portfolioView(java.time.LocalDateTime.now(IST));
    }

    /** At {@code now} (IST): package-visible for tests. */
    PortfolioView portfolioView(java.time.LocalDateTime now) {
        List<Map<String, Object>> account = jdbc.queryForList(
                "select start_capital, cash, started_on from momentum_paper_account where id = 1");
        if (account.isEmpty()) {
            return new PortfolioView(now, true, null, startCapital, startCapital, 0, 0, 0, 0, 0, 0,
                    startCapital, 0, 0, 0, 0, List.of());
        }
        double start = ((Number) account.get(0).get("start_capital")).doubleValue();
        double cash = ((Number) account.get(0).get("cash")).doubleValue();
        Object startedOn = account.get(0).get("started_on");
        LocalDate since = startedOn instanceof java.sql.Date d ? d.toLocalDate()
                : startedOn instanceof LocalDate d ? d : LocalDate.parse(String.valueOf(startedOn).substring(0, 10));

        List<Holding> open = open();
        boolean session = now.getDayOfWeek().getValue() <= 5
                && !now.toLocalTime().isBefore(java.time.LocalTime.of(9, 15))
                && now.toLocalTime().isBefore(java.time.LocalTime.of(15, 31));
        // The market stream keeps each stock's last price after the close: the same price the
        // 15:40 snapshot stores, so after the close this view and the snapshot agree.
        Map<String, Double> live = new java.util.HashMap<>();
        if (!open.isEmpty()) {
            reactor.core.publisher.Flux.fromIterable(open)
                    .flatMap(h -> stockServiceClient.getStockPrice(h.symbol())
                            .timeout(Duration.ofSeconds(3))
                            .filter(r -> r.getPrice() != null && r.getPrice() > 0)
                            .map(r -> Map.entry(h.symbol(), r.getPrice()))
                            .onErrorResume(ex -> Mono.empty()), 8)
                    .collectList()
                    .blockOptional(Duration.ofSeconds(15))
                    .ifPresent(list -> list.forEach(e -> live.put(e.getKey(), e.getValue())));
        }
        List<String> symbols = open.stream().map(Holding::symbol).toList();
        Map<String, Object[]> stored = lastMinuteCloses(symbols);
        Map<String, Object[]> snapshot = snapshotCloses(symbols);

        List<HoldingView> rows = new ArrayList<>();
        double cost = 0, value = 0;
        int priced = 0;
        for (Holding h : open) {
            double c = h.entryPrice() * h.quantity();
            Double last = live.get(h.symbol());
            String source = session ? "LIVE" : "CLOSE";
            java.time.LocalDateTime at = now;
            if (last == null) {
                // No stream price: the newer of the 15:40 snapshot price and the last stored minute.
                Object[] snap = snapshot.get(h.symbol()), minute = stored.get(h.symbol());
                boolean useSnap = snap != null && (minute == null
                        || !((java.time.LocalDateTime) snap[1]).isBefore((java.time.LocalDateTime) minute[1]));
                Object[] s = useSnap ? snap : minute;
                if (s != null) {
                    last = (Double) s[0];
                    at = (java.time.LocalDateTime) s[1];
                    source = useSnap ? "CLOSE" : "LAST_MINUTE";
                }
            }
            if (last == null) {
                source = "NONE";
                at = null;
            } else {
                priced++;
            }
            double v = (last != null ? last : h.entryPrice()) * h.quantity();
            cost += c;
            value += v;
            rows.add(new HoldingView(h.symbol(), h.quantity(), h.entryPrice(), c, last, source, at, v,
                    last == null ? null : v - c, last == null ? null : (last / h.entryPrice() - 1) * 100));
        }
        Double realised = jdbc.queryForObject(
                "select coalesce(sum(net_pnl), 0) from momentum_paper_position where status = 'CLOSED'", Double.class);
        Double charges = jdbc.queryForObject("select coalesce(sum(buy_charges + coalesce(sell_charges, 0)), 0) "
                + "from momentum_paper_position", Double.class);
        double equity = cash + value;
        return new PortfolioView(now, true, since, start, cash, cost, value, value - cost,
                cost > 0 ? (value / cost - 1) * 100 : 0, realised, charges, equity, equity - start,
                (equity / start - 1) * 100, open.size(), priced, rows);
    }

    /** symbol -> {close, minute} of its latest stored 1-minute candle. */
    private Map<String, Object[]> lastMinuteCloses(List<String> symbols) {
        Map<String, Object[]> out = new java.util.HashMap<>();
        if (symbols.isEmpty()) {
            return out;
        }
        String in = String.join(",", java.util.Collections.nCopies(symbols.size(), "?"));
        jdbc.query("select m.symbol, m.close_price, m.candle_minute from minute_candle m join ("
                        + "select symbol, max(candle_minute) mx from minute_candle where symbol in (" + in + ") "
                        + "and close_price > 0 group by symbol) x on m.symbol = x.symbol and m.candle_minute = x.mx",
                rs -> {
                    out.put(rs.getString(1), new Object[]{rs.getDouble(2), rs.getTimestamp(3).toLocalDateTime()});
                }, symbols.toArray());
        return out;
    }

    /**
     * True when NIFTY has a stored minute candle today from 09:15, i.e. the exchange traded.
     * False on market holidays (the feed then repeats the last day's prices) or if no candles
     * were stored, so the scheduled buys and the snapshot never use stale prices.
     */
    public boolean marketTradedToday() {
        return marketTraded(LocalDate.now(IST));
    }

    boolean marketTraded(LocalDate day) {
        try {
            Integer n = jdbc.queryForObject("select count(*) from minute_candle where symbol = 'NIFTY' "
                    + "and candle_minute >= ? and candle_minute < ?", Integer.class,
                    day.atTime(9, 15), day.plusDays(1).atStartOfDay());
            return n != null && n > 0;
        } catch (Exception ex) {
            log.warn("Momentum paper: could not check today's NIFTY candles: {}", ex.getMessage());
            return false;
        }
    }

    /** symbol -> {price, day at 15:40} from the latest snapshot that priced it. */
    private Map<String, Object[]> snapshotCloses(List<String> symbols) {
        Map<String, Object[]> out = new java.util.HashMap<>();
        if (symbols.isEmpty()) {
            return out;
        }
        String in = String.join(",", java.util.Collections.nCopies(symbols.size(), "?"));
        jdbc.query("select c.symbol, c.price, c.equity_day from momentum_paper_close c join ("
                        + "select symbol, max(equity_day) mx from momentum_paper_close where symbol in (" + in + ") "
                        + "group by symbol) x on c.symbol = x.symbol and c.equity_day = x.mx",
                rs -> {
                    out.put(rs.getString(1), new Object[]{rs.getDouble(2),
                            rs.getDate(3).toLocalDate().atTime(15, 40)});
                }, symbols.toArray());
        return out;
    }

    private void send(String title, String text) {
        notificationClient.sendNotification(NotificationRequest.builder()
                        .channel(NotificationChannel.SLACK).title(title).message(text).build())
                .doOnError(ex -> log.error("Momentum paper: Slack send failed", ex))
                .onErrorResume(ex -> Mono.empty())
                .subscribe();
    }
}
