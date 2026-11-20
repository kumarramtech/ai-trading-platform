package com.ram.trading.stock.service.momexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.stock.service.calendar.MarketCalendarService;
import com.ram.trading.stock.service.momexec.MomentumExecPlanner.BookHolding;
import com.ram.trading.stock.service.momexec.MomentumExecPlanner.Limits;
import com.ram.trading.stock.service.momexec.MomentumExecPlanner.Order;
import com.ram.trading.stock.service.momexec.MomentumExecPlanner.Plan;
import com.ram.trading.stock.service.momexec.UpstoxDeliveryBroker.OrderState;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MOMENTUM EXECUTOR (separate component, built 2026-11-20): turns the monthly momentum PAPER book's decisions into
 * real DELIVERY orders. It only READS the paper book's tables (momentum_paper_position, momentum_paper_account) and
 * never changes them or the momentum code in signal-engine; it writes only its own tables (mom_exec_book,
 * mom_exec_order).
 * <p>
 * Modes (momentum-exec.mode):
 * <ul>
 *   <li>OFF: nothing;</li>
 *   <li>DRY_RUN (default): builds the exact real orders (symbol, quantity, limit price, delivery) at 09:30, 11:30 and
 *       13:10, sends them to Slack and books them as filled at the last price in its own dry-run book. Nothing goes to
 *       Upstox;</li>
 *   <li>LIVE: places the orders at Upstox. Needs a second switch (momentum-exec.live-confirm=REAL-MONEY), a capital
 *       cap and a per-order cap; limit orders only; sells only shares this executor bought (and no more than Upstox
 *       holds); checks each order's status every 2 minutes, cancels what is still open at 15:10, and compares its book
 *       with the Upstox holdings, alerting on any shortfall. Without an Upstox login it does nothing and alerts.
 *       An empty LIVE book starts only on a paper rebalance day, so it never buys a month's list mid-month.</li>
 * </ul>
 */
