package com.ram.trading.signal.engine.service.longterm;

import com.ram.trading.signal.engine.client.NotificationClient;
import com.ram.trading.signal.engine.client.StockServiceClient;
import com.ram.trading.signal.engine.dto.StockResponse;
import com.ram.trading.signal.engine.dto.notification.NotificationChannel;
import com.ram.trading.signal.engine.dto.notification.NotificationRequest;
import com.ram.trading.signal.engine.service.replay.MomentumPaperService;
import com.ram.trading.signal.engine.service.replay.MomentumPaperService.Holding;
import com.ram.trading.signal.engine.service.replay.MomentumSignalService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Watches the long-term (monthly momentum) paper holdings through the session, on its
 * own thread: nothing here runs on the shared trading scheduler, so a slow price call
 * cannot delay an intraday scan, entry or exit.
 *
 * Every {@code interval-seconds} from 09:16 to 15:29 it prices each holding and:
 *   - alerts once a day when a stock is {@code warn-pct} below its buy price;
 *   - alerts once a day when it touches the stop ({@code stop-pct} below the buy price);
 *   - at {@code stop-check} (15:20) paper-sells every holding still at or below the stop.
 *     Selling on the late price, not on the first touch, is what the backtest measured
 *     (a close below the stop), so an intraday wick does not shake a holding out;
 *   - alerts once a day when NIFTY is {@code nifty-drop-pct} below yesterday's close.
 * Rebalancing stays monthly (MomentumSignalScheduler). With {@code weekly-entry} on, a
 * month the market filter left in cash is entered on the first session of the first
 * week after NIFTY closed back above its average: list at 08:52, paper buys from 09:20.
 * stop-pct 0 = alerts only.
 */
