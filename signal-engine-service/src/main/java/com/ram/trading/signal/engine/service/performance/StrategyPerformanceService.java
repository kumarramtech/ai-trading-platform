package com.ram.trading.signal.engine.service.performance;

import com.ram.trading.signal.engine.entity.PaperTrade;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Measures closed paper trades per entry setup: which strategy, direction and
 * time of day actually make money after charges. The same numbers feed the
 * edge gate ({@link SetupEdgeGate}), which stops taking setups that lose.
 */
@Service
@RequiredArgsConstructor
public class StrategyPerformanceService {

    public static final String UNKNOWN_STRATEGY = "UNKNOWN";

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final PaperTradeRepository repository;

    /** Below this many trades a group's numbers are reported but not trusted. */
    @Value("${trading.performance.min-trades:20}")
    private int minTrades = 20;

    public StrategyPerformanceReport report(LocalDate from, LocalDate to) {

        List<PaperTrade> trades = closedTrades(from.atStartOfDay(), to.plusDays(1).atStartOfDay());

        return new StrategyPerformanceReport(
                from,
                to,
                minTrades,
                stats("ALL", trades),
                group(trades, StrategyPerformanceService::strategyOf),
                group(trades, t -> strategyOf(t) + " " + directionOf(t)),
                group(trades, t -> strategyOf(t) + " " + TimeBucket.of(entryTimeOf(t)).label()),
                group(trades, t -> t.getExitReason() == null ? "UNKNOWN" : t.getExitReason().name()));
    }

    /** Statistics for one strategy and entry-time bucket over the last {@code lookbackDays}. */
    public GroupStats setupStats(String strategy, TimeBucket bucket, int lookbackDays) {

        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.toLocalDate().minusDays(lookbackDays).atStartOfDay();
        String wanted = normalizeStrategy(strategy);

        List<PaperTrade> trades = closedTrades(from, to).stream()
                .filter(t -> wanted.equals(strategyOf(t)))
                .filter(t -> TimeBucket.of(entryTimeOf(t)) == bucket)
                .toList();

        return stats(wanted + " " + bucket.label(), trades);
    }

    public int minTrades() {
        return minTrades;
    }

    private List<PaperTrade> closedTrades(LocalDateTime from, LocalDateTime to) {
        return repository.findByExitTimeBetweenOrderByExitTimeDesc(from, to).stream()
                .filter(t -> t.getProfitLoss() != null && t.getExitTime() != null)
                .sorted(Comparator.comparing(PaperTrade::getExitTime))
                .toList();
    }

