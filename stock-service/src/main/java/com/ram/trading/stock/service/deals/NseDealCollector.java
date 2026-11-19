package com.ram.trading.stock.service.deals;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Collects NSE bulk and block deals (intraday research, 2026-11-19) from NSE's public daily files
 * (nsearchives .../content/equities/bulk.csv and block.csv), which hold the latest trading day's deals.
 * Read three times each evening; rows are stored once in their own table for a later, pre-registered event
 * study (next-day move after large disclosed deals). Data only: trades nothing and does not touch the
 * intraday, FADE, momentum, options, index-rebalance, earnings or announcements code.
 */
@Service
@Slf4j
public class NseDealCollector {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final DateTimeFormatter DAY = new DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("dd-MMM-yyyy").toFormatter(Locale.ENGLISH);
    private static final String BASE = "https://nsearchives.nseindia.com/content/equities/";

    private final JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final AtomicBoolean busy = new AtomicBoolean();

    @Value("${deals.collector.enabled:true}")
    private boolean enabled = true;

    public NseDealCollector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void createTable() {
        jdbc.execute("create table if not exists nse_deal ("
                + "id bigint auto_increment primary key, row_hash char(64) not null, kind varchar(5) not null, "
                + "deal_date date not null, symbol varchar(40), security_name varchar(255), client varchar(255), "
                + "side varchar(4), quantity bigint, price double, remarks varchar(255), seen_at datetime not null, "
                + "unique key uq_nse_deal_row (row_hash), key ix_nse_deal_symbol (symbol, deal_date), "
                + "key ix_nse_deal_date (deal_date))");
    }

    // ------------------------------------------------------------------ pure parts (tested)

    public record Deal(String kind, LocalDate date, String symbol, String securityName, String client, String side,
                       long quantity, double price, String remarks, String rowHash) {
    }

    /**
     * Parses one NSE deals file (header: Date,Symbol,Security Name,Client Name,Buy/Sell,Quantity Traded,
     * Trade Price / Wght. Avg. Price[,Remarks]). Rows that do not parse are skipped. Identical rows in one file
     * are kept apart by their occurrence number, so re-reading the same file never duplicates them.
     */
    static List<Deal> parse(String kind, String csv) throws Exception {
        List<Deal> out = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        String body = csv.startsWith("﻿") ? csv.substring(1) : csv;
        for (CSVRecord r : CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setTrim(true)
                .setIgnoreEmptyLines(true).build().parse(new StringReader(body))) {
            try {
                if (r.size() < 7) {
                    continue;
                }
                LocalDate date = LocalDate.parse(r.get(0), DAY);
                String side = r.get(4).toUpperCase(Locale.ROOT);
                long qty = Long.parseLong(r.get(5).replace(",", ""));
                double price = Double.parseDouble(r.get(6).replace(",", ""));
                String remarks = r.size() > 7 ? r.get(7) : null;
                String line = String.join("|", kind, r.get(0), r.get(1), r.get(3), side, r.get(5), r.get(6));
                int n = seen.merge(line, 1, Integer::sum);
                out.add(new Deal(kind, date, trim(r.get(1).toUpperCase(Locale.ROOT), 40), trim(r.get(2), 255),
                        trim(r.get(3), 255), trim(side, 4), qty, price,
                        remarks == null || remarks.equals("-") ? null : trim(remarks, 255), sha256(line + "|" + n)));
            } catch (Exception ex) {
                // a malformed row: skipped
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ job

    /** NSE posts the files after the close; three reads catch late updates, duplicates are ignored. */
    @Scheduled(cron = "0 30 18,20,22 * * *", zone = "Asia/Kolkata")
    public void scheduled() {
        collect();
    }

    public String collect() {
        if (!enabled || !busy.compareAndSet(false, true)) {
            return "collector busy or disabled";
        }
        StringBuilder sb = new StringBuilder();
        try {
            for (String kind : List.of("BULK", "BLOCK")) {
                try {
                    HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(BASE + kind.toLowerCase(Locale.ROOT) + ".csv"))
                                    .timeout(Duration.ofSeconds(30)).header("User-Agent", "research-client/1.0").GET().build(),
                            HttpResponse.BodyHandlers.ofByteArray());
                    if (r.statusCode() != 200) {
                        log.warn("NSE DEALS | {} HTTP {}", kind, r.statusCode());
                        sb.append(kind).append(": HTTP ").append(r.statusCode()).append('\n');
                        continue;
                    }
                    List<Deal> deals = parse(kind, new String(r.body(), StandardCharsets.UTF_8));
                    Timestamp now = Timestamp.valueOf(LocalDateTime.now(IST));
                    int added = 0;
                    for (Deal d : deals) {
                        added += jdbc.update("insert ignore into nse_deal (row_hash, kind, deal_date, symbol, security_name, "
                                        + "client, side, quantity, price, remarks, seen_at) values (?,?,?,?,?,?,?,?,?,?,?)",
                                d.rowHash(), d.kind(), d.date(), d.symbol(), d.securityName(), d.client(), d.side(),
                                d.quantity(), d.price(), d.remarks(), now);
                    }
                    log.info("NSE DEALS | {} rows {} | new {}", kind, deals.size(), added);
                    sb.append(String.format("%s: rows %d, new %d%n", kind, deals.size(), added));
                } catch (Exception ex) {
                    log.warn("NSE DEALS | {} failed: {}", kind, ex.toString());
                    sb.append(kind).append(": failed ").append(ex).append('\n');
                }
            }
        } finally {
            busy.set(false);
        }
        return sb.toString();
    }

    public String summary() {
        StringBuilder sb = new StringBuilder("NSE BULK / BLOCK DEALS (data collection only, from 2026-11-19)\n");
        jdbc.query("select kind, count(*), count(distinct deal_date), min(deal_date), max(deal_date), "
                + "count(distinct symbol) from nse_deal group by kind order by kind", rs -> {
            sb.append(String.format("  %-5s rows %d, days %d (%s .. %s), symbols %d%n", rs.getString(1), rs.getLong(2),
                    rs.getLong(3), rs.getString(4), rs.getString(5), rs.getLong(6)));
        });
        sb.append("  latest day, largest deals by value (Rs cr):\n");
        jdbc.query("select kind, deal_date, symbol, side, quantity * price / 1e7 v, client from nse_deal "
                + "where deal_date = (select max(deal_date) from nse_deal) order by v desc limit 10", rs -> {
            sb.append(String.format("    %-5s %s %-12s %-4s %8.1f  %s%n", rs.getString(1), rs.getString(2),
                    rs.getString(3), rs.getString(4), rs.getDouble(5), rs.getString(6)));
        });
        return sb.toString();
    }

    private static String trim(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