@Service
@Slf4j
public class LongTermMonitorService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime OPEN = LocalTime.of(9, 16);
    private static final LocalTime CLOSE = LocalTime.of(15, 29);
    private static final LocalTime LIST_TIME = LocalTime.of(8, 52);
    private static final LocalTime BUY_TIME = LocalTime.of(9, 20);
    private static final LocalTime HOLIDAY_BY = LocalTime.of(10, 0);

    private final MomentumPaperService paper;
    private final MomentumSignalService signal;
    private final StockServiceClient stockServiceClient;
    private final NotificationClient notificationClient;
    private final JdbcTemplate jdbc;

    @Value("${trading.long-term.enabled:true}")
    private boolean enabled = true;

    @Value("${trading.long-term.interval-seconds:60}")
    private int intervalSeconds = 60;

    @Value("${trading.long-term.warn-pct:10}")
    private double warnPct = 10;

    @Value("${trading.long-term.stop-pct:0}")
    private double stopPct = 0;

    @Value("${trading.long-term.stop-check:15:20}")
    private String stopCheck = "15:20";

    @Value("${trading.long-term.nifty-drop-pct:2.5}")
    private double niftyDropPct = 2.5;

    @Value("${trading.momentum.weekly-entry:false}")
    private boolean weeklyEntry = false;

    private ScheduledExecutorService executor;

    /** Last price and time per symbol, for the status page. */
    private final Map<String, Double> lastPrice = new ConcurrentHashMap<>();
    private volatile LocalDateTime lastTick;

    /** Alert de-duplication: "WARN:SYMBOL" -> day sent. */
    private final Map<String, LocalDate> alerted = new ConcurrentHashMap<>();
    private volatile LocalDate stopCheckedOn;
    private volatile int weekChecked = -1;
    private volatile LocalDate buyOn;

    public LongTermMonitorService(MomentumPaperService paper, MomentumSignalService signal,
                                  StockServiceClient stockServiceClient, NotificationClient notificationClient,
                                  JdbcTemplate jdbc) {
        this.paper = paper;
        this.signal = signal;
        this.stockServiceClient = stockServiceClient;
        this.notificationClient = notificationClient;
        this.jdbc = jdbc;
    }

    public enum Level { OK, WARN, STOP }

    /** How far below its buy price a holding is: STOP from stop-pct (when on), WARN from warn-pct. */
    public static Level level(double entry, double ltp, double warnPct, double stopPct) {
        double down = (entry - ltp) / entry * 100;
        if (stopPct > 0 && down >= stopPct) {
            return Level.STOP;
        }
        return warnPct > 0 && down >= warnPct ? Level.WARN : Level.OK;
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("Long-term monitor disabled");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "long-term-monitor");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::safeTick, 30, Math.max(15, intervalSeconds), TimeUnit.SECONDS);
        log.info("Long-term monitor started: every {} s, warn {}%, stop {}%, stop check {}, weekly entry {}",
                intervalSeconds, warnPct, stopPct > 0 ? stopPct : "off", stopCheck, weeklyEntry);
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void safeTick() {
        try {
            tick(LocalDateTime.now(IST));
        } catch (Exception ex) {
            log.error("Long-term monitor tick failed", ex);
        }
    }

    private com.ram.trading.signal.engine.calendar.TradingCalendar calendar;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCalendar(com.ram.trading.signal.engine.calendar.TradingCalendar calendar) {
        this.calendar = calendar;
    }

    /** One pass at {@code now} (IST); package-visible for tests. */
    void tick(LocalDateTime now) {
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return;
        }
        if (calendar != null && !calendar.isTradingDay()) {
            return; // NSE holiday: prices are the last day's, nothing to watch or buy
        }
        LocalDate today = now.toLocalDate();
        LocalTime t = now.toLocalTime();
        if (weeklyEntry) {
            weeklyEntry(today, t);
        }
        if (t.isBefore(OPEN) || t.isAfter(CLOSE)) {
            return;
        }
        lastTick = now;
        boolean stopTime = stopPct > 0 && !t.isBefore(LocalTime.parse(stopCheck)) && !today.equals(stopCheckedOn);
        for (Holding h : paper.holdings()) {
            Double ltp = price(h.symbol());
            if (ltp == null) {
                continue;
            }
            lastPrice.put(h.symbol(), ltp);
            Level level = level(h.entryPrice(), ltp, warnPct, stopPct);
            double down = (h.entryPrice() - ltp) / h.entryPrice() * 100;
            if (level == Level.STOP && stopTime) {
                Double pnl = paper.sell(h, ltp, String.format("stop: %.1f%% below the buy price at %s", down, t.withNano(0)));
                log.warn("LONG-TERM STOP SELL | {} | ltp {} | entry {} | pnl {}", h.symbol(), ltp, h.entryPrice(), pnl);
                continue;
            }
            if (level == Level.STOP) {
                once("STOP:" + h.symbol(), today, "Long-term stop touched: " + h.symbol(), String.format(
                        "%s at %.2f is %.1f%% below the buy price %.2f (stop %.0f%%).%nPaper sells at %s if it is still below.",
                        h.symbol(), ltp, down, h.entryPrice(), stopPct, stopCheck));
            } else if (level == Level.WARN) {
                once("WARN:" + h.symbol(), today, "Long-term warning: " + h.symbol(), String.format(
                        "%s at %.2f is %.1f%% below the buy price %.2f. No action: the holding is reviewed at the monthly check%s.",
                        h.symbol(), ltp, down, h.entryPrice(),
                        stopPct > 0 ? String.format(" or sold if it closes %.0f%% down", stopPct) : ""));
            }
        }
        if (stopTime) {
            stopCheckedOn = today;
        }
        niftyCheck(today);
    }

    /** Weekly entry after a cash month: the list before the open, the paper buys after it. */
    private void weeklyEntry(LocalDate today, LocalTime t) {
        int week = today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        YearMonth month = YearMonth.from(today);
        if (week != weekChecked && !t.isBefore(LIST_TIME) && t.isBefore(OPEN)) {
            weekChecked = week;
            // The week's first session only, as backtested; the candles also survive a restart mid-week.
            LocalDate monday = today.with(DayOfWeek.MONDAY);
            if (niftySessions(monday, today) == 0 && signal.cashMonth(month) && signal.riskOnNow()) {
                signal.publish(true).filter(s -> !s.target().isEmpty()).ifPresent(s -> {
                    buyOn = today;
                    log.info("LONG-TERM WEEKLY ENTRY | NIFTY back above its average | {} stocks listed", s.target().size());
                });
            }
        }
        if (buyOn != null && !today.isBefore(buyOn) && !t.isBefore(BUY_TIME)) {
            if (niftySessions(today, today.plusDays(1)) > 0) {
                buyOn = null;
                paper.rebalance(true).ifPresent(text -> log.info("LONG-TERM WEEKLY ENTRY BOUGHT\n{}", text));
            } else if (t.isAfter(HOLIDAY_BY)) {
                // No NIFTY candle by mid-morning: a market holiday, so the buy waits for the next weekday.
                buyOn = today.plusDays(1);
                log.info("LONG-TERM WEEKLY ENTRY | No NIFTY candles today, buying on the next session");
            }
        }
    }

    /** NIFTY minute candles from {@code from} (inclusive) to {@code to} (exclusive): 0 means no session. */
    private int niftySessions(LocalDate from, LocalDate to) {
        Integer n = jdbc.queryForObject("select count(*) from minute_candle where symbol = 'NIFTY' "
                + "and candle_minute >= ? and candle_minute < ?", Integer.class, from, to);
        return n == null ? 0 : n;
    }

    private void niftyCheck(LocalDate today) {
        if (niftyDropPct <= 0 || today.equals(alerted.get("NIFTY"))) {
            return;
        }
        List<Double> prev = jdbc.queryForList("select nifty_close from momentum_paper_equity where equity_day < ? "
                + "and nifty_close is not null order by equity_day desc limit 1", Double.class, today);
        Double ltp = prev.isEmpty() ? null : price("NIFTY");
        if (ltp == null) {
            return;
        }
        double move = (ltp / prev.get(0) - 1) * 100;
        if (move <= -niftyDropPct) {
            once("NIFTY", today, "NIFTY down " + String.format("%.1f%%", -move), String.format(
                    "NIFTY %.0f is %.1f%% below yesterday's close %.0f. Long-term holdings are not sold for this; "
                            + "the market filter decides at the monthly check.", ltp, -move, prev.get(0)));
        }
    }

    private void once(String key, LocalDate today, String title, String text) {
        if (today.equals(alerted.get(key))) {
            return;
        }
        alerted.put(key, today);
        log.warn("LONG-TERM ALERT | {} | {}", title, text.replace('\n', ' '));
        notificationClient.sendNotification(NotificationRequest.builder()
                        .channel(NotificationChannel.SLACK).title(title).message(text).build())
                .doOnError(ex -> log.error("Long-term monitor: Slack send failed", ex))
                .onErrorResume(ex -> Mono.empty())
                .subscribe();
    }

    private Double price(String symbol) {
        try {
            StockResponse r = stockServiceClient.getStockPrice(symbol).block(Duration.ofSeconds(5));
            return r == null || r.getPrice() == null || r.getPrice() <= 0 ? null : r.getPrice();
        } catch (Exception ex) {
            log.debug("Long-term monitor: no price for {}: {}", symbol, ex.getMessage());
            return null;
        }
    }

    /** Holdings with the last price seen, distance from the buy price and the rules in force. */
    public String status() {
        StringBuilder out = new StringBuilder();
        out.append(String.format("LONG-TERM MONITOR  every %d s on its own thread | warn %.0f%% | stop %s | weekly entry %s%n",
                intervalSeconds, warnPct, stopPct > 0 ? String.format("%.0f%% (sold at %s)", stopPct, stopCheck) : "off (alerts only)",
                weeklyEntry ? "on" : "off"));
        out.append("last check: ").append(lastTick == null ? "none yet" : lastTick.withNano(0)).append("\n");
        List<Holding> holdings = paper.holdings();
        if (holdings.isEmpty()) {
            out.append("No long-term holdings (the monthly list is in cash).\n");
        }
        for (Holding h : holdings) {
            Double ltp = lastPrice.get(h.symbol());
            out.append(ltp == null
                    ? String.format("  %-12s %4d @ %9.2f  last -%n", h.symbol(), h.quantity(), h.entryPrice())
                    : String.format("  %-12s %4d @ %9.2f  last %9.2f  %+6.1f%%  %s%n", h.symbol(), h.quantity(),
                    h.entryPrice(), ltp, (ltp / h.entryPrice() - 1) * 100, level(h.entryPrice(), ltp, warnPct, stopPct)));
        }
        return out.toString();
    }
}