    private List<GroupStats> group(List<PaperTrade> trades, Function<PaperTrade, String> key) {
        Map<String, List<PaperTrade>> groups = trades.stream()
                .collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.toList()));
        List<GroupStats> result = new ArrayList<>();
        groups.forEach((name, groupTrades) -> result.add(stats(name, groupTrades)));
        result.sort(Comparator.comparingDouble(GroupStats::netPnl).reversed());
        return result;
    }

    GroupStats stats(String name, List<PaperTrade> trades) {

        int count = trades.size();
        double gross = 0, charges = 0, net = 0, winSum = 0, lossSum = 0, rSum = 0, holdMinutes = 0;
        int wins = 0, losses = 0, rCount = 0, holdCount = 0;
        double equity = 0, peak = 0, maxDrawdown = 0;
        Double best = null, worst = null;

        for (PaperTrade t : trades) {
            double tradeNet = t.getProfitLoss();
            net += tradeNet;
            gross += t.getGrossProfitLoss() != null ? t.getGrossProfitLoss() : tradeNet;
            charges += t.getCharges() != null ? t.getCharges() : 0.0;

            if (tradeNet > 0) {
                wins++;
                winSum += tradeNet;
            } else if (tradeNet < 0) {
                losses++;
                lossSum += tradeNet;
            }
            if (t.getRMultiple() != null) {
                rSum += t.getRMultiple();
                rCount++;
            }
            if (t.getEntryTime() != null && t.getExitTime() != null) {
                holdMinutes += Duration.between(t.getEntryTime(), t.getExitTime()).toSeconds() / 60.0;
                holdCount++;
            }
            best = best == null ? tradeNet : Math.max(best, tradeNet);
            worst = worst == null ? tradeNet : Math.min(worst, tradeNet);

            equity += tradeNet;
            peak = Math.max(peak, equity);
            maxDrawdown = Math.max(maxDrawdown, peak - equity);
        }

        double expectancy = count == 0 ? 0 : net / count;
        Verdict verdict = count < minTrades
                ? Verdict.INSUFFICIENT_DATA
                : expectancy > 0 ? Verdict.POSITIVE_EDGE : Verdict.NEGATIVE_EDGE;

        return new GroupStats(
                name,
                count,
                wins,
                losses,
                round(count == 0 ? 0 : wins * 100.0 / count),
                round(gross),
                round(charges),
                round(net),
                round(expectancy),
                rCount == 0 ? null : round(rSum / rCount),
                round(wins == 0 ? 0 : winSum / wins),
                round(losses == 0 ? 0 : lossSum / losses),
                lossSum == 0 ? null : round(winSum / -lossSum),
                round(maxDrawdown),
                best == null ? null : round(best),
                worst == null ? null : round(worst),
                holdCount == 0 ? null : round(holdMinutes / holdCount),
                verdict);
    }

    private static String strategyOf(PaperTrade trade) {
        return normalizeStrategy(trade.getStrategy());
    }

    private static String normalizeStrategy(String strategy) {
        return strategy == null || strategy.isBlank() ? UNKNOWN_STRATEGY : strategy.trim().toUpperCase();
    }

    private static String directionOf(PaperTrade trade) {
        return trade.getSignal() == null ? "UNKNOWN" : trade.getSignal().toUpperCase();
    }

    /**
     * Entry time of day in IST. Trades are stamped with LocalDateTime.now() in
     * the JVM zone, which is UTC on the EC2 host (09:15 IST is stored as 03:45).
     */
    private static LocalTime entryTimeOf(PaperTrade trade) {
        return trade.getEntryTime() == null
                ? null
                : trade.getEntryTime()
                        .atZone(ZoneId.systemDefault())
                        .withZoneSameInstant(IST)
                        .toLocalTime();
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** Entry time of day; edge is measured per bucket because the same setup behaves differently by hour. */
    public enum TimeBucket {
        OPENING(LocalTime.of(9, 15), LocalTime.of(10, 30)),
        LATE_MORNING(LocalTime.of(10, 30), LocalTime.of(11, 30)),
        MIDDAY(LocalTime.of(11, 30), LocalTime.of(13, 0)),
        AFTERNOON(LocalTime.of(13, 0), LocalTime.of(15, 30)),
        OTHER(null, null);

        private final LocalTime start;
        private final LocalTime end;

        TimeBucket(LocalTime start, LocalTime end) {
            this.start = start;
            this.end = end;
        }

        public static TimeBucket of(LocalTime time) {
            if (time == null) {
                return OTHER;
            }
            for (TimeBucket bucket : values()) {
                if (bucket.start != null && !time.isBefore(bucket.start) && time.isBefore(bucket.end)) {
                    return bucket;
                }
            }
            return OTHER;
        }

        public String label() {
            return start == null ? name() : name() + " " + start + "-" + end;
        }
    }

    public enum Verdict { INSUFFICIENT_DATA, POSITIVE_EDGE, NEGATIVE_EDGE }

    /** All money figures in rupees; netPnl and expectancy are after charges. */
    public record GroupStats(
            String group,
            int trades,
            int wins,
            int losses,
            double winRatePct,
            double grossPnl,
            double charges,
            double netPnl,
            double expectancyPerTrade,
            Double averageR,
            double averageWin,
            double averageLoss,
            Double profitFactor,
            double maxDrawdown,
            Double bestTrade,
            Double worstTrade,
            Double averageHoldMinutes,
            Verdict verdict) {
    }

    public record StrategyPerformanceReport(
            LocalDate from,
            LocalDate to,
            int minTradesForVerdict,
            GroupStats overall,
            List<GroupStats> byStrategy,
            List<GroupStats> byStrategyAndDirection,
            List<GroupStats> byStrategyAndEntryTime,
            List<GroupStats> byExitReason) {
    }
}
