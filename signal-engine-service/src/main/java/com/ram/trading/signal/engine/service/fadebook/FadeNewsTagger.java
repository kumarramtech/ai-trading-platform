package com.ram.trading.signal.engine.service.fadebook;

import com.ram.trading.signal.engine.client.NewsAnalysisClient;
import com.ram.trading.signal.engine.dto.ai.NewsArticle;
import com.ram.trading.signal.engine.entity.FadePaperTrade;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * FADE news study, pre-registered 2026-11-09 (data collection only: FADE's rules,
 * entries and exits are not changed by anything here).
 *
 * Right after the 09:46 shorts, each one is tagged from the headlines the news service
 * returns for the company, keeping only headlines that (1) name the company or its
 * symbol, (2) are not quote, chart or profile pages and (3) were published from 09:00
 * IST on the previous weekday up to the entry. Tag:
 *   CATALYST  a kept headline names a concrete event (results, order, approval, deal...)
 *   NEWS      kept headlines, none naming such an event
 *   NO_NEWS   no kept headline
 *   ERROR     the lookup failed (left out of the study)
 *   EMPTY     no articles at all came back: the news client turns failures into an empty
 *             list, so this is not evidence of no news (left out of the study)
 * Stored in fade_news_tag (its own table, so the FADE minute loop that saves the
 * trades can never overwrite a tag). Tags are only ever made at entry time: tagging
 * later would see news published after the short.
 *
 * Hypothesis (Chan 2003): moves without news reverse, moves with news drift on.
 * Pass mark, fixed before any data: after at least 60 sessions, NO_NEWS shorts earn
 * >= +15 bps net per trade with t >= 2, are positive on both NIFTY up and down days,
 * and beat the NEWS+CATALYST shorts.
 */
@Component
@Slf4j
public class FadeNewsTagger {

