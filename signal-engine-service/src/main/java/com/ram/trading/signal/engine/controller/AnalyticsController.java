package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.dto.TradeInsightsResponse;
import com.ram.trading.signal.engine.service.AnalyticsService;
import com.ram.trading.signal.engine.service.performance.GoLiveReadinessService;
import com.ram.trading.signal.engine.service.performance.SetupEdgeGate;
import com.ram.trading.signal.engine.service.performance.StrategyPerformanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    private final StrategyPerformanceService strategyPerformanceService;

    private final SetupEdgeGate setupEdgeGate;

    private final GoLiveReadinessService goLiveReadinessService;

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
}
