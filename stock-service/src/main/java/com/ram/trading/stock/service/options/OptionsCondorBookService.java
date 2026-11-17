package com.ram.trading.stock.service.options;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.stock.service.auth.UpstoxTokenService;
import com.ram.trading.stock.service.calendar.MarketCalendarService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OPTIONS paper book (separate strategy, paper only, rules fixed 2026-11-17 with the user).
 * A weekly NIFTY iron condor, 1 lot:
 * <ul>
 *   <li>entry 10:00 IST on the first trading day after a NIFTY expiry, on the next expiry;</li>
 *   <li>sell the call at the first strike at least 2% above spot and the put at the first strike
 *       at least 2% below; buy the wings at least 3.5% away;</li>
 *   <li>fills: sells at the bid, buys at the ask (half the bid-ask gap of slippage per leg);</li>
 *   <li>skip the week if the net credit is under Rs 10 a unit or a quote is missing;</li>
 *   <li>exit 15:15 on expiry day (no stop; the wings cap the loss).</li>
 * </ul>
 * It only reads Upstox option data and writes its own tables. It never places an order and does
 * not touch the intraday, FADE, momentum or earnings code.
 */
@Service
@Slf4j
public class OptionsCondorBookService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final String UNDERLYING = "NSE_INDEX|Nifty 50";
    static final double SHORT_PCT = 2.0;
    static final double LONG_PCT = 3.5;
    static final double MIN_CREDIT = 10.0;
    static final String RULES = "NIFTY iron condor, 1 lot: enter 10:00 the day after expiry; sell call/put >= 2% "
            + "from spot, buy wings >= 3.5%; sells at bid, buys at ask; skip if credit < Rs 10/unit; exit 15:15 on expiry day";

    private final JdbcTemplate jdbc;
    private final UpstoxTokenService tokens;
    private final MarketCalendarService calendar;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicBoolean busy = new AtomicBoolean();

    @Value("${options.book.enabled:true}")
    private boolean enabled = true;

    @Value("${upstox.base-url:https://api.upstox.com/v2}")
    private String baseUrl = "https://api.upstox.com/v2";

    @Value("${options.book.notify-url:http://localhost:8087/notifications/send}")
    private String notifyUrl = "http://localhost:8087/notifications/send";

    /** Upstox Plus: Rs 30 per executed order. */
    @Value("${options.book.brokerage-per-order:30}")
    private double brokeragePerOrder = 30;

    /** STT on the option premium sold, in %. 0.15 is the higher post-Budget-2026 figure (conservative). */
    @Value("${options.book.stt-sell-pct:0.15}")
    private double sttSellPct = 0.15;

    public OptionsCondorBookService(JdbcTemplate jdbc, UpstoxTokenService tokens, MarketCalendarService calendar) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.calendar = calendar;
    }

    @PostConstruct
    void createTables() {
        jdbc.execute("create table if not exists options_expiry_seen (expiry date primary key, seen_at datetime not null)");
        jdbc.execute("create table if not exists options_condor_trade ("
                + "id bigint auto_increment primary key, entry_day date not null, expiry date, "
                + "status varchar(10) not null, reason varchar(255), lot_size int, spot_entry double, "
                + "short_call double, long_call double, short_put double, long_put double, "
                + "sc_key varchar(32), lc_key varchar(32), sp_key varchar(32), lp_key varchar(32), "
                + "sc_in double, lc_in double, sp_in double, lp_in double, credit double, max_loss_rs double, "
                + "entry_at datetime, exit_at datetime, spot_exit double, "
                + "sc_out double, lc_out double, sp_out double, lp_out double, debit double, "
                + "gross_pnl double, charges double, net_pnl double, exit_reason varchar(24), "
                + "unique key uq_options_entry_day (entry_day))");
    }

    // ------------------------------------------------------------------ pure rules (tested)

    public record Quote(String key, double ltp, double bid, double ask) {
    }

    public record Strike(double strike, Quote call, Quote put) {
    }

    public record Legs(Strike shortCall, Strike longCall, Strike shortPut, Strike longPut) {
    }

    /** First strike at least 2% / 3.5% above spot for the calls, below for the puts; null if the chain is too short. */
    static Legs pickStrikes(double spot, List<Strike> chain) {
        TreeMap<Double, Strike> byStrike = new TreeMap<>();
        chain.forEach(s -> byStrike.put(s.strike(), s));
        Map.Entry<Double, Strike> sc = byStrike.ceilingEntry(spot * (1 + SHORT_PCT / 100));
        Map.Entry<Double, Strike> lc = byStrike.ceilingEntry(spot * (1 + LONG_PCT / 100));
        Map.Entry<Double, Strike> sp = byStrike.floorEntry(spot * (1 - SHORT_PCT / 100));
        Map.Entry<Double, Strike> lp = byStrike.floorEntry(spot * (1 - LONG_PCT / 100));
        if (sc == null || lc == null || sp == null || lp == null || lc.getKey() <= sc.getKey() || lp.getKey() >= sp.getKey()) {
            return null;
        }
        return new Legs(sc.getValue(), lc.getValue(), sp.getValue(), lp.getValue());
    }

    /** Net credit a unit: sell the shorts at the bid, buy the wings at the ask. */
    static double entryCredit(Legs l) {
        return l.shortCall().call().bid() + l.shortPut().put().bid() - l.longCall().call().ask() - l.longPut().put().ask();
    }

    static boolean quotesUsable(Legs l) {
        return l.shortCall().call().bid() > 0 && l.shortPut().put().bid() > 0
                && l.longCall().call().ask() > 0 && l.longPut().put().ask() > 0;
    }

    /** Worst case a unit at expiry: the wider wing minus the credit. */
    static double maxLossPerUnit(double sc, double lc, double sp, double lp, double credit) {
        return Math.max(lc - sc, sp - lp) - credit;
    }

    /** Price to close a leg: buy back at the ask, sell at the bid; falls back to the LTP, then to intrinsic. */
    static double closePrice(Quote q, boolean buyBack, double intrinsic) {
        double p = buyBack ? q.ask() : q.bid();
        if (p > 0) {
            return p;
        }
        if (buyBack) {
            return q.ltp() > 0 ? Math.max(q.ltp(), intrinsic) : intrinsic;
        }
        return Math.max(0, intrinsic);
    }

    static double callIntrinsic(double spot, double strike) {
        return Math.max(0, spot - strike);
    }

    static double putIntrinsic(double spot, double strike) {
        return Math.max(0, strike - spot);
    }

    /**
     * NSE option charges for the round trip (8 orders): brokerage per order, STT on premium sold,
     * exchange 0.03503% and SEBI Rs 10/crore on premium turnover, stamp 0.003% on premium bought,
     * GST 18% on brokerage + exchange + SEBI.
     */
    static double charges(double soldPremium, double boughtPremium, int qty, int orders, double brokeragePerOrder, double sttSellPct) {
        double sell = soldPremium * qty, buy = boughtPremium * qty, turnover = sell + buy;
        double brokerage = orders * brokeragePerOrder;
        double stt = sell * sttSellPct / 100;
        double exchange = turnover * 0.03503 / 100;
        double sebi = turnover * 10 / 1e7;
        double stamp = buy * 0.003 / 100;
        double gst = 0.18 * (brokerage + exchange + sebi);
        return brokerage + stt + exchange + sebi + stamp + gst;
    }

    /** Previous NSE trading day before the given day. */
    LocalDate previousTradingDay(LocalDate day) {
        LocalDate d = day.minusDays(1);
        for (int i = 0; i < 15 && !calendar.check(d).tradingDay(); i++) {
            d = d.minusDays(1);
        }
        return d;
    }

    // ------------------------------------------------------------------ schedule

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        Thread t = new Thread(() -> {
            recordExpiries();
            settleMissed();
        }, "options-book-startup");
        t.setDaemon(true);
        t.start();
    }

    /** Keeps every NIFTY expiry ever listed, so "the day after expiry" is known after it drops off the list. */
    @Scheduled(cron = "0 5 9,21 * * *", zone = "Asia/Kolkata")
    public void recordExpiries() {
        try {
            Contracts c = contracts(token());
            for (LocalDate e : c.expiries()) {
                jdbc.update("insert ignore into options_expiry_seen (expiry, seen_at) values (?, ?)",
                        Date.valueOf(e), Timestamp.valueOf(LocalDateTime.now(IST)));
            }
            log.info("OPTIONS BOOK | {} expiries listed, lot {}", c.expiries().size(), c.lotSize());
        } catch (Exception ex) {
            log.warn("OPTIONS BOOK | expiry record failed: {}", ex.toString());
        }
    }

    /** 10:00, with retries at 10:05 and 10:10 only if the data could not be read. */
    @Scheduled(cron = "0 0,5,10 10 * * MON-FRI", zone = "Asia/Kolkata")
    public void entryJob() {
        LocalTime now = LocalTime.now(IST);
        enter(LocalDate.now(IST), !now.isBefore(LocalTime.of(10, 10)));
    }

    @Scheduled(cron = "0 15,20,25 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void exitJob() {
        exitDue(LocalDate.now(IST), false);
    }

    /** After the close: anything still open on or after its expiry is settled at intrinsic from the NIFTY close. */
    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void settleMissed() {
        exitDue(LocalDate.now(IST), true);
    }

    // ------------------------------------------------------------------ entry

    public synchronized String enter(LocalDate today, boolean lastAttempt) {
        if (!enabled) {
            return "disabled";
        }
        if (!calendar.check(today).tradingDay()) {
            return "not a trading day";
        }
        Integer already = jdbc.queryForObject("select count(*) from options_condor_trade where entry_day = ?", Integer.class, Date.valueOf(today));
        Integer open = jdbc.queryForObject("select count(*) from options_condor_trade where status = 'OPEN'", Integer.class);
        if (already != null && already > 0) {
            return "already handled today";
        }
        if (open != null && open > 0) {
            return "a condor is still open";
        }
        LocalDate prev = previousTradingDay(today);
        Integer wasExpiry = jdbc.queryForObject("select count(*) from options_expiry_seen where expiry = ?", Integer.class, Date.valueOf(prev));
        if (wasExpiry == null || wasExpiry == 0) {
            return "not the day after an expiry (previous session " + prev + ")";
        }
        try {
            String token = token();
            Contracts c = contracts(token);
            LocalDate expiry = c.expiries().stream().filter(e -> e.isAfter(today)).findFirst().orElse(null);
            if (expiry == null) {
                return skip(today, null, "no next expiry listed");
            }
            Chain chain = chain(token, expiry);
            Legs legs = pickStrikes(chain.spot(), chain.strikes());
            if (legs == null) {
                return skip(today, expiry, "chain too short for the strikes");
            }
            if (!quotesUsable(legs)) {
                return skip(today, expiry, "a leg had no bid/ask");
            }
            double credit = entryCredit(legs);
            if (credit < MIN_CREDIT) {
                return skip(today, expiry, String.format("credit Rs %.2f/unit < Rs %.0f", credit, MIN_CREDIT));
            }
            int lot = c.lotSize();
            double sc = legs.shortCall().strike(), lc = legs.longCall().strike(), sp = legs.shortPut().strike(), lp = legs.longPut().strike();
            double maxLoss = maxLossPerUnit(sc, lc, sp, lp, credit) * lot;
            jdbc.update("insert into options_condor_trade (entry_day, expiry, status, reason, lot_size, spot_entry, "
                            + "short_call, long_call, short_put, long_put, sc_key, lc_key, sp_key, lp_key, "
                            + "sc_in, lc_in, sp_in, lp_in, credit, max_loss_rs, entry_at) "
                            + "values (?, ?, 'OPEN', null, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Date.valueOf(today), Date.valueOf(expiry), lot, chain.spot(), sc, lc, sp, lp,
                    legs.shortCall().call().key(), legs.longCall().call().key(), legs.shortPut().put().key(), legs.longPut().put().key(),
                    legs.shortCall().call().bid(), legs.longCall().call().ask(), legs.shortPut().put().bid(), legs.longPut().put().ask(),
                    credit, maxLoss, Timestamp.valueOf(LocalDateTime.now(IST)));
            String msg = String.format("Paper iron condor, NIFTY %s expiry, spot %.1f, lot %d%n"
                            + "Sell %.0f CE @ %.2f, buy %.0f CE @ %.2f%nSell %.0f PE @ %.2f, buy %.0f PE @ %.2f%n"
                            + "Credit Rs %.2f/unit = Rs %.0f; max loss about Rs %.0f before charges",
                    expiry, chain.spot(), lot, sc, legs.shortCall().call().bid(), lc, legs.longCall().call().ask(),
                    sp, legs.shortPut().put().bid(), lp, legs.longPut().put().ask(), credit, credit * lot, maxLoss);
            notifySlack("OPTIONS book: condor opened", msg);
            log.info("OPTIONS BOOK | entered | {}", msg.replace('\n', ' '));
            return "entered: " + msg;
        } catch (Exception ex) {
            log.warn("OPTIONS BOOK | entry failed: {}", ex.toString());
            return lastAttempt ? skip(today, null, "data error: " + ex.getMessage()) : "entry failed, will retry: " + ex;
        }
    }

    private String skip(LocalDate today, LocalDate expiry, String reason) {
        jdbc.update("insert ignore into options_condor_trade (entry_day, expiry, status, reason, entry_at) values (?, ?, 'SKIPPED', ?, ?)",
                Date.valueOf(today), expiry == null ? null : Date.valueOf(expiry), reason, Timestamp.valueOf(LocalDateTime.now(IST)));
        notifySlack("OPTIONS book: week skipped", today + ": " + reason);
        log.info("OPTIONS BOOK | skipped {} | {}", today, reason);
        return "skipped: " + reason;
    }

    // ------------------------------------------------------------------ exit

    /** Closes every OPEN condor whose expiry is today or earlier: from the chain, or (settle) at intrinsic. */
    public synchronized String exitDue(LocalDate today, boolean settle) {
        if (!enabled || !busy.compareAndSet(false, true)) {
            return "busy or disabled";
        }
        try {
            List<Map<String, Object>> due = jdbc.queryForList(
                    "select * from options_condor_trade where status = 'OPEN' and expiry <= ?", Date.valueOf(today));
            List<String> out = new ArrayList<>();
            for (Map<String, Object> r : due) {
                LocalDate expiry = ((Date) r.get("expiry")).toLocalDate();
                try {
                    if (!settle && expiry.equals(today) && !LocalTime.now(IST).isBefore(LocalTime.of(15, 15))) {
                        out.add(closeFromChain(r, expiry));
                    } else if (settle && (expiry.isBefore(today) || !LocalTime.now(IST).isBefore(LocalTime.of(15, 30)))) {
                        out.add(settleAtIntrinsic(r, expiry));
                    }
                } catch (Exception ex) {
                    log.warn("OPTIONS BOOK | exit failed for {}: {}", r.get("id"), ex.toString());
                    out.add("exit failed: " + ex.getMessage());
                }
            }
            return out.isEmpty() ? "nothing due" : String.join("; ", out);
        } finally {
            busy.set(false);
        }
    }

    private String closeFromChain(Map<String, Object> r, LocalDate expiry) throws Exception {
        Chain chain = chain(token(), expiry);
        TreeMap<Double, Strike> by = new TreeMap<>();
        chain.strikes().forEach(s -> by.put(s.strike(), s));
        double spot = chain.spot();
        double sc = d(r, "short_call"), lc = d(r, "long_call"), sp = d(r, "short_put"), lp = d(r, "long_put");
        if (!by.containsKey(sc) || !by.containsKey(lc) || !by.containsKey(sp) || !by.containsKey(lp)) {
            throw new IllegalStateException("a strike is missing from the expiry chain");
        }
        double scOut = closePrice(by.get(sc).call(), true, callIntrinsic(spot, sc));
        double spOut = closePrice(by.get(sp).put(), true, putIntrinsic(spot, sp));
        double lcOut = closePrice(by.get(lc).call(), false, callIntrinsic(spot, lc));
        double lpOut = closePrice(by.get(lp).put(), false, putIntrinsic(spot, lp));
        return close(r, spot, scOut, lcOut, spOut, lpOut, "EXPIRY_15_15");
    }

    private String settleAtIntrinsic(Map<String, Object> r, LocalDate expiry) throws Exception {
        double spot = niftyClose(token(), expiry);
        double sc = d(r, "short_call"), lc = d(r, "long_call"), sp = d(r, "short_put"), lp = d(r, "long_put");
        return close(r, spot, callIntrinsic(spot, sc), callIntrinsic(spot, lc), putIntrinsic(spot, sp), putIntrinsic(spot, lp),
                "SETTLED_AT_CLOSE");
    }

    private String close(Map<String, Object> r, double spot, double scOut, double lcOut, double spOut, double lpOut, String reason) {
        int lot = ((Number) r.get("lot_size")).intValue();
        double credit = d(r, "credit");
        double debit = scOut + spOut - lcOut - lpOut;
        double gross = (credit - debit) * lot;
        double sold = d(r, "sc_in") + d(r, "sp_in") + lcOut + lpOut;
        double bought = d(r, "lc_in") + d(r, "lp_in") + scOut + spOut;
        double ch = charges(sold, bought, lot, 8, brokeragePerOrder, sttSellPct);
        double net = gross - ch;
        jdbc.update("update options_condor_trade set status = 'CLOSED', exit_at = ?, spot_exit = ?, sc_out = ?, lc_out = ?, "
                        + "sp_out = ?, lp_out = ?, debit = ?, gross_pnl = ?, charges = ?, net_pnl = ?, exit_reason = ? where id = ?",
                Timestamp.valueOf(LocalDateTime.now(IST)), spot, scOut, lcOut, spOut, lpOut, debit, gross, ch, net, reason, r.get("id"));
        Double total = jdbc.queryForObject("select coalesce(sum(net_pnl), 0) from options_condor_trade where status = 'CLOSED'", Double.class);
        Integer n = jdbc.queryForObject("select count(*) from options_condor_trade where status = 'CLOSED'", Integer.class);
        String msg = String.format("NIFTY %s expiry closed (%s), spot %.1f%nCredit %.2f, debit %.2f a unit%n"
                        + "Gross Rs %.0f, charges Rs %.0f, net Rs %.0f%nBook: %d condor(s), net Rs %.0f",
                r.get("expiry"), reason, spot, credit, debit, gross, ch, net, n, total);
        notifySlack("OPTIONS book: condor closed, net Rs " + Math.round(net), msg);
        log.info("OPTIONS BOOK | closed | {}", msg.replace('\n', ' '));
        return "closed " + r.get("id") + ": net Rs " + Math.round(net);
    }

    private static double d(Map<String, Object> r, String col) {
        Object v = r.get(col);
        return v == null ? 0 : ((Number) v).doubleValue();
    }

    // ------------------------------------------------------------------ report

    public String report() {
        StringBuilder sb = new StringBuilder("OPTIONS PAPER BOOK (separate strategy, paper only)\n").append(RULES).append("\n\n");
        List<Map<String, Object>> rows = jdbc.queryForList("select * from options_condor_trade order by entry_day");
        if (rows.isEmpty()) {
            sb.append("No trades yet. The first entry is 10:00 on the first trading day after an expiry.\n");
        }
        double net = 0;
        int closed = 0, wins = 0;
        for (Map<String, Object> r : rows) {
            String status = (String) r.get("status");
            if ("SKIPPED".equals(status)) {
                sb.append(String.format("%s  SKIPPED  %s%n", r.get("entry_day"), r.get("reason")));
                continue;
            }
            sb.append(String.format("%s  exp %s  %s  spot %.1f  %.0f/%.0f CE  %.0f/%.0f PE  credit %.2f  max loss Rs %.0f",
                    r.get("entry_day"), r.get("expiry"), status, d(r, "spot_entry"), d(r, "short_call"), d(r, "long_call"),
                    d(r, "short_put"), d(r, "long_put"), d(r, "credit"), d(r, "max_loss_rs")));
            if ("CLOSED".equals(status)) {
                closed++;
                net += d(r, "net_pnl");
                wins += d(r, "net_pnl") > 0 ? 1 : 0;
                sb.append(String.format("  -> spot %.1f, debit %.2f, gross Rs %.0f, charges Rs %.0f, net Rs %.0f (%s)",
                        d(r, "spot_exit"), d(r, "debit"), d(r, "gross_pnl"), d(r, "charges"), d(r, "net_pnl"), r.get("exit_reason")));
            }
            sb.append('\n');
        }
        sb.append(String.format("%nClosed %d, winners %d, net Rs %.0f%n", closed, wins, net));
        return sb.toString();
    }

    // ------------------------------------------------------------------ Upstox

    record Contracts(List<LocalDate> expiries, int lotSize) {
    }

    record Chain(double spot, List<Strike> strikes) {
    }

    private String token() {
        String t = tokens.getAccessToken().block(Duration.ofSeconds(15));
        if (t == null || t.isBlank()) {
            throw new IllegalStateException("no Upstox token");
        }
        return t.trim();
    }

    private JsonNode get(String token, String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + token).header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IllegalStateException("Upstox " + path.split("\\?")[0] + " HTTP " + r.statusCode());
        }
        return mapper.readTree(r.body());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    Contracts contracts(String token) throws Exception {
        JsonNode data = get(token, "/option/contract?instrument_key=" + enc(UNDERLYING)).path("data");
        return parseContracts(data);
    }

    static Contracts parseContracts(JsonNode data) {
        TreeSet<LocalDate> expiries = new TreeSet<>();
        int lot = 0;
        for (JsonNode c : data) {
            expiries.add(LocalDate.parse(c.path("expiry").asText()));
            if (lot == 0) {
                lot = c.path("lot_size").asInt(0);
            }
        }
        if (expiries.isEmpty() || lot <= 0) {
            throw new IllegalStateException("no NIFTY option contracts");
        }
        return new Contracts(new ArrayList<>(expiries), lot);
    }

    Chain chain(String token, LocalDate expiry) throws Exception {
        JsonNode data = get(token, "/option/chain?instrument_key=" + enc(UNDERLYING) + "&expiry_date=" + expiry).path("data");
        return parseChain(data);
    }

    static Chain parseChain(JsonNode data) {
        List<Strike> strikes = new ArrayList<>();
        double spot = 0;
        for (JsonNode s : data) {
            spot = s.path("underlying_spot_price").asDouble(spot);
            strikes.add(new Strike(s.path("strike_price").asDouble(), quote(s.path("call_options")), quote(s.path("put_options"))));
        }
        if (strikes.isEmpty() || spot <= 0) {
            throw new IllegalStateException("empty option chain");
        }
        return new Chain(spot, strikes);
    }

    private static Quote quote(JsonNode o) {
        JsonNode m = o.path("market_data");
        return new Quote(o.path("instrument_key").asText(null), m.path("ltp").asDouble(0),
                m.path("bid_price").asDouble(0), m.path("ask_price").asDouble(0));
    }

    /** NIFTY daily close for the day (Upstox daily candles: [time, open, high, low, close, ...]). */
    double niftyClose(String token, LocalDate day) throws Exception {
        String key = enc(UNDERLYING);
        JsonNode candles;
        if (day.equals(LocalDate.now(IST))) {
            candles = get(token, "/historical-candle/intraday/" + key + "/1minute").path("data").path("candles");
            if (candles.size() > 0) {
                return candles.get(0).get(4).asDouble(); // newest first
            }
        }
        candles = get(token, "/historical-candle/" + key + "/day/" + day + "/" + day).path("data").path("candles");
        if (candles.size() == 0) {
            throw new IllegalStateException("no NIFTY close for " + day);
        }
        return candles.get(0).get(4).asDouble();
    }

    private void notifySlack(String title, String message) {
        try {
            String body = mapper.writeValueAsString(Map.of("channel", "SLACK", "title", title, "message", message));
            http.sendAsync(HttpRequest.newBuilder(URI.create(notifyUrl)).header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ex -> {
                        log.warn("OPTIONS BOOK | Slack send failed: {}", ex.toString());
                        return null;
                    });
        } catch (Exception ex) {
            log.warn("OPTIONS BOOK | Slack send failed: {}", ex.toString());
        }
    }
}