@Service
@Slf4j
public class MomentumExecutionService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final String CONFIRM = "REAL-MONEY";

    private final JdbcTemplate jdbc;
    private final UpstoxDeliveryBroker broker;
    private final MarketCalendarService calendar;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${momentum-exec.mode:DRY_RUN}")
    private String mode = "DRY_RUN";

    @Value("${momentum-exec.live-confirm:}")
    private String liveConfirm = "";

    /** Rupees this executor may have invested; 0 = an exact copy of the paper book (DRY_RUN only). */
    @Value("${momentum-exec.capital:0}")
    private double capital = 0;

    @Value("${momentum-exec.per-order-cap:0}")
    private double perOrderCap = 0;

    @Value("${momentum-exec.buffer-pct:0.5}")
    private double bufferPct = 0.5;

    @Value("${momentum-exec.max-orders:40}")
    private int maxOrders = 40;

    @Value("${momentum-exec.notify-url:http://localhost:8087/notifications/send}")
    private String notifyUrl = "http://localhost:8087/notifications/send";

    public MomentumExecutionService(JdbcTemplate jdbc, UpstoxDeliveryBroker broker, MarketCalendarService calendar) {
        this.jdbc = jdbc;
        this.broker = broker;
        this.calendar = calendar;
    }

    @PostConstruct
    void createTables() {
        jdbc.execute("create table if not exists mom_exec_book (mode varchar(8) not null, symbol varchar(40) not null, "
                + "instrument_key varchar(64), qty int not null, avg_price double not null, paper_qty_seen int not null, "
                + "updated_at datetime not null, primary key (mode, symbol))");
        jdbc.execute("create table if not exists mom_exec_order (id bigint auto_increment primary key, "
                + "created_at datetime not null, mode varchar(8) not null, symbol varchar(40) not null, "
                + "instrument_key varchar(64), side varchar(4) not null, qty int not null, ltp double, limit_price double, "
                + "paper_qty int, reason varchar(120), status varchar(10) not null, broker_order_id varchar(40), "
                + "filled_qty int, avg_fill double, message varchar(255), updated_at datetime, "
                + "index idx_mom_exec_order_status (mode, status))");
    }

    String mode() {
        String m = mode == null ? "" : mode.trim().toUpperCase();
        return m.equals("LIVE") || m.equals("OFF") ? m : "DRY_RUN";
    }

    /** Why LIVE may not send orders, or null when every live switch is set. */
    String liveBlock() {
        if (!CONFIRM.equals(liveConfirm == null ? "" : liveConfirm.trim())) {
            return "momentum-exec.live-confirm is not " + CONFIRM;
        }
        if (capital <= 0) {
            return "momentum-exec.capital is not set";
        }
        if (perOrderCap <= 0) {
            return "momentum-exec.per-order-cap is not set";
        }
        return null;
    }

    // ------------------------------------------------------------------ schedules

    /** After the paper book's 09:20 sells / 09:25 top-up, its 11:20 late sells, and its 13:00 buys. */
    @Scheduled(cron = "0 30 9,11 * * MON-FRI", zone = "Asia/Kolkata")
    public void morningJob() {
        safeRun("scheduled");
    }

    @Scheduled(cron = "0 10 13 * * MON-FRI", zone = "Asia/Kolkata")
    public void middayJob() {
        safeRun("scheduled");
    }

    @Scheduled(cron = "0 */2 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void verifyJob() {
        if (!"LIVE".equals(mode())) {
            return;
        }
        LocalTime t = LocalTime.now(IST);
        if (t.isBefore(LocalTime.of(9, 16)) || t.isAfter(LocalTime.of(15, 40))) {
            return;
        }
        try {
            verify(!t.isBefore(LocalTime.of(15, 10)));
        } catch (Exception ex) {
            log.error("MOMENTUM EXEC | verify failed", ex);
        }
    }

    private void safeRun(String trigger) {
        try {
            String out = run(trigger);
            log.info("MOMENTUM EXEC | {}", out.replace('\n', ' '));
        } catch (Exception ex) {
            log.error("MOMENTUM EXEC | run failed", ex);
            notifySlack("Momentum executor FAILED", ex.toString());
        }
    }

    // ------------------------------------------------------------------ the run

    public synchronized String run(String trigger) throws Exception {
        String m = mode();
        if ("OFF".equals(m)) {
            return "mode OFF";
        }
        LocalDate today = LocalDate.now(IST);
        boolean live = "LIVE".equals(m);
        if (live || !"manual".equals(trigger)) {
            if (!calendar.check(today).tradingDay()) {
                return "not a trading day";
            }
        }
        if (live) {
            String block = liveBlock();
            if (block != null) {
                notifySlack("Momentum executor LIVE blocked", block + ": no orders sent.");
                return "LIVE blocked: " + block;
            }
            LocalTime t = LocalTime.now(IST);
            if (t.isBefore(LocalTime.of(9, 15)) || t.isAfter(LocalTime.of(15, 0))) {
                return "LIVE: outside 09:15-15:00, no orders";
            }
        }
        String token = broker.token();
        if (token == null) {
            notifySlack("Momentum executor: no Upstox login", "Mode " + m + ": nothing done. Log in to Upstox, then "
                    + "POST /momentum-exec/run.");
            return "no Upstox token: nothing done";
        }

        // The paper book (read only).
        Map<String, Integer> paper = new HashMap<>();
        Map<String, Double> paperEntry = new HashMap<>();
        boolean paperTradedToday = false;
        for (Map<String, Object> r : jdbc.queryForList("select symbol, sum(quantity) q, max(entry_price) px, "
                + "max(entry_day) d from momentum_paper_position where status = 'OPEN' group by symbol")) {
            paper.put((String) r.get("symbol"), ((Number) r.get("q")).intValue());
            paperEntry.put((String) r.get("symbol"), ((Number) r.get("px")).doubleValue());
            paperTradedToday |= today.equals(toDate(r.get("d")));
        }
        paperTradedToday |= jdbc.queryForObject("select count(*) from momentum_paper_position where exit_day = ?",
                Integer.class, Date.valueOf(today)) > 0;
        List<Double> cashRow = jdbc.queryForList("select cash from momentum_paper_account where id = 1", Double.class);
        double paperCash = cashRow.isEmpty() ? 0 : cashRow.get(0);

        Map<String, BookHolding> book = book(m);
        Map<String, Integer> pendingBuy = new HashMap<>();
        Set<String> pendingSell = new HashSet<>();
        for (Map<String, Object> r : jdbc.queryForList("select symbol, side, qty from mom_exec_order where mode = ? "
                + "and status = 'SENT'", m)) {
            if ("BUY".equals(r.get("side"))) {
                pendingBuy.merge((String) r.get("symbol"), ((Number) r.get("qty")).intValue(), Integer::sum);
            } else {
                pendingSell.add((String) r.get("symbol"));
            }
        }
        if (live && book.isEmpty() && pendingBuy.isEmpty() && !paperTradedToday) {
            return "LIVE book is empty: it starts on the next paper rebalance day";
        }

        Set<String> symbols = new LinkedHashSet<>(paper.keySet());
        symbols.addAll(book.keySet());
        Map<String, String> keys = new HashMap<>();
        Map<String, Double> ticks = new HashMap<>();
        for (String s : symbols) {
            List<Map<String, Object>> k = jdbc.queryForList("select instrument_key, tick_size from instrument_master "
                    + "where segment = 'NSE_EQ' and upper(trading_symbol) = ? limit 1", s);
            if (!k.isEmpty()) {
                keys.put(s, (String) k.get(0).get("instrument_key"));
                if (k.get(0).get("tick_size") != null) {
                    ticks.put(s, ((Number) k.get(0).get("tick_size")).doubleValue() / 100); // stored in paise
                }
            }
        }
        Map<String, Double> byKey = broker.ltp(token, new ArrayList<>(new LinkedHashSet<>(keys.values())));
        Map<String, Double> ltp = new HashMap<>();
        keys.forEach((s, k) -> {
            if (byKey.containsKey(k)) {
                ltp.put(s, byKey.get(k));
            }
        });
        double paperEquity = paperCash;
        for (Map.Entry<String, Integer> e : paper.entrySet()) {
            paperEquity += e.getValue() * ltp.getOrDefault(e.getKey(), paperEntry.get(e.getKey()));
        }

        Plan plan = MomentumExecPlanner.plan(paper, paperEquity, book, pendingBuy, pendingSell, ltp, ticks,
                new Limits(capital, perOrderCap, bufferPct, maxOrders));
        List<String> skipped = new ArrayList<>(plan.skipped());
        List<Order> orders = new ArrayList<>(plan.orders());

        if (live) {
            orders = liveGuards(token, orders, keys, book, skipped);
        }
        StringBuilder lines = new StringBuilder();
        for (Order o : orders) {
            String key = keys.get(o.symbol());
            if (!live) {
                long id = insertOrder(m, o, key, "DRY_RUN", null, null);
                applyFill(m, id, o.symbol(), key, o.side(), o.qty(), o.ltp(), o.paperQty());
                lines.append(line(o, "dry run, booked at the last price"));
                continue;
            }
            try {
                String orderId = broker.place(token, key, o.side(), o.qty(), o.limit());
                insertOrder(m, o, key, "SENT", orderId, null);
                lines.append(line(o, "sent, order " + orderId));
            } catch (Exception ex) {
                insertOrder(m, o, key, "REJECTED", null, ex.getMessage());
                lines.append(line(o, "NOT SENT: " + ex.getMessage()));
            }
        }
        plan.seenUpdates().forEach((s, q) -> jdbc.update("update mom_exec_book set paper_qty_seen = ? where mode = ? "
                + "and symbol = ?", q, m, s));

        String head = String.format("MOMENTUM EXECUTOR %s (%s, %s): paper equity Rs %,.0f, scale %.2f, %d orders",
                m, trigger, LocalDateTime.now(IST).toLocalTime().withNano(0), paperEquity, plan.scale(), orders.size());
        String text = head + "\n" + lines + (skipped.isEmpty() ? "" : "Not done:\n  " + String.join("\n  ", skipped) + "\n");
        if (!orders.isEmpty() || (live && !skipped.isEmpty())) {
            notifySlack(live ? "Momentum LIVE orders" : "Momentum DRY-RUN orders (nothing sent to Upstox)", text
                    + "\n" + bookLine(m));
        }
        return text;
    }

    /** LIVE: sells never above the shares Upstox holds; buys within the cash Upstox shows. */
    private List<Order> liveGuards(String token, List<Order> orders, Map<String, String> keys,
                                   Map<String, BookHolding> book, List<String> skipped) throws Exception {
        Map<String, Integer> shares = broker.brokerShares(token);
        double funds = broker.availableFunds(token);
        List<Order> ok = new ArrayList<>();
        for (Order o : orders) {
            if (o.side().equals("SELL")) {
                int at = shares.getOrDefault(keys.get(o.symbol()), 0);
                if (at < o.qty()) {
                    skipped.add(String.format("%s sell: book %d shares but Upstox shows %d: MISMATCH, not sold",
                            o.symbol(), o.qty(), at));
                    notifySlack("Momentum executor MISMATCH", o.symbol() + ": this book holds " + o.qty()
                            + " but Upstox shows " + at + ". Nothing sold; check the account.");
                    continue;
                }
                ok.add(o);
            } else if (o.value() * 1.002 > funds) {
                skipped.add(String.format("%s buy: Rs %.0f needed, Upstox cash Rs %.0f", o.symbol(), o.value(), funds));
            } else {
                funds -= o.value() * 1.002;
                ok.add(o);
            }
        }
        return ok;
    }

    // ------------------------------------------------------------------ LIVE order checks

    /** LIVE: each SENT order's status; fills go to the book. {@code cancelOpen}: cancel what is still open (15:10). */
    public synchronized String verify(boolean cancelOpen) throws Exception {
        List<Map<String, Object>> sent = jdbc.queryForList("select * from mom_exec_order where mode = 'LIVE' "
                + "and status = 'SENT' order by id");
        if (sent.isEmpty()) {
            return "no open orders";
        }
        String token = broker.token();
        if (token == null) {
            notifySlack("Momentum executor: no Upstox login", sent.size() + " LIVE orders could not be checked.");
            return "no Upstox token";
        }
        StringBuilder out = new StringBuilder();
        boolean changed = false;
        for (Map<String, Object> r : sent) {
            long id = ((Number) r.get("id")).longValue();
            String orderId = (String) r.get("broker_order_id");
            OrderState st = broker.order(token, orderId);
            if (!st.finished() && cancelOpen) {
                try {
                    broker.cancel(token, orderId);
                } catch (Exception ex) {
                    out.append(r.get("symbol")).append(": cancel failed ").append(ex.getMessage()).append("\n");
                }
                st = broker.order(token, orderId);
            }
            if (!st.finished()) {
                continue;
            }
            int filled = st.filled();
            String status = "complete".equals(st.status()) ? "FILLED" : filled > 0 ? "PARTIAL" : st.status().toUpperCase();
            jdbc.update("update mom_exec_order set status = ?, filled_qty = ?, avg_fill = ?, message = ?, updated_at = ? "
                    + "where id = ? and status = 'SENT'", status, filled, st.average(), cut(st.message()),
                    LocalDateTime.now(IST), id);
            if (filled > 0) {
                applyFill("LIVE", id, (String) r.get("symbol"), (String) r.get("instrument_key"), (String) r.get("side"),
                        filled, st.average(), r.get("paper_qty") == null ? 0 : ((Number) r.get("paper_qty")).intValue());
            }
            out.append(String.format("  %-4s %-12s %s %d of %s @ %.2f %s%n", r.get("side"), r.get("symbol"), status, filled,
                    r.get("qty"), st.average(), st.message()));
            changed = true;
        }
        if (changed) {
            String mismatch = reconcile(token);
            notifySlack("Momentum LIVE fills", out + (mismatch.isEmpty() ? "Book matches Upstox." : mismatch)
                    + "\n" + bookLine("LIVE"));
        }
        return out.toString();
    }

    /** Stocks where this book holds more than Upstox shows (empty = all fine). */
    String reconcile(String token) throws Exception {
        Map<String, Integer> shares = broker.brokerShares(token);
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> r : jdbc.queryForList("select symbol, instrument_key, qty from mom_exec_book where mode = 'LIVE'")) {
            int qty = ((Number) r.get("qty")).intValue();
            int at = shares.getOrDefault((String) r.get("instrument_key"), 0);
            if (at < qty) {
                out.append(String.format("MISMATCH %s: book %d, Upstox %d%n", r.get("symbol"), qty, at));
            }
        }
        if (!out.isEmpty()) {
            notifySlack("Momentum executor MISMATCH", out.toString());
        }
        return out.toString();
    }

    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void closeCheckJob() {
        if (!"LIVE".equals(mode()) || !calendar.check(LocalDate.now(IST)).tradingDay()) {
            return;
        }
        try {
            String token = broker.token();
            if (token != null) {
                reconcile(token);
            }
        } catch (Exception ex) {
            log.error("MOMENTUM EXEC | close check failed", ex);
        }
    }

    // ------------------------------------------------------------------ book

    private Map<String, BookHolding> book(String m) {
        Map<String, BookHolding> out = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("select symbol, qty, paper_qty_seen from mom_exec_book where mode = ?", m)) {
            out.put((String) r.get("symbol"), new BookHolding((String) r.get("symbol"), ((Number) r.get("qty")).intValue(),
                    ((Number) r.get("paper_qty_seen")).intValue()));
        }
        return out;
    }

    private long insertOrder(String m, Order o, String key, String status, String orderId, String message) {
        LocalDateTime now = LocalDateTime.now(IST);
        jdbc.update("insert into mom_exec_order (created_at, mode, symbol, instrument_key, side, qty, ltp, limit_price, "
                        + "paper_qty, reason, status, broker_order_id, filled_qty, avg_fill, message, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", now, m, o.symbol(), key, o.side(), o.qty(),
                o.ltp(), o.limit(), o.paperQty(), cut(o.reason()), status, orderId,
                "DRY_RUN".equals(status) ? o.qty() : null, "DRY_RUN".equals(status) ? o.ltp() : null, cut(message), now);
        return jdbc.queryForObject("select max(id) from mom_exec_order where mode = ? and symbol = ?", Long.class, m, o.symbol());
    }

    private void applyFill(String m, long orderId, String symbol, String key, String side, int filled, double price,
                           int paperQty) {
        LocalDateTime now = LocalDateTime.now(IST);
        List<Map<String, Object>> cur = jdbc.queryForList("select qty, avg_price from mom_exec_book where mode = ? and symbol = ?",
                m, symbol);
        int have = cur.isEmpty() ? 0 : ((Number) cur.get(0).get("qty")).intValue();
        double avg = cur.isEmpty() ? 0 : ((Number) cur.get(0).get("avg_price")).doubleValue();
        if ("BUY".equals(side)) {
            int total = have + filled;
            double newAvg = (have * avg + filled * price) / total;
            if (cur.isEmpty()) {
                jdbc.update("insert into mom_exec_book (mode, symbol, instrument_key, qty, avg_price, paper_qty_seen, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?)", m, symbol, key, total, newAvg, paperQty, now);
            } else {
                jdbc.update("update mom_exec_book set qty = ?, avg_price = ?, paper_qty_seen = ?, updated_at = ? "
                        + "where mode = ? and symbol = ?", total, newAvg, paperQty, now, m, symbol);
            }
        } else if (have - filled <= 0) {
            jdbc.update("delete from mom_exec_book where mode = ? and symbol = ?", m, symbol);
        } else {
            jdbc.update("update mom_exec_book set qty = ?, updated_at = ? where mode = ? and symbol = ?", have - filled,
                    now, m, symbol);
        }
        log.info("MOMENTUM EXEC | {} order {} {} {} x {} @ {}", m, orderId, side, symbol, filled, price);
    }

    // ------------------------------------------------------------------ report

    private String bookLine(String m) {
        Map<String, Object> r = jdbc.queryForMap("select count(*) n, coalesce(sum(qty * avg_price), 0) cost from mom_exec_book "
                + "where mode = ?", m);
        return String.format("%s book: %s stocks, cost Rs %,.0f", m, r.get("n"), ((Number) r.get("cost")).doubleValue());
    }

    public String report() {
        String m = mode();
        StringBuilder sb = new StringBuilder("MOMENTUM EXECUTOR (separate component; copies the paper momentum book)\n");
        sb.append("mode ").append(m);
        if ("LIVE".equals(m)) {
            String b = liveBlock();
            sb.append(b == null ? " (all live switches set)" : " (BLOCKED: " + b + ")");
        } else {
            sb.append(" (nothing is sent to Upstox)");
        }
        sb.append(String.format("%ncapital %s, per-order cap %s, limit buffer %.2f%%, max %d orders a run%n",
                capital > 0 ? String.format("Rs %,.0f", capital) : "exact copy of the paper book",
                perOrderCap > 0 ? String.format("Rs %,.0f", perOrderCap) : "none", bufferPct, maxOrders));
        sb.append("Runs 09:30, 11:30, 13:10 on trading days").append("LIVE".equals(m)
                ? "; order checks every 2 min, open orders cancelled at 15:10, holdings check 15:35\n" : "\n");

        Map<String, Integer> paper = new HashMap<>();
        jdbc.queryForList("select symbol, sum(quantity) q from momentum_paper_position where status = 'OPEN' group by symbol")
                .forEach(r -> paper.put((String) r.get("symbol"), ((Number) r.get("q")).intValue()));
        for (String bm : List.of("DRY_RUN", "LIVE")) {
            Map<String, BookHolding> book = book(bm);
            if (book.isEmpty() && !bm.equals(m)) {
                continue;
            }
            sb.append("\n").append(bookLine(bm)).append(" (paper book holds ").append(paper.size()).append(")\n");
            Set<String> all = new java.util.TreeSet<>(paper.keySet());
            all.addAll(book.keySet());
            List<String> diff = new ArrayList<>();
            for (String s : all) {
                int p = paper.getOrDefault(s, 0), b = book.containsKey(s) ? book.get(s).qty() : 0;
                if ((p == 0) != (b == 0)) {
                    diff.add(s + " paper " + p + " / book " + b);
                }
            }
            sb.append(diff.isEmpty() ? "  same stocks as the paper book\n" : "  differs: " + String.join(", ", diff) + "\n");
        }
        List<Map<String, Object>> orders = jdbc.queryForList("select * from mom_exec_order order by id desc limit 40");
        if (!orders.isEmpty()) {
            sb.append("\nLast orders:\n");
        }
        for (Map<String, Object> o : orders) {
            sb.append(String.format("  %s %-7s %-4s %-12s %4s @ %9.2f limit %9.2f  %-8s %s%n",
                    String.valueOf(o.get("created_at")).substring(0, 16), o.get("mode"), o.get("side"), o.get("symbol"),
                    o.get("qty"), num(o.get("ltp")), num(o.get("limit_price")), o.get("status"),
                    o.get("message") == null ? o.get("reason") : o.get("message")));
        }
        return sb.toString();
    }

    private static String line(Order o, String what) {
        return String.format("  %-4s %-12s %4d  last %9.2f  limit %9.2f  Rs %,9.0f  (%s; %s)%n", o.side(), o.symbol(),
                o.qty(), o.ltp(), o.limit(), o.value(), o.reason(), what);
    }

    private static double num(Object v) {
        return v == null ? 0 : ((Number) v).doubleValue();
    }

    private static LocalDate toDate(Object v) {
        return v instanceof Date d ? d.toLocalDate() : v instanceof LocalDate d ? d : v == null ? null
                : LocalDate.parse(String.valueOf(v).substring(0, 10));
    }

    private static String cut(String s) {
        return s == null ? null : s.length() <= 250 ? s : s.substring(0, 250);
    }

    private void notifySlack(String title, String message) {
        try {
            String body = mapper.writeValueAsString(Map.of("channel", "SLACK", "title", title, "message", message));
            http.sendAsync(HttpRequest.newBuilder(URI.create(notifyUrl)).header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ex -> {
                        log.warn("MOMENTUM EXEC | Slack send failed: {}", ex.toString());
                        return null;
                    });
        } catch (Exception ex) {
            log.warn("MOMENTUM EXEC | Slack send failed: {}", ex.toString());
        }
    }
}
