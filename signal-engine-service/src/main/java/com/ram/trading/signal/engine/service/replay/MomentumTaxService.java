package com.ram.trading.signal.engine.service.replay;

import com.ram.trading.signal.engine.client.StockServiceClient;
import com.ram.trading.signal.engine.dto.indicator.Candle;
import com.ram.trading.signal.engine.dto.indicator.HistoricalCandleResponse;
import com.ram.trading.signal.engine.service.replay.SwingBacktestService.DeliveryCharges;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The paper momentum book after Indian capital-gains tax, next to the simple alternative:
 * the same capital in a Nifty200 Momentum 30 index fund bought on the book's first day.
 * Read-only; nothing here changes the book.
 *
 * Tax (listed equity, resident individual, defaults as of FY 2025-26): a lot held up to
 * 12 months is short-term (STCG, 20%), longer is long-term (LTCG, 12.5% above Rs 1.25 lakh
 * a year), plus 4% cess; no surcharge. Gains and losses net within a financial year
 * (April-March): short-term losses offset either gain, long-term losses only long-term;
 * what is left carries forward. STT is not deductible, so it is added back to the gain;
 * the other charges are. unused-basic-exemption is the part of the basic exemption limit
 * the rest of your income leaves unused (0 for most salaried people); the section 87A
 * rebate does not cover these special-rate gains, so it is not applied.
 */
