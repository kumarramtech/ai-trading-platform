package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.service.charges.IntradayChargesCalculator;
import com.ram.trading.signal.engine.service.replay.StrategyReplayService.Result;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-exits the same replayed setups under other exit rules and risk sizes,
 * using the minute path each setup stored, so every variant is judged on
 * identical entries. Entry fill (with slippage) and initial stop are unchanged.
 */
final class ReplayExperiments {

    private ReplayExperiments() {
    }

    /** Exit rule applied to one setup's minute path. */
    record Rule(String name, double targetR, boolean breakevenAt1R, boolean trailOneR, int maxMinutes) {
    }

    static final List<Rule> RULES = List.of(
            new Rule("2R target (current)", 2.0, false, false, 0),
            new Rule("1R target", 1.0, false, false, 0),
            new Rule("1.5R target", 1.5, false, false, 0),
            new Rule("3R target", 3.0, false, false, 0),
            new Rule("no target, hold to 15:00", 0, false, false, 0),
            new Rule("2R target, breakeven at 1R", 2.0, true, false, 0),
            new Rule("no target, trail 1R after 1R", 0, false, true, 0),
            new Rule("2R target, exit after 60 min", 2.0, false, false, 60),
            new Rule("1R target, exit after 30 min", 1.0, false, false, 30));

    static final double[] RISKS = {250, 500, 1000};

    record Outcome(double gross, double charges, double risk) {
        double netR() {
            return (gross - charges) / risk;
        }
    }

    static String render(List<Result> results, int days, IntradayChargesCalculator charges,
                         double slippagePct, double maxNotional) {
        StringBuilder out = new StringBuilder();
        out.append("EXPERIMENTS: same entries, other exits and risk sizes (net of charges)\n");
        out.append("Charges are mostly fixed per order (brokerage capped at Rs 20), so a larger risk per trade\n")
                .append("pays a smaller share of R in costs. Avg R and PF below; 'Rs/day' at that risk size.\n\n");

        Map<String, List<Result>> sets = new LinkedHashMap<>();
        sets.put("LIVE_RULES, all strategies", results.stream().filter(Result::passesLiveRules).toList());
        results.stream().map(Result::strategy).distinct().sorted().forEach(strategy ->
                sets.put("LIVE_RULES, " + strategy, results.stream()
                        .filter(r -> r.passesLiveRules() && strategy.equals(r.strategy())).toList()));
        sets.put("MEAN_REVERSION in SIDEWAYS", results.stream()
                .filter(r -> r.passesLiveRules() && ResearchStrategies.MEAN_REVERSION.equals(r.strategy())
                        && "SIDEWAYS".equals(r.regime())).toList());
        sets.put("LIVE_RULES, in play (OR rvol >= 20%)", results.stream()
                .filter(r -> r.passesLiveRules() && r.orRvol() >= 0.20).toList());
        sets.put("ALL ORB setups (stop-distance rules off)", results.stream()
                .filter(r -> "ORB".equals(r.strategy())).toList());

        String best = null;
        double bestR = -99;

        for (Map.Entry<String, List<Result>> set : sets.entrySet()) {
            List<Result> list = set.getValue();
            out.append(set.getKey()).append("  (n=").append(list.size()).append(")\n");
            out.append(String.format("  %-34s", "exit rule"));
            for (double risk : RISKS) {
                out.append(String.format(" | Rs %4.0f: avgR    PF  Rs/day", risk));
            }
            out.append(String.format(" | win%% | gross R%n"));
            for (Rule rule : RULES) {
                out.append(String.format("  %-34s", rule.name()));
                double win = 0;
                double grossR = 0;
                for (double risk : RISKS) {
                    List<Outcome> outcomes = new ArrayList<>();
                    for (Result r : list) {
                        Outcome o = simulate(r, rule, risk, charges, slippagePct, maxNotional);
                        if (o != null) {
                            outcomes.add(o);
                        }
                    }
                    double sumR = 0, gain = 0, loss = 0, net = 0, wins = 0, gross = 0;
                    for (Outcome o : outcomes) {
                        double pnl = o.gross() - o.charges();
                        sumR += o.netR();
                        net += pnl;
                        gross += o.gross() / o.risk();
                        if (pnl > 0) {
                            gain += pnl;
                            wins++;
                        } else {
                            loss -= pnl;
                        }
                    }
                    int n = Math.max(1, outcomes.size());
                    double avgR = sumR / n;
                    out.append(String.format(" | %+5.2fR %5.2f %7.0f", avgR,
                            loss > 0 ? gain / loss : 0.0, days > 0 ? net / days : 0.0));
                    win = wins / n * 100;
                    grossR = gross / n;
                    if (risk == 1000 && list.size() >= 60 && avgR > bestR) {
                        bestR = avgR;
                        best = set.getKey() + " / " + rule.name() + String.format(" at Rs 1000: %+.2fR", avgR);
                    }
                }
                out.append(String.format(" | %3.0f%% | %+5.2f%n", win, grossR));
            }
            out.append("\n");
        }
        out.append("Best combination with >= 60 trades: ").append(best == null ? "none" : best).append("\n");
        out.append("Go-live needs avg >= +0.20R net and PF >= 1.3.\n");
        return out.toString();
    }

    static Outcome simulate(Result r, Rule rule, double risk, IntradayChargesCalculator charges,
                            double slippagePct, double maxNotional) {
        boolean buy = "BUY".equals(r.direction());
        double entry = r.entry();
        double perShare = Math.abs(entry - r.stop());
        if (perShare <= 0 || r.pathClose() == null) {
            return null;
        }
        int qty = (int) Math.min(Math.floor(risk / perShare), Math.floor(maxNotional / entry));
        if (qty < 1) {
            return null;
        }

        double stop = r.stop();
        double target = rule.targetR() > 0 ? (buy ? entry + rule.targetR() * perShare : entry - rule.targetR() * perShare) : Double.NaN;
        double best = entry;
        double exitRef = entry;
        int n = r.pathClose().length;

        for (int i = 0; i < n; i++) {
            double hi = r.pathHigh()[i];
            double lo = r.pathLow()[i];
            exitRef = r.pathClose()[i];

            if (buy ? lo <= stop : hi >= stop) {
                exitRef = stop;
                break;
            }
            if (!Double.isNaN(target) && (buy ? hi >= target : lo <= target)) {
                exitRef = target;
                break;
            }
            if (rule.maxMinutes() > 0 && i + 1 >= rule.maxMinutes()) {
                break;
            }

            // Stop updates use this candle's extreme and apply from the next candle.
            best = buy ? Math.max(best, hi) : Math.min(best, lo);
            double favourable = buy ? best - entry : entry - best;
            if (rule.breakevenAt1R() && favourable >= perShare) {
                stop = buy ? Math.max(stop, entry) : Math.min(stop, entry);
            }
            if (rule.trailOneR() && favourable >= perShare) {
                double trail = buy ? best - perShare : best + perShare;
                stop = buy ? Math.max(stop, trail) : Math.min(stop, trail);
            }
        }

        double exit = buy ? exitRef * (1 - slippagePct / 100.0) : exitRef * (1 + slippagePct / 100.0);
        double gross = (buy ? exit - entry : entry - exit) * qty;
        double cost = charges.roundTrip(r.direction(), entry, exit, qty).getTotal();
        return new Outcome(gross, cost, risk);
    }
}
