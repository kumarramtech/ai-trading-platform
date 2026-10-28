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
            @RequestParam(required = false, defaultValue = "0") int offset) {

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        LocalTime t = now.toLocalTime();
        if (now.getDayOfWeek().getValue() <= 5
                && !t.isBefore(LocalTime.of(9, 0)) && t.isBefore(LocalTime.of(15, 35))) {
            return ResponseEntity.status(409).body("Replay is not run during market hours (09:00-15:35 IST).");
        }

        LocalDate end = to != null ? to : now.toLocalDate().minusDays(1);
        LocalDate start = from != null ? from : end.minusDays(60);
        int size = Math.max(10, Math.min(universe, 500));
        if (!strategyReplayService.start(start, end, size, Math.max(0, offset))) {
            return ResponseEntity.status(409).body("A replay is already running; GET /analytics/replay for progress.");
        }
        return ResponseEntity.accepted().body("Replay started: " + start + " to " + end + ", top " + size
                + " stocks from turnover rank " + (Math.max(0, offset) + 1) + ". GET /analytics/replay for progress and the report.");
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
            @RequestParam(required = false, defaultValue = "300") int universe) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        LocalTime t = now.toLocalTime();
        if (now.getDayOfWeek().getValue() <= 5
                && !t.isBefore(LocalTime.of(9, 0)) && t.isBefore(LocalTime.of(15, 35))) {
            return ResponseEntity.status(409).body("Backtests are not run during market hours (09:00-15:35 IST).");
        }
        int y = Math.max(1, Math.min(years, 5));
        // Up to every listed stock: liquidity is then judged on each day (point in time), not by today's turnover.
        int size = Math.max(20, Math.min(universe, 4000));
        return swingBacktestService.start(y, size)
                ? ResponseEntity.accepted().body("Swing backtest started: " + y + " years, top " + size
                + " stocks. GET /analytics/swing-backtest for the report.")
                : ResponseEntity.status(409).body("A swing backtest is already running.");
    }

    @GetMapping(value = "/swing-backtest", produces = "text/plain")
    public String swingBacktestStatus() {
        return swingBacktestService.status();
    }

    /** Progress of the running replay, or the report of the last one (plain text). */
    @GetMapping(value = "/replay", produces = "text/plain")
    public String replayStatus() {
        StrategyReplayService.Status s = strategyReplayService.status();
        return s.report() != null ? s.report()
                : s.state() + " | " + s.progress() + (s.startedAt() != null ? " | started " + s.startedAt() : "");
    }
}