@Service
@Slf4j
public class MomentumTaxService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate jdbc;
    private final StockServiceClient stockServiceClient;
    private final MomentumPaperService paper;

    @Value("${trading.momentum.tax.stcg-pct:20}")
    private double stcgPct = 20;

    @Value("${trading.momentum.tax.ltcg-pct:12.5}")
    private double ltcgPct = 12.5;

    @Value("${trading.momentum.tax.ltcg-exempt:125000}")
    private double ltcgExempt = 125000;

    @Value("${trading.momentum.tax.cess-pct:4}")
    private double cessPct = 4;

    @Value("${trading.momentum.tax.unused-basic-exemption:0}")
    private double unusedBasicExemption = 0;

    @Value("${trading.momentum.paper.slippage-pct:0.10}")
    private double slippagePct = 0.10;

    @Value("${trading.momentum.paper.brokerage:0}")
    private double brokerage = 0;

    @Value("${trading.momentum.benchmark.symbol:NIFTY200MOMENTM30}")
    private String indexSymbol = "NIFTY200MOMENTM30";

    /** Yearly expense ratio of an index fund on that index (direct plans are about 0.3%). */
    @Value("${trading.momentum.benchmark.expense-pct:0.3}")
    private double indexExpensePct = 0.3;

    public MomentumTaxService(JdbcTemplate jdbc, StockServiceClient stockServiceClient, MomentumPaperService paper) {
        this.jdbc = jdbc;
        this.stockServiceClient = stockServiceClient;
        this.paper = paper;
    }

    // ------------------------------------------------------------ pure tax arithmetic

    /** Tax rates; pcts as percent. */
    public record Rates(double stcgPct, double ltcgPct, double ltcgExempt, double cessPct, double unusedBasicExemption) {
    }

    /** One sale for tax: the gain is the taxable gain (STT added back). */
    public record Lot(LocalDate bought, LocalDate sold, double gain) {
        boolean longTerm() {
            return sold.isAfter(bought.plusMonths(12));
        }
    }

    /** One financial year: net gains before set-off, what is taxed, the tax and losses carried out. */
    public record YearTax(String year, double shortTermGain, double longTermGain, double taxedShortTerm,
                          double taxedLongTerm, double tax, double shortLossCarried, double longLossCarried) {
    }

    /** "FY 2026-27" for 2026-11-11. */
    static String financialYear(LocalDate d) {
        int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return String.format("FY %d-%02d", start, (start + 1) % 100);
    }

    /** Tax per financial year, oldest first, with set-off and carry-forward of losses. */
    static List<YearTax> taxByYear(List<Lot> lots, Rates r) {
        Map<String, double[]> years = new TreeMap<>();
        for (Lot l : lots) {
            double[] y = years.computeIfAbsent(financialYear(l.sold()), k -> new double[2]);
            y[l.longTerm() ? 1 : 0] += l.gain();
        }
        List<YearTax> out = new ArrayList<>();
        double stCarry = 0, ltCarry = 0;
        for (Map.Entry<String, double[]> e : years.entrySet()) {
            double st0 = e.getValue()[0], lt0 = e.getValue()[1];
            double st = st0, lt = lt0;
            // This year's short-term loss against this year's long-term gain.
            if (st < 0 && lt > 0) {
                double use = Math.min(-st, lt);
                st += use;
                lt -= use;
            }
            // Brought-forward losses: short-term against either, long-term against long-term.
            double use = Math.min(stCarry, Math.max(st, 0));
            st -= use;
            stCarry -= use;
            use = Math.min(stCarry, Math.max(lt, 0));
            lt -= use;
            stCarry -= use;
            use = Math.min(ltCarry, Math.max(lt, 0));
            lt -= use;
            ltCarry -= use;
            stCarry += Math.max(0, -st);
            ltCarry += Math.max(0, -lt);
            double taxedSt = Math.max(0, st);
            double taxedLt = Math.max(0, Math.max(0, lt) - r.ltcgExempt());
            // Unused basic exemption absorbs the higher-rate gain first.
            double basic = r.unusedBasicExemption();
            double b = Math.min(basic, taxedSt);
            taxedSt -= b;
            basic -= b;
            taxedLt -= Math.min(basic, taxedLt);
            double tax = (taxedSt * r.stcgPct() + taxedLt * r.ltcgPct()) / 100 * (1 + r.cessPct() / 100);
            out.add(new YearTax(e.getKey(), st0, lt0, taxedSt, taxedLt, tax, stCarry, ltCarry));
        }
        return out;
    }

    static double totalTax(List<YearTax> years) {
        return years.stream().mapToDouble(YearTax::tax).sum();
    }

    /** Taxable gain of a delivery round trip: net P&L with STT on both sides added back (not deductible). */
    static double taxableGain(double netPnl, double buyValue, double sellValue) {
        return netPnl + (buyValue + sellValue) * DeliveryCharges.STT_PCT / 100;
    }

    /** Index fund value after the expense ratio, and after tax when redeemed on {@code sold}. */
    static double[] indexFund(double capital, double baseLevel, double lastLevel, LocalDate bought, LocalDate sold,
                              double expensePct, Rates r) {
        long days = Math.max(0, ChronoUnit.DAYS.between(bought, sold));
        double value = capital * lastLevel / baseLevel * (1 - expensePct / 100 * days / 365.0);
        List<YearTax> tax = taxByYear(List.of(new Lot(bought, sold, value - capital)), r);
        return new double[]{value, totalTax(tax)};
    }

    // ------------------------------------------------------------ the view

    public record OpenLot(String symbol, LocalDate bought, int quantity, double lastPrice, double gainIfSold,
                          boolean longTerm) {
    }

    /**
     * basis SINCE_START: the whole strategy from the book's first day, months in cash included;
     * SINCE_FIRST_BUY: from the first stock bought, so stock picking only. Book % is from its
     * equity on the evening before the base day.
     */
    public record IndexComparison(String basis, String symbol, LocalDate baseDay, double baseLevel, LocalDate lastDay,
                                  double lastLevel, double value, double expense, double taxIfSold,
                                  double afterTaxValue, double preTaxPct, double afterTaxPct,
                                  Double bookEquityThatDay, Double bookPctThatDay, Double bookAheadPctPoints) {
    }

    public record AfterTaxView(LocalDateTime asOf, boolean paperOnly, LocalDate since, double startCapital,
                               double equity, double preTaxPnl, double preTaxPct,
                               double realisedTaxableGain, double taxOnRealised,
                               double sellCostsIfSoldNow, double unrealisedTaxableGain, double taxIfSoldNow,
                               double afterTaxEquity, double afterTaxPnl, double afterTaxPct,
                               List<YearTax> years, List<OpenLot> open, IndexComparison indexSinceStart,
                               IndexComparison indexSinceFirstBuy, List<String> notes) {
    }

    private Rates rates() {
        return new Rates(stcgPct, ltcgPct, ltcgExempt, cessPct, unusedBasicExemption);
    }

    /** The book after tax if everything were sold now, and the index fund over the same days. */
    public AfterTaxView afterTax() {
        Rates r = rates();
        MomentumPaperService.PortfolioView view = paper.portfolioView();
        LocalDate today = LocalDate.now(IST);
        List<Lot> realised = jdbc.query("select entry_day, exit_day, quantity, entry_price, exit_price, net_pnl "
                        + "from momentum_paper_position where status = 'CLOSED' and exit_day is not null",
                (rs, n) -> new Lot(rs.getDate(1).toLocalDate(), rs.getDate(2).toLocalDate(),
                        taxableGain(rs.getDouble(6), rs.getInt(3) * rs.getDouble(4), rs.getInt(3) * rs.getDouble(5))));
        double realisedGain = realised.stream().mapToDouble(Lot::gain).sum();
        double taxRealised = totalTax(taxByYear(realised, r));

        Map<String, Double> last = new java.util.HashMap<>();
        view.positions().forEach(p -> last.put(p.symbol(), p.lastPrice() != null ? p.lastPrice() : p.buyPrice()));
        List<Lot> all = new ArrayList<>(realised);
        List<OpenLot> openLots = new ArrayList<>();
        double sellCosts = 0, unrealisedGain = 0;
        for (Map<String, Object> p : jdbc.queryForList("select symbol, entry_day, quantity, entry_price, buy_charges "
                + "from momentum_paper_position where status = 'OPEN' order by id")) {
            String symbol = (String) p.get("symbol");
            LocalDate bought = toDate(p.get("entry_day"));
            int qty = ((Number) p.get("quantity")).intValue();
            double entry = ((Number) p.get("entry_price")).doubleValue();
            double buyCharges = ((Number) p.get("buy_charges")).doubleValue();
            double price = last.getOrDefault(symbol, entry);
            double sellValue = price * (1 - slippagePct / 100) * qty;
            double sellCharges = DeliveryCharges.sellSide(sellValue, brokerage);
            double net = sellValue - sellCharges - entry * qty - buyCharges;
            double gain = taxableGain(net, entry * qty, sellValue);
            // Slippage and sell charges: what selling everything would cost on top of the marked value.
            sellCosts += price * qty - sellValue + sellCharges;
            unrealisedGain += gain;
            Lot lot = new Lot(bought, today, gain);
            all.add(lot);
            openLots.add(new OpenLot(symbol, bought, qty, price, gain, lot.longTerm()));
        }
        List<YearTax> years = taxByYear(all, r);
        double taxAll = totalTax(years);
        double taxIfSold = taxAll - taxRealised;
        double start = view.startCapital();
        double afterTaxEquity = view.equity() - sellCosts - taxAll;

        List<String> notes = new ArrayList<>();
        notes.add("Paper only. Estimate for a resident individual: STCG " + stcgPct + "% up to 12 months, LTCG "
                + ltcgPct + "% above Rs " + Math.round(ltcgExempt) + " a year, " + cessPct
                + "% cess, no surcharge; STT is not deductible. Check with a tax adviser before filing.");
        notes.add(unusedBasicExemption > 0
                ? "Unused basic exemption of Rs " + Math.round(unusedBasicExemption) + " applied."
                : "No basic exemption applied (other income assumed to use it); the 87A rebate does not cover these gains.");
        notes.add("years: sales so far plus, in " + MomentumTaxService.financialYear(today)
                + ", selling every open holding today. A top-up keeps the holding's first buy date.");
        notes.add("Interest on idle cash is not taxed here; in a real liquid fund it is taxed at your slab rate.");
        IndexComparison sinceStart = index("SINCE_START", view.since(), start, r, notes);
        IndexComparison sinceFirstBuy = null;
        LocalDate firstBuy = jdbc.queryForObject("select min(entry_day) from momentum_paper_position", LocalDate.class);
        if (firstBuy != null && view.since() != null && firstBuy.isAfter(view.since())) {
            // The book sat in cash until firstBuy: measure from its equity the evening before.
            List<Double> before = jdbc.queryForList("select equity from momentum_paper_equity where equity_day < ? "
                    + "order by equity_day desc limit 1", Double.class, firstBuy);
            sinceFirstBuy = index("SINCE_FIRST_BUY", firstBuy, before.isEmpty() ? start : before.get(0), r, notes);
        }
        return new AfterTaxView(view.asOf(), true, view.since(), start, view.equity(), view.totalPnl(),
                view.totalPnlPct(), realisedGain, taxRealised, sellCosts, unrealisedGain, taxIfSold,
                afterTaxEquity, afterTaxEquity - start, (afterTaxEquity / start - 1) * 100, years, openLots,
                sinceStart, sinceFirstBuy, notes);
    }

    /**
     * {@code capital} in an index fund bought at the index's open on {@code since}, valued at the
     * latest daily close the history has (the previous trading day during the session), next to
     * the book's stored 15:40 equity on that same day measured from {@code capital}.
     */
    private IndexComparison index(String basis, LocalDate since, double capital, Rates r, List<String> notes) {
        if (since == null) {
            return null;
        }
        try {
            HistoricalCandleResponse h = stockServiceClient.getHistoricalCandles(indexSymbol, "day", since,
                    LocalDate.now(IST)).block(Duration.ofSeconds(20));
            List<Candle> candles = h == null || h.getCandles() == null ? List.of() : h.getCandles().stream()
                    .filter(c -> c.getDateTime() != null && c.getClose() != null && c.getOpen() != null)
                    .sorted(java.util.Comparator.comparing(Candle::getDateTime)).toList();
            if (candles.isEmpty()) {
                notes.add("No " + indexSymbol + " prices yet: index comparison skipped.");
                return null;
            }
            Candle first = candles.get(0), lastCandle = candles.get(candles.size() - 1);
            // Daily candles are stamped 00:00 IST; read the day in IST (in UTC it is the evening before).
            LocalDate baseDay = first.getDateTime().atZoneSameInstant(IST).toLocalDate();
            LocalDate lastDay = lastCandle.getDateTime().atZoneSameInstant(IST).toLocalDate();
            double base = first.getOpen().doubleValue(), level = lastCandle.getClose().doubleValue();
            double[] fund = indexFund(capital, base, level, baseDay, lastDay, indexExpensePct, r);
            double gross = capital * level / base;
            List<Double> book = jdbc.queryForList("select equity from momentum_paper_equity where equity_day = ?",
                    Double.class, lastDay);
            Double bookEquity = book.isEmpty() ? null : book.get(0);
            Double bookPct = bookEquity == null ? null : (bookEquity / capital - 1) * 100;
            double indexPct = (fund[0] / capital - 1) * 100;
            notes.add(basis + ": index fund Rs " + Math.round(capital) + " at the " + indexSymbol + " open on " + baseDay
                    + ", " + indexExpensePct + "% a year expenses, valued at the " + lastDay
                    + " close; no tax until it is sold. Compared with the book's 15:40 equity that day (before tax).");
            return new IndexComparison(basis, indexSymbol, baseDay, base, lastDay, level, fund[0], gross - fund[0], fund[1],
                    fund[0] - fund[1], indexPct, ((fund[0] - fund[1]) / capital - 1) * 100, bookEquity, bookPct,
                    bookPct == null ? null : bookPct - indexPct);
        } catch (Exception ex) {
            log.warn("Momentum after-tax: no {} history: {}", indexSymbol, ex.getMessage());
            notes.add("Could not load " + indexSymbol + ": index comparison skipped.");
            return null;
        }
    }

    private static LocalDate toDate(Object o) {
        return o instanceof java.sql.Date d ? d.toLocalDate()
                : o instanceof LocalDate d ? d : LocalDate.parse(String.valueOf(o).substring(0, 10));
    }
}
