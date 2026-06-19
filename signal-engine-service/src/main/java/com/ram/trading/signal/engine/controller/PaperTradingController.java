package com.ram.trading.signal.engine.controller;

import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.dto.*;
import com.ram.trading.signal.engine.entity.PaperTrade;
import com.ram.trading.signal.engine.service.PaperTradingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/paper-trades")
public class PaperTradingController {

    private final PaperTradingService service;

    @GetMapping("/status/{status}")
    public List<PaperTrade> getByStatus(
            @PathVariable SignalStatus status) {
        return service.getByStatus(status);
    }

    @GetMapping
    public List<PaperTrade> getAll() {
        return service.getAll();
    }

    @GetMapping("/summary")
    public PaperTradeSummary getSummary() {
        return service.getSummary();
    }

    @GetMapping("/performance")
    public TradePerformance getPerformance() {
        return service.getPerformance();
    }

    @GetMapping("/history")
    public List<PaperTrade> history() {
        return service.getHistory();
    }

    @GetMapping("/insights")
    public TradeInsights getInsights() {
        return service.getInsights();
    }

    @GetMapping("/dashboard")
    public PaperTradeDashboard getDashboard() {
        return service.getDashboard();
    }

    @GetMapping("/strategy-report")
    public StrategyReport getStrategyReport() {
        return service.getStrategyReport();
    }

    @GetMapping("/{id}/review")
    public Mono<TradeReviewResponse> reviewTrade(
            @PathVariable Long id) {
        return service.reviewTrade(id);
    }

    @GetMapping("/strategy-review")
    public Mono<StrategyReviewResponse> strategyReview() {
        return service.strategyReview();
    }

    @GetMapping("/analytics")
    public TradeAnalyticsResponse getAnalytics() {
        return service.getAnalytics();
    }

    @GetMapping("/{symbol}/position-size")
    public Mono<PositionSizingResponse> getPositionSize(@PathVariable String symbol,@RequestParam Double capital) {
        return service.getPositionSize(symbol,capital);
    }

    @GetMapping("/daily-pnl")
    public DailyPnLResponse getDailyPnL() {

        return service.getDailyPnL();
    }

    @GetMapping("/pnldashboard")
    public TradingDashboardResponse dashboard() {

        return service.getPnLDashboard();
    }

    @GetMapping("/opportunity-dashboard")
    public Mono<OpportunityDashboardResponse>getOpportunityDashboard(@RequestParam Double capital) {

        return service.getBestOpportunities(capital);
    }

    @GetMapping("/advanced-metrics")
    public AnalyticsMetricsResponse advancedMetrics() {

        return service.getAdvancedMetrics();
    }

}