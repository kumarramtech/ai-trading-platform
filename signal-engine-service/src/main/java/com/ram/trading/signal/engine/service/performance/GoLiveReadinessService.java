package com.ram.trading.signal.engine.service.performance;

import com.ram.trading.signal.engine.entity.PaperTrade;
import com.ram.trading.signal.engine.entity.ShadowTrade;
import com.ram.trading.signal.engine.repo.ShadowTradeRepository;
import com.ram.trading.signal.engine.service.performance.StrategyPerformanceService.GroupStats;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Answers "is paper trading good enough to go live, and how far is it from the
 * monthly profit target?" from closed paper trades since the current rule set
 * went in (trading.go-live.measure-from), plus what each entry filter's
 * rejected setups did as shadow trades.
 *
 * Go-live needs every criterion to pass: enough trades to trust the numbers,
 * a positive average R after charges, a profit factor above 1, and a worst
 * drawdown the account can take.
 */
@Service
@RequiredArgsConstructor
public class GoLiveReadinessService {

    private static final String UNKNOWN = "UNKNOWN";

    private final StrategyPerformanceService performanceService;
    private final ShadowTradeRepository shadowTradeRepository;

    /** First day of the rule set being judged; earlier trades used different sizing and filters. */
    @Value("${trading.go-live.measure-from:2026-10-28}")
    private LocalDate measureFrom = LocalDate.of(2026, 10, 28);

    @Value("${trading.go-live.min-trades:60}")
    private int minTrades = 60;

    /** Average result per trade in R, after charges. */
    @Value("${trading.go-live.min-average-r:0.20}")
    private double minAverageR = 0.20;

    @Value("${trading.go-live.min-profit-factor:1.3}")
    private double minProfitFactor = 1.3;

    /** Worst peak-to-trough fall of the closed-trade equity curve, in R. */
    @Value("${trading.go-live.max-drawdown-r:10}")
    private double maxDrawdownR = 10;

    @Value("${trading.risk.risk-per-trade:250}")
    private double riskPerTrade = 250;

    /** Risk per trade the projection scales to once the edge is proven (1% of capital). */
    @Value("${trading.go-live.scaled-risk-per-trade:1000}")
    private double scaledRiskPerTrade = 1000;

    @Value("${trading.go-live.monthly-profit-target:10000}")
    private double monthlyProfitTarget = 10000;

    @Value("${trading.go-live.trading-days-per-month:21}")
    private int tradingDaysPerMonth = 21;

    /** Real trades lose about this much R to charges, so a shadow average below it means the filter saved money. */
    @Value("${trading.go-live.shadow-charges-r:0.20}")
    private double shadowChargesR = 0.20;

    /** Below this many closed shadow trades a filter's verdict is not given. */
    @Value("${trading.go-live.shadow-min-trades:10}")
    private int shadowMinTrades = 10;

    public ReadinessReport report(LocalDate to) {

        LocalDate from = measureFrom;
        LocalDate end = to.isBefore(from) ? from : to;

        List<PaperTrade> trades = performanceService.closedTrades(
                from.atStartOfDay(), end.plusDays(1).atStartOfDay());

        LocalDate weekStart = end.minusDays(6);
        List<PaperTrade> lastWeek = trades.stream()
                .filter(t -> !t.getExitTime().toLocalDate().isBefore(weekStart))
                .toList();

        GroupStats overall = performanceService.stats("ALL", trades);

        List<Criterion> criteria = criteria(overall);
        boolean ready = criteria.stream().allMatch(c -> c.status() == CriterionStatus.PASS);

        return new ReadinessReport(
                from,
                end,
                ready,
                criteria,
                projection(overall, from, end),
                overall,
                performanceService.stats("LAST 7 DAYS", lastWeek),
                group(trades, t -> normalize(t.getStrategy())),
                group(trades, t -> normalize(t.getMarketRegime())),
                group(trades, t -> normalize(t.getStrategy()) + " in " + normalize(t.getMarketRegime())),
                shadowFilters(from, end));
    }

