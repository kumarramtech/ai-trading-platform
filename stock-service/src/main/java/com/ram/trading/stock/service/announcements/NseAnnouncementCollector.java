package com.ram.trading.stock.service.announcements;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import jakarta.annotation.PostConstruct;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects NSE corporate announcements (path 1 of the intraday research, 2026-11-18) from NSE's official
 * public RSS feed, every 5 minutes, all day, every day. It only records data (announcement time, company,
 * subject, filing link) into its own table so that an event study can be run later on announcements captured
 * in real time. It trades nothing and does not touch the intraday, FADE, momentum, options or earnings code.
 */
@Service
@Slf4j
public class NseAnnouncementCollector {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final DateTimeFormatter PUB = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH);
    private static final Pattern FILE_PREFIX = Pattern.compile("/corporate/(?:[^/]*/)?([A-Za-z0-9&\\-]+?)_\\d{14}_");

    private final JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile Set<String> symbols = Set.of();
    private volatile LocalDateTime symbolsLoadedAt;

    @Value("${announcements.collector.enabled:true}")
    private boolean enabled = true;

    @Value("${announcements.collector.url:https://nsearchives.nseindia.com/content/RSS/Online_announcements.xml}")
    private String feedUrl = "https://nsearchives.nseindia.com/content/RSS/Online_announcements.xml";

    public NseAnnouncementCollector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void createTable() {
        jdbc.execute("create table if not exists nse_announcement ("
                + "id bigint auto_increment primary key, link_hash char(64) not null, link varchar(1000), "
                + "raw_symbol varchar(40), symbol varchar(40), company varchar(255), subject varchar(255), "
                + "description text, pub_at datetime, seen_at datetime not null, "
                + "unique key uq_nse_announcement_link (link_hash), key ix_nse_announcement_symbol (symbol, pub_at), "
                + "key ix_nse_announcement_pub (pub_at))");
    }

    // ------------------------------------------------------------------ pure parts (tested)

    public record Item(String link, String company, String subject, String description, LocalDateTime pubAt) {
    }

    /** Parses the RSS document; external entities and DTDs are refused. */
    static List<Item> parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setExpandEntityReferences(false);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        NodeList items = doc.getElementsByTagName("item");
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < items.getLength(); i++) {
            Element e = (Element) items.item(i);
            String link = text(e, "link");
            if (link == null || link.isBlank()) {
                continue;
            }
            String desc = text(e, "description");
            out.add(new Item(link.trim(), trim(text(e, "title"), 255), trim(subject(desc), 255), desc,
                    pubAt(text(e, "pubDate"))));
        }
        return out;
    }

    static String subject(String description) {
        if (description == null) {
            return null;
        }
        int i = description.lastIndexOf("SUBJECT:");
        return i < 0 ? null : description.substring(i + 8).trim();
    }

    static LocalDateTime pubAt(String s) {
        try {
            return s == null ? null : LocalDateTime.parse(s.trim(), PUB);
        } catch (Exception ex) {
            return null;
        }
    }

    /** The symbol-like prefix of the filing's file name, e.g. "SBIN1" from .../corporate/SBIN1_18112026162054_x.pdf. */
    static String rawSymbol(String link) {
        Matcher m = FILE_PREFIX.matcher(link == null ? "" : link);
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : null;
    }

    /** The NSE equity symbol: the prefix itself, or the prefix without trailing digits (SBIN1 -> SBIN); else null. */
    static String resolve(String raw, Set<String> known) {
        if (raw == null) {
            return null;
        }
        if (known.contains(raw)) {
            return raw;
        }
        String stripped = raw.replaceAll("\\d+$", "");
        return !stripped.isEmpty() && known.contains(stripped) ? stripped : null;
    }

    // ------------------------------------------------------------------ job

    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    public void collect() {
        if (!enabled || !busy.compareAndSet(false, true)) {
            return;
        }
        try {
            HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(feedUrl))
                    .timeout(Duration.ofSeconds(30)).header("User-Agent", "research-client/1.0").GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) {
                log.warn("NSE ANNOUNCEMENTS | feed HTTP {}", r.statusCode());
                return;
            }
            List<Item> items = parse(r.body());
            Set<String> known = knownSymbols();
            Timestamp now = Timestamp.valueOf(LocalDateTime.now(IST));
            int added = 0;
            for (Item it : items) {
                String raw = rawSymbol(it.link());
                added += jdbc.update("insert ignore into nse_announcement (link_hash, link, raw_symbol, symbol, company, "
                                + "subject, description, pub_at, seen_at) values (?,?,?,?,?,?,?,?,?)",
                        sha256(it.link()), trim(it.link(), 1000), trim(raw, 40), resolve(raw, known), it.company(),
                        it.subject(), it.description(), it.pubAt() == null ? null : Timestamp.valueOf(it.pubAt()), now);
            }
            if (added > 0) {
                log.info("NSE ANNOUNCEMENTS | feed items {} | new {}", items.size(), added);
            }
        } catch (Exception ex) {
            log.warn("NSE ANNOUNCEMENTS | collect failed: {}", ex.toString());
        } finally {
            busy.set(false);
        }
    }

    private Set<String> knownSymbols() {
        LocalDateTime now = LocalDateTime.now(IST);
        if (symbolsLoadedAt == null || symbolsLoadedAt.isBefore(now.minusHours(12)) || symbols.isEmpty()) {
            try {
                symbols = new HashSet<>(jdbc.queryForList("select distinct upper(trading_symbol) from instrument_master "
                        + "where segment = 'NSE_EQ' and trading_symbol is not null", String.class));
                symbolsLoadedAt = now;
            } catch (Exception ex) {
                log.warn("NSE ANNOUNCEMENTS | symbol list unavailable: {}", ex.toString());
            }
        }
        return symbols;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder("NSE ANNOUNCEMENTS (data collection only, from 2026-11-18)\n");
        jdbc.query("select count(*), sum(symbol is not null), min(pub_at), max(pub_at), "
                + "avg(timestampdiff(second, pub_at, seen_at)) from nse_announcement", rs -> {
            sb.append(String.format("  total %d, symbol resolved %d, first %s, last %s, avg capture delay %.0f s%n",
                    rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getDouble(5)));
        });
        sb.append("  top subjects (excluding NAV):\n");
        jdbc.query("select subject, count(*) c from nse_announcement where subject <> 'Declaration of NAV' "
                + "group by subject order by c desc limit 15", rs -> {
            sb.append(String.format("    %5d  %s%n", rs.getLong(2), rs.getString(1)));
        });
        return sb.toString();
    }

    private static String text(Element e, String tag) {
        NodeList n = e.getElementsByTagName(tag);
        return n.getLength() == 0 ? null : n.item(0).getTextContent();
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
