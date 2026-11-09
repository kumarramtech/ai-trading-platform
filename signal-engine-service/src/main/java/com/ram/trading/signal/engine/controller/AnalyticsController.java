package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.dto.TradeInsightsResponse;
import com.ram.trading.signal.engine.service.AnalyticsService;
import com.ram.trading.signal.engine.service.performance.GoLiveReadinessService;
import com.ram.trading.signal.engine.service.performance.SetupEdgeGate;
import com.ram.trading.signal.engine.service.performance.StrategyPerformanceService;
import com.ram.trading.signal.engine.service.replay.StrategyReplayService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    private final StrategyPerformanceService strategyPerformanceService;

    private final SetupEdgeGate setupEdgeGate;

    private final GoLiveReadinessService goLiveReadinessService;

    private final StrategyReplayService strategyReplayService;

    private final com.ram.trading.signal.engine.service.replay.SwingBacktestService swingBacktestService;

    private final com.ram.trading.signal.engine.service.playbook.OpeningPlaybookService openingPlaybookService;

    private final com.ram.trading.signal.engine.service.replay.MomentumSignalService momentumSignalService;

    private final com.ram.trading.signal.engine.service.replay.MomentumPaperService momentumPaperService;

    private final com.ram.trading.signal.engine.service.fadebook.FadePaperBookService fadePaperBookService;

    private final com.ram.trading.signal.engine.service.longterm.LongTermMonitorService longTermMonitorService;

    private final com.ram.trading.signal.engine.service.account.AccountOverviewService accountOverviewService;

    private final com.ram.trading.signal.engine.service.fadebook.FadeNewsStudyService fadeNewsStudyService;

    @GetMapping("/trade-insights")
    public TradeInsightsResponse getTradeInsights() {

        return analyticsService.getTradeInsights();
    }

    /**
     * Closed paper trades per strategy, direction, entry time and exit reason,
     * net of charges. Defaults to the last 30 days.
     */
    @GetMapping("/strategy-performance")
    public StrategyPerformanceService.StrategyPerformanceReport getStrategyPerformance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(30);

        return strategyPerformanceService.report(start, end);
    }

    /**
     * Go-live criteria, monthly-target projection and per-filter shadow results
     * since trading.go-live.measure-from. format=text returns the Slack version.
     */
    @GetMapping("/go-live-readiness")
    public Object getGoLiveReadiness(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "json") String format) {

        GoLiveReadinessService.ReadinessReport report =
                goLiveReadinessService.report(to != null ? to : LocalDate.now());

        return "text".equalsIgnoreCase(format)
                ? goLiveReadinessService.toText(report)
                : report;
    }

    /** What the edge gate would decide right now for every strategy and entry-time bucket. */
    @GetMapping("/edge-gate")
    public List<SetupEdgeGate.EdgeDecision> getEdgeGate() {

        return setupEdgeGate.currentDecisions();
    }

    /**
     * Starts a background replay of the live entry rules over past 1-minute
     * candles (default: the last 60 days, top 200 stocks by turnover). Refused
     * during market hours so it never competes with live trading.
     */
    @PostMapping("/replay")
    public ResponseEntity<String> startReplay(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "200") int universe,
            @RequestParam(required = false, defaultValue = "0") int offset,
            @RequestParam(required = false, defaultValue = "all") String mode,
            @RequestParam(required = false, defaultValue = "0") int rps) {

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        LocalTime t = now.toLocalTime();
        if (now.getDayOfWeek().getValue() <= 5
                && !t.isBefore(LocalTime.of(9, 0)) && t.isBefore(LocalTime.of(15, 35))) {
            return ResponseEntity.status(409).body("Replay is not run during market hours (09:00-15:35 IST).");
        }

        LocalDate end = to != null ? to : now.toLocalDate().minusDays(1);
        LocalDate start = from != null ? from : end.minusDays(60);
        // Up to 1000: the Round 3 risk books need turnover ranks 1-600 in one run.
        int size = Math.max(10, Math.min(universe, 1000));
        // mode=fade: only the FADE studies (skips the live-rule simulation); rps: history fetch rate, at most 20.
        boolean fadeOnly = "fade".equalsIgnoreCase(mode);
        if (!strategyReplayService.start(start, end, size, Math.max(0, offset), fadeOnly, Math.max(0, Math.min(rps, 20)))) {
            return ResponseEntity.status(409).body("A replay is already running; GET /analytics/replay for progress.");
        }
        return ResponseEntity.accepted().body("Replay started: " + start + " to " + end + ", top " + size
                + " stocks from turnover rank " + (Math.max(0, offset) + 1) + (fadeOnly ? ", fade studies only" : "")
                + ". GET /analytics/replay for progress and the report.");
    }

    /** Today's 09:31 opening playbook (stocks in play); rebuild=true recomputes it now. */
    @GetMapping(value = "/opening-playbook", produces = "text/plain")
    public String openingPlaybook(@RequestParam(required = false, defaultValue = "false") boolean rebuild) {
        return openingPlaybookService.toText(rebuild ? openingPlaybookService.build() : openingPlaybookService.today());
    }

    /**
     * Starts the swing (multi-day, long-only, delivery) backtest on daily candles:
     * breakout, pullback and weekly rotation over the last {@code years} years.
     */
    @PostMapping("/swing-backtest")
    public ResponseEntity<String> startSwingBacktest(
            @RequestParam(required = false, defaultValue = "3") int years,
            @RequestParam(required = false, defaultValue = "300") int universe,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        LocalTime t = now.toLocalTime();
        if (now.getDayOfWeek().getValue() <= 5
                && !t.isBefore(LocalTime.of(9, 0)) && t.isBefore(LocalTime.of(15, 35))) {
            return ResponseEntity.status(409).body("Backtests are not run during market hours (09:00-15:35 IST).");
        }
        int y = Math.max(1, Math.min(years, 5));
        // Up to every listed stock: liquidity is then judged on each day (point in time), not by today's turnover.
        int size = Math.max(20, Math.min(universe, 4000));
        // An earlier end date tests another period (out of sample); default yesterday.
        java.time.LocalDate end = to != null && to.isBefore(now.toLocalDate()) ? to : now.toLocalDate().minusDays(1);
        return swingBacktestService.start(y, size, end)
                ? ResponseEntity.accepted().body("Swing backtest started: " + y + " years to " + end + ", top " + size
                + " stocks. GET /analytics/swing-backtest for the report.")
                : ResponseEntity.status(409).body("A swing backtest is already running.");
    }

    @GetMapping(value = "/swing-backtest", produces = "text/plain")
    public String swingBacktestStatus() {
        return swingBacktestService.status();
    }

    /** This month's momentum list from the latest closes; not stored or sent (takes ~2 minutes). */
    @GetMapping(value = "/momentum-signal", produces = "text/plain")
    public reactor.core.publisher.Mono<String> momentumSignalPreview() {
        // Blocking (history fetch, JDBC): off the event loop.
        return blocking(() -> momentumSignalService.toText(momentumSignalService.preview()));
    }

    /** Stores this month's list and sends it to Slack; force=true replaces a list already published. */
    @PostMapping(value = "/momentum-signal/publish", produces = "text/plain")
    public reactor.core.publisher.Mono<ResponseEntity<String>> publishMomentumSignal(
            @RequestParam(required = false, defaultValue = "false") boolean force) {
        return blocking(() -> momentumSignalService.publish(force)
                .map(s -> ResponseEntity.ok(momentumSignalService.toText(s)))
                .orElseGet(() -> ResponseEntity.status(409)
                        .body("This month's list is already published; force=true replaces it.")));
    }

    /** Paper momentum portfolio: return since the start vs NIFTY, cash, holdings. */
    @GetMapping(value = "/momentum-paper", produces = "text/plain")
    public String momentumPaperReport() {
        return momentumPaperService.report();
    }

    /**
     * Paper momentum holdings at the latest price (live in the session, else the last
     * stored minute close), with cost, value, unrealised and total P&L, as JSON.
     */
    @GetMapping(value = "/momentum-paper/holdings", produces = "application/json")
    public reactor.core.publisher.Mono<com.ram.trading.signal.engine.service.replay.MomentumPaperService.PortfolioView>
    momentumPaperHoldings() {
        return blocking(momentumPaperService::portfolioView);
    }

    /** Trades this month's published list on paper now (once per month). */
    @PostMapping(value = "/momentum-paper/rebalance", produces = "text/plain")
    public reactor.core.publisher.Mono<ResponseEntity<String>> rebalanceMomentumPaper() {
        return blocking(() -> momentumPaperService.rebalance()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(409)
                        .body("No list published this month yet, or this month is already traded.")));
    }

    /** Long-term holdings as the monitor last priced them, and the rules in force. */
    @GetMapping(value = "/long-term-monitor", produces = "text/plain")
    public reactor.core.publisher.Mono<String> longTermMonitor() {
        return blocking(longTermMonitorService::status);
    }

    private static <T> reactor.core.publisher.Mono<T> blocking(java.util.concurrent.Callable<T> work) {
        return reactor.core.publisher.Mono.fromCallable(work)
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /** The FADE paper book: the day's shorts (default today) and its record since the start. */
    @GetMapping(value = "/fade-book", produces = "text/plain")
    public reactor.core.publisher.Mono<String> fadeBook(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now(ZoneId.of("Asia/Kolkata"));
        // JPA: off the event loop.
        return reactor.core.publisher.Mono.fromCallable(() -> fadePaperBookService.toText(day))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /** The FADE paper book as JSON for a dashboard: the day's shorts (default today) and its record. */
    @GetMapping(value = "/fade-book/summary", produces = "application/json")
    public reactor.core.publisher.Mono<com.ram.trading.signal.engine.service.fadebook.FadePaperBookService.BookView> fadeBookSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now(ZoneId.of("Asia/Kolkata"));
        return blocking(() -> fadePaperBookService.toView(day));
    }

    /**
     * Read-only view across intraday, FADE and monthly momentum (default today):
     * budgets, today's and total P&L, charges, open exposure, worst day and worst fall.
     * format=text returns a plain-text version.
     */
    @GetMapping("/account-overview")
    public reactor.core.publisher.Mono<ResponseEntity<?>> accountOverview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false, defaultValue = "json") String format) {
        LocalDate day = date != null ? date : LocalDate.now(ZoneId.of("Asia/Kolkata"));
        return blocking(() -> {
            var o = accountOverviewService.overview(day);
            return "text".equalsIgnoreCase(format)
                    ? ResponseEntity.ok().contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                    .body((Object) com.ram.trading.signal.engine.service.account.AccountOverviewService.toText(o))
                    : ResponseEntity.ok((Object) o);
        });
    }

    /** FADE news study: shorts with and without news at entry, against the pre-registered pass mark. */
    @GetMapping(value = "/fade-news-study", produces = "text/plain")
    public reactor.core.publisher.Mono<String> fadeNewsStudy() {
        return blocking(() -> com.ram.trading.signal.engine.service.fadebook.FadeNewsStudyService.toText(
                fadeNewsStudyService.report()));
    }

    /** Progress of the running replay, or the report of the last one (plain text). */
    @GetMapping(value = "/replay", produces = "text/plain")
    public String replayStatus() {
        StrategyReplayService.Status s = strategyReplayService.status();
        return s.report() != null ? s.report()
                : s.state() + " | " + s.progress() + (s.startedAt() != null ? " | started " + s.startedAt() : "");
    }
}