    private List<Criterion> criteria(GroupStats overall) {

        List<Criterion> criteria = new ArrayList<>();

        criteria.add(new Criterion(
                "Closed trades",
                ">= " + minTrades,
                String.valueOf(overall.trades()),
                overall.trades() >= minTrades ? CriterionStatus.PASS : CriterionStatus.FAIL));

        Double averageR = overall.averageR();
        criteria.add(new Criterion(
                "Average R per trade (after charges)",
                ">= " + minAverageR,
                averageR == null ? "n/a" : String.valueOf(averageR),
                averageR != null && averageR >= minAverageR ? CriterionStatus.PASS : CriterionStatus.FAIL));

        Double profitFactor = overall.profitFactor();
        boolean noLosses = profitFactor == null && overall.trades() > 0 && overall.losses() == 0;
        criteria.add(new Criterion(
                "Profit factor",
                ">= " + minProfitFactor,
                profitFactor == null ? (noLosses ? "no losses" : "n/a") : String.valueOf(profitFactor),
                noLosses || (profitFactor != null && profitFactor >= minProfitFactor)
                        ? CriterionStatus.PASS : CriterionStatus.FAIL));

        double drawdownR = riskPerTrade > 0 ? round(overall.maxDrawdown() / riskPerTrade) : 0;
        criteria.add(new Criterion(
                "Max drawdown",
                "<= " + maxDrawdownR + "R",
                drawdownR + "R (Rs " + overall.maxDrawdown() + ")",
                overall.trades() > 0 && drawdownR <= maxDrawdownR ? CriterionStatus.PASS : CriterionStatus.FAIL));

        return criteria;
    }

    /**
     * Monthly profit if the measured trade rate and edge continue: at today's
     * risk (from the actual rupee expectancy) and at the scaled risk (from the
     * average R; conservative, since charges grow less than position size).
     */
    private Projection projection(GroupStats overall, LocalDate from, LocalDate to) {

        int tradingDays = weekdaysBetween(from, to);
        double tradesPerDay = tradingDays == 0 ? 0 : (double) overall.trades() / tradingDays;
        double tradesPerMonth = tradesPerDay * tradingDaysPerMonth;
        Double averageR = overall.averageR();

        double monthlyAtCurrentRisk = overall.expectancyPerTrade() * tradesPerMonth;
        Double monthlyAtScaledRisk = averageR == null ? null : averageR * scaledRiskPerTrade * tradesPerMonth;
        Double averageRNeeded = tradesPerMonth <= 0 ? null
                : monthlyProfitTarget / (tradesPerMonth * scaledRiskPerTrade);

        return new Projection(
                tradingDays,
                round(tradesPerDay),
                round(tradesPerMonth),
                riskPerTrade,
                round(monthlyAtCurrentRisk),
                scaledRiskPerTrade,
                monthlyAtScaledRisk == null ? null : round(monthlyAtScaledRisk),
                monthlyProfitTarget,
                averageRNeeded == null ? null : round(averageRNeeded));
    }

    private List<ShadowFilterStats> shadowFilters(LocalDate from, LocalDate to) {

        List<ShadowTrade> closed = shadowTradeRepository
                .findByEntryTimeBetween(from.atStartOfDay(), to.plusDays(1).atStartOfDay())
                .stream()
                .filter(s -> s.getRMultiple() != null)
                .toList();

        Map<String, List<ShadowTrade>> byFilter = closed.stream()
                .collect(Collectors.groupingBy(s -> normalize(s.getRejectedBy()), LinkedHashMap::new, Collectors.toList()));

        List<ShadowFilterStats> result = new ArrayList<>();

        byFilter.forEach((filter, shadows) -> {
            int count = shadows.size();
            long winners = shadows.stream().filter(s -> s.getRMultiple() > 0).count();
            double totalR = shadows.stream().mapToDouble(ShadowTrade::getRMultiple).sum();
            double averageR = totalR / count;

            ShadowVerdict verdict = count < shadowMinTrades
                    ? ShadowVerdict.INSUFFICIENT_DATA
                    : averageR < shadowChargesR ? ShadowVerdict.SAVING_MONEY : ShadowVerdict.COSTING_MONEY;

            result.add(new ShadowFilterStats(
                    filter, count, (int) winners, round(totalR), round(averageR), verdict));
        });

        result.sort(Comparator.comparing(ShadowFilterStats::filter));
        return result;
    }