    public static final String CATALYST = "CATALYST";
    public static final String NEWS = "NEWS";
    public static final String NO_NEWS = "NO_NEWS";
    public static final String ERROR = "ERROR";
    public static final String EMPTY = "EMPTY";

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Pattern NOT_NEWS = Pattern.compile(
            "stock price|share price today|share price live|quote|chart|company profile|stock list|historical data",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EVENT = Pattern.compile(
            "\\b(results?|q[1-4]|profit|loss|revenue|earnings|ebitda|margin|order|orders|contract|wins|bags|secures"
                    + "|approval|approves|approved|usfda|fda|acquisition|acquires?|merger|demerger|stake|dividend|bonus"
                    + "|split|warrants?|fund ?raise|qip|preferential|ipo|listing|upgrade|downgrade|target|rating|buyback"
                    + "|deal|partnership|tie-up|launch|launches|block deal|bulk deal|promoter|pledge|raid|penalty"
                    + "|sebi|guidance)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final List<String> SUFFIXES = List.of("limited", "ltd", "lt", "co", "company");

    private final NewsAnalysisClient news;
    private final JdbcTemplate jdbc;

    @Value("${trading.fade-book.news-study:true}")
    private boolean enabled = true;

    public FadeNewsTagger(NewsAnalysisClient news, JdbcTemplate jdbc) {
        this.news = news;
        this.jdbc = jdbc;
    }

    @PostConstruct
    void createTable() {
        jdbc.execute("create table if not exists fade_news_tag ("
                + "trade_id bigint primary key, trade_date date not null, symbol varchar(64) not null, "
                + "tag varchar(12) not null, kept int not null, returned int not null, "
                + "headlines varchar(2000), checked_at datetime not null, "
                + "index idx_fade_news_tag_day (trade_date))");
    }

    /** Tags the day's new shorts in the background; never delays or fails the FADE entry. */
    public void tagAtEntry(List<FadePaperTrade> trades) {
        if (!enabled || trades.isEmpty()) {
            return;
        }
        LocalDateTime entry = LocalDateTime.now(IST);
        Flux.fromIterable(trades)
                .filter(t -> t.getId() != null)
                .flatMap(t -> Mono.fromCallable(() -> java.util.Optional.ofNullable(companyName(t.getSymbol())))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(name -> news.getLatestNews(t.getSymbol())
                                .timeout(Duration.ofSeconds(25))
                                .map(articles -> classify(articles, name.orElse(null), t.getSymbol(), entry)))
                        .onErrorResume(ex -> Mono.just(new Tag(ERROR, 0, 0, ex.toString())))
                        .publishOn(Schedulers.boundedElastic())
                        .doOnNext(tag -> store(t, tag, entry)), 2)
                .doOnError(ex -> log.warn("FADE news study: tagging failed: {}", ex.toString()))
                .onErrorResume(ex -> Mono.empty())
                .subscribe();
    }

    public record Tag(String tag, int kept, int returned, String headlines) {
    }

    /** Pure: the tag for one stock from the returned articles at {@code entry} (IST). */
    static Tag classify(List<NewsArticle> articles, String companyName, String symbol, LocalDateTime entry) {
        if (articles == null) {
            return new Tag(ERROR, 0, 0, "no answer");
        }
        if (articles.isEmpty()) {
            // The news client turns a failed lookup into an empty list: not evidence of "no news".
            return new Tag(EMPTY, 0, 0, "");
        }
        LocalDateTime from = previousWeekday(entry.toLocalDate()).atTime(9, 0);
        String key = nameKey(companyName);
        Pattern sym = Pattern.compile("\\b" + Pattern.quote(symbol.toLowerCase(Locale.ROOT)) + "\\b");
        List<String> kept = new ArrayList<>();
        boolean event = false;
        for (NewsArticle a : articles) {
            String title = a.getTitle() == null ? "" : a.getTitle();
            LocalDateTime at = published(a.getPublishedAt());
            if (at == null || at.isBefore(from) || at.isAfter(entry) || NOT_NEWS.matcher(title).find()) {
                continue;
            }
            String norm = normalise(title);
            if (!(sym.matcher(title.toLowerCase(Locale.ROOT)).find() || (!key.isEmpty() && norm.contains(key)))) {
                continue;
            }
            kept.add(title);
            event |= EVENT.matcher(title).find();
        }
        String tag = kept.isEmpty() ? NO_NEWS : event ? CATALYST : NEWS;
        String text = String.join(" | ", kept);
        return new Tag(tag, kept.size(), articles.size(), text.length() <= 1990 ? text : text.substring(0, 1990));
    }

    /** First two words of the company name without suffixes ("ind swift" for IND SWIFT LABORATORIES LT). */
    static String nameKey(String companyName) {
        if (companyName == null) {
            return "";
        }
        List<String> words = new ArrayList<>(List.of(normalise(companyName).split(" ")));
        words.removeIf(w -> w.isBlank() || SUFFIXES.contains(w));
        if (words.isEmpty()) {
            return "";
        }
        String key = String.join(" ", words.subList(0, Math.min(2, words.size())));
        return key.length() >= 4 ? key : "";
    }

    private static String normalise(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    static LocalDate previousWeekday(LocalDate d) {
        LocalDate p = d.minusDays(1);
        while (p.getDayOfWeek() == DayOfWeek.SATURDAY || p.getDayOfWeek() == DayOfWeek.SUNDAY) {
            p = p.minusDays(1);
        }
        return p;
    }

    /** RSS dates ("Thu, 05 Nov 2026 18:13:00 GMT") or ISO, to IST; null when unreadable. */
    static LocalDateTime published(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                    .withZoneSameInstant(IST).toLocalDateTime();
        } catch (RuntimeException ignored) {
            // try ISO next
        }
        try {
            return ZonedDateTime.parse(s.trim()).withZoneSameInstant(IST).toLocalDateTime();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String companyName(String symbol) {
        List<String> names = jdbc.queryForList("select company_name from instrument_master "
                + "where trading_symbol = ? and exchange = 'NSE' limit 1", String.class, symbol);
        return names.isEmpty() ? null : names.get(0);
    }

    private void store(FadePaperTrade t, Tag tag, LocalDateTime at) {
        jdbc.update("replace into fade_news_tag (trade_id, trade_date, symbol, tag, kept, returned, headlines, checked_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)", t.getId(), t.getTradeDate(), t.getSymbol(), tag.tag(),
                tag.kept(), tag.returned(), tag.headlines(), at);
        log.info("FADE NEWS | {} | {} | kept {} of {} | {}", t.getSymbol(), tag.tag(), tag.kept(), tag.returned(),
                tag.headlines().length() > 160 ? tag.headlines().substring(0, 160) + "..." : tag.headlines());
    }
}
