package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.client.NotificationClient;
import com.ram.trading.signal.engine.dto.indicator.HistoricalCandleResponse;
import com.ram.trading.signal.engine.dto.notification.NotificationChannel;
import com.ram.trading.signal.engine.dto.notification.NotificationRequest;
import com.ram.trading.signal.engine.service.replay.SwingBacktestService.Series;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live monthly momentum signal: the rule the swing backtest locked on 2026-10-29.
 *
 * On the first trading day of each month, rank the stocks among the top
 * {@code liquidRank} by 20-day average turnover by their 6-month return excluding
 * the last month, divided by its daily volatility, and hold the top {@code top} in
 * equal weight (delivery). If NIFTY's last close is below its {@code regimeSma}-day
 * average, hold nothing (liquid fund) until the next month's check.
 *
 * The list is a recommendation for manual orders: nothing is traded. Each published
 * month is stored in momentum_signal so the next month can say what to buy and sell.
 */
@Service
@Slf4j
public class MomentumSignalService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String CASH = "CASH";

    private final JdbcTemplate jdbc;
    private final WebClient stockService;
    private final NotificationClient notificationClient;

    @Value("${trading.momentum.capital:30000}")
    private double capital = 30000;

    @Value("${trading.momentum.top:30}")
    private int top = 30;

    @Value("${trading.momentum.lookback:126}")
    private int lookback = 126;

    @Value("${trading.momentum.skip:21}")
    private int skip = 21;

    @Value("${trading.momentum.liquid-rank:200}")
    private int liquidRank = 200;

    /** Candidates fetched (by recent turnover) so that the top liquidRank is found among them. */
    @Value("${trading.momentum.universe:600}")
    private int universe = 600;

    @Value("${trading.momentum.regime-sma:100}")
    private int regimeSma = 100;

    @Value("${trading.momentum.min-price:50}")
    private double minPrice = 50;

    @Value("${trading.replay.requests-per-second:6}")
    private int requestsPerSecond = 6;

    public MomentumSignalService(JdbcTemplate jdbc, WebClient.Builder builder, NotificationClient notificationClient,
                                 @Value("${stock.service.url}") String stockServiceUrl) {
        this.jdbc = jdbc;
        this.notificationClient = notificationClient;
        this.stockService = builder.baseUrl(stockServiceUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    @PostConstruct
    void createTable() {
        jdbc.execute("create table if not exists momentum_signal ("
                + "id bigint auto_increment primary key, list_month varchar(7) not null, signal_day date not null, "
                + "symbol varchar(32) not null, rank_no int, score double, close_price double, quantity int, "
                + "amount double, created_at timestamp default current_timestamp, index idx_momentum_month (list_month))");
    }

    public record Pick(int rank, String symbol, double score, double close, int quantity, double amount) {
    }

    public record MonthlySignal(String month, LocalDate signalDay, boolean riskOn, double niftyClose, double niftyAverage,
                         double capital, List<Pick> target, List<String> previous, List<String> buys,
                         List<String> sells, List<String> holds) {
    }

    /** True once this month's list has been stored. */
    public boolean published(YearMonth month) {
        Integer n = jdbc.queryForObject("select count(*) from momentum_signal where list_month = ?", Integer.class,
                month.toString());
        return n != null && n > 0;
    }

    /** This month's list from the latest data, not stored or sent. */
    public MonthlySignal preview() {
        LocalDate today = LocalDate.now(IST);
        return compute(today, previous(YearMonth.from(today)));
    }

    /** Computes this month's list, stores it and sends it to Slack; empty when already published. */
    public synchronized java.util.Optional<MonthlySignal> publish(boolean force) {
        LocalDate today = LocalDate.now(IST);
        YearMonth month = YearMonth.from(today);
        if (published(month) && !force) {
            return java.util.Optional.empty();
        }
        MonthlySignal signal = compute(today, previous(month));
        jdbc.update("delete from momentum_signal where list_month = ?", month.toString());
        if (signal.target().isEmpty()) {
            jdbc.update("insert into momentum_signal (list_month, signal_day, symbol) values (?, ?, ?)",
                    month.toString(), signal.signalDay(), CASH);
        }
        for (Pick p : signal.target()) {
            jdbc.update("insert into momentum_signal (list_month, signal_day, symbol, rank_no, score, close_price, quantity, amount) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                    month.toString(), signal.signalDay(), p.symbol(), p.rank(), p.score(), p.close(), p.quantity(), p.amount());
        }
        String text = toText(signal);
        log.info("MONTHLY MOMENTUM SIGNAL\n{}", text);
        notificationClient.sendNotification(NotificationRequest.builder()
                        .channel(NotificationChannel.SLACK)
                        .title("Monthly momentum list " + month + (signal.riskOn() ? "" : ": stay in cash"))
                        .message(text)
                        .build())
                .doOnError(ex -> log.error("Monthly momentum signal: Slack send failed", ex))
                .onErrorResume(ex -> Mono.empty())
                .subscribe();
        return java.util.Optional.of(signal);
    }

    /** Symbols of the last stored month before {@code month} (empty after a cash month). */
    List<String> previous(YearMonth month) {
        return jdbc.queryForList("select symbol from momentum_signal where symbol <> ? and list_month = "
                        + "(select max(list_month) from momentum_signal where list_month < ?) order by rank_no",
                String.class, CASH, month.toString());
    }

    private MonthlySignal compute(LocalDate today, List<String> previous) {
        List<String> symbols = new ArrayList<>(jdbc.queryForList(
                "select symbol from historical_price where interval_type = 'DAY' and trade_date >= ? "
                        + "group by symbol having count(*) >= 15 "
                        + "order by avg(close_price * volume) desc limit ?",
                String.class, today.minusDays(30), universe));
        symbols.add("NIFTY");
        // About 220 sessions: the 126-session window, the 100-day NIFTY average and the 20-day turnover.
        LocalDate from = today.minusDays(330);
        LocalDate to = today.minusDays(1);
        Map<String, Series> data = new ConcurrentHashMap<>();
        Flux.fromIterable(symbols)
                .delayElements(Duration.ofMillis(1000L / Math.max(1, requestsPerSecond)))
                .flatMap(symbol -> stockService.get()
                        .uri(b -> b.path("/api/v1/stocks/{symbol}/history")
                                .queryParam("interval", "day")
                                .queryParam("from", from).queryParam("to", to)
                                .build(symbol))
                        .retrieve().bodyToMono(HistoricalCandleResponse.class)
                        .timeout(Duration.ofSeconds(30))
                        .map(r -> SwingBacktestService.since(SwingBacktestService.toSeries(symbol, r.getCandles()), from))
                        .onErrorResume(e -> Mono.empty()), 3)
                .filter(s -> s.size() > 0)
                .doOnNext(s -> data.put(s.symbol(), s))
                .blockLast(Duration.ofMinutes(15));

        Series nifty = data.remove("NIFTY");
        if (nifty == null || nifty.size() < regimeSma) {
            throw new IllegalStateException("No NIFTY daily history (broker token or stock-service down?)");
        }
        log.info("Monthly momentum signal: {} of {} symbols fetched", data.size(), symbols.size() - 1);
        return build(data, nifty, previous, YearMonth.from(today), capital, top, lookback, skip, liquidRank,
                regimeSma, minPrice);
    }

    /** The month's list from daily series ending on the last completed session (pure, for tests). */
    static MonthlySignal build(Map<String, Series> data, Series nifty, List<String> previous, YearMonth month,
                               double capital, int top, int lookback, int skip, int liquidRank, int regimeSma,
                               double minPrice) {
        LocalDate signalDay = nifty.date()[nifty.size() - 1];
        int n = nifty.size() - 1;
        double niftyClose = nifty.close()[n];
        double niftyAverage = SwingBacktestService.sma(nifty.close(), n, regimeSma);
        boolean riskOn = SwingBacktestService.aboveAverage(nifty, signalDay, regimeSma);

        Set<String> liquid = SwingBacktestService.topByTurnover(data.values(), signalDay, liquidRank);
        List<Map.Entry<Series, Double>> ranked = new ArrayList<>();
        for (Series s : data.values()) {
            int i = s.indexOf(signalDay);
            if (i < lookback + 1 || s.close()[i] < minPrice || !liquid.contains(s.symbol())) {
                continue;
            }
            ranked.add(Map.entry(s, SwingBacktestService.momentumScore(s, i, lookback, skip, true)));
        }
        ranked.sort(Map.Entry.<Series, Double>comparingByValue().reversed());

        List<Pick> target = new ArrayList<>();
        if (riskOn) {
            double slot = capital / top;
            for (Map.Entry<Series, Double> e : ranked.subList(0, Math.min(top, ranked.size()))) {
                Series s = e.getKey();
                double close = s.close()[s.indexOf(signalDay)];
                int quantity = (int) Math.floor(slot / close);
                target.add(new Pick(target.size() + 1, s.symbol(), e.getValue(), close, quantity, quantity * close));
            }
        }
        List<String> targetSymbols = target.stream().map(Pick::symbol).toList();
        List<String> buys = targetSymbols.stream().filter(sym -> !previous.contains(sym)).toList();
        List<String> sells = previous.stream().filter(sym -> !targetSymbols.contains(sym)).toList();
        List<String> holds = targetSymbols.stream().filter(previous::contains).toList();
        return new MonthlySignal(month.toString(), signalDay, riskOn, niftyClose, niftyAverage, capital, target,
                previous, buys, sells, holds);
    }

    public String toText(MonthlySignal s) {
        StringBuilder out = new StringBuilder();
        out.append(String.format("MONTHLY MOMENTUM %s  (ranked on the %s close; delivery/CNC orders at today's open)%n",
                s.month(), s.signalDay()));
        out.append(String.format("NIFTY %.0f vs %d-day average %.0f: %s%n", s.niftyClose(), regimeSma, s.niftyAverage(),
                s.riskOn() ? "ABOVE - invest" : "BELOW - sell everything, park the money in a liquid fund"));
        if (!s.riskOn()) {
            if (!s.sells().isEmpty()) {
                out.append("SELL ALL: ").append(String.join(", ", s.sells())).append("\n");
            }
            out.append("Next check: first trading day of next month.\n");
            return out.toString();
        }
        double slot = s.capital() / top;
        out.append(String.format("Capital Rs %.0f, %d stocks, Rs %.0f each (equal weight)%n%n", s.capital(), top, slot));
        out.append("SELL (dropped out of the top ").append(top).append("): ")
                .append(s.sells().isEmpty() ? "none" : String.join(", ", s.sells())).append("\n");
        out.append("KEEP (already held): ").append(s.holds().isEmpty() ? "none" : String.join(", ", s.holds()))
                .append("\n\n");
        out.append("BUY\n");
        double total = 0;
        List<String> tooDear = new ArrayList<>();
        for (Pick p : s.target()) {
            if (!s.buys().contains(p.symbol())) {
                continue;
            }
            if (p.quantity() == 0) {
                tooDear.add(p.symbol() + " (Rs " + Math.round(p.close()) + ")");
                continue;
            }
            total += p.amount();
            out.append(String.format("  %2d. %-12s qty %4d  @ ~Rs %8.2f  = Rs %7.0f%n", p.rank(), p.symbol(),
                    p.quantity(), p.close(), p.amount()));
        }
        if (s.buys().isEmpty()) {
            out.append("  none\n");
        }
        out.append(String.format("  buys total ~Rs %.0f%n", total));
        if (!tooDear.isEmpty()) {
            out.append("NOT BOUGHT - one share costs more than the Rs ").append(Math.round(slot)).append(" slot: ")
                    .append(String.join(", ", tooDear)).append("\n");
        }
        out.append("\nFULL LIST: ").append(String.join(", ", s.target().stream().map(Pick::symbol).toList())).append("\n");
        out.append("Rule: top ").append(top).append(" of the ").append(liquidRank)
                .append(" most traded by 6m-1m return / volatility; no stops, next check next month.\n");
        return out.toString();
    }
}
