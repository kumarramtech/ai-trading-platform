package com.ram.trading.stock.service.shortexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.stock.service.calendar.MarketCalendarService;
import com.ram.trading.stock.service.dashboard.BookViews;
import com.ram.trading.stock.service.shortexec.ShortExecPlanner.Cover;
import com.ram.trading.stock.service.shortexec.ShortExecPlanner.Enter;
import com.ram.trading.stock.service.shortexec.ShortExecPlanner.Paper;
import com.ram.trading.stock.service.shortexec.ShortExecPlanner.Plan;
import com.ram.trading.stock.service.shortexec.ShortExecPlanner.Position;
import com.ram.trading.stock.service.shortexec.ShortExecPlanner.Rules;
import com.ram.trading.stock.service.shortexec.ShortExecPlanner.Skip;
import com.ram.trading.stock.service.shortexec.UpstoxIntradayBroker.OrderState;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * INTRADAY SHORT EXECUTOR (separate component, built 2026-11-26): copies two PAPER books into real INTRADAY (MIS)
 * short orders. It only READS the paper tables (fade_paper_trade, fo_excl_trade) and never changes them or the
 * paper code; it writes only its own table, short_exec_position.
 * <p>
 * Books: {@code fade} (FADE: paper shorts at 09:46, covers when paper covers, 15:00) and {@code foex} (F&amp;O
 * exclusion: paper shorts at 09:20; paper covers at 15:25, which is after the broker's 15:20 intraday square-off, so
 * this executor covers at 15:10). Each book has its own settings (short-exec.&lt;book&gt;.*):
 * <ul>
 *   <li>mode: OFF, DRY_RUN (default: every minute builds the exact orders, books them at the last price, Slack;
 *       nothing goes to Upstox) or LIVE (needs short-exec.live-confirm=REAL-MONEY too);</li>
 *   <li>positions / notional: copy at most that many paper shorts a day, each floor(notional / price) shares;</li>
 *   <li>stop-pct: a protective buy stop above the entry. The paper books have none: it is a catastrophe guard (server
 *       down, short squeeze), placed at the broker in LIVE;</li>
 *   <li>max-book-loss / max-loss-streak / start-date: new entries stop once the closed shorts since start-date lose
 *       that much in total, or lose that many times in a row; open shorts are still covered. Moving start-date
 *       re-arms the book.</li>
 * </ul>
 * LIVE entries are SELL limits at most buffer % below the last price, cancelled if not filled in 3 minutes; the stop is
 * placed after the fill (if it cannot be placed, the short is covered at once); covers are MARKET orders. Every
 * minute the open orders are checked; at 15:25 the Upstox intraday positions are compared with this book.
 */
