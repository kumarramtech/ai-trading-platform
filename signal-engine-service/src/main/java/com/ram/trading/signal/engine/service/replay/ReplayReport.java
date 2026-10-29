package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.service.replay.StrategyReplayService.Result;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

/** Plain-text replay report: every number net of charges unless it says gross. */
final class ReplayReport {

    private ReplayReport() {
    }

    record Stats(int n, double winPct, double avgNetR, double avgGrossR, double profitFactor,
                 double netRupees, double maxDrawdownR) {
    }

    static Stats stats(List<Result> results, double riskPerTrade) {
        if (results.isEmpty()) {
            return new Stats(0, 0, 0, 0, 0, 0, 0);
        }
        List<Result> ordered = new ArrayList<>(results);
        ordered.sort(Comparator.comparing(Result::day).thenComparing(Result::time));
        double wins = 0, gain = 0, loss = 0, sumR = 0, sumGrossR = 0, net = 0, peak = 0, equity = 0, maxDd = 0;
        for (Result r : ordered) {
            double pnl = r.grossPnl() - r.charges();
            net += pnl;
            sumR += r.netR();
            sumGrossR += r.grossPnl() / riskPerTrade;
            if (pnl > 0) {
                wins++;
                gain += pnl;
            } else {
                loss -= pnl;
            }
            equity += r.netR();
            peak = Math.max(peak, equity);
            maxDd = Math.max(maxDd, peak - equity);
        }
        int n = ordered.size();
        return new Stats(n, wins / n * 100.0, sumR / n, sumGrossR / n,
                loss > 0 ? gain / loss : (gain > 0 ? 99.0 : 0.0), net, maxDd);
    }

    static String render(LocalDate from, LocalDate to, int days, int universe, List<Result> all, double risk) {
        StringBuilder out = new StringBuilder();
        List<Result> live = all.stream().filter(Result::passesLiveRules).toList();

        out.append("STRATEGY REPLAY  ").append(from).append(" to ").append(to)
                .append("  |  ").append(days).append(" sessions, top ").append(universe)
                .append(" stocks by turnover, Rs ").append((int) risk).append(" risk/trade\n");
        out.append("Net of charges and slippage. Not modelled: daily-indicator rules, AI veto, trailing stop,\n")
                .append("max 2 open trades, daily loss limit. LIVE_RULES = stop distance, liquidity, regime guard, fresh flip.\n\n");

        Stats s = stats(live, risk);
        out.append("VERDICT (LIVE_RULES): ").append(verdict(s)).append("\n");
        out.append(String.format("  %.1f trades/day, Rs %.0f per day, %.0f%% win, avg %+.2fR net (%+.2fR gross), PF %.2f, max DD %.1fR%n%n",
                days == 0 ? 0.0 : (double) s.n() / days, days == 0 ? 0.0 : s.netRupees() / days,
                s.winPct(), s.avgNetR(), s.avgGrossR(), s.profitFactor(), s.maxDrawdownR()));

        section(out, "LIVE_RULES BY STRATEGY", live, Result::strategy, risk);
        section(out, "LIVE_RULES BY STRATEGY AND DIRECTION", live, r -> r.strategy() + " " + r.direction(), risk);
        section(out, "LIVE_RULES BY REGIME", live, r -> r.strategy() + " " + r.relation() + " (" + r.regime() + ")", risk);
        section(out, "LIVE_RULES BY EXIT", live, Result::exit, risk);

        section(out, "WHAT EACH FILTER BLOCKED (would-be result of the setups it stopped)", all, Result::gate, risk);

        out.append("09:15-09:30 PRE-MARKET FACTS (LIVE_RULES setups; answers: which opening conditions pay?)\n");
        section(out, "  Opening-range relative volume (OR volume / 20-day avg daily volume)", live,
                r -> bucket(r.orRvol(), new double[]{0.05, 0.10, 0.20}, new String[]{"<5%", "5-10%", "10-20%", ">=20% (in play)"}), risk);
        section(out, "  Gap from yesterday's close", live,
                r -> bucket(Math.abs(r.gapPct()), new double[]{0.5, 1.5, 3.0}, new String[]{"<0.5%", "0.5-1.5%", "1.5-3%", ">=3%"}), risk);
        section(out, "  Gap direction vs trade", live,
                r -> Double.isNaN(r.gapPct()) || Math.abs(r.gapPct()) < 0.5 ? "no gap"
                        : (r.gapPct() > 0) == "BUY".equals(r.direction()) ? "with the gap" : "against the gap", risk);
        section(out, "  Opening-range width", live,
                r -> bucket(r.orWidthPct(), new double[]{0.8, 1.5, 2.5}, new String[]{"<0.8%", "0.8-1.5%", "1.5-2.5%", ">=2.5%"}), risk);
        section(out, "  Relative strength vs NIFTY at entry (in trade direction)", live,
                r -> bucket(r.relStrengthPct(), new double[]{0.0, 1.0, 2.0}, new String[]{"against NIFTY", "0-1%", "1-2%", ">=2%"}), risk);

        List<Result> gaps = live.stream().filter(r -> r.strategy().startsWith("GAP_GO")).toList();
        if (!gaps.isEmpty()) {
            out.append("GAP_GO VARIANTS (base: OR-side stop at 09:30 | WIDE: 1.5x stop, 1-4% | LATE: 09:45 beyond the OR)\n");
            section(out, "  By gap size", gaps, r -> r.strategy() + " "
                    + bucket(Math.abs(r.gapPct()), new double[]{1.5, 3.0, 5.0}, new String[]{"1-1.5%", "1.5-3%", "3-5%", ">=5%"}), risk);
            section(out, "  By opening relative volume", gaps, r -> r.strategy() + " "
                    + bucket(r.orRvol(), new double[]{0.20, 0.40}, new String[]{"10-20%", "20-40%", ">=40%"}), risk);
            section(out, "  By direction and index", gaps, r -> r.strategy() + " " + r.direction() + " " + r.relation(), risk);
        }

        List<Result> inPlay = all.stream().filter(r -> r.strategy().startsWith("INPLAY_")).toList();
        if (!inPlay.isEmpty()) {
            out.append("IN-PLAY 5-MIN ORB (top N by first-5-min volume / 20-day avg daily volume, stop = ATR fraction,\n")
                    .append("no target, out at square-off; ALL5 = same trade in every liquid stock)\n");
            section(out, "  By variant", inPlay, Result::strategy, risk);
            section(out, "  By variant and direction", inPlay, r -> r.strategy() + " " + r.direction(), risk);
            section(out, "  By variant and exit", inPlay, r -> r.strategy() + " " + r.exit(), risk);
            section(out, "  By first-5-min volume (share of a normal day)", inPlay, r -> r.strategy() + " "
                    + bucket(r.orRvol(), new double[]{0.05, 0.10, 0.20}, new String[]{"<5%", "5-10%", "10-20%", ">=20%"}), risk);
        }

        out.append("ALL SETUPS (every filter off) BY STRATEGY\n");
        section(out, "", all, Result::strategy, risk);

        out.append("BEST CANDIDATE RULES (LIVE_RULES subsets with at least 30 trades)\n");
        candidates(out, live, risk);

        out.append("\nDAILY P&L (LIVE_RULES)\n");
        Map<LocalDate, List<Result>> byDay = new TreeMap<>();
        live.forEach(r -> byDay.computeIfAbsent(r.day(), d -> new ArrayList<>()).add(r));
        byDay.forEach((day, list) -> {
            Stats d = stats(list, risk);
            out.append(String.format("  %s  trades %2d  net Rs %7.0f  avg %+.2fR%n", day, d.n(), d.netRupees(), d.avgNetR()));
        });
        return out.toString();
    }

