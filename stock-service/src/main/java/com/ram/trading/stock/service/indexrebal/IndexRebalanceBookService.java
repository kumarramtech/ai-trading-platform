package com.ram.trading.stock.service.indexrebal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ram.trading.stock.service.auth.UpstoxTokenService;
import com.ram.trading.stock.service.calendar.MarketCalendarService;
import com.ram.trading.stock.service.dashboard.BookViews;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * INDEX REBALANCE paper book (separate strategy, paper only; built 2026-11-18 after test B1 of the pre-registered
 * study research/index_rebalance.py passed). Rules, fixed from that test:
 * <ul>
 *   <li>events: the scheduled semi-annual review of NIFTY 50, NIFTY Next 50 and NIFTY Midcap 150, read from the
 *       official NSE Indices press release (niftyindices.com/Press_Release/ind_prsDDMMYYYY.pdf); a stock named in
 *       several sections counts once, by the largest index (NIFTY 50 &gt; Next 50 &gt; Midcap 150);</li>
 *   <li>trade day: the change date, i.e. the "close of" date in the release (the last close before the change);</li>
 *   <li>eligible: previous close &gt;= Rs 100 and 20-day average traded value &gt;= Rs 10 crore;</li>
 *   <li>09:20 buy every added stock and short every removed stock, Rs 50,000 each; 15:25 close everything;</li>
 *   <li>costs 0.13% of the entry value a round trip (as in the study); the result is also shown against NIFTY.</li>
 * </ul>
 * It reads public NSE releases and Upstox market data, writes only its own tables, never places an order and does
 * not touch the intraday, FADE, momentum, options or earnings code.
 */