@Service
@Slf4j
public class ShortExecutionService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final String CONFIRM = "REAL-MONEY";
    static final List<String> BOOKS = List.of("fade", "foex");
    static final LocalTime FIRST = LocalTime.of(9, 16), LAST = LocalTime.of(15, 20);
    static final Duration ENTRY_WAIT = Duration.ofMinutes(3);

    private final JdbcTemplate jdbc;
    private final UpstoxIntradayBroker broker;
    private final MarketCalendarService calendar;
    private final Environment env;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Set<String> alerted = new HashSet<>();

    public ShortExecutionService(JdbcTemplate jdbc, UpstoxIntradayBroker broker, MarketCalendarService calendar,
                                 Environment env) {
        this.jdbc = jdbc;
        this.broker = broker;
        this.calendar = calendar;
        this.env = env;
    }

    /** One book's settings, read on every run (so a config change needs only a restart). */
    record Book(String name, String label, String mode, int positions, double notional, double stopPct,
                LocalTime entryUntil, LocalTime forceCover, double maxBookLoss, int maxLossStreak, LocalDate start) {

        Rules rules(double bufferPct) {
            return new Rules(positions, notional, stopPct, bufferPct, entryUntil, forceCover);
        }

        String text() {
            return String.format("%s: mode %s, %d positions x Rs %,.0f, protective stop %s, entries until %s, cover by %s, "
                            + "book stop Rs %,.0f%s since %s", label, mode, positions, notional,
                    stopPct > 0 ? stopPct + "%" : "none", entryUntil, forceCover, maxBookLoss,
                    maxLossStreak > 0 ? " or " + maxLossStreak + " losses in a row" : "", start == null ? "the start" : start);
        }
    }

    Book book(String name) {
        boolean fade = "fade".equals(name);
        String p = "short-exec." + name + ".";
        String m = env.getProperty(p + "mode", "DRY_RUN").trim().toUpperCase();
        String start = env.getProperty(p + "start-date", "").trim();
        return new Book(name, fade ? "FADE" : "F&O EXCLUSION",
                m.equals("LIVE") || m.equals("OFF") ? m : "DRY_RUN",
                env.getProperty(p + "positions", Integer.class, 2),
                env.getProperty(p + "notional", Double.class, fade ? 40_000d : 50_000d),
                env.getProperty(p + "stop-pct", Double.class, 5d),
                LocalTime.parse(env.getProperty(p + "entry-until", fade ? "10:00" : "09:40")),
                LocalTime.parse(env.getProperty(p + "force-cover", fade ? "15:05" : "15:10")),
                env.getProperty(p + "max-book-loss", Double.class, 5_000d),
                env.getProperty(p + "max-loss-streak", Integer.class, fade ? 0 : 4),
                start.isEmpty() ? null : LocalDate.parse(start));
    }

    private double bufferPct() {
        return env.getProperty("short-exec.buffer-pct", Double.class, 0.5);
    }

    /** Why LIVE may not send orders, or null. */
    String liveBlock() {
        return CONFIRM.equals(env.getProperty("short-exec.live-confirm", "").trim()) ? null
                : "short-exec.live-confirm is not " + CONFIRM;
    }

    @PostConstruct
    void createTables() {
        jdbc.execute("create table if not exists short_exec_position (id bigint auto_increment primary key, "
                + "mode varchar(8) not null, book varchar(8) not null, trade_date date not null, symbol varchar(40) not null, "
                + "instrument_key varchar(64), paper_id bigint not null, status varchar(10) not null, qty int, "
                + "ltp_at_entry double, entry_limit double, entry_order_id varchar(40), entry_price double, entry_at datetime, "
                + "stop_price double, stop_order_id varchar(40), exit_reason varchar(60), exit_order_id varchar(40), "
                + "exit_filled int, exit_value double, exit_price double, exit_at datetime, gross_pnl double, brokerage double, "
                + "stt double, exchange_txn double, sebi_fee double, stamp_duty double, gst double, charges double, "
                + "net_pnl double, message varchar(255), created_at datetime not null, updated_at datetime, "
                + "unique key uq_short_exec (mode, book, paper_id), index idx_short_exec_day (mode, book, trade_date))");
    }

    // ------------------------------------------------------------------ schedule

    @Scheduled(cron = "35 * 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void tickJob() {
        LocalDateTime now = LocalDateTime.now(IST);
        if (now.toLocalTime().isBefore(FIRST) || now.toLocalTime().isAfter(LAST)
                || !calendar.check(now.toLocalDate()).tradingDay()) {
            return;
        }
        for (String b : BOOKS) {
            try {
                run(b, now);
            } catch (Exception ex) {
                log.error("SHORT EXEC | {} run failed", b, ex);
                alertOnce(now.toLocalDate(), b + "-fail", "Short executor " + b + " FAILED", ex.toString());
            }
        }
    }

    @Scheduled(cron = "0 25 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void closeCheckJob() {
        LocalDate today = LocalDate.now(IST);
        if (!calendar.check(today).tradingDay()) {
            return;
        }
        try {
            reconcile(today);
        } catch (Exception ex) {
            log.error("SHORT EXEC | close check failed", ex);
        }
    }

    @Scheduled(cron = "0 32 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void daySummaryJob() {
        LocalDate today = LocalDate.now(IST);
        for (String b : BOOKS) {
            Book book = book(b);
            if (count("select count(*) from short_exec_position where mode = ? and book = ? and trade_date = ?",
                    book.mode(), b, Date.valueOf(today)) > 0) {
                notifySlack(book.label() + " executor " + book.mode() + ": day closed", dayText(book, today));
            }
        }
    }

    // ------------------------------------------------------------------ the run

    /** One minute for one book. Returns what it did (also the manual endpoint's answer). */
    public synchronized String run(String name, LocalDateTime now) throws Exception {
        if (!BOOKS.contains(name)) {
            return "unknown book " + name + " (fade or foex)";
        }
        Book b = book(name);
        if ("OFF".equals(b.mode())) {
            return b.label() + ": mode OFF";
        }
        LocalDate today = now.toLocalDate();
        boolean live = "LIVE".equals(b.mode());
        List<Paper> paper = paper(name, today);
        List<Position> mine = positions(b, today);
        Set<Long> copied = new HashSet<>(jdbc.queryForList("select paper_id from short_exec_position where mode = ? "
                + "and book = ? and trade_date = ?", Long.class, b.mode(), name, Date.valueOf(today)));
        boolean toEnter = now.toLocalTime().isBefore(b.entryUntil())
                && paper.stream().anyMatch(p -> "OPEN".equals(p.status()) && !copied.contains(p.id()));
        if (mine.isEmpty() && !toEnter) {
            return b.label() + ": nothing to do";
        }
        if (live && liveBlock() != null) {
            alertOnce(today, name + "-block", b.label() + " executor LIVE blocked", liveBlock() + ": no orders sent.");
            return b.label() + ": LIVE blocked: " + liveBlock();
        }
        String token = broker.token();
        if (token == null) {
            alertOnce(today, name + "-login", b.label() + " executor: no Upstox login",
                    "Mode " + b.mode() + ": nothing done. Log in to Upstox.");
            return b.label() + ": no Upstox token";
        }
        StringBuilder out = new StringBuilder();
        if (live) {
            out.append(checkOrders(b, token, now));
            mine = positions(b, today);
        }

        Set<String> symbols = new LinkedHashSet<>();
        mine.forEach(p -> symbols.add(p.symbol()));
        paper.stream().filter(p -> "OPEN".equals(p.status()) && !copied.contains(p.id())).forEach(p -> symbols.add(p.symbol()));
        Map<String, String> keys = new HashMap<>();
        Map<String, Double> ticks = new HashMap<>();
        for (String s : symbols) {
            List<Map<String, Object>> k = jdbc.queryForList("select instrument_key, tick_size from instrument_master "
                    + "where segment = 'NSE_EQ' and upper(trading_symbol) = ? limit 1", s.toUpperCase());
            if (!k.isEmpty()) {
                keys.put(s, (String) k.get(0).get("instrument_key"));
                if (k.get(0).get("tick_size") != null) {
                    ticks.put(s, ((Number) k.get(0).get("tick_size")).doubleValue() / 100); // stored in paise
                }
            }
        }
        Map<String, Double> byKey = keys.isEmpty() ? Map.of()
                : broker.ltp(token, new ArrayList<>(new LinkedHashSet<>(keys.values())));
        Map<String, Double> ltp = new HashMap<>();
        keys.forEach((s, k) -> {
            if (byKey.containsKey(k)) {
                ltp.put(s, byKey.get(k));
            }
        });

        String paused = ShortExecPlanner.pauseReason(closedNets(b), b.maxBookLoss(), b.maxLossStreak());
        Plan plan = ShortExecPlanner.plan(paper, mine, copied, ltp, ticks, now.toLocalTime(), b.rules(bufferPct()),
                paused, !live);

        for (Cover c : plan.covers()) {
            out.append(live ? coverLive(b, token, c, now) : coverDry(c, now));
        }
        for (Skip s : plan.skips()) {
            insert(b, today, s.paper(), keys.get(s.paper().symbol()), "SKIPPED", s.reason(), now);
            out.append(String.format("  SKIP  %-12s %s%n", s.paper().symbol(), s.reason()));
        }
        for (Enter e : plan.enters()) {
            out.append(live ? enterLive(b, token, e, keys.get(e.paper().symbol()), now)
                    : enterDry(b, e, keys.get(e.paper().symbol()), now));
        }
        plan.notes().forEach(n -> log.info("SHORT EXEC | {} | {}", name, n));
        if (!out.isEmpty()) {
            String text = String.format("%s executor %s %s%n%s%s", b.label(), b.mode(), now.toLocalTime().withNano(0), out,
                    live ? "" : "(dry run: nothing was sent to Upstox)");
            notifySlack(b.label() + (live ? " LIVE orders" : " DRY-RUN orders"), text);
            log.info("SHORT EXEC | {}", text.replace('\n', ' '));
            return text;
        }
        return b.label() + ": no change";
    }

    // ------------------------------------------------------------------ DRY_RUN

    private String enterDry(Book b, Enter e, String key, LocalDateTime now) {
        long id = insert(b, now.toLocalDate(), e.paper(), key, "OPEN", "dry run: filled at the last price", now);
        jdbc.update("update short_exec_position set qty = ?, ltp_at_entry = ?, entry_limit = ?, entry_price = ?, entry_at = ?, "
                        + "stop_price = ? where id = ?", e.qty(), e.ltp(), e.limit(), e.ltp(), Timestamp.valueOf(now),
                e.stop(), id);
        return String.format("  SHORT %-12s %5d @ %9.2f  limit %9.2f  stop %s  Rs %,.0f%n", e.paper().symbol(), e.qty(),
                e.ltp(), e.limit(), e.stop() == null ? "none" : String.format("%.2f", e.stop()), e.value());
    }

    private String coverDry(Cover c, LocalDateTime now) {
        if (c.ltp() <= 0) {
            return String.format("  COVER %-12s %s: no last price, retried next minute%n", c.position().symbol(), c.reason());
        }
        double px = "STOP".equals(c.reason()) && c.position().stop() != null ? Math.max(c.ltp(), c.position().stop()) : c.ltp();
        return close(c.position().id(), c.position().qty(), px, c.reason(), now);
    }

    // ------------------------------------------------------------------ LIVE

    private String enterLive(Book b, String token, Enter e, String key, LocalDateTime now) {
        long id = insert(b, now.toLocalDate(), e.paper(), key, "ENTERING", null, now);
        jdbc.update("update short_exec_position set qty = ?, ltp_at_entry = ?, entry_limit = ? where id = ?", e.qty(),
                e.ltp(), e.limit(), id);
        try {
            String orderId = broker.place(token, key, "SELL", e.qty(), "LIMIT", e.limit(), 0);
            jdbc.update("update short_exec_position set entry_order_id = ?, updated_at = ? where id = ?", orderId,
                    Timestamp.valueOf(now), id);
            return String.format("  SHORT %-12s %5d limit %9.2f sent, order %s%n", e.paper().symbol(), e.qty(), e.limit(), orderId);
        } catch (Exception ex) {
            status(id, "REJECTED", ex.getMessage(), now);
            return String.format("  SHORT %-12s NOT SENT: %s%n", e.paper().symbol(), ex.getMessage());
        }
    }

    private String coverLive(Book b, String token, Cover c, LocalDateTime now) throws Exception {
        Map<String, Object> r = jdbc.queryForMap("select * from short_exec_position where id = ?", c.position().id());
        String stopId = (String) r.get("stop_order_id");
        if (stopId != null) {
            try {
                broker.cancel(token, stopId);
            } catch (Exception ex) {
                log.warn("SHORT EXEC | stop cancel {} failed: {}", stopId, ex.toString());
            }
            OrderState st = broker.order(token, stopId);
            if ("complete".equals(st.status())) {
                return close(c.position().id(), c.position().qty(), st.average(), "STOP (broker)", now);
            }
        }
        try {
            String orderId = broker.place(token, (String) r.get("instrument_key"), "BUY", c.position().qty(), "MARKET", 0, 0);
            jdbc.update("update short_exec_position set status = 'EXITING', exit_reason = ?, exit_order_id = ?, exit_filled = 0, "
                    + "exit_value = 0, updated_at = ? where id = ?", cut(c.reason(), 60), orderId, Timestamp.valueOf(now),
                    c.position().id());
            return String.format("  COVER %-12s %5d market (%s) sent, order %s%n", c.position().symbol(), c.position().qty(),
                    c.reason(), orderId);
        } catch (Exception ex) {
            notifySlack(b.label() + " executor: COVER FAILED", c.position().symbol() + " " + c.position().qty()
                    + " shares are still SHORT (" + ex.getMessage() + "). Retried next minute; check Upstox now.");
            return String.format("  COVER %-12s NOT SENT: %s%n", c.position().symbol(), ex.getMessage());
        }
    }

    /** LIVE: entry fills (then the stop), stop fills, cover fills; unfilled entries are cancelled after 3 minutes. */
    private String checkOrders(Book b, String token, LocalDateTime now) throws Exception {
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> r : jdbc.queryForList("select * from short_exec_position where mode = 'LIVE' and book = ? "
                + "and trade_date = ? and status in ('ENTERING', 'OPEN', 'EXITING') order by id", b.name(),
                Date.valueOf(now.toLocalDate()))) {
            long id = ((Number) r.get("id")).longValue();
            String sym = (String) r.get("symbol"), status = (String) r.get("status");
            if ("ENTERING".equals(status)) {
                String orderId = (String) r.get("entry_order_id");
                OrderState st = broker.order(token, orderId);
                LocalDateTime created = ((Timestamp) r.get("created_at")).toLocalDateTime();
                if (!st.finished() && !now.isBefore(created.plus(ENTRY_WAIT))) {
                    try {
                        broker.cancel(token, orderId);
                    } catch (Exception ex) {
                        log.warn("SHORT EXEC | entry cancel {} failed: {}", orderId, ex.toString());
                    }
                    st = broker.order(token, orderId);
                }
                if (!st.finished()) {
                    continue;
                }
                if (st.filled() <= 0) {
                    status(id, "complete".equals(st.status()) ? "REJECTED" : st.status().toUpperCase(),
                            "entry not filled: " + st.message(), now);
                    out.append(String.format("  SHORT %-12s not filled (%s)%n", sym, st.status()));
                    continue;
                }
                double tick = tick(sym);
                Double stop = b.stopPct() > 0 && tick > 0
                        ? ShortExecPlanner.align(st.average() * (1 + b.stopPct() / 100), tick, RoundingMode.CEILING) : null;
                jdbc.update("update short_exec_position set status = 'OPEN', qty = ?, entry_price = ?, entry_at = ?, "
                        + "stop_price = ?, updated_at = ? where id = ?", st.filled(), st.average(), Timestamp.valueOf(now),
                        stop, Timestamp.valueOf(now), id);
                out.append(String.format("  SHORT %-12s FILLED %d @ %.2f%n", sym, st.filled(), st.average()));
                if (stop != null) {
                    try {
                        String stopId = broker.place(token, (String) r.get("instrument_key"), "BUY", st.filled(), "SL-M", 0, stop);
                        jdbc.update("update short_exec_position set stop_order_id = ? where id = ?", stopId, id);
                        out.append(String.format("        stop %.2f placed, order %s%n", stop, stopId));
                    } catch (Exception ex) {
                        out.append(String.format("        STOP NOT PLACED (%s): covering now%n", ex.getMessage()));
                        out.append(coverLive(b, token, new Cover(new Position(id, sym, ((Number) r.get("paper_id")).longValue(),
                                st.filled(), stop, "OPEN"), "NO STOP", 0), now));
                    }
                }
            } else if ("OPEN".equals(status) && r.get("stop_order_id") != null) {
                OrderState st = broker.order(token, (String) r.get("stop_order_id"));
                if ("complete".equals(st.status())) {
                    out.append(close(id, ((Number) r.get("qty")).intValue(), st.average(), "STOP (broker)", now));
                } else if (st.finished()) {
                    out.append(String.format("  STOP  %-12s order %s %s: covering%n", sym, st.status(), st.message()));
                    jdbc.update("update short_exec_position set stop_order_id = null where id = ?", id);
                    out.append(coverLive(b, token, new Cover(new Position(id, sym, ((Number) r.get("paper_id")).longValue(),
                            ((Number) r.get("qty")).intValue(), null, "OPEN"), "STOP ORDER " + st.status(), 0), now));
                }
            } else if ("EXITING".equals(status)) {
                OrderState st = broker.order(token, (String) r.get("exit_order_id"));
                if (!st.finished()) {
                    continue;
                }
                int qty = ((Number) r.get("qty")).intValue();
                int filled = num(r.get("exit_filled")).intValue() + st.filled();
                double value = num(r.get("exit_value")).doubleValue() + st.filled() * st.average();
                jdbc.update("update short_exec_position set exit_filled = ?, exit_value = ? where id = ?", filled, value, id);
                if (filled >= qty) {
                    out.append(close(id, qty, value / filled, (String) r.get("exit_reason"), now));
                } else {
                    try {
                        String orderId = broker.place(token, (String) r.get("instrument_key"), "BUY", qty - filled, "MARKET", 0, 0);
                        jdbc.update("update short_exec_position set exit_order_id = ? where id = ?", orderId, id);
                        out.append(String.format("  COVER %-12s %d of %d filled, rest sent again (order %s)%n", sym, filled, qty, orderId));
                    } catch (Exception ex) {
                        notifySlack(b.label() + " executor: COVER FAILED", sym + " " + (qty - filled)
                                + " shares are still SHORT (" + ex.getMessage() + "). Check Upstox now.");
                    }
                }
            }
        }
        return out.toString();
    }

    /** 15:25, LIVE: any intraday quantity Upstox still shows on a stock this executor traded today. */
    String reconcile(LocalDate today) throws Exception {
        List<Map<String, Object>> rows = jdbc.queryForList("select distinct book, symbol, instrument_key from short_exec_position "
                + "where mode = 'LIVE' and trade_date = ? and status <> 'SKIPPED'", Date.valueOf(today));
        if (rows.isEmpty()) {
            return "nothing traded live today";
        }
        String token = broker.token();
        if (token == null) {
            notifySlack("Short executor: no Upstox login", "The 15:25 position check could not run.");
            return "no token";
        }
        Map<String, Integer> net = broker.intradayNet(token);
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> r : rows) {
            int q = net.getOrDefault((String) r.get("instrument_key"), 0);
            if (q != 0) {
                out.append(String.format("%s %s: Upstox still shows %d intraday shares%n", r.get("book"), r.get("symbol"), q));
            }
        }
        if (!out.isEmpty()) {
            notifySlack("Short executor MISMATCH at 15:25", out.toString());
        }
        return out.isEmpty() ? "all flat" : out.toString();
    }

    // ------------------------------------------------------------------ book

    private List<Paper> paper(String book, LocalDate day) {
        String sql = "fade".equals(book)
                ? "select id, symbol, status, exit_reason from fade_paper_trade where trade_date = ? order by id"
                : "select id, symbol, status, reason exit_reason from fo_excl_trade where event_date = ? "
                + "and status in ('OPEN', 'CLOSED') order by id";
        List<Paper> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(sql, Date.valueOf(day))) {
            out.add(new Paper(((Number) r.get("id")).longValue(), (String) r.get("symbol"), (String) r.get("status"),
                    (String) r.get("exit_reason")));
        }
        return out;
    }

    private List<Position> positions(Book b, LocalDate day) {
        List<Position> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("select id, symbol, paper_id, qty, stop_price, status from short_exec_position "
                + "where mode = ? and book = ? and trade_date = ? and status in ('ENTERING', 'OPEN', 'EXITING') order by id",
                b.mode(), b.name(), Date.valueOf(day))) {
            out.add(new Position(((Number) r.get("id")).longValue(), (String) r.get("symbol"),
                    ((Number) r.get("paper_id")).longValue(), r.get("qty") == null ? 0 : ((Number) r.get("qty")).intValue(),
                    r.get("stop_price") == null ? null : ((Number) r.get("stop_price")).doubleValue(), (String) r.get("status")));
        }
        return out;
    }

    private List<Double> closedNets(Book b) {
        return jdbc.queryForList("select net_pnl from short_exec_position where mode = ? and book = ? and status = 'CLOSED' "
                + "and trade_date >= ? order by trade_date, id", Double.class, b.mode(), b.name(),
                Date.valueOf(b.start() == null ? LocalDate.of(2000, 1, 1) : b.start()));
    }

    private long insert(Book b, LocalDate day, Paper p, String key, String status, String message, LocalDateTime now) {
        jdbc.update("insert into short_exec_position (mode, book, trade_date, symbol, instrument_key, paper_id, status, message, "
                        + "created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", b.mode(), b.name(), Date.valueOf(day),
                p.symbol(), key, p.id(), status, cut(message, 255), Timestamp.valueOf(now), Timestamp.valueOf(now));
        return jdbc.queryForObject("select id from short_exec_position where mode = ? and book = ? and paper_id = ?", Long.class,
                b.mode(), b.name(), p.id());
    }

    private void status(long id, String status, String message, LocalDateTime now) {
        jdbc.update("update short_exec_position set status = ?, message = ?, updated_at = ? where id = ?", status,
                cut(message, 255), Timestamp.valueOf(now), id);
    }

    private String close(long id, int qty, double exit, String reason, LocalDateTime now) {
        Map<String, Object> r = jdbc.queryForMap("select symbol, entry_price from short_exec_position where id = ?", id);
        int q = qty;
        double entry = ((Number) r.get("entry_price")).doubleValue();
        ShortExecPlanner.Charges c = ShortExecPlanner.shortCharges(q, entry, exit);
        double[] res = ShortExecPlanner.shortNet(q, entry, exit);
        jdbc.update("update short_exec_position set status = 'CLOSED', exit_price = ?, exit_at = ?, exit_reason = ?, gross_pnl = ?, "
                        + "brokerage = ?, stt = ?, exchange_txn = ?, sebi_fee = ?, stamp_duty = ?, gst = ?, charges = ?, net_pnl = ?, "
                        + "updated_at = ? where id = ?", ShortExecPlanner.round(exit), Timestamp.valueOf(now), cut(reason, 60),
                res[0], c.brokerage(), c.stt(), c.exchangeTxn(), c.sebiFee(), c.stampDuty(), c.gst(), res[1], res[2],
                Timestamp.valueOf(now), id);
        return String.format("  COVER %-12s %5d @ %9.2f  (%s)  gross Rs %,.0f - charges Rs %,.0f = net Rs %,.0f%n",
                r.get("symbol"), qty, exit, reason, res[0], res[1], res[2]);
    }

    private double tick(String symbol) {
        List<Double> t = jdbc.queryForList("select tick_size from instrument_master where segment = 'NSE_EQ' "
                + "and upper(trading_symbol) = ? limit 1", Double.class, symbol.toUpperCase());
        return t.isEmpty() || t.get(0) == null ? 0 : t.get(0) / 100;
    }

    // ------------------------------------------------------------------ reports

    /** Today's executor shorts against the paper trades they copy. */
    String dayText(Book b, LocalDate day) {
        StringBuilder sb = new StringBuilder();
        double mine = 0, paperNet = 0, gross = 0, charges = 0;
        String paperTable = "fade".equals(b.name()) ? "fade_paper_trade" : "fo_excl_trade";
        for (Map<String, Object> r : jdbc.queryForList("select s.*, p.net_pnl paper_net, p.exit_price paper_exit from "
                + "short_exec_position s left join " + paperTable + " p on p.id = s.paper_id where s.mode = ? and s.book = ? "
                + "and s.trade_date = ? order by s.id", b.mode(), b.name(), Date.valueOf(day))) {
            if ("CLOSED".equals(r.get("status"))) {
                mine += num(r.get("net_pnl")).doubleValue();
                gross += num(r.get("gross_pnl")).doubleValue();
                charges += num(r.get("charges")).doubleValue();
                paperNet += num(r.get("paper_net")).doubleValue();
                sb.append(String.format("  %-12s %5s  %9.2f -> %9.2f  %-14s gross Rs %,7.0f  charges Rs %,4.0f  net Rs %,7.0f   paper net Rs %,7.0f%n",
                        r.get("symbol"), r.get("qty"), num(r.get("entry_price")).doubleValue(),
                        num(r.get("exit_price")).doubleValue(), r.get("exit_reason"), num(r.get("gross_pnl")).doubleValue(),
                        num(r.get("charges")).doubleValue(), num(r.get("net_pnl")).doubleValue(), num(r.get("paper_net")).doubleValue()));
            } else {
                sb.append(String.format("  %-12s %-8s %s%n", r.get("symbol"), r.get("status"),
                        r.get("message") == null ? "" : r.get("message")));
            }
        }
        sb.append(String.format("Executor gross Rs %,.0f - all charges Rs %,.0f = net Rs %,.0f (paper net Rs %,.0f on the same "
                + "shorts)", gross, charges, mine, paperNet));
        return sb.toString();
    }

    public String report() {
        LocalDate today = LocalDate.now(IST);
        StringBuilder sb = new StringBuilder("INTRADAY SHORT EXECUTOR (separate component; copies the FADE and F&O exclusion "
                + "paper books)\n").append(liveBlock() == null ? "live switch SET\n" : "live switch not set (LIVE books are blocked)\n");
        for (String name : BOOKS) {
            Book b = book(name);
            sb.append("\n").append(b.text()).append("\n");
            String paused = ShortExecPlanner.pauseReason(closedNets(b), b.maxBookLoss(), b.maxLossStreak());
            Map<String, Object> t = BookViews.totals(closedRows(b), "net_pnl", "gross_pnl", "charges");
            sb.append(String.format("Closed %s, winners %s, net Rs %,.0f, worst Rs %s, max drawdown Rs %,.0f%s%n", t.get("closed"),
                    t.get("winners"), (Double) t.get("netRs"), t.get("worstRs") == null ? "-" : t.get("worstRs"),
                    (Double) t.get("maxDrawdownRs"), paused == null ? "" : "  PAUSED: " + paused));
            if (count("select count(*) from short_exec_position where mode = ? and book = ? and trade_date = ?", b.mode(),
                    name, Date.valueOf(today)) > 0) {
                sb.append("Today:\n").append(dayText(b, today)).append("\n");
            }
        }
        return sb.toString();
    }

    private List<Map<String, Object>> closedRows(Book b) {
        return jdbc.queryForList("select * from short_exec_position where mode = ? and book = ? and status = 'CLOSED' "
                + "order by trade_date, id", b.mode(), b.name());
    }

    /** JSON for a dashboard: per book the settings, pause state, totals, equity curve and every row. */
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("component", "SHORT_EXECUTOR");
        out.put("asOf", LocalDateTime.now(IST).withNano(0).toString());
        out.put("liveSwitchSet", liveBlock() == null);
        List<Map<String, Object>> books = new ArrayList<>();
        for (String name : BOOKS) {
            Book b = book(name);
            List<Map<String, Object>> closed = closedRows(b);
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("book", name);
            v.put("label", b.label());
            v.put("mode", b.mode());
            v.put("positions", b.positions());
            v.put("notionalRs", b.notional());
            v.put("stopPct", b.stopPct());
            v.put("entryUntil", b.entryUntil().toString());
            v.put("forceCover", b.forceCover().toString());
            v.put("maxBookLossRs", b.maxBookLoss());
            v.put("maxLossStreak", b.maxLossStreak());
            v.put("startDate", b.start() == null ? null : b.start().toString());
            v.put("paused", ShortExecPlanner.pauseReason(closedNets(b), b.maxBookLoss(), b.maxLossStreak()));
            v.put("totals", BookViews.totals(closed, "net_pnl", "gross_pnl", "charges"));
            v.put("equity", BookViews.curve(closed, "trade_date", "net_pnl"));
            v.put("trades", BookViews.rows(jdbc.queryForList("select * from short_exec_position where mode = ? and book = ? "
                    + "order by trade_date desc, id desc limit 200", b.mode(), name)));
            books.add(v);
        }
        out.put("books", books);
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private void alertOnce(LocalDate day, String kind, String title, String message) {
        if (alerted.add(day + ":" + kind)) {
            notifySlack(title, message);
        }
    }

    private static Number num(Object v) {
        return v == null ? 0 : (Number) v;
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }

    private void notifySlack(String title, String message) {
        try {
            String body = mapper.writeValueAsString(Map.of("channel", "SLACK", "title", title, "message", message));
            http.sendAsync(HttpRequest.newBuilder(URI.create(env.getProperty("short-exec.notify-url",
                                    "http://localhost:8087/notifications/send"))).header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ex -> {
                        log.warn("SHORT EXEC | Slack send failed: {}", ex.toString());
                        return null;
                    });
        } catch (Exception ex) {
            log.warn("SHORT EXEC | Slack send failed: {}", ex.toString());
        }
    }
}
