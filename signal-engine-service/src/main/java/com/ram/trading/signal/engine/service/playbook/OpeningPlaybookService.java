package com.ram.trading.signal.engine.service.playbook;

import com.ram.trading.signal.engine.client.NotificationClient;
import com.ram.trading.signal.engine.dto.notification.NotificationChannel;
import com.ram.trading.signal.engine.dto.notification.NotificationRequest;
import com.ram.trading.signal.engine.dto.premarket.OpeningRange;
import com.ram.trading.signal.engine.service.OpeningRangeService;
import com.ram.trading.signal.engine.service.replay.DailyBars;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The 09:15-09:30 read a trader does before taking the first trade: which
 * stocks are "in play" today.
 *
 * At 09:31, for every stock with a complete opening range, it compares the
 * first 15 minutes with what the stock normally does:
 *  - opening relative volume: OR volume / 20-session average daily volume
 *    (a normal open trades ~5-10% of a day's volume; 20%+ means news or flows),
 *  - gap from yesterday's close,
 *  - opening-range width (the ORB stop distance),
 * and keeps the liquid ones (price and turnover minimums of the liquidity filter).
 *
 * The top of the list is logged and sent to Slack. When
 * trading.playbook.require-in-play=true, ORB/JUDAS setups in stocks below
 * min-opening-rvol are skipped (and followed as NOT_IN_PLAY shadow trades);
 * the default is off until the replay shows the rule pays.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OpeningPlaybookService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OpeningRangeService openingRangeService;
    private final JdbcTemplate jdbc;
    private final NotificationClient notificationClient;

    @Value("${trading.playbook.enabled:true}")
    private boolean enabled = true;

    @Value("${trading.playbook.require-in-play:false}")
    private boolean requireInPlay = false;

    @Value("${trading.playbook.min-opening-rvol:0.10}")
    private double minOpeningRvol = 0.10;

    @Value("${trading.playbook.top:15}")
    private int top = 15;

    @Value("${trading.liquidity.min-price:50}")
    private double minPrice = 50;

    @Value("${trading.liquidity.min-average-turnover-crore:5}")
    private double minTurnoverCrore = 5;

    public record Entry(String symbol, double price, double gapPct, double orWidthPct, double openingRvol,
                        double orMovePct, double avgTurnoverCrore) {

        /** Direction the opening favours: the side the price has moved since yesterday's close. */
        public String bias() {
            return gapPct + orMovePct >= 0 ? "LONG" : "SHORT";
        }
    }

    public record Playbook(LocalDate day, LocalDateTime builtAt, int stocksScanned, List<Entry> inPlay) {
    }

    private volatile Playbook today;
    private final Map<String, Entry> bySymbol = new ConcurrentHashMap<>();

    @Scheduled(cron = "${trading.playbook.cron:0 31 9 * * MON-FRI}", zone = "Asia/Kolkata")
    public void buildAtOpen() {
        if (!enabled) {
            return;
        }
        try {
            Playbook playbook = build();
            send(playbook);
        } catch (Exception ex) {
            log.error("OPENING PLAYBOOK failed", ex);
        }
    }

    /** Builds (or rebuilds) today's playbook from the opening ranges seen so far. */
    public Playbook build() {
        LocalDate day = LocalDate.now(IST);
        Map<String, OpeningRange> ranges = openingRangeService.calculateAll();
        DailyBars daily = DailyBars.load(jdbc, null, day.minusDays(45), day);

        List<Entry> entries = new ArrayList<>();
        ranges.forEach((symbol, range) -> {
            if (range == null || !range.isComplete() || range.getClose() == null || range.getLow() == null
                    || range.getLow() <= 0 || range.getVolume() == null) {
                return;
            }
            DailyBars.Stats stats = daily.statsBefore(symbol, day);
            if (stats == null || stats.avgVolume20() <= 0 || stats.prevClose() <= 0) {
                return;
            }
            double price = range.getClose();
            if (price < minPrice || stats.avgTurnover20() < minTurnoverCrore * 1e7) {
                return;
            }
            entries.add(new Entry(symbol, price,
                    (range.getOpen() - stats.prevClose()) / stats.prevClose() * 100.0,
                    (range.getHigh() - range.getLow()) / range.getLow() * 100.0,
                    range.getVolume() / stats.avgVolume20(),
                    (range.getClose() - range.getOpen()) / range.getOpen() * 100.0,
                    stats.avgTurnover20() / 1e7));
        });

        entries.sort(Comparator.comparingDouble(Entry::openingRvol).reversed());
        bySymbol.clear();
        entries.forEach(e -> bySymbol.put(e.symbol(), e));

        List<Entry> inPlay = entries.stream().filter(e -> e.openingRvol() >= minOpeningRvol).toList();
        Playbook playbook = new Playbook(day, LocalDateTime.now(IST), ranges.size(), inPlay);
        today = playbook;
        log.info("OPENING PLAYBOOK | Day={} | OpeningRanges={} | Liquid={} | InPlay(rvol>={})={} | Gate={}",
                day, ranges.size(), entries.size(), minOpeningRvol, inPlay.size(),
                requireInPlay ? "ON" : "OFF (informational)");
        return playbook;
    }

    public Playbook today() {
        Playbook p = today;
        return p != null && p.day().equals(LocalDate.now(IST)) ? p : null;
    }

    /**
     * True when the in-play gate should skip a setup in this symbol. Never blocks
     * while the gate is off or before today's playbook exists.
     */
    public boolean blocksSetup(String symbol) {
        if (!requireInPlay || today() == null) {
            return false;
        }
        Entry e = bySymbol.get(symbol);
        return e == null || e.openingRvol() < minOpeningRvol;
    }

    public String toText(Playbook p) {
        if (p == null) {
            return "No opening playbook yet today (built at 09:31 IST).";
        }
        StringBuilder out = new StringBuilder(String.format(
                "OPENING PLAYBOOK %s  |  %d stocks in play (first 15 min >= %.0f%% of a normal day's volume) of %d scanned%n",
                p.day(), p.inPlay().size(), minOpeningRvol * 100, p.stocksScanned()));
        out.append(String.format("%-12s %5s %8s %7s %7s %6s %8s%n",
                "Symbol", "Bias", "Price", "Gap%", "ORw%", "Rvol", "Turn.cr"));
        p.inPlay().stream().limit(top).forEach(e -> out.append(String.format(
                "%-12s %5s %8.2f %+7.2f %7.2f %5.0f%% %8.0f%n",
                e.symbol(), e.bias(), e.price(), e.gapPct(), e.orWidthPct(), e.openingRvol() * 100, e.avgTurnoverCrore())));
        out.append(requireInPlay
                ? "In-play gate ON: ORB/JUDAS only in these stocks."
                : "In-play gate OFF: informational; setups elsewhere still trade.");
        return out.toString();
    }

    private void send(Playbook playbook) {
        String text = toText(playbook);
        log.info("{}", text);
        notificationClient.sendNotification(NotificationRequest.builder()
                        .channel(NotificationChannel.SLACK)
                        .title("Opening playbook: " + playbook.inPlay().size() + " stocks in play")
                        .message(text)
                        .build())
                .doOnError(ex -> log.warn("OPENING PLAYBOOK | Slack send failed | {}", ex.getMessage()))
                .onErrorResume(ex -> Mono.empty())
                .subscribe();
    }
}
