package com.ram.trading.signal.engine.service.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One read-only view across the three paper books: intraday (paper_trade), FADE
 * (fade_paper_trade) and monthly momentum (momentum_paper_*). It only reads their
 * tables: nothing here can change a rule, a trade or a balance.
 *
 * Intraday and FADE are counted from the start of their current rules (intraday
 * rule_version, FADE's first trade); momentum from its account start. Momentum is
 * valued at its last 15:40 snapshot, so during the session its "today" is the last
 * stored day. Exposure is at cost (entry price x quantity); FADE's open shorts add
 * their last-minute unrealised P&L.
 */
@Service
public class AccountOverviewService {

    private final JdbcTemplate jdbc;

    @Value("${trading.total-capital:100000}")
    private double intradayCapital = 100000;

    @Value("${trading.risk.max-daily-loss:2000}")
    private double intradayDailyLoss = 2000;

    @Value("${trading.account-view.intraday-rule-version:2026-11-03}")
    private String intradayRuleVersion = "2026-11-03";

    @Value("${trading.fade-book.enabled:true}")
    private boolean fadeEnabled = true;

    @Value("${trading.fade-book.positions:5}")
    private int fadePositions = 5;

    @Value("${trading.fade-book.notional:40000}")
    private double fadeNotional = 40000;

    @Value("${trading.fade-book.daily-stop:10000}")
    private double fadeDailyStop = 10000;

    public AccountOverviewService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One book. budget: capital (intraday, momentum) or the most it can short (FADE). */
    public record BookLine(String book, String role, boolean realMoneyCandidate, String budgetNote, double budget,
                           LocalDate since, int sessions, int trades, int winners,
                           double todayNet, int todayTrades, double todayCharges,
                           double totalNet, double totalCharges, double worstDay, double worstFall,
                           int openPositions, double longExposure, double shortExposure, double unrealised,
                           String note) {
    }

    public record Overview(LocalDate date, boolean paperOnly, List<BookLine> books,
                           double todayNet, double totalNet, double totalCharges,
                           double longExposure, double shortExposure, double worstFallCombined,
                           List<String> notes) {
    }

    public Overview overview(LocalDate day) {
        TreeMap<LocalDate, Double> combined = new TreeMap<>();
        List<BookLine> books = new ArrayList<>();
        books.add(intraday(day, combined));
        books.add(fade(day, combined));
        books.add(momentum(day, combined));

        double today = books.stream().mapToDouble(BookLine::todayNet).sum();
        double total = books.stream().mapToDouble(BookLine::totalNet).sum();
        double charges = books.stream().mapToDouble(BookLine::totalCharges).sum();
        double lng = books.stream().mapToDouble(BookLine::longExposure).sum();
        double sht = books.stream().mapToDouble(BookLine::shortExposure).sum();
        List<String> notes = List.of(
                "Paper only: no real orders are placed by any book.",
                "Only monthly momentum is a real-money candidate (review after January 2027).",
                "Intraday rules are frozen until about 60 trades; FADE is a practice book with no changes.");
        return new Overview(day, true, books, today, total, charges, lng, sht, worstFall(combined), notes);
    }

    private BookLine intraday(LocalDate day, Map<LocalDate, Double> combined) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select date(entry_time) d, count(*) n, sum(case when profit_loss > 0 then 1 else 0 end) w, "
                        + "sum(profit_loss) net, sum(coalesce(charges, 0)) chg from paper_trade "
                        + "where rule_version = ? and status <> 'OPEN' and date(entry_time) <= ? "
                        + "group by date(entry_time) order by d", intradayRuleVersion, day);
        Daily d = daily(rows, day, combined);
        List<Map<String, Object>> open = jdbc.queryForList(
                "select paper_signal side, entry_price p, quantity q from paper_trade where status = 'OPEN'");
        double lng = 0, sht = 0;
        for (Map<String, Object> o : open) {
            double v = num(o.get("p")) * num(o.get("q"));
            if ("SELL".equalsIgnoreCase(String.valueOf(o.get("side")))) {
                sht += v;
            } else {
                lng += v;
            }
        }
        return new BookLine("INTRADAY", "rules frozen until about 60 trades", false,
                String.format("capital Rs %.0f, daily loss limit Rs %.0f", intradayCapital, intradayDailyLoss),
                intradayCapital, d.since, d.sessions, d.trades, d.winners, d.todayNet, d.todayTrades, d.todayCharges,
                d.totalNet, d.totalCharges, d.worstDay, d.worstFall, open.size(), lng, sht, 0,
                "since rule version " + intradayRuleVersion + "; realised P&L net of charges");
    }

    private BookLine fade(LocalDate day, Map<LocalDate, Double> combined) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select trade_date d, count(*) n, sum(case when net_pnl > 0 then 1 else 0 end) w, "
                        + "sum(net_pnl) net, sum(coalesce(charges, 0)) chg from fade_paper_trade "
                        + "where status <> 'OPEN' and trade_date <= ? group by trade_date order by d", day);
        Daily d = daily(rows, day, combined);
        List<Map<String, Object>> open = jdbc.queryForList(
                "select entry_price p, quantity q, last_price l from fade_paper_trade where status = 'OPEN'");
        double sht = 0, unrealised = 0;
        for (Map<String, Object> o : open) {
            double p = num(o.get("p")), q = num(o.get("q"));
            sht += p * q;
            if (o.get("l") != null) {
                unrealised += (p - num(o.get("l"))) * q;
            }
        }
        double budget = fadePositions * fadeNotional;
        return new BookLine("FADE", fadeEnabled ? "practice book, no changes" : "switched off", false,
                String.format("up to %d shorts x Rs %.0f = Rs %.0f, day stop Rs %.0f",
                        fadePositions, fadeNotional, budget, fadeDailyStop),
                budget, d.since, d.sessions, d.trades, d.winners, d.todayNet, d.todayTrades, d.todayCharges,
                d.totalNet, d.totalCharges, d.worstDay, d.worstFall, open.size(), 0, sht, unrealised,
                "failed on unseen months: a good day here is luck, not a signal");
    }

    private BookLine momentum(LocalDate day, Map<LocalDate, Double> combined) {
        List<Map<String, Object>> account = jdbc.queryForList(
                "select start_capital, cash, started_on from momentum_paper_account where id = 1");
        if (account.isEmpty()) {
            return new BookLine("MONTHLY MOMENTUM", "main bet, paper until January 2027", true, "not started", 0,
                    null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "not started");
        }
        double start = num(account.get(0).get("start_capital"));
        double cash = num(account.get(0).get("cash"));
        LocalDate since = toDate(account.get(0).get("started_on"));
        List<Map<String, Object>> eq = jdbc.queryForList(
                "select equity_day d, equity e from momentum_paper_equity where equity_day <= ? order by equity_day", day);
        double prev = start, peak = start, worstFall = 0, worstDay = 0, todayNet = 0, last = start;
        LocalDate lastDay = null;
        for (Map<String, Object> r : eq) {
            LocalDate d = toDate(r.get("d"));
            double e = num(r.get("e"));
            double change = e - prev;
            combined.merge(d, change, Double::sum);
            worstDay = Math.min(worstDay, change);
            peak = Math.max(peak, e);
            worstFall = Math.min(worstFall, e - peak);
            if (d.equals(day)) {
                todayNet = change;
            }
            prev = e;
            last = e;
            lastDay = d;
        }
        Map<String, Object> pos = jdbc.queryForMap("select count(*) n, "
                + "coalesce(sum(case when status = 'OPEN' then quantity * entry_price else 0 end), 0) cost, "
                + "coalesce(sum(case when status = 'OPEN' then 1 else 0 end), 0) open_n, "
                + "coalesce(sum(case when entry_day = ? then buy_charges else 0 end), 0) "
                + "+ coalesce(sum(case when exit_day = ? then sell_charges else 0 end), 0) today_chg, "
                + "coalesce(sum(buy_charges + coalesce(sell_charges, 0)), 0) chg, "
                + "coalesce(sum(case when status = 'CLOSED' then 1 else 0 end), 0) closed_n, "
                + "coalesce(sum(case when status = 'CLOSED' and net_pnl > 0 then 1 else 0 end), 0) won, "
                + "coalesce(sum(case when entry_day = ? then 1 else 0 end), 0) "
                + "+ coalesce(sum(case when exit_day = ? then 1 else 0 end), 0) today_n "
                + "from momentum_paper_position", day, day, day, day);
        double cost = num(pos.get("cost"));
        double holdings = last - cash;
        String note = String.format("equity Rs %.0f (cash Rs %.0f) at the %s snapshot; %.0f%% of capital invested",
                last, cash, lastDay == null ? "first" : lastDay, last > 0 ? (last - cash) / last * 100 : 0);
        return new BookLine("MONTHLY MOMENTUM", "main bet, paper until January 2027", true,
                String.format("capital Rs %.0f, top 30 equal weight, delivery", start), start, since, eq.size(),
                (int) num(pos.get("closed_n")), (int) num(pos.get("won")), todayNet, (int) num(pos.get("today_n")),
                num(pos.get("today_chg")),
                last - start, num(pos.get("chg")), worstDay, worstFall,
                (int) num(pos.get("open_n")), cost, 0, holdings - cost, note);
    }

    private record Daily(LocalDate since, int sessions, int trades, int winners, double todayNet, int todayTrades,
                         double todayCharges, double totalNet, double totalCharges, double worstDay, double worstFall) {
    }

    /** Rows: d, n, w, net, chg per day, oldest first; adds each day's net to {@code combined}. */
    private static Daily daily(List<Map<String, Object>> rows, LocalDate day, Map<LocalDate, Double> combined) {
        LocalDate since = null;
        int trades = 0, winners = 0, todayTrades = 0;
        double total = 0, charges = 0, today = 0, todayChg = 0, worstDay = 0;
        TreeMap<LocalDate, Double> series = new TreeMap<>();
        for (Map<String, Object> r : rows) {
            LocalDate d = toDate(r.get("d"));
            double net = num(r.get("net"));
            since = since == null ? d : since;
            trades += (int) num(r.get("n"));
            winners += (int) num(r.get("w"));
            total += net;
            charges += num(r.get("chg"));
            worstDay = Math.min(worstDay, net);
            series.put(d, net);
            combined.merge(d, net, Double::sum);
            if (d.equals(day)) {
                today = net;
                todayTrades = (int) num(r.get("n"));
                todayChg = num(r.get("chg"));
            }
        }
        return new Daily(since, rows.size(), trades, winners, today, todayTrades, todayChg, total, charges,
                worstDay, worstFall(series));
    }

    /** Largest fall from a running peak of the cumulative daily P&L (0 or negative, in Rs). */
    static double worstFall(TreeMap<LocalDate, Double> dailyNet) {
        double cum = 0, peak = 0, worst = 0;
        for (double v : dailyNet.values()) {
            cum += v;
            peak = Math.max(peak, cum);
            worst = Math.min(worst, cum - peak);
        }
        return worst;
    }

    private static double num(Object o) {
        return o == null ? 0 : ((Number) o).doubleValue();
    }

    private static LocalDate toDate(Object o) {
        if (o instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (o instanceof LocalDate d) {
            return d;
        }
        if (o instanceof java.time.LocalDateTime t) {
            return t.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(o).substring(0, 10));
    }

    /** Plain-text version for Slack or a terminal. */
    public static String toText(Overview o) {
        StringBuilder out = new StringBuilder();
        out.append(String.format("WHOLE ACCOUNT %s (paper only)%n", o.date()));
        out.append(String.format("  today Rs %+,.0f | total Rs %+,.0f | charges Rs %,.0f | open long Rs %,.0f, short Rs %,.0f"
                        + " | worst fall all books Rs %,.0f%n%n", o.todayNet(), o.totalNet(), o.totalCharges(),
                o.longExposure(), o.shortExposure(), o.worstFallCombined()));
        for (BookLine b : o.books()) {
            out.append(String.format("%s - %s%s%n", b.book(), b.role(), b.realMoneyCandidate() ? " (real-money candidate)" : ""));
            out.append("  ").append(b.budgetNote()).append("\n");
            out.append(String.format("  today Rs %+,.0f (%d trades, charges Rs %,.0f)%n",
                    b.todayNet(), b.todayTrades(), b.todayCharges()));
            out.append(String.format("  since %s: Rs %+,.0f over %d sessions, %d trades, %d winners, charges Rs %,.0f%n",
                    b.since(), b.totalNet(), b.sessions(), b.trades(), b.winners(), b.totalCharges()));
            out.append(String.format("  worst day Rs %,.0f, worst fall Rs %,.0f%n", b.worstDay(), b.worstFall()));
            out.append(String.format("  open %d: long Rs %,.0f, short Rs %,.0f, unrealised Rs %+,.0f%n",
                    b.openPositions(), b.longExposure(), b.shortExposure(), b.unrealised()));
            out.append("  ").append(b.note()).append("\n\n");
        }
        o.notes().forEach(n -> out.append("* ").append(n).append("\n"));
        return out.toString();
    }
}
