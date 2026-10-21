package com.ram.trading.trade.execution.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;

@Repository
@RequiredArgsConstructor
public class ExecutionIdempotencyRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Attempts to create the idempotency claim for a signal.
     *
     * @return true only when this call inserted the claim; false when a unique
     * key already exists (signal_id or tag).
     *
     * A duplicate-key result must be followed by a lookup/reconciliation by the
     * caller. It must never be interpreted as permission to submit another order.
     */
    public Mono<Boolean> tryClaim(
            Long signalId,
            String tag,
            String symbol,
            String signal,
            Integer quantity,
            String instrumentKey,
            BigDecimal entryPrice,
            BigDecimal requiredMargin,
            BigDecimal stopLoss,
            BigDecimal target) {

        return Mono.fromCallable(() -> {
            try {
                int rows = jdbcTemplate.update("""
                        INSERT INTO trade_execution_idempotency
                            (signal_id, tag, symbol, trade_signal, quantity, status,
                             instrument_key, entry_price, required_margin, stop_loss, target_price)
                        VALUES (?, ?, ?, ?, ?, 'PROCESSING', ?, ?, ?, ?, ?)
                        """, signalId, tag, symbol, signal, quantity,
                        instrumentKey, entryPrice, requiredMargin, stopLoss, target);

                if (rows != 1) {
                    throw new IllegalStateException(
                            "Expected to insert one idempotency claim for signal "
                                    + signalId + ", but inserted " + rows + " rows");
                }

                return true;
            } catch (DuplicateKeyException duplicate) {
                // Either signal_id or tag already exists. The caller must inspect
                // the existing record before deciding what to do next.
                return false;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ExecutionIdempotencyRecord> find(Long signalId) {
        return Mono.fromCallable(() -> jdbcTemplate.query(
                        """
                        SELECT signal_id, tag, symbol, trade_signal, quantity, status,
                               order_id, filled_quantity, average_price, rejection_reason,
                               instrument_key, entry_price, required_margin, stop_loss, target_price,
                               created_at, updated_at
                        FROM trade_execution_idempotency
                        WHERE signal_id = ?
                        """,
                        rs -> {
                            if (!rs.next()) {
                                return null;
                            }

                            return new ExecutionIdempotencyRecord(
                                    rs.getLong("signal_id"),
                                    rs.getString("tag"),
                                    rs.getString("symbol"),
                                    rs.getString("trade_signal"),
                                    rs.getInt("quantity"),
                                    rs.getString("status"),
                                    rs.getString("order_id"),
                                    (Integer) rs.getObject("filled_quantity"),
                                    rs.getBigDecimal("average_price"),
                                    rs.getString("rejection_reason"),
                                    rs.getString("instrument_key"),
                                    rs.getBigDecimal("entry_price"),
                                    rs.getBigDecimal("required_margin"),
                                    rs.getBigDecimal("stop_loss"),
                                    rs.getBigDecimal("target_price"),
                                    rs.getTimestamp("created_at") == null
                                            ? null
                                            : rs.getTimestamp("created_at").toLocalDateTime(),
                                    rs.getTimestamp("updated_at") == null
                                            ? null
                                            : rs.getTimestamp("updated_at").toLocalDateTime());
                        },
                        signalId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(Mono::justOrEmpty);
    }

    /**
     * Returns unresolved execution claims for operational monitoring.
     * These rows are diagnostic/reconciliation candidates only; callers must
     * never interpret them as permission to submit another broker order.
     */
    public Mono<java.util.List<ExecutionIdempotencyRecord>> findUnresolved() {
        return Mono.fromCallable(() -> jdbcTemplate.query(
                        """
                        SELECT signal_id, tag, symbol, trade_signal, quantity, status,
                               order_id, filled_quantity, average_price, rejection_reason,
                               instrument_key, entry_price, required_margin, stop_loss, target_price,
                               created_at, updated_at
                        FROM trade_execution_idempotency
                        WHERE UPPER(COALESCE(status, '')) IN
                              ('PROCESSING', 'ORDER_PLACED', 'PENDING', 'UNKNOWN', 'RECONCILIATION_REQUIRED')
                        ORDER BY updated_at ASC, signal_id ASC
                        """,
                        (rs, rowNum) -> new ExecutionIdempotencyRecord(
                                rs.getLong("signal_id"),
                                rs.getString("tag"),
                                rs.getString("symbol"),
                                rs.getString("trade_signal"),
                                rs.getInt("quantity"),
                                rs.getString("status"),
                                rs.getString("order_id"),
                                (Integer) rs.getObject("filled_quantity"),
                                rs.getBigDecimal("average_price"),
                                rs.getString("rejection_reason"),
                                rs.getString("instrument_key"),
                                rs.getBigDecimal("entry_price"),
                                rs.getBigDecimal("required_margin"),
                                rs.getBigDecimal("stop_loss"),
                                rs.getBigDecimal("target_price"),
                                rs.getTimestamp("created_at") == null
                                        ? null : rs.getTimestamp("created_at").toLocalDateTime(),
                                rs.getTimestamp("updated_at") == null
                                        ? null : rs.getTimestamp("updated_at").toLocalDateTime())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Finds terminal filled/partial-filled idempotency claims that have no persisted
     * execution trade. This can occur after a process crash or later data loss. The
     * broker order must be reconciled and the actual exposure persisted before startup
     * readiness can open.
     */
    public Mono<java.util.List<ExecutionIdempotencyRecord>> findFilledClaimsWithoutTrade() {
        return Mono.fromCallable(() -> jdbcTemplate.query(
                        """
                        SELECT e.signal_id, e.tag, e.symbol, e.trade_signal, e.quantity, e.status,
                               e.order_id, e.filled_quantity, e.average_price, e.rejection_reason,
                               e.instrument_key, e.entry_price, e.required_margin, e.stop_loss, e.target_price,
                               e.created_at, e.updated_at
                        FROM trade_execution_idempotency e
                        LEFT JOIN executed_trades t ON t.signal_id = e.signal_id
                        WHERE UPPER(COALESCE(e.status, '')) IN ('FILLED', 'PARTIAL_FILLED')
                          AND t.signal_id IS NULL
                        ORDER BY e.updated_at ASC, e.signal_id ASC
                        """,
                        (rs, rowNum) -> new ExecutionIdempotencyRecord(
                                rs.getLong("signal_id"),
                                rs.getString("tag"),
                                rs.getString("symbol"),
                                rs.getString("trade_signal"),
                                rs.getInt("quantity"),
                                rs.getString("status"),
                                rs.getString("order_id"),
                                (Integer) rs.getObject("filled_quantity"),
                                rs.getBigDecimal("average_price"),
                                rs.getString("rejection_reason"),
                                rs.getString("instrument_key"),
                                rs.getBigDecimal("entry_price"),
                                rs.getBigDecimal("required_margin"),
                                rs.getBigDecimal("stop_loss"),
                                rs.getBigDecimal("target_price"),
                                rs.getTimestamp("created_at") == null
                                        ? null : rs.getTimestamp("created_at").toLocalDateTime(),
                                rs.getTimestamp("updated_at") == null
                                        ? null : rs.getTimestamp("updated_at").toLocalDateTime())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> updateOrderPlaced(Long signalId, String orderId) {
        return update(signalId, "ORDER_PLACED", orderId, null, null, null);
    }

    public Mono<Void> updateFilled(
            Long signalId,
            String orderId,
            Integer filledQuantity,
            BigDecimal averagePrice) {
        return update(signalId, "FILLED", orderId, filledQuantity, averagePrice, null);
    }

    public Mono<Void> updatePending(
            Long signalId,
            String orderId,
            Integer filledQuantity,
            BigDecimal averagePrice,
            String reason) {
        return update(signalId, "PENDING", orderId, filledQuantity, averagePrice, reason);
    }

    /**
     * Marks a broker-terminal entry order that created a real but smaller-than-requested
     * position. This is a terminal idempotency outcome: it must not remain in the
     * unresolved-claim queue, and it must never authorize a replacement order.
     */
    public Mono<Void> updatePartialFilled(
            Long signalId,
            String orderId,
            Integer filledQuantity,
            BigDecimal averagePrice,
            String reason) {
        if (filledQuantity == null || filledQuantity <= 0) {
            return Mono.error(new IllegalArgumentException(
                    "Partial-filled claim requires a positive filled quantity"));
        }
        return update(signalId, "PARTIAL_FILLED", orderId, filledQuantity, averagePrice, reason);
    }

    public Mono<Void> updateRejected(Long signalId, String orderId, String reason) {
        return update(signalId, "REJECTED", orderId, null, null, reason);
    }

    public Mono<Void> updateStatus(
            Long signalId,
            String status,
            String orderId,
            Integer filledQuantity,
            BigDecimal averagePrice,
            String reason) {
        return update(signalId, status, orderId, filledQuantity, averagePrice, reason);
    }

    private Mono<Void> update(
            Long signalId,
            String status,
            String orderId,
            Integer filledQuantity,
            BigDecimal averagePrice,
            String reason) {

        return Mono.fromRunnable(() -> {
                    int rows = jdbcTemplate.update("""
                            UPDATE trade_execution_idempotency
                               SET status = ?,
                                   order_id = COALESCE(?, order_id),
                                   filled_quantity = COALESCE(?, filled_quantity),
                                   average_price = COALESCE(?, average_price),
                                   rejection_reason = COALESCE(?, rejection_reason),
                                   updated_at = CURRENT_TIMESTAMP
                             WHERE signal_id = ?
                            """,
                            status,
                            orderId,
                            filledQuantity,
                            averagePrice,
                            reason,
                            signalId);

                    if (rows == 0) {
                        Integer count = jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM trade_execution_idempotency WHERE signal_id = ?",
                                Integer.class,
                                signalId);

                        if (count == null || count == 0) {
                            throw new IllegalStateException(
                                    "Idempotency record not found for signal " + signalId);
                        }

                        // The row exists; MySQL may report zero changed rows when
                        // the update writes values identical to those already stored.
                        return;
                    }

                    if (rows != 1) {
                        throw new IllegalStateException(
                                "Expected to update at most one idempotency record for signal "
                                        + signalId + ", but updated " + rows + " rows");
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
