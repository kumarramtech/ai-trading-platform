package com.ram.trading.trade.execution.risk;

import com.ram.trading.trade.execution.dto.TradeExecutionRequest;
import com.ram.trading.trade.execution.repository.ExecutedTradeRepository;
import com.ram.trading.trade.execution.repository.ExecutionIdempotencyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Last line of defence before a new live entry reaches the broker. Signal Engine
 * owns sizing, trading risk (including the daily loss limit) and all session
 * timing; this guard only enforces hard execution backstops, so a sizing bug
 * cannot exceed the pilot budget. A limit of zero disables it.
 *
 * It also defines the trading day used to recognise state left over from an
 * earlier session. Persisted timestamps are written in the JVM default zone, so
 * the day boundary is computed in the market zone and converted back.
 */
@Component
public class ExecutionRiskGuard {

    private final ExecutedTradeRepository executedTradeRepository;
    private final ExecutionIdempotencyRepository idempotencyRepository;
    private final ZoneId marketZone;
    private final BigDecimal maxMarginPerTrade;
    private final int maxOpenPositions;
    private final int maxEntriesPerDay;
    private final Clock clock;

    @Autowired
    public ExecutionRiskGuard(
            ExecutedTradeRepository executedTradeRepository,
            ExecutionIdempotencyRepository idempotencyRepository,
            @Value("${execution.market.zone:Asia/Kolkata}") String marketZone,
            @Value("${execution.risk.max-margin-per-trade:10000}") BigDecimal maxMarginPerTrade,
            @Value("${execution.risk.max-open-positions:2}") int maxOpenPositions,
            @Value("${execution.risk.max-entries-per-day:6}") int maxEntriesPerDay) {
        this(executedTradeRepository, idempotencyRepository, marketZone, maxMarginPerTrade,
                maxOpenPositions, maxEntriesPerDay, Clock.systemDefaultZone());
    }

    public ExecutionRiskGuard(
            ExecutedTradeRepository executedTradeRepository,
            ExecutionIdempotencyRepository idempotencyRepository,
            String marketZone,
            BigDecimal maxMarginPerTrade,
            int maxOpenPositions,
            int maxEntriesPerDay,
            Clock clock) {
        this.executedTradeRepository = executedTradeRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.marketZone = ZoneId.of(marketZone);
        this.maxMarginPerTrade = maxMarginPerTrade;
        this.maxOpenPositions = maxOpenPositions;
        this.maxEntriesPerDay = maxEntriesPerDay;
        this.clock = clock;
    }

    /**
     * Returns a rejection reason, or null when a new live entry may be claimed.
     * Blocking (JDBC): call on a blocking-capable scheduler, serialized with the claim.
     */
    public String evaluateNewEntry(TradeExecutionRequest request) {
        BigDecimal margin = request.getRequiredMargin();
        if (isEnabled(maxMarginPerTrade)) {
            if (margin == null || margin.compareTo(BigDecimal.ZERO) <= 0) {
                return "requiredMargin is required to enforce the per-trade margin limit";
            }
            if (margin.compareTo(maxMarginPerTrade) > 0) {
                return "requiredMargin " + margin + " exceeds the per-trade limit " + maxMarginPerTrade;
            }
        }

        // Never hold two positions in one symbol: their stops and exits would interfere.
        String symbol = request.getSymbol();
        if (symbol != null && !symbol.isBlank()) {
            int activeForSymbol = executedTradeRepository.countActivePositionsForSymbol(symbol)
                    + idempotencyRepository.countUnresolvedClaimsWithoutTradeForSymbol(symbol);
            if (activeForSymbol > 0) {
                return "An open/pending position already exists for " + symbol;
            }
        }

        if (maxOpenPositions > 0) {
            int active = executedTradeRepository.countActivePositions()
                    + idempotencyRepository.countUnresolvedClaimsWithoutTrade();
            if (active >= maxOpenPositions) {
                return "Open/pending positions " + active + " already at the limit " + maxOpenPositions;
            }
        }

        if (maxEntriesPerDay > 0) {
            int entriesToday = idempotencyRepository.countEntryClaimsSince(sessionStart());
            if (entriesToday >= maxEntriesPerDay) {
                return "Entries today " + entriesToday + " already at the daily limit " + maxEntriesPerDay;
            }
        }
        return null;
    }

    /** Start of the current market-zone trading day, expressed in the JVM zone used for persistence. */
    public LocalDateTime sessionStart() {
        LocalDate today = LocalDate.now(clock.withZone(marketZone));
        return today.atStartOfDay(marketZone)
                .withZoneSameInstant(ZoneId.systemDefault())
                .toLocalDateTime();
    }

    /** True when a persisted (JVM-zone) timestamp belongs to an earlier trading day. */
    public boolean isPriorSession(LocalDateTime persistedTime) {
        return persistedTime != null && persistedTime.isBefore(sessionStart());
    }

    private static boolean isEnabled(BigDecimal limit) {
        return limit != null && limit.compareTo(BigDecimal.ZERO) > 0;
    }
}