    private List<GroupStats> group(List<PaperTrade> trades, Function<PaperTrade, String> key) {

        Map<String, List<PaperTrade>> groups = trades.stream()
                .collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.toList()));

        List<GroupStats> result = new ArrayList<>();
        groups.forEach((name, groupTrades) -> result.add(performanceService.stats(name, groupTrades)));
        result.sort(Comparator.comparingDouble(GroupStats::netPnl).reversed());
        return result;
    }

    /** Plain-text version for Slack and the log. */
    public String toText(ReadinessReport report) {

        StringBuilder text = new StringBuilder();
        GroupStats all = report.overall();
        Projection p = report.projection();

        text.append(String.format("GO-LIVE READINESS  %s to %s%n", report.from(), report.to()));
        text.append(report.readyForLive()
                ? "VERDICT: READY - every criterion passes\n"
                : "VERDICT: NOT READY\n");

        for (Criterion c : report.criteria()) {
            text.append(String.format("  [%s] %s: %s (need %s)%n",
                    c.status(), c.name(), c.actual(), c.required()));
        }

        text.append(String.format("%nOVERALL  trades %d | win %.0f%% | net Rs %.0f | per trade Rs %.0f | avg R %s | PF %s | charges Rs %.0f%n",
                all.trades(), all.winRatePct(), all.netPnl(), all.expectancyPerTrade(),
                all.averageR(), all.profitFactor(), all.charges()));

        GroupStats week = report.lastSevenDays();
        text.append(String.format("LAST 7 DAYS  trades %d | win %.0f%% | net Rs %.0f | avg R %s%n",
                week.trades(), week.winRatePct(), week.netPnl(), week.averageR()));

        text.append(String.format("%nTARGET Rs %.0f/month  (%.2f trades/day over %d trading days)%n",
                p.monthlyProfitTarget(), p.tradesPerDay(), p.tradingDays()));
        text.append(String.format("  at Rs %.0f risk/trade: Rs %.0f/month projected%n",
                p.riskPerTrade(), p.monthlyAtCurrentRisk()));
        text.append(String.format("  at Rs %.0f risk/trade: %s/month projected%n",
                p.scaledRiskPerTrade(),
                p.monthlyAtScaledRisk() == null ? "n/a" : "Rs " + Math.round(p.monthlyAtScaledRisk())));
        text.append(String.format("  avg R needed at that risk and trade rate: %s%n",
                p.averageRNeededForTarget() == null ? "n/a" : p.averageRNeededForTarget()));

        appendGroups(text, "BY STRATEGY", report.byStrategy());
        appendGroups(text, "BY REGIME", report.byRegime());
        appendGroups(text, "BY STRATEGY AND REGIME", report.byStrategyAndRegime());

        text.append("\nFILTERS (shadow trades: gross R of the setups each filter blocked)\n");
        if (report.shadowFilters().isEmpty()) {
            text.append("  no closed shadow trades yet\n");
        }
        for (ShadowFilterStats s : report.shadowFilters()) {
            text.append(String.format("  %-18s blocked %d | would-win %d | avg R %.2f | %s%n",
                    s.filter(), s.setups(), s.winners(), s.averageR(), s.verdict()));
        }

        return text.toString();
    }

    private static void appendGroups(StringBuilder text, String title, List<GroupStats> groups) {
        text.append('\n').append(title).append('\n');
        for (GroupStats g : groups) {
            text.append(String.format("  %-22s trades %d | win %.0f%% | net Rs %.0f | avg R %s | PF %s%n",
                    g.group(), g.trades(), g.winRatePct(), g.netPnl(), g.averageR(), g.profitFactor()));
        }
    }

    private static int weekdaysBetween(LocalDate from, LocalDate to) {
        int days = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days++;
            }
        }
        return days;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value.trim().toUpperCase();
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public enum CriterionStatus { PASS, FAIL }

    public enum ShadowVerdict { INSUFFICIENT_DATA, SAVING_MONEY, COSTING_MONEY }

    public record Criterion(String name, String required, String actual, CriterionStatus status) {
    }

    /** Money in rupees per month; null where there is no R data yet. */
    public record Projection(
            int tradingDays,
            double tradesPerDay,
            double tradesPerMonth,
            double riskPerTrade,
            double monthlyAtCurrentRisk,
            double scaledRiskPerTrade,
            Double monthlyAtScaledRisk,
            double monthlyProfitTarget,
            Double averageRNeededForTarget) {
    }

    public record ShadowFilterStats(
            String filter,
            int setups,
            int winners,
            double totalR,
            double averageR,
            ShadowVerdict verdict) {
    }

    public record ReadinessReport(
            LocalDate from,
            LocalDate to,
            boolean readyForLive,
            List<Criterion> criteria,
            Projection projection,
            GroupStats overall,
            GroupStats lastSevenDays,
            List<GroupStats> byStrategy,
            List<GroupStats> byRegime,
            List<GroupStats> byStrategyAndRegime,
            List<ShadowFilterStats> shadowFilters) {
    }
}
