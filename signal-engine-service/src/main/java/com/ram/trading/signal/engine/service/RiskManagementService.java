package com.ram.trading.signal.engine.service;

import com.ram.trading.signal.engine.client.TradeExecutionClient.TradeExecutionPosition;
import com.ram.trading.signal.engine.contant.SignalStatus;
import com.ram.trading.signal.engine.risk.LiveTradeSnapshotService;
import com.ram.trading.signal.engine.risk.RiskCheckResponse;
import com.ram.trading.signal.engine.entity.PaperTrade;
import com.ram.trading.signal.engine.repo.PaperTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Owns trading-risk limits (daily loss, trades per symbol, open trades, losing
 * streak) for new entries. Paper mode reads PaperTrade rows; live mode reads the
 * positions owned by Trade Execution Service, because live entries never create
 * PaperTrade rows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RiskManagementService {

    private static final Set<String> ACTIVE_LIVE_STATUSES = Set.of("OPEN", "ENTRY_PENDING", "EXIT_PENDING");

    private final PaperTradeRepository repository;

    private final LiveTradeSnapshotService liveTradeSnapshotService;

    /**
     * New entries stop for the day after this many losing trades in a row,
     * across all symbols. 0 disables it: the day is then halted only by
     * max-daily-loss, and a losing stock is blocked by max-losses-per-symbol-per-day.
     */
    @Value("${trading.risk.max-consecutive-losses:0}")
    private int maxConsecutiveLosses = 0;

    /**
     * A symbol takes no new entry for the rest of the day after this many
     * losing trades in it (scratches excluded); other symbols keep trading.
     * 0 disables it.
     */
    @Value("${trading.risk.max-losses-per-symbol-per-day:3}")
    private int maxLossesPerSymbolPerDay = 3;

    /**
     * A loss smaller than this % of the position's notional is a scratch
     * (typically a break-even stop filled a tick or two worse). Scratches carry
     * no information about the setup, so they neither extend nor reset the
     * losing streak. On 2026-10-26 two scratches of Rs 3 and Rs 31 on ~Rs 50k
     * positions counted as "losses" and halted trading at 09:55 IST.
     */
    @Value("${trading.risk.scratch-loss-pct:0.10}")
    private double scratchLossPct = 0.10;

    @Value("${trading.risk.max-daily-loss:2000}")
    private double maxDailyLoss;

    @Value("${trading.risk.max-open-trades:20}")
    private int maxOpenTrades;

    @Value("${trading.max-trades-per-symbol-per-day}")
    private int maxTradesPerSymbolPerDay;

    /** Same cooldown paper mode applies in PaperTradingService.validateTradeCreation. */
    @Value("${trading.reentry.cooldown-minutes:15}")
    private long reentryCooldownMinutes = 15;

    public RiskCheckResponse validateTrade(String symbol) {

        if (liveTradeSnapshotService.isLiveMode()) {
            return validateLiveTrade(symbol);
        }

        List<String> violations = new ArrayList<>();

        if (maxTradesPerSymbolPerDayReached(symbol)) {
            violations.add(
                    "Maximum daily trades reached for symbol: " + symbol);
        }

        if (maxLossesPerSymbolPerDayReached(symbol)) {
            violations.add(
                    "Maximum daily losses reached for symbol: " + symbol);
        }

        if (dailyLossExceeded()) {
            violations.add(
                    "Daily loss limit exceeded. Trading halted.");
        }

        if (maxOpenTradesReached()) {
            violations.add(
                    "Maximum open trades reached.");
        }

        if (consecutiveLossesExceeded()) {
            violations.add(
                    "Maximum consecutive losses reached.");
        }

        return response(violations);
    }

    /**
     * Same limits as paper mode, evaluated against today's live positions. Fails
     * closed when the live snapshot is unavailable or stale.
     */
    private RiskCheckResponse validateLiveTrade(String symbol) {

        Optional<List<TradeExecutionPosition>> snapshot = liveTradeSnapshotService.freshTrades();
        if (snapshot.isEmpty()) {
            log.warn("LIVE RISK CHECK BLOCKED | Symbol={} | Live position data from Trade Execution is unavailable or stale",
                    symbol);
            return response(List.of(
                    "Live position data from Trade Execution is unavailable or stale. Trading halted until it recovers."));
        }

        List<TradeExecutionPosition> trades = snapshot.get();
        List<String> violations = new ArrayList<>();

        double todayPnL = trades.stream()
                .map(TradeExecutionPosition::realizedPnl)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue)
                .sum();
        long symbolTradesToday = trades.stream()
                .filter(trade -> symbol != null && symbol.equalsIgnoreCase(trade.symbol()))
                .count();
        long openTrades = trades.stream()
                .filter(trade -> isActiveLiveStatus(trade.status()))
                .count();
        boolean symbolPositionOpen = trades.stream()
                .anyMatch(trade -> symbol != null && symbol.equalsIgnoreCase(trade.symbol())
                        && isActiveLiveStatus(trade.status()));
        long symbolLossesToday = trades.stream()
                .filter(trade -> symbol != null && symbol.equalsIgnoreCase(trade.symbol()))
                .filter(trade -> "CLOSED".equalsIgnoreCase(trade.status()) && trade.realizedPnl() != null)
                .filter(trade -> isRealLoss(trade.realizedPnl().doubleValue(),
                        trade.averageEntryPrice() == null ? null : trade.averageEntryPrice().doubleValue(),
                        trade.filledQuantity()))
                .count();

        log.info("LIVE RISK CHECK | Symbol={} | TodayRealizedPnL={} | SymbolTradesToday={} | SymbolLossesToday={} | OpenTrades={}",
                symbol, todayPnL, symbolTradesToday, symbolLossesToday, openTrades);

        if (symbolTradesToday >= maxTradesPerSymbolPerDay) {
            violations.add("Maximum daily trades reached for symbol: " + symbol);
        }
        if (maxLossesPerSymbolPerDay > 0 && symbolLossesToday >= maxLossesPerSymbolPerDay) {
            violations.add("Maximum daily losses reached for symbol: " + symbol);
        }
        if (todayPnL <= -maxDailyLoss) {
            violations.add("Daily loss limit exceeded. Trading halted.");
        }
        if (openTrades >= maxOpenTrades) {
            violations.add("Maximum open trades reached.");
        }
        if (symbolPositionOpen) {
            violations.add("Open live position already exists for symbol: " + symbol);
        }
        LocalDateTime cooldownUntil = liveReentryCooldownUntil(trades, symbol);
        if (cooldownUntil != null && LocalDateTime.now().isBefore(cooldownUntil)) {
            violations.add("Re-entry cooldown active for symbol: " + symbol + " until " + cooldownUntil);
        }
        if (maxConsecutiveLosses > 0 && liveConsecutiveLosses(trades) >= maxConsecutiveLosses) {
            violations.add("Maximum consecutive losses reached.");
        }

        return response(violations);
    }

    private static boolean isActiveLiveStatus(String status) {
        return status != null && ACTIVE_LIVE_STATUSES.contains(status.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private LocalDateTime liveReentryCooldownUntil(List<TradeExecutionPosition> trades, String symbol) {
        if (symbol == null || reentryCooldownMinutes <= 0) {
            return null;
        }
        return trades.stream()
                .filter(trade -> symbol.equalsIgnoreCase(trade.symbol()) && trade.closedAt() != null)
                .map(TradeExecutionPosition::closedAt)
                .max(Comparator.naturalOrder())
                .map(closedAt -> closedAt.plusMinutes(reentryCooldownMinutes))
                .orElse(null);
    }

    private int liveConsecutiveLosses(List<TradeExecutionPosition> trades) {
        List<TradeExecutionPosition> closed = trades.stream()
                .filter(trade -> "CLOSED".equalsIgnoreCase(trade.status()) && trade.realizedPnl() != null)
                .sorted(Comparator.comparing(TradeExecutionPosition::closedAt,
                        Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder())))
                .toList();
        int losses = 0;
        for (TradeExecutionPosition trade : closed) {
            double pnl = trade.realizedPnl().doubleValue();
            if (pnl >= 0) {
                break;
            }
            if (isScratch(pnl,
                    trade.averageEntryPrice() == null ? null : trade.averageEntryPrice().doubleValue(),
                    trade.filledQuantity())) {
                continue;
            }
            losses++;
        }
        return losses;
    }

    private boolean isScratch(double pnl, Double entryPrice, Integer quantity) {
        if (pnl >= 0 || entryPrice == null || quantity == null || entryPrice <= 0 || quantity <= 0) {
            return false;
        }
        double notional = entryPrice * quantity;
        return -pnl < notional * scratchLossPct / 100.0;
    }

    /**
     * Whether the setup won or lost on price: gross P&L when recorded. Net P&L
     * also carries ~0.13% round-trip charges, which would turn every
     * break-even exit into a "real loss"; money limits (daily loss) use net.
     */
    private static Double priceOutcome(PaperTrade trade) {
        return trade.getGrossProfitLoss() != null
                ? trade.getGrossProfitLoss()
                : trade.getProfitLoss();
    }

    private boolean isRealLoss(double pnl, Double entryPrice, Integer quantity) {
        return pnl < 0 && !isScratch(pnl, entryPrice, quantity);
    }

    private boolean maxLossesPerSymbolPerDayReached(String symbol) {

        if (maxLossesPerSymbolPerDay <= 0) {
            return false;
        }

        LocalDate today = LocalDate.now();

        long lossesToday =
                repository.findBySymbol(symbol)
                        .stream()
                        .filter(trade ->
                                trade.getExitTime() != null
                                        && trade.getExitTime().toLocalDate().equals(today))
                        .filter(trade ->
                                priceOutcome(trade) != null
                                        && isRealLoss(priceOutcome(trade), trade.getEntryPrice(), trade.getQuantity()))
                        .count();

        log.info(
                "Symbol = {}, Symbol Losses Today = {}, Max Losses Per Symbol Per Day = {}",
                symbol,
                lossesToday,
                maxLossesPerSymbolPerDay);

        return lossesToday >= maxLossesPerSymbolPerDay;
    }

    private static RiskCheckResponse response(List<String> violations) {
        return RiskCheckResponse.builder()
                .allowed(violations.isEmpty())
                .violations(violations)
                .build();
    }

    private boolean dailyLossExceeded() {

        LocalDate today =
                LocalDate.now();

        double todayPnL =
                repository.findAll()
                        .stream()
                        .filter(trade ->
                                trade.getExitTime() != null)
                        .filter(trade ->
                                trade.getExitTime()
                                        .toLocalDate()
                                        .equals(today))
                        .filter(trade ->
                                trade.getProfitLoss() != null)
                        .mapToDouble(
                                PaperTrade::getProfitLoss)
                        .sum();

        log.info(
                "Today's PnL = {}",
                todayPnL);

        return todayPnL <= -maxDailyLoss;
    }

    private boolean maxTradesPerSymbolPerDayReached(String symbol) {

        LocalDate today = LocalDate.now();

        long tradesToday =
                repository.findBySymbol(symbol)
                        .stream()
                        .filter(trade ->
                                trade.getEntryTime() != null)
                        .filter(trade ->
                                trade.getEntryTime()
                                        .toLocalDate()
                                        .equals(today))
                        .count();

        log.info(
                "Symbol = {}, Symbol Trades Today = {}, Max Per Symbol Per Day = {}",
                symbol,
                tradesToday,
                maxTradesPerSymbolPerDay);

        return tradesToday >= maxTradesPerSymbolPerDay;
    }

    private boolean maxOpenTradesReached() {

        long openTrades =
                repository.countByStatus(
                        SignalStatus.OPEN);

        log.info(
                "Open Trades = {}, Max Open Trades = {}",
                openTrades,
                maxOpenTrades);

        return openTrades >= maxOpenTrades;
    }

    private boolean consecutiveLossesExceeded() {

        if (maxConsecutiveLosses <= 0) {
            return false;
        }

        List<PaperTrade> trades =
                repository.findAllByOrderByEntryTimeDesc();

        int consecutiveLosses = 0;
        LocalDate today = LocalDate.now();

        for (PaperTrade trade : trades) {

            // The streak is a daily breaker, as in live mode; an older streak
            // would otherwise block entries forever (no new trade can reset it).
            if (trade.getEntryTime() == null
                    || !trade.getEntryTime().toLocalDate().equals(today)) {
                break;
            }

            Double outcome = priceOutcome(trade);

            if (outcome == null) {
                continue;
            }

            if (isScratch(outcome, trade.getEntryPrice(), trade.getQuantity())) {
                continue;
            }

            if (outcome < 0) {

                consecutiveLosses++;

                if (consecutiveLosses
                        >= maxConsecutiveLosses) {

                    log.warn(
                            "Consecutive losses reached = {}",
                            consecutiveLosses);

                    return true;
                }

            } else {

                break;
            }
        }

        return false;
    }
}