@Service
@Slf4j
public class IndexRebalanceBookService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final String NIFTY = "NSE_INDEX|Nifty 50";
    static final double NOTIONAL = 50_000, COST_PCT = 0.13, MIN_PRICE = 100, MIN_TURNOVER = 10e7;
    static final List<String> INDICES = List.of("NIFTY 50", "NIFTY NEXT 50", "NIFTY MIDCAP 150");
    static final double OTHER_COST_PCT = 0.18;
    static final Map<String, String> STUDY = Map.of("MAIN", "B1 +0.58%", "O2", "+0.81%", "O1", "+2.17% on 35 trades");
    static final String RULES = "On the change date of each NSE scheduled review: 09:20 buy added stocks, short removed "
            + "stocks, Rs 50,000 each; 15:25 close; price >= Rs 100 and 20-day traded value >= Rs 10 cr.\n"
            + "Groups: MAIN = NIFTY 50 / Next 50 / Midcap 150 (study B1 +0.58%, cost 0.13%); O2 = every other index "
            + "(study +0.81%, cost 0.18%); O1 = stocks with a Smallcap 250 change (study +2.17% on only 35 trades, "
            + "cost 0.18%; tracked, not yet tested). A stock with a MAIN change, or added to one index and dropped from "
            + "another, is not in O1/O2";

    private static final Pattern MONTH_CLOSE = Pattern.compile("close of (January|February|March|April|May|June|July|"
            + "August|September|October|November|December)\\s+(\\d{1,2}),?\\s*(\\d{4})");
    private static final Pattern HEAD = Pattern.compile(
            "^\\s*(?:\\d+|[a-zA-Z])[).]\\s*(NIFTY[ A-Za-z0-9&:\\-]*?)\\s*(?:index)?\\s*:?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCOPE = Pattern.compile("replacements? on account of", Pattern.CASE_INSENSITIVE);
    private static final Pattern REVIEW = Pattern.compile("semi-annual review|periodic review", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_BROAD = Pattern.compile("sectoral|thematic|strategy", Pattern.CASE_INSENSITIVE);
    private static final Pattern OTHER_REVIEW = Pattern.compile(
            "semi-annual review|periodic review|annual review|quarterly review", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROW = Pattern.compile("^\\s*\\d+\\.?\\s+.*?\\s((?=[A-Z0-9&\\-]*[A-Z])[A-Z0-9][A-Z0-9&\\-]{1,19})\\s*$");
    private static final Pattern NUMBER_ONLY = Pattern.compile("^\\s*\\d+\\.?\\s*$");

    private final JdbcTemplate jdbc;
    private final UpstoxTokenService tokens;
    private final MarketCalendarService calendar;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final AtomicBoolean busy = new AtomicBoolean();

    @Value("${index-rebalance.book.enabled:true}")
    private boolean enabled = true;

    @Value("${upstox.base-url:https://api.upstox.com/v2}")
    private String baseUrl = "https://api.upstox.com/v2";

    @Value("${index-rebalance.book.release-url:https://niftyindices.com/Press_Release/}")
    private String releaseUrl = "https://niftyindices.com/Press_Release/";

    @Value("${index-rebalance.book.notify-url:http://localhost:8087/notifications/send}")
    private String notifyUrl = "http://localhost:8087/notifications/send";

    public IndexRebalanceBookService(JdbcTemplate jdbc, UpstoxTokenService tokens, MarketCalendarService calendar) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.calendar = calendar;
    }

    @PostConstruct
    void createTables() {
        jdbc.execute("create table if not exists index_rebal_release (file varchar(64) primary key, announce_date date, "
                + "change_date date, events int, note varchar(1000), seen_at datetime not null)");
        jdbc.execute("create table if not exists index_rebal_event (id bigint auto_increment primary key, "
                + "change_date date not null, symbol varchar(40) not null, index_name varchar(40), side varchar(4) not null, "
                + "announce_date date, source varchar(64), created_at datetime not null, "
                + "unique key uq_index_rebal_event (change_date, symbol))");
        jdbc.execute("create table if not exists index_rebal_trade (id bigint auto_increment primary key, "
                + "change_date date not null, symbol varchar(40) not null, instrument_key varchar(64), index_name varchar(40), "
                + "side varchar(4), status varchar(10) not null, reason varchar(255), qty int, entry_price double, "
                + "nifty_entry double, entry_at datetime, exit_price double, nifty_exit double, exit_at datetime, "
                + "gross_pnl double, charges double, net_pnl double, excess_pct double, "
                + "unique key uq_index_rebal_trade (change_date, symbol))");
        // 2026-11-21: other NSE indices (groups O1 / O2) next to the original MAIN events.
        for (String t : List.of("index_rebal_event", "index_rebal_trade")) {
            alter("alter table " + t + " add column grp varchar(4) not null default 'MAIN'");
            alter("alter table " + t + " modify index_name varchar(255)");
        }
    }

    private void alter(String sql) {
        try {
            jdbc.execute(sql);
        } catch (Exception ex) {
            log.debug("INDEX REBALANCE | {} skipped: {}", sql, ex.getMessage()); // column already there
        }
    }

    /** Round-trip cost % by group: MAIN as study B1, O1 / O2 as the other-indices study (extra slippage). */
    static double costPct(String group) {
        return group == null || "MAIN".equals(group) ? COST_PCT : OTHER_COST_PCT;
    }

    // ------------------------------------------------------------------ release parsing (pure, tested)

    public record Event(String index, String side, String symbol) {
    }

    public record Release(LocalDate changeDate, List<Event> events, List<String> problems) {
    }

    /**
     * Parses the text of a press release (PDF text in reading order, one table row per line). Returns null unless
     * it is a scheduled review decided by the Index Maintenance Sub-Committee with a "close of" date. Events are
     * classified once per stock by the largest index; a section whose exclusion and inclusion counts differ (these
     * indices have a fixed size) is skipped and reported as a problem.
     */
    static Release parse(String text) {
        LocalDate close = reviewClose(text);
        if (close == null) {
            return null;
        }
        Map<String, Map<String, List<String>>> secs = sections(text, false);
        List<String> problems = new ArrayList<>();
        Map<String, Event> best = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, List<String>>> e : secs.entrySet()) {
            List<String> drop = e.getValue().get("DROP"), add = e.getValue().get("ADD");
            if (drop.size() != add.size()) {
                problems.add(e.getKey() + " unbalanced: " + drop.size() + " out / " + add.size() + " in");
                continue;
            }
            for (String sd : List.of("DROP", "ADD")) {
                for (String sym : e.getValue().get(sd)) {
                    Event prev = best.get(sym);
                    if (prev == null || INDICES.indexOf(e.getKey()) < INDICES.indexOf(prev.index())) {
                        best.put(sym, new Event(e.getKey(), sd, sym));
                    }
                }
            }
        }
        return new Release(close, new ArrayList<>(best.values()), problems);
    }

    /** One stock of the OTHER indices: its side, group (O1 = has a Smallcap 250 change, else O2) and its indices. */
    public record OtherEvent(String symbol, String side, String group, List<String> indices) {
    }

    public record OtherRelease(LocalDate changeDate, List<OtherEvent> events, int mixed, int unbalanced) {
    }

    /**
     * The OTHER-indices events of a scheduled review (added 2026-11-21 after the pre-registered out-of-sample check
     * research/index_other_study.py passed for O2 and survived research/index_other_verify.py). Same parse as
     * research/index_events_other.py: every index section except NIFTY 50 / Next 50 / Midcap 150, sectoral, thematic
     * and strategy reviews included; unbalanced sections skipped. One event per stock: left out when the stock has a
     * NIFTY 50 / Next 50 / Midcap 150 event in the release (the MAIN book trades it) or is added to one index and
     * dropped from another.
     */
    static OtherRelease parseOther(String text) {
        LocalDate close = reviewClose(text);
        if (close == null) {
            return null;
        }
        java.util.Set<String> main = new java.util.HashSet<>();
        Release r = parse(text);
        if (r != null) {
            r.events().forEach(e -> main.add(e.symbol()));
        }
        Map<String, List<String[]>> bySymbol = new LinkedHashMap<>();
        int unbalanced = 0;
        for (Map.Entry<String, Map<String, List<String>>> e : sections(text, true).entrySet()) {
            List<String> drop = e.getValue().get("DROP"), add = e.getValue().get("ADD");
            if (drop.size() != add.size()) {
                unbalanced++;
                continue;
            }
            for (String sd : List.of("DROP", "ADD")) {
                for (String sym : e.getValue().get(sd)) {
                    bySymbol.computeIfAbsent(sym, k -> new ArrayList<>()).add(new String[]{e.getKey(), sd});
                }
            }
        }
        List<OtherEvent> out = new ArrayList<>();
        int mixed = 0;
        for (Map.Entry<String, List<String[]>> e : bySymbol.entrySet()) {
            if (main.contains(e.getKey())) {
                continue;
            }
            java.util.Set<String> sides = new java.util.HashSet<>();
            List<String> idx = new ArrayList<>();
            for (String[] x : e.getValue()) {
                sides.add(x[1]);
                if (!idx.contains(x[0])) {
                    idx.add(x[0]);
                }
            }
            if (sides.size() > 1) {
                mixed++;
                continue;
            }
            out.add(new OtherEvent(e.getKey(), sides.iterator().next(),
                    idx.contains("NIFTY SMALLCAP 250") ? "O1" : "O2", idx));
        }
        return new OtherRelease(close, out, mixed, unbalanced);
    }

    /** The "close of" date of a scheduled review decided by the Index Maintenance Sub-Committee, else null. */
    private static LocalDate reviewClose(String text) {
        String flat = text.replaceAll("\\s+", " ");
        if (!flat.contains("Index Maintenance Sub") || !REVIEW.matcher(flat).find()) {
            return null;
        }
        Matcher m = MONTH_CLOSE.matcher(flat);
        if (!m.find()) {
            return null;
        }
        return LocalDate.parse(m.group(1) + " " + m.group(2) + " " + m.group(3),
                DateTimeFormatter.ofPattern("MMMM d yyyy", Locale.ENGLISH));
    }

    /**
     * Index sections: name -> {DROP: symbols, ADD: symbols}. {@code other} false = NIFTY 50 / Next 50 / Midcap 150 in
     * the broad scheduled reviews only; true = every other index, in any scheduled (incl. annual / quarterly,
     * sectoral / thematic / strategy) review part.
     */
    private static Map<String, Map<String, List<String>>> sections(String text, boolean other) {
        Map<String, Map<String, List<String>>> secs = new LinkedHashMap<>();
        String sec = null, side = null, pending = null;
        boolean scope = true;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw;
            if (SCOPE.matcher(line).find()) {
                scope = other ? OTHER_REVIEW.matcher(line).find()
                        : REVIEW.matcher(line).find() && !NOT_BROAD.matcher(line).find();
                sec = null;
                continue;
            }
            Matcher h = HEAD.matcher(line);
            if (h.matches()) {
                String name = h.group(1).replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
                boolean empty = secs.containsKey(name) && secs.get(name).get("DROP").isEmpty() && secs.get(name).get("ADD").isEmpty();
                boolean wanted = other ? !INDICES.contains(name) : INDICES.contains(name);
                sec = scope && wanted && (!secs.containsKey(name) || empty) ? name : null;
                side = null;
                pending = null;
                if (sec != null) {
                    Map<String, List<String>> s = new LinkedHashMap<>();
                    s.put("DROP", new ArrayList<>());
                    s.put("ADD", new ArrayList<>());
                    secs.put(sec, s);
                }
                continue;
            }
            if (sec == null) {
                continue;
            }
            String low = line.toLowerCase(Locale.ROOT);
            if (low.contains("being excluded")) {
                side = "DROP";
            } else if (low.contains("being included")) {
                side = "ADD";
            } else if (low.trim().startsWith("note") || low.contains("applicable to")) {
                side = null;
            } else if (side != null) {
                if (NUMBER_ONLY.matcher(line).matches()) {
                    pending = line; // a row whose company name wraps onto the next lines
                    continue;
                }
                if (pending != null) {
                    line = pending + " " + line;
                    pending = line.split(" ").length < 13 ? line : null;
                }
                Matcher r = ROW.matcher(line);
                if (r.matches()) {
                    pending = null;
                    List<String> list = secs.get(sec).get(side);
                    if (!list.contains(r.group(1))) {
                        list.add(r.group(1));
                    }
                }
            }
        }
        return secs;
    }

    static String pdfText(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper s = new PDFTextStripper();
            s.setSortByPosition(false);
            return s.getText(doc);
        }
    }

    /** Average daily traded value of the last 20 sessions, from daily candles [time, o, h, l, c, volume], newest first. */
    static double avgTurnover(JsonNode candles) {
        int n = Math.min(20, candles.size());
        if (n < 20) {
            return 0;
        }
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += candles.get(i).get(4).asDouble() * candles.get(i).get(5).asDouble();
        }
        return sum / n;
    }

    /** Paper result of one trade: [gross Rs, charges Rs, net Rs, excess % vs NIFTY after costs]. */
    static double[] result(int dir, int qty, double in, double out, double niftyIn, double niftyOut) {
        return result(dir, qty, in, out, niftyIn, niftyOut, COST_PCT);
    }

    static double[] result(int dir, int qty, double in, double out, double niftyIn, double niftyOut, double costPct) {
        double gross = dir * qty * (out - in);
        double charges = qty * in * costPct / 100;
        double excess = dir * ((out / in - 1) - (niftyOut / niftyIn - 1)) * 100 - costPct;
        return new double[]{gross, charges, gross - charges, excess};
    }

    // ------------------------------------------------------------------ release scan

    /** Evenings: looks for new NSE Indices releases of the last 4 days (the review is usually out ~5 weeks ahead). */
    @Scheduled(cron = "0 30 19,22 * * *", zone = "Asia/Kolkata")
    public void scanJob() {
        LocalDate today = LocalDate.now(IST);
        for (int back = 0; back < 4; back++) {
            scan(today.minusDays(back));
        }
    }

    public synchronized String scan(LocalDate day) {
        if (!enabled) {
            return "disabled";
        }
        List<String> out = new ArrayList<>();
        String base = "ind_prs" + day.format(DateTimeFormatter.ofPattern("ddMMyyyy"));
        for (String suffix : List.of("", "_1", "_2", "_3")) {
            String file = base + suffix + ".pdf";
            Integer seen = jdbc.queryForObject("select count(*) from index_rebal_release where file = ?", Integer.class, file);
            if (seen != null && seen > 0) {
                continue;
            }
            try {
                HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(releaseUrl + file))
                        .timeout(Duration.ofSeconds(30)).header("User-Agent", "research-client/1.0").GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                byte[] body = r.body();
                if (r.statusCode() != 200 || body.length < 5 || !new String(body, 0, 4, StandardCharsets.ISO_8859_1).equals("%PDF")) {
                    continue;
                }
                String text = pdfText(body);
                Release rel = parse(text);
                OtherRelease oth = parseOther(text);
                int added = 0, otherAdded = 0;
                String note = rel == null ? "not a scheduled review" : String.join("; ", rel.problems());
                if (rel != null) {
                    for (Event e : rel.events()) {
                        // A MAIN change always wins over an O1/O2 row of the same stock and date (another release).
                        added += jdbc.update("insert into index_rebal_event (change_date, symbol, index_name, side, "
                                        + "announce_date, source, created_at, grp) values (?, ?, ?, ?, ?, ?, ?, 'MAIN') "
                                        + "on duplicate key update index_name = if(grp = 'MAIN', index_name, values(index_name)), "
                                        + "side = if(grp = 'MAIN', side, values(side)), grp = 'MAIN'",
                                Date.valueOf(rel.changeDate()), e.symbol(), e.index(), e.side(), Date.valueOf(day), file,
                                Timestamp.valueOf(LocalDateTime.now(IST))) > 0 ? 1 : 0;
                    }
                }
                if (oth != null) {
                    for (OtherEvent e : oth.events()) {
                        // Same stock and date already known: a MAIN row stays; an O1/O2 row of the other side -> MIX (skipped).
                        otherAdded += jdbc.update("insert into index_rebal_event (change_date, symbol, index_name, side, "
                                        + "announce_date, source, created_at, grp) values (?, ?, ?, ?, ?, ?, ?, ?) "
                                        + "on duplicate key update side = if(grp <> 'MAIN' and side <> values(side), 'MIX', side)",
                                Date.valueOf(oth.changeDate()), e.symbol(), trim(String.join("; ", e.indices()), 255),
                                e.side(), Date.valueOf(day), file, Timestamp.valueOf(LocalDateTime.now(IST)), e.group()) == 1 ? 1 : 0;
                    }
                    String o = String.format("other indices: %d stocks (%d O1), %d added-and-dropped left out, %d unbalanced "
                                    + "sections skipped", oth.events().size(),
                            oth.events().stream().filter(e -> "O1".equals(e.group())).count(), oth.mixed(), oth.unbalanced());
                    note = note == null || note.isEmpty() || rel == null ? o : note + "; " + o;
                }
                jdbc.update("insert ignore into index_rebal_release (file, announce_date, change_date, events, note, seen_at) "
                                + "values (?, ?, ?, ?, ?, ?)", file, Date.valueOf(day),
                        rel == null ? null : Date.valueOf(rel.changeDate()), added + otherAdded, trim(note, 1000),
                        Timestamp.valueOf(LocalDateTime.now(IST)));
                if (rel != null && (added + otherAdded > 0 || !rel.problems().isEmpty())) {
                    long adds = rel.events().stream().filter(e -> e.side().equals("ADD")).count();
                    long oAdds = oth == null ? 0 : oth.events().stream().filter(e -> e.side().equals("ADD")).count();
                    int oN = oth == null ? 0 : oth.events().size();
                    String msg = String.format("%s: change date %s, MAIN %d stocks (%d added, %d removed); other indices "
                                    + "O1/O2 %d stocks (%d added, %d removed)%s",
                            file, rel.changeDate(), rel.events().size(), adds, rel.events().size() - adds, oN, oAdds,
                            oN - oAdds, rel.problems().isEmpty() ? "" : "\nCHECK BY HAND: " + note);
                    notifySlack("INDEX REBALANCE book: review release found", msg);
                    log.info("INDEX REBALANCE | {}", msg.replace('\n', ' '));
                }
                out.add(file + ": " + (rel == null ? "not a review" : added + " MAIN + " + otherAdded + " other events, change "
                        + rel.changeDate()));
            } catch (Exception ex) {
                log.warn("INDEX REBALANCE | release {} failed: {}", file, ex.toString());
                out.add(file + ": failed " + ex.getMessage());
            }
        }
        return out.isEmpty() ? "no new release for " + day : String.join("; ", out);
    }

    // ------------------------------------------------------------------ trading

    /** 09:20, retried at 09:22 and 09:24 for anything not yet handled. */
    @Scheduled(cron = "0 20,22,24 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void entryJob() {
        enter(LocalDate.now(IST), !LocalTime.now(IST).isBefore(LocalTime.of(9, 24)));
    }

    /** 15:25, retried at 15:26 and 15:27. */
    @Scheduled(cron = "0 25,26,27 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void exitJob() {
        exit(LocalDate.now(IST));
    }

    /** After the close: anything still open is closed at the 15:25 one-minute candle. */
    @Scheduled(cron = "0 45 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void settleJob() {
        settleFromCandles();
    }

    public synchronized String enter(LocalDate today, boolean lastAttempt) {
        if (!enabled || !calendar.check(today).tradingDay()) {
            return "disabled or not a trading day";
        }
        List<Map<String, Object>> events = jdbc.queryForList("select e.* from index_rebal_event e left join index_rebal_trade t "
                + "on t.change_date = e.change_date and t.symbol = e.symbol where e.change_date = ? and t.id is null", Date.valueOf(today));
        if (events.isEmpty()) {
            return "no change date today";
        }
        try {
            String token = token();
            Map<String, String> keys = new HashMap<>();
            for (Map<String, Object> e : events) {
                String sym = (String) e.get("symbol");
                List<String> k = jdbc.queryForList("select instrument_key from instrument_master where segment = 'NSE_EQ' "
                        + "and upper(trading_symbol) = ? limit 1", String.class, sym);
                if (k.isEmpty()) {
                    record(today, e, null, "SKIPPED", "symbol not in instrument_master");
                } else {
                    keys.put(sym, k.get(0));
                }
            }
            List<String> all = new ArrayList<>(keys.values());
            all.add(NIFTY);
            Map<String, Double> ltp = ltp(token, all);
            Double nifty = ltp.get(NIFTY);
            if (nifty == null || nifty <= 0) {
                throw new IllegalStateException("no NIFTY price");
            }
            int opened = 0;
            for (Map<String, Object> e : events) {
                String sym = (String) e.get("symbol");
                String key = keys.get(sym);
                if (key == null) {
                    continue;
                }
                if ("MIX".equals(e.get("side"))) {
                    record(today, e, key, "SKIPPED", "added to one index and dropped from another");
                    continue;
                }
                Double px = ltp.get(key);
                if (px == null || px <= 0) {
                    if (lastAttempt) {
                        record(today, e, key, "SKIPPED", "no price at 09:20");
                    }
                    continue;
                }
                JsonNode daily = get(token, "/historical-candle/" + enc(key) + "/day/" + today.minusDays(1) + "/"
                        + today.minusDays(45)).path("data").path("candles");
                double prevClose = daily.size() > 0 ? daily.get(0).get(4).asDouble() : 0;
                double turnover = avgTurnover(daily);
                if (prevClose < MIN_PRICE || turnover < MIN_TURNOVER) {
                    record(today, e, key, "SKIPPED", String.format("ineligible: prev close %.1f, 20-day value Rs %.1f cr",
                            prevClose, turnover / 1e7));
                    continue;
                }
                int qty = (int) Math.max(1, Math.floor(NOTIONAL / px));
                jdbc.update("insert ignore into index_rebal_trade (change_date, symbol, instrument_key, index_name, side, status, "
                                + "qty, entry_price, nifty_entry, entry_at, grp) values (?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?)",
                        Date.valueOf(today), sym, key, e.get("index_name"), e.get("side"), qty, px, nifty,
                        Timestamp.valueOf(LocalDateTime.now(IST)), group(e));
                opened++;
            }
            String msg = String.format("%s: %d paper positions opened at 09:20 (adds long, removals short), NIFTY %.1f",
                    today, opened, nifty);
            notifySlack("INDEX REBALANCE book: change day entries", msg);
            log.info("INDEX REBALANCE | {}", msg);
            return msg;
        } catch (Exception ex) {
            log.warn("INDEX REBALANCE | entry failed: {}", ex.toString());
            if (lastAttempt) {
                for (Map<String, Object> e : events) {
                    record(today, e, null, "SKIPPED", "data error at entry: " + ex.getMessage());
                }
            }
            return "entry failed: " + ex.getMessage();
        }
    }

    private void record(LocalDate day, Map<String, Object> e, String key, String status, String reason) {
        jdbc.update("insert ignore into index_rebal_trade (change_date, symbol, instrument_key, index_name, side, status, reason, grp) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?)", Date.valueOf(day), e.get("symbol"), key, e.get("index_name"), e.get("side"),
                status, trim(reason, 255), group(e));
    }

    private static String group(Map<String, Object> row) {
        Object g = row.get("grp");
        return g == null ? "MAIN" : g.toString();
    }

    public synchronized String exit(LocalDate today) {
        if (!enabled || !busy.compareAndSet(false, true)) {
            return "busy or disabled";
        }
        try {
            List<Map<String, Object>> open = jdbc.queryForList(
                    "select * from index_rebal_trade where status = 'OPEN' and change_date = ?", Date.valueOf(today));
            if (open.isEmpty()) {
                return "nothing open";
            }
            String token = token();
            List<String> keys = new ArrayList<>(open.stream().map(r -> (String) r.get("instrument_key")).toList());
            keys.add(NIFTY);
            Map<String, Double> ltp = ltp(token, keys);
            Double nifty = ltp.get(NIFTY);
            if (nifty == null || nifty <= 0) {
                return "no NIFTY price, will retry";
            }
            int closed = 0;
            for (Map<String, Object> r : open) {
                Double px = ltp.get((String) r.get("instrument_key"));
                if (px != null && px > 0) {
                    close(r, px, nifty, "15:25");
                    closed++;
                }
            }
            summary(today);
            return closed + " closed";
        } catch (Exception ex) {
            log.warn("INDEX REBALANCE | exit failed: {}", ex.toString());
            return "exit failed: " + ex.getMessage();
        } finally {
            busy.set(false);
        }
    }

    /** Anything still open (exit missed) is closed at the close of its 15:25 one-minute candle. */
    public synchronized String settleFromCandles() {
        List<Map<String, Object>> open = jdbc.queryForList("select * from index_rebal_trade where status = 'OPEN'");
        if (open.isEmpty()) {
            return "nothing open";
        }
        try {
            String token = token();
            Map<LocalDate, Double> niftyAt = new HashMap<>();
            for (Map<String, Object> r : open) {
                LocalDate day = ((Date) r.get("change_date")).toLocalDate();
                if (day.equals(LocalDate.now(IST)) && LocalTime.now(IST).isBefore(LocalTime.of(15, 30))) {
                    continue;
                }
                Double n = niftyAt.computeIfAbsent(day, d -> candle1525(token, NIFTY, d));
                Double px = candle1525(token, (String) r.get("instrument_key"), day);
                if (n != null && px != null) {
                    close(r, px, n, "15:25 candle (settled after the close)");
                }
            }
            return "settled";
        } catch (Exception ex) {
            log.warn("INDEX REBALANCE | settle failed: {}", ex.toString());
            return "settle failed: " + ex.getMessage();
        }
    }

    private void close(Map<String, Object> r, double px, double nifty, String how) {
        int dir = "ADD".equals(r.get("side")) ? 1 : -1;
        double[] res = result(dir, ((Number) r.get("qty")).intValue(), d(r, "entry_price"), px, d(r, "nifty_entry"), nifty,
                costPct(group(r)));
        jdbc.update("update index_rebal_trade set status = 'CLOSED', reason = ?, exit_price = ?, nifty_exit = ?, exit_at = ?, "
                        + "gross_pnl = ?, charges = ?, net_pnl = ?, excess_pct = ? where id = ? and status = 'OPEN'",
                how, px, nifty, Timestamp.valueOf(LocalDateTime.now(IST)), res[0], res[1], res[2], res[3], r.get("id"));
    }

    private void summary(LocalDate day) {
        StringBuilder msg = new StringBuilder(day + ": closed at 15:25, after costs vs NIFTY");
        for (String g : List.of("MAIN", "O2", "O1")) {
            Map<String, Object> s = jdbc.queryForMap("select count(*) n, coalesce(sum(net_pnl), 0) net, coalesce(avg(excess_pct), 0) ex "
                    + "from index_rebal_trade where status = 'CLOSED' and change_date = ? and grp = ?", Date.valueOf(day), g);
            Map<String, Object> all = jdbc.queryForMap("select count(*) n, coalesce(sum(net_pnl), 0) net, coalesce(avg(excess_pct), 0) ex "
                    + "from index_rebal_trade where status = 'CLOSED' and grp = ?", g);
            if (((Number) all.get("n")).intValue() == 0) {
                continue;
            }
            msg.append(String.format("%n%-4s today %s trades, net Rs %.0f, average %+.2f%% | so far %s trades, net Rs %.0f, "
                            + "average %+.2f%% (study %s)", g, s.get("n"), d(s, "net"), d(s, "ex"), all.get("n"), d(all, "net"),
                    d(all, "ex"), STUDY.get(g)));
        }
        notifySlack("INDEX REBALANCE book: change day closed", msg.toString());
        log.info("INDEX REBALANCE | {}", msg.toString().replace('\n', ' '));
    }

    // ------------------------------------------------------------------ report

    /**
     * Where the next releases should come from: NSE changes take effect at the end of a quarter month. NIFTY 50 / Next 50 /
     * Midcap 150 (MAIN) are reviewed for March and September; many other indices (O2/O1) also for June and December. The
     * release usually comes about 3 weeks before the change. Text only: the book trades whatever release it actually finds.
     */
    static String expectedReviews(LocalDate today) {
        java.time.YearMonth other = nextChangeMonth(today, List.of(3, 6, 9, 12));
        java.time.YearMonth main = nextChangeMonth(today, List.of(3, 9));
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("MMM yyyy", java.util.Locale.ENGLISH);
        return String.format("Expected next: other indices (O2/O1) change end of %s, release about 3 weeks before; "
                + "MAIN change end of %s, release about 3-5 weeks before. The release is checked every evening.",
                other.format(f), main.format(f));
    }

    private static java.time.YearMonth nextChangeMonth(LocalDate today, List<Integer> months) {
        java.time.YearMonth m = java.time.YearMonth.from(today);
        while (!months.contains(m.getMonthValue()) || today.isAfter(m.atEndOfMonth())) {
            m = m.plusMonths(1);
        }
        return m;
    }

    /**
     * The book as JSON for a dashboard: rules, per group (MAIN / O2 / O1) the study figure, cost, totals and cumulative
     * P&amp;L, upcoming change dates, the expected next reviews, each change day, the latest releases and all trades.
     */
    public Map<String, Object> summary() {
        LocalDate today = LocalDate.now(IST);
        List<Map<String, Object>> up = jdbc.queryForList("select change_date, grp, count(*) n, sum(side = 'ADD') adds, "
                + "sum(side = 'DROP') drops, min(source) src from index_rebal_event where change_date >= ? "
                + "group by change_date, grp order by change_date, grp", Date.valueOf(today));
        List<Map<String, Object>> rows = jdbc.queryForList("select * from index_rebal_trade order by change_date, grp, symbol");
        Map<String, String> studyPct = Map.of("MAIN", "0.58", "O2", "0.81", "O1", "2.17");
        Map<String, String> studyNote = Map.of("MAIN", "B1 on NIFTY 50 / Next 50 / Midcap 150, 4 reviews",
                "O2", "1486 trades, t 13.8, all 8 change dates positive, passed V1-V7",
                "O1", "Smallcap 250 changes: only 35 trades, tracked, not yet tested");
        Map<String, Object> groups = new LinkedHashMap<>();
        for (String g : List.of("MAIN", "O2", "O1")) {
            List<Map<String, Object>> closed = new ArrayList<>();
            int open = 0, skipped = 0;
            double ex = 0;
            for (Map<String, Object> r : rows) {
                if (!g.equals(group(r))) {
                    continue;
                }
                String st = (String) r.get("status");
                if ("CLOSED".equals(st)) {
                    closed.add(r);
                    ex += d(r, "excess_pct");
                }
                open += "OPEN".equals(st) ? 1 : 0;
                skipped += "SKIPPED".equals(st) ? 1 : 0;
            }
            Map<String, Object> totals = BookViews.totals(closed, "net_pnl", "gross_pnl", "charges");
            totals.put("avgExcessPct", closed.isEmpty() ? null : BookViews.round(ex / closed.size()));
            totals.put("open", open);
            totals.put("skipped", skipped);
            Map<String, Object> grp = new LinkedHashMap<>();
            grp.put("costPct", costPct(g));
            grp.put("studyNetPctVsNifty", Double.parseDouble(studyPct.get(g)));
            grp.put("study", studyNote.get(g));
            grp.put("tested", !"O1".equals(g));
            grp.put("totals", totals);
            grp.put("equity", BookViews.curve(closed, "change_date", "net_pnl"));
            groups.put(g, grp);
        }
        List<Map<String, Object>> days = jdbc.queryForList("select change_date, grp, count(*) n, sum(status = 'CLOSED') closed, "
                + "sum(status = 'SKIPPED') skipped, coalesce(sum(net_pnl), 0) net, avg(excess_pct) ex from index_rebal_trade "
                + "group by change_date, grp order by change_date, grp");
        List<Map<String, Object>> rel = jdbc.queryForList("select file, announce_date, change_date, events, note, seen_at "
                + "from index_rebal_release where change_date is not null order by announce_date desc limit 5");
        java.time.format.DateTimeFormatter f = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("otherIndicesChange", "end of " + nextChangeMonth(today, List.of(3, 6, 9, 12)).format(f));
        expected.put("mainChange", "end of " + nextChangeMonth(today, List.of(3, 9)).format(f));
        expected.put("text", expectedReviews(today));
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("event", "change date of an NSE scheduled index review (releases checked every evening)");
        rules.put("entry", "09:20: buy added stocks, short removed stocks");
        rules.put("exit", "15:25");
        rules.put("notionalRs", NOTIONAL);
        rules.put("minPriceRs", MIN_PRICE);
        rules.put("minTurnoverCr", MIN_TURNOVER / 1e7);
        rules.put("text", RULES);
        List<Map<String, Object>> trades = BookViews.rows(rows);
        long openNow = rows.stream().filter(r -> "OPEN".equals(r.get("status"))).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("book", "INDEX_REBALANCE");
        out.put("paperOnly", true);
        out.put("asOf", LocalDateTime.now(IST).withNano(0).toString());
        out.put("status", openNow > 0 ? "OPEN" : up.isEmpty() ? "WAITING_FOR_NSE_RELEASE" : "UPCOMING");
        out.put("nextChangeDate", up.isEmpty() ? null : BookViews.value(up.get(0).get("change_date")));
        out.put("rules", rules);
        out.put("groups", groups);
        out.put("upcoming", BookViews.rows(up));
        out.put("expectedNext", expected);
        out.put("changeDays", BookViews.rows(days));
        out.put("releases", BookViews.rows(rel));
        out.put("trades", trades);
        return out;
    }

    public String report() {
        StringBuilder sb = new StringBuilder("INDEX REBALANCE PAPER BOOK (separate strategy, paper only)\n").append(RULES).append("\n\n");
        List<Map<String, Object>> up = jdbc.queryForList("select change_date, grp, count(*) n, sum(side = 'ADD') adds, min(source) src "
                + "from index_rebal_event where change_date >= ? group by change_date, grp order by change_date, grp",
                Date.valueOf(LocalDate.now(IST)));
        sb.append(up.isEmpty() ? "No upcoming change date yet. " + expectedReviews(LocalDate.now(IST)) + "\n" : "");
        for (Map<String, Object> u : up) {
            sb.append(String.format("Upcoming change date %s: %-4s %s stocks (%s added) from %s%n", u.get("change_date"),
                    u.get("grp"), u.get("n"), u.get("adds"), u.get("src")));
        }
        List<Map<String, Object>> days = jdbc.queryForList("select change_date, grp, count(*) n, sum(status = 'CLOSED') closed, "
                + "sum(status = 'SKIPPED') skipped, coalesce(sum(net_pnl), 0) net, avg(excess_pct) ex from index_rebal_trade "
                + "group by change_date, grp order by change_date, grp");
        for (Map<String, Object> dd : days) {
            sb.append(String.format("%s  %-4s %s stocks, %s closed, %s skipped, net Rs %.0f, average %s vs NIFTY%n",
                    dd.get("change_date"), dd.get("grp"), dd.get("n"), dd.get("closed"), dd.get("skipped"), d(dd, "net"),
                    dd.get("ex") == null ? "-" : String.format("%+.2f%%", d(dd, "ex"))));
        }
        sb.append("\n");
        for (String g : List.of("MAIN", "O2", "O1")) {
            Map<String, Object> all = jdbc.queryForMap("select count(*) n, coalesce(sum(net_pnl), 0) net, avg(excess_pct) ex, "
                    + "coalesce(sum(net_pnl > 0), 0) wins from index_rebal_trade where status = 'CLOSED' and grp = ?", g);
            sb.append(String.format("%-4s closed %s, winners %s, net Rs %.0f, average %s vs NIFTY after costs (study %s)%n", g,
                    all.get("n"), all.get("wins"), d(all, "net"),
                    all.get("ex") == null ? "-" : String.format("%+.2f%%", d(all, "ex")), STUDY.get(g)));
        }
        List<Map<String, Object>> rel = jdbc.queryForList("select file, change_date, events, note from index_rebal_release "
                + "where change_date is not null order by announce_date desc limit 3");
        for (Map<String, Object> r : rel) {
            sb.append(String.format("release %s: change %s, %s events %s%n", r.get("file"), r.get("change_date"), r.get("events"),
                    r.get("note") == null || r.get("note").toString().isEmpty() ? "" : "(" + r.get("note") + ")"));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ Upstox

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

    /** Last traded prices by instrument key (the response is keyed by symbol; instrument_token carries the key). */
    Map<String, Double> ltp(String token, List<String> keys) throws Exception {
        Map<String, Double> out = new HashMap<>();
        for (int i = 0; i < keys.size(); i += 400) {
            String joined = String.join(",", keys.subList(i, Math.min(keys.size(), i + 400)));
            JsonNode data = get(token, "/market-quote/ltp?instrument_key=" + enc(joined)).path("data");
            data.fields().forEachRemaining(f -> {
                String k = f.getValue().path("instrument_token").asText(null);
                double p = f.getValue().path("last_price").asDouble(0);
                if (k != null) {
                    out.put(k, p);
                }
            });
        }
        return out;
    }

    private Double candle1525(String token, String key, LocalDate day) {
        try {
            JsonNode c = get(token, day.equals(LocalDate.now(IST))
                    ? "/historical-candle/intraday/" + enc(key) + "/1minute"
                    : "/historical-candle/" + enc(key) + "/1minute/" + day + "/" + day).path("data").path("candles");
            for (JsonNode x : c) {
                if (x.get(0).asText().contains("T15:25:")) {
                    return x.get(4).asDouble();
                }
            }
        } catch (Exception ex) {
            log.warn("INDEX REBALANCE | 15:25 candle for {} {} failed: {}", key, day, ex.toString());
        }
        return null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static double d(Map<String, Object> r, String col) {
        Object v = r.get(col);
        return v == null ? 0 : ((Number) v).doubleValue();
    }

    private static String trim(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }

    private void notifySlack(String title, String message) {
        try {
            String body = mapper.writeValueAsString(Map.of("channel", "SLACK", "title", title, "message", message));
            http.sendAsync(HttpRequest.newBuilder(URI.create(notifyUrl)).header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ex -> {
                        log.warn("INDEX REBALANCE | Slack send failed: {}", ex.toString());
                        return null;
                    });
        } catch (Exception ex) {
            log.warn("INDEX REBALANCE | Slack send failed: {}", ex.toString());
        }
    }
}