    private static void candidates(StringBuilder out, List<Result> live, double risk) {
        Map<String, Predicate<Result>> rules = new java.util.LinkedHashMap<>();
        rules.put("in play (OR rvol >= 10%)", r -> r.orRvol() >= 0.10);
        rules.put("in play (OR rvol >= 20%)", r -> r.orRvol() >= 0.20);
        rules.put("regime ALIGNED only", r -> "ALIGNED".equals(r.relation()));
        rules.put("relative strength >= 1%", r -> r.relStrengthPct() >= 1.0);
        rules.put("in play >=10% AND rel. strength >= 1%", r -> r.orRvol() >= 0.10 && r.relStrengthPct() >= 1.0);
        rules.put("in play >=10% AND ALIGNED", r -> r.orRvol() >= 0.10 && "ALIGNED".equals(r.relation()));
        rules.put("with the gap (gap >= 0.5%)", r -> !Double.isNaN(r.gapPct()) && Math.abs(r.gapPct()) >= 0.5
                && (r.gapPct() > 0) == "BUY".equals(r.direction()));
        rules.forEach((name, rule) -> {
            Stats s = stats(live.stream().filter(rule).toList(), risk);
            if (s.n() >= 30) {
                out.append(line(name, s));
            } else {
                out.append(String.format("  %-44s n=%d (too few)%n", name, s.n()));
            }
        });
    }

    private static void section(StringBuilder out, String title, List<Result> results,
                                Function<Result, String> key, double risk) {
        if (!title.isEmpty()) {
            out.append(title).append("\n");
        }
        Map<String, List<Result>> groups = new TreeMap<>(StrategyReplayService.group(results, key));
        if (groups.isEmpty()) {
            out.append("  (none)\n");
        }
        groups.forEach((k, list) -> out.append(line(k, stats(list, risk))));
        out.append("\n");
    }

    private static String line(String name, Stats s) {
        return String.format("  %-44s n=%4d  win %3.0f%%  avg %+.2fR net (%+.2fR gross)  PF %4.2f  net Rs %8.0f  maxDD %4.1fR%n",
                name, s.n(), s.winPct(), s.avgNetR(), s.avgGrossR(), s.profitFactor(), s.netRupees(), s.maxDrawdownR());
    }

    private static String verdict(Stats s) {
        if (s.n() < 60) {
            return "INSUFFICIENT DATA (" + s.n() + " trades; need 60)";
        }
        boolean edge = s.avgNetR() >= 0.20 && s.profitFactor() >= 1.3 && s.maxDrawdownR() <= 10.0;
        return edge ? "EDGE - passes the go-live thresholds on history"
                : s.avgNetR() > 0 ? "MARGINAL - profitable but below the go-live thresholds"
                : "NO EDGE - loses money after charges";
    }

    private static String bucket(double value, double[] limits, String[] labels) {
        if (Double.isNaN(value)) {
            return "unknown";
        }
        for (int i = 0; i < limits.length; i++) {
            if (value < limits[i]) {
                return (i + 1) + " " + labels[i];
            }
        }
        return (limits.length + 1) + " " + labels[limits.length];
    }
}
