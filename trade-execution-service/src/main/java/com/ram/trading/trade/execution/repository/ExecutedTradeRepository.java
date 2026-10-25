package com.ram.trading.trade.execution.repository;

import com.ram.trading.trade.execution.dto.ExecutedTradeRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class ExecutedTradeRepository {

    private final JdbcTemplate jdbcTemplate;

    public ExecutedTradeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<ExecutedTradeRecord> rowMapper = (rs, rowNum) ->
            ExecutedTradeRecord.builder()
                    .id(rs.getLong("id"))
                    .signalId(rs.getLong("signal_id"))
                    .symbol(rs.getString("symbol"))
                    .instrumentKey(rs.getString("instrument_key"))
                    .side(rs.getString("trade_signal"))
                    .requestedQuantity(rs.getInt("requested_quantity"))
                    .filledQuantity(rs.getInt("filled_quantity"))
                    .averageEntryPrice(rs.getBigDecimal("average_entry_price"))
                    .initialStopLoss(rs.getBigDecimal("initial_stop_loss"))
                    .currentStopLoss(rs.getBigDecimal("current_stop_loss"))
                    .targetPrice(rs.getBigDecimal("target_price"))
                    .targetExtended(rs.getBoolean("target_extended"))
                    .status(rs.getString("status"))
                    .entryOrderId(rs.getString("entry_order_id"))
                    .protectiveOrderId(rs.getString("protective_order_id"))
                    .protectiveOrderStatus(rs.getString("protective_order_status"))
                    .protectiveOrderQuantity(rs.getObject("protective_order_quantity", Integer.class))
                    .protectiveTriggerPrice(rs.getBigDecimal("protective_trigger_price"))
                    .protectiveOrderTag(rs.getString("protective_order_tag"))
                    .exitOrderId(rs.getString("exit_order_id"))
                    .exitOrderQuantity(rs.getObject("exit_order_quantity", Integer.class))
                    .exitAttempt(rs.getObject("exit_attempt", Integer.class))
                    .exitFilledQuantity(rs.getInt("exit_filled_quantity"))
                    .remainingQuantity(rs.getInt("remaining_quantity"))
                    .exitAveragePrice(rs.getBigDecimal("exit_average_price"))
                    .exitPrice(rs.getBigDecimal("exit_price"))
                    .realizedPnl(rs.getBigDecimal("realized_pnl"))
                    .exitReason(rs.getString("exit_reason"))
                    .openedAt(toLocalDateTime(rs.getTimestamp("opened_at")))
                    .closedAt(toLocalDateTime(rs.getTimestamp("closed_at")))
                    .createdAt(toLocalDateTime(rs.getTimestamp("created_at")))
                    .updatedAt(toLocalDateTime(rs.getTimestamp("updated_at")))
                    .build();

    /**
     * Inserts a new trade when id is null; otherwise updates the existing trade.
     */
    public ExecutedTradeRecord save(ExecutedTradeRecord trade) {
        if (trade.getId() == null) {
            return insert(trade);
        }

        update(trade);
        return findById(trade.getId())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Executed trade not found after update: " + trade.getId()));
    }

    private ExecutedTradeRecord insert(ExecutedTradeRecord trade) {
        String sql = """
                INSERT INTO executed_trades (
                    signal_id, symbol, instrument_key, trade_signal,
                    requested_quantity, filled_quantity,
                    average_entry_price, initial_stop_loss, current_stop_loss,
                    target_price, status, entry_order_id, protective_order_id, protective_order_status,
                    protective_order_quantity, protective_trigger_price, protective_order_tag,
                    exit_order_id, exit_order_quantity,
                    exit_filled_quantity, remaining_quantity, exit_average_price,
                    exit_price, realized_pnl, exit_reason, opened_at, closed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    sql, Statement.RETURN_GENERATED_KEYS);

            int filledQuantity = trade.getFilledQuantity() == null
                    ? 0 : trade.getFilledQuantity();
            int exitFilledQuantity = trade.getExitFilledQuantity() == null
                    ? 0 : trade.getExitFilledQuantity();
            int remainingQuantity = trade.getRemainingQuantity() == null
                    ? Math.max(0, filledQuantity - exitFilledQuantity)
                    : trade.getRemainingQuantity();

            ps.setLong(1, trade.getSignalId());
            ps.setString(2, trade.getSymbol());
            ps.setString(3, trade.getInstrumentKey());
            ps.setString(4, trade.getSide());
            ps.setInt(5, trade.getRequestedQuantity());
            ps.setInt(6, filledQuantity);
            ps.setBigDecimal(7, trade.getAverageEntryPrice());
            ps.setBigDecimal(8, trade.getInitialStopLoss());
            ps.setBigDecimal(9, trade.getCurrentStopLoss());
            ps.setBigDecimal(10, trade.getTargetPrice());
            ps.setString(11, trade.getStatus());
            ps.setString(12, trade.getEntryOrderId());
            ps.setString(13, trade.getProtectiveOrderId());
            ps.setString(14, trade.getProtectiveOrderStatus());
            ps.setObject(15, trade.getProtectiveOrderQuantity(), java.sql.Types.INTEGER);
            ps.setBigDecimal(16, trade.getProtectiveTriggerPrice());
            ps.setString(17, trade.getProtectiveOrderTag());
            ps.setString(18, trade.getExitOrderId());
            ps.setObject(19, trade.getExitOrderQuantity(), java.sql.Types.INTEGER);
            ps.setInt(20, exitFilledQuantity);
            ps.setInt(21, remainingQuantity);
            ps.setBigDecimal(22, trade.getExitAveragePrice());
            ps.setBigDecimal(23, trade.getExitPrice());
            ps.setBigDecimal(24, trade.getRealizedPnl());
            ps.setString(25, trade.getExitReason());
            ps.setTimestamp(26, toTimestamp(trade.getOpenedAt()));
            ps.setTimestamp(27, toTimestamp(trade.getClosedAt()));

            return ps;
        }, keyHolder);

        if (keyHolder.getKey() == null) {
            throw new IllegalStateException(
                    "Failed to obtain generated ID for executed trade");
        }

        trade.setId(keyHolder.getKey().longValue());

        return findById(trade.getId())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Inserted trade could not be retrieved"));
    }

    private void update(ExecutedTradeRecord trade) {
        String sql = """
                UPDATE executed_trades
                SET symbol = ?,
                    instrument_key = COALESCE(?, instrument_key),
                    trade_signal = ?,
                    requested_quantity = ?,
                    filled_quantity = CASE
                        WHEN ? IS NULL THEN filled_quantity
                        ELSE GREATEST(filled_quantity, ?)
                    END,
                    average_entry_price = COALESCE(?, average_entry_price),
                    initial_stop_loss = COALESCE(?, initial_stop_loss),
                    current_stop_loss = COALESCE(?, current_stop_loss),
                    target_price = COALESCE(?, target_price),
                    status = CASE
                        WHEN UPPER(status) IN ('EXIT_PENDING', 'CLOSED') THEN status
                        ELSE ?
                    END,
                    entry_order_id = COALESCE(?, entry_order_id),
                    protective_order_id = COALESCE(?, protective_order_id),
                    protective_order_status = COALESCE(?, protective_order_status),
                    protective_order_quantity = COALESCE(?, protective_order_quantity),
                    protective_trigger_price = COALESCE(?, protective_trigger_price),
                    protective_order_tag = COALESCE(?, protective_order_tag),
                    exit_order_id = COALESCE(?, exit_order_id),
                    exit_order_quantity = COALESCE(?, exit_order_quantity),
                    exit_filled_quantity = CASE
                        WHEN ? IS NULL THEN exit_filled_quantity
                        ELSE GREATEST(exit_filled_quantity, ?)
                    END,
                    remaining_quantity = COALESCE(?, remaining_quantity),
                    exit_average_price = COALESCE(?, exit_average_price),
                    exit_price = COALESCE(?, exit_price),
                    realized_pnl = COALESCE(?, realized_pnl),
                    exit_reason = COALESCE(?, exit_reason),
                    opened_at = COALESCE(?, opened_at),
                    closed_at = COALESCE(?, closed_at),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND UPPER(status) NOT IN ('EXIT_PENDING', 'CLOSED')
                """;

        int rows = jdbcTemplate.update(sql,
                trade.getSymbol(),
                trade.getInstrumentKey(),
                trade.getSide(),
                trade.getRequestedQuantity(),
                trade.getFilledQuantity(),
                trade.getFilledQuantity(),
                trade.getAverageEntryPrice(),
                trade.getInitialStopLoss(),
                trade.getCurrentStopLoss(),
                trade.getTargetPrice(),
                trade.getStatus(),
                trade.getEntryOrderId(),
                trade.getProtectiveOrderId(),
                trade.getProtectiveOrderStatus(),
                trade.getProtectiveOrderQuantity(),
                trade.getProtectiveTriggerPrice(),
                trade.getProtectiveOrderTag(),
                trade.getExitOrderId(),
                trade.getExitOrderQuantity(),
                trade.getExitFilledQuantity(),
                trade.getExitFilledQuantity(),
                trade.getRemainingQuantity(),
                trade.getExitAveragePrice(),
                trade.getExitPrice(),
                trade.getRealizedPnl(),
                trade.getExitReason(),
                toTimestamp(trade.getOpenedAt()),
                toTimestamp(trade.getClosedAt()),
                trade.getId());

        if (rows == 0) {
            boolean exists = findById(trade.getId()).isPresent();

            if (!exists) {
                throw new IllegalStateException(
                        "Executed trade not found for ID " + trade.getId());
            }
            ExecutedTradeRecord current = findById(trade.getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Executed trade disappeared during update: " + trade.getId()));
            if ("EXIT_PENDING".equalsIgnoreCase(current.getStatus())) {
                throw new IllegalStateException(
                        "Generic trade update refused because exit reconciliation is pending. SignalId="
                                + current.getSignalId());
            }
            // CLOSED records remain immutable; an unchanged active record may also report zero rows.
        } else if (rows != 1) {
            throw new IllegalStateException(
                    "Expected to update one executed trade, but updated "
                            + rows + " rows for ID " + trade.getId());
        }
    }

    public Optional<ExecutedTradeRecord> findById(Long id) {
        List<ExecutedTradeRecord> trades = jdbcTemplate.query(
                "SELECT * FROM executed_trades WHERE id = ?",
                rowMapper,
                id);

        return trades.stream().findFirst();
    }

    public Optional<ExecutedTradeRecord> findBySignalId(Long signalId) {
        List<ExecutedTradeRecord> trades = jdbcTemplate.query(
                "SELECT * FROM executed_trades WHERE signal_id = ?",
                rowMapper,
                signalId);

        return trades.stream().findFirst();
    }


    /**
     * Updates entry-fill details only while the trade is not being exited
     * or already closed. Exit quantities and exit financial fields are
     * deliberately not modified by this operation.
     */
    public boolean persistExitOrderDetailsIfPending(
            Long signalId,
            String orderId,
            int submittedQuantity) {

        if (signalId == null
                || orderId == null
                || orderId.isBlank()
                || submittedQuantity <= 0) {
            throw new IllegalArgumentException(
                    "Valid signal ID, exit order ID and quantity are required");
        }

        int updated = jdbcTemplate.update("""
            UPDATE executed_trades
            SET exit_order_id = ?,
                exit_order_quantity = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE signal_id = ?
              AND UPPER(status) = 'EXIT_PENDING'
              AND (
                    exit_order_id IS NULL
                    OR TRIM(exit_order_id) = ''
                    OR exit_order_id = ?
              )
              AND (
                    exit_order_quantity IS NULL
                    OR exit_order_quantity = ?
              )
            """,
                orderId,
                submittedQuantity,
                signalId,
                orderId,
                submittedQuantity);

        if (updated > 1) {
            throw new IllegalStateException(
                    "Multiple trades found for signal ID " + signalId);
        }

        if (updated == 1) {
            return true;
        }

        // A zero-row update may mean the same values were already persisted.
        // Accept only an exact match in the expected state.
        ExecutedTradeRecord current = findBySignalId(signalId)
                .orElseThrow(() -> new IllegalStateException(
                        "Trade not found while persisting exit order details. "
                                + "SignalId=" + signalId));

        return "EXIT_PENDING".equalsIgnoreCase(current.getStatus())
                && orderId.equals(current.getExitOrderId())
                && current.getExitOrderQuantity() != null
                && current.getExitOrderQuantity() == submittedQuantity;
    }



    /**
     * Atomically updates internal protection levels for an OPEN trade.
     * Stop-loss movement is monotonic: BUY stops may only move up; SELL stops
     * may only move down. EXIT_PENDING/CLOSED trades cannot be modified.
     */
    @Transactional
    public ExecutedTradeRecord updateProtectionLevels(
            Long signalId, BigDecimal newStopLoss, BigDecimal newTargetPrice) {
        return updateProtectionLevels(signalId, newStopLoss, newTargetPrice, null);
    }

    @Transactional
    public ExecutedTradeRecord updateProtectionLevels(
            Long signalId, BigDecimal newStopLoss, BigDecimal newTargetPrice,
            Boolean targetExtended) {

        if (signalId == null || (newStopLoss == null && newTargetPrice == null)) {
            throw new IllegalArgumentException(
                    "signalId and at least one protection price are required");
        }
        if (Boolean.TRUE.equals(targetExtended) && newTargetPrice == null) {
            throw new IllegalArgumentException(
                    "targetPrice is required when marking a target extension");
        }
        if (newStopLoss != null && newStopLoss.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("stopLoss must be positive");
        }
        if (newTargetPrice != null && newTargetPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("targetPrice must be positive");
        }

        ExecutedTradeRecord trade;
        try {
            trade = jdbcTemplate.queryForObject(
                    "SELECT * FROM executed_trades WHERE signal_id = ? FOR UPDATE",
                    rowMapper, signalId);
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            throw new IllegalArgumentException(
                    "Executed trade not found for signalId=" + signalId, ex);
        }

        if (trade == null || !"OPEN".equalsIgnoreCase(trade.getStatus())) {
            throw new IllegalStateException(
                    "Protection levels can only be updated for OPEN trades. signalId=" + signalId);
        }

        String side = trade.getSide() == null ? "" : trade.getSide().trim().toUpperCase();
        BigDecimal entry = trade.getAverageEntryPrice();
        if ((!("BUY".equals(side) || "SELL".equals(side)))
                || entry == null || entry.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException(
                    "Trade side or average entry price is invalid. signalId=" + signalId);
        }

        if (newStopLoss != null) {
            BigDecimal oldStop = trade.getCurrentStopLoss() != null
                    ? trade.getCurrentStopLoss() : trade.getInitialStopLoss();
            if (oldStop != null && (("BUY".equals(side) && newStopLoss.compareTo(oldStop) < 0)
                    || ("SELL".equals(side) && newStopLoss.compareTo(oldStop) > 0))) {
                throw new IllegalArgumentException(
                        "Stop-loss cannot move in a direction that increases risk");
            }
        }

        if (newTargetPrice != null) {
            if ("BUY".equals(side) && newTargetPrice.compareTo(entry) <= 0) {
                throw new IllegalArgumentException("BUY targetPrice must be above entry price");
            }
            if ("SELL".equals(side) && newTargetPrice.compareTo(entry) >= 0) {
                throw new IllegalArgumentException("SELL targetPrice must be below entry price");
            }
        }

        if (Boolean.TRUE.equals(targetExtended)
                && Boolean.TRUE.equals(trade.getTargetExtended())) {
            if (trade.getTargetPrice() != null
                    && trade.getTargetPrice().compareTo(newTargetPrice) == 0) {
                // Idempotent repeat of the same confirmed extension. Continue so
                // a simultaneous monotonic stop-loss update is not discarded.
                targetExtended = null;
            } else {
                throw new IllegalStateException(
                        "Target extension has already been committed for signalId=" + signalId);
            }
        }

        if (Boolean.TRUE.equals(targetExtended)) {
            BigDecimal oldTarget = trade.getTargetPrice();
            if (oldTarget == null
                    || ("BUY".equals(side) && newTargetPrice.compareTo(oldTarget) <= 0)
                    || ("SELL".equals(side) && newTargetPrice.compareTo(oldTarget) >= 0)) {
                throw new IllegalArgumentException(
                        "Target extension must move the target farther in the profitable direction");
            }
        }

        int updated;
        if (Boolean.TRUE.equals(targetExtended)) {
            updated = jdbcTemplate.update(
                    "UPDATE executed_trades SET current_stop_loss = COALESCE(?, current_stop_loss), "
                            + "target_price = COALESCE(?, target_price), target_extended = TRUE, "
                            + "updated_at = CURRENT_TIMESTAMP "
                            + "WHERE signal_id = ? AND UPPER(status) = 'OPEN'",
                    newStopLoss, newTargetPrice, signalId);
        } else {
            updated = jdbcTemplate.update(
                    "UPDATE executed_trades SET current_stop_loss = COALESCE(?, current_stop_loss), "
                            + "target_price = COALESCE(?, target_price), "
                            + "updated_at = CURRENT_TIMESTAMP "
                            + "WHERE signal_id = ? AND UPPER(status) = 'OPEN'",
                    newStopLoss, newTargetPrice, signalId);
        }
        if (updated != 1) {
            throw new IllegalStateException(
                    "Protection update lost its OPEN-state claim. signalId=" + signalId);
        }
        return findBySignalId(signalId).orElseThrow(() ->
                new IllegalStateException("Trade disappeared after protection update: " + signalId));
    }

    /** Persist broker-confirmed protective-stop state without changing trade lifecycle fields. */
    public ExecutedTradeRecord updateProtectiveOrderState(
            Long signalId, String orderId, String status, Integer quantity,
            BigDecimal triggerPrice, String tag) {
        if (signalId == null || status == null || status.isBlank()) {
            throw new IllegalArgumentException("signalId and protective status are required");
        }
        int updated = jdbcTemplate.update("""
                UPDATE executed_trades
                SET protective_order_id = COALESCE(?, protective_order_id),
                    protective_order_status = ?,
                    protective_order_quantity = COALESCE(?, protective_order_quantity),
                    protective_trigger_price = COALESCE(?, protective_trigger_price),
                    protective_order_tag = COALESCE(?, protective_order_tag),
                    updated_at = CURRENT_TIMESTAMP
                WHERE signal_id = ? AND UPPER(status) NOT IN ('CLOSED')
                """, orderId, status, quantity, triggerPrice, tag, signalId);
        if (updated != 1) {
            throw new IllegalStateException("Could not persist protective-order state for signalId=" + signalId);
        }
        return findBySignalId(signalId).orElseThrow(() ->
                new IllegalStateException("Trade disappeared after protective state update: " + signalId));
    }

    public List<ExecutedTradeRecord> findOpenTrades() {
        return jdbcTemplate.query("""
                SELECT * FROM executed_trades
                WHERE status = 'OPEN'
                ORDER BY opened_at DESC
                """, rowMapper);
    }


    /**
     * Updates entry-fill details only while the trade is not being exited
     * or already closed. Exit quantities and exit financial fields are
     * deliberately not modified by this operation.
     */
    public boolean updateEntryFillIfTradeIsActive(
            Long signalId,
            String orderId,
            int filledQuantity,
            BigDecimal averagePrice) {

        if (signalId == null
                || orderId == null
                || orderId.isBlank()
                || filledQuantity <= 0
                || averagePrice == null
                || averagePrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Valid entry-fill details are required");
        }

        int updated = jdbcTemplate.update("""
            UPDATE executed_trades
            SET filled_quantity = ?,
                average_entry_price = ?,
                remaining_quantity = ? - exit_filled_quantity,
                status = 'OPEN',
                updated_at = CURRENT_TIMESTAMP
            WHERE signal_id = ?
              AND entry_order_id = ?
              AND UPPER(status) NOT IN ('EXIT_PENDING', 'CLOSED')
              AND ? >= filled_quantity
              AND exit_filled_quantity >= 0
              AND exit_filled_quantity <= ?
            """,
                filledQuantity,
                averagePrice,
                filledQuantity,
                signalId,
                orderId,
                filledQuantity,
                filledQuantity);

        if (updated > 1) {
            throw new IllegalStateException(
                    "Multiple trades found for signal ID " + signalId);
        }

        return updated == 1;
    }


    /**
     * Records a confirmed partial entry fill while the broker entry order is
     * still non-terminal. The trade remains ENTRY_PENDING and cannot be claimed
     * by the normal exit path until cancellation/terminal status is confirmed.
     */
    public boolean updateEntryFillAsPending(
            Long signalId,
            String orderId,
            int filledQuantity,
            BigDecimal averagePrice) {

        if (signalId == null || orderId == null || orderId.isBlank()
                || filledQuantity <= 0 || averagePrice == null
                || averagePrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Valid pending entry-fill details are required");
        }

        int updated = jdbcTemplate.update("""
            UPDATE executed_trades
               SET filled_quantity = ?,
                   average_entry_price = ?,
                   remaining_quantity = ? - exit_filled_quantity,
                   status = 'ENTRY_PENDING',
                   updated_at = CURRENT_TIMESTAMP
             WHERE signal_id = ?
               AND entry_order_id = ?
               AND UPPER(status) = 'ENTRY_PENDING'
               AND ? >= filled_quantity
               AND exit_filled_quantity = 0
            """,
                filledQuantity, averagePrice, filledQuantity,
                signalId, orderId, filledQuantity);

        if (updated > 1) {
            throw new IllegalStateException("Multiple trades found for signal ID " + signalId);
        }
        return updated == 1;
    }

    public List<ExecutedTradeRecord> findEntryPendingTrades() {
        return jdbcTemplate.query("""
                SELECT * FROM executed_trades
                 WHERE UPPER(status) = 'ENTRY_PENDING'
                 ORDER BY updated_at ASC
                """, rowMapper);
    }

    public List<ExecutedTradeRecord> findClosedTrades() {
        return jdbcTemplate.query("""
                SELECT * FROM executed_trades
                WHERE status = 'CLOSED'
                ORDER BY closed_at DESC
                """, rowMapper);
    }

    /**
     * Atomically claims an open trade for exit processing.
     *
     * Returns true only when this call successfully claims the trade.
     * A concurrent request cannot claim the same OPEN trade.
     */
    public boolean claimExit(Long signalId, int exitQuantity) {
        if (signalId == null) {
            throw new IllegalArgumentException("Signal ID must not be null");
        }

        if (exitQuantity <= 0) {
            throw new IllegalArgumentException(
                    "Exit quantity must be greater than zero");
        }

        String sql = """
            UPDATE executed_trades
            SET status = 'EXIT_PENDING',
                exit_order_id = NULL,
                exit_order_quantity = ?,
                exit_attempt = COALESCE(exit_attempt, 0) + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE signal_id = ?
              AND UPPER(status) = 'OPEN'
              AND remaining_quantity >= ?
              AND remaining_quantity > 0
              AND filled_quantity > 0
              AND exit_filled_quantity >= 0
              AND exit_filled_quantity < filled_quantity
              AND remaining_quantity =
                  filled_quantity - exit_filled_quantity
            """;

        int rows = jdbcTemplate.update(
                sql, exitQuantity, signalId, exitQuantity);

        if (rows > 1) {
            throw new IllegalStateException(
                    "Multiple trades found for signal ID " + signalId);
        }

        return rows == 1;
    }


    public boolean releaseRejectedExitClaim(Long signalId) {
        if (signalId == null) {
            throw new IllegalArgumentException(
                    "Signal ID must not be null");
        }

        int rows = jdbcTemplate.update("""
                UPDATE executed_trades
                SET status = 'OPEN',
                    exit_order_id = NULL,
                    exit_order_quantity = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE signal_id = ?
                  AND UPPER(status) = 'EXIT_PENDING'
                  AND (exit_order_id IS NULL OR TRIM(exit_order_id) = '')
                  AND remaining_quantity > 0
                  AND filled_quantity > 0
                  AND exit_filled_quantity >= 0
                  AND exit_filled_quantity < filled_quantity
                  AND remaining_quantity = filled_quantity - exit_filled_quantity
                """, signalId);

        if (rows > 1) {
            throw new IllegalStateException(
                    "Multiple trades found for signal ID " + signalId);
        }

        return rows == 1;
    }

    /**
     * Releases an exit claim that never produced a broker order: still EXIT_PENDING
     * for the same attempt, no order ID, and claimed at least {@code minAgeSeconds}
     * ago (age measured by the database clock). The caller must already have
     * confirmed that broker history has no order under the attempt's tag. The
     * attempt number is kept, so the next claim builds a new tag.
     */
    public boolean releaseUnsubmittedExitClaim(Long signalId, int exitAttempt, long minAgeSeconds) {
        if (signalId == null) {
            throw new IllegalArgumentException(
                    "Signal ID must not be null");
        }

        int rows = jdbcTemplate.update("""
                UPDATE executed_trades
                SET status = 'OPEN',
                    exit_order_id = NULL,
                    exit_order_quantity = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE signal_id = ?
                  AND UPPER(status) = 'EXIT_PENDING'
                  AND COALESCE(exit_attempt, 0) = ?
                  AND (exit_order_id IS NULL OR TRIM(exit_order_id) = '')
                  AND updated_at <= TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP)
                  AND remaining_quantity > 0
                  AND filled_quantity > 0
                  AND exit_filled_quantity >= 0
                  AND exit_filled_quantity < filled_quantity
                  AND remaining_quantity = filled_quantity - exit_filled_quantity
                """, signalId, exitAttempt, -Math.max(0L, minAgeSeconds));

        if (rows > 1) {
            throw new IllegalStateException(
                    "Multiple trades found for signal ID " + signalId);
        }

        return rows == 1;
    }

    /**
     * Applies one terminal broker exit-order snapshot exactly once.
     * The row lock serializes concurrent reconciliation for the same trade.
     * This uses the existing executed_trades table; no additional table is required.
     */

    @Transactional
    public ExecutedTradeRecord applyExitFill(
            Long signalId,
            String orderId,
            int orderFilled,
            BigDecimal orderAverage,
            String brokerStatus) {

        // 1. Validate request inputs
        if (signalId == null) {
            throw new IllegalArgumentException(
                    "Signal ID must not be null");
        }

        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException(
                    "Exit order ID must not be blank");
        }

        if (brokerStatus == null || brokerStatus.isBlank()) {
            throw new IllegalArgumentException(
                    "Broker status must not be blank");
        }

        String normalizedStatus = brokerStatus.trim().toUpperCase();

        if (!List.of(
                "COMPLETE",
                "REJECTED",
                "CANCELLED",
                "CANCELED",
                "EXPIRED"
        ).contains(normalizedStatus)) {
            throw new IllegalArgumentException(
                    "Exit fill can only be applied for a terminal broker status: "
                            + brokerStatus);
        }

        // 2. Lock the trade row to prevent concurrent updates
        ExecutedTradeRecord trade = jdbcTemplate.queryForObject(
                "SELECT * FROM executed_trades WHERE signal_id = ? FOR UPDATE",
                rowMapper,
                signalId);

        if (trade == null) {
            throw new IllegalStateException(
                    "Executed trade not found for signal ID " + signalId);
        }

        // 3. Prevent reapplying a finalized exit order
        if (!"EXIT_PENDING".equalsIgnoreCase(trade.getStatus())) {
            if (orderId.equals(trade.getExitOrderId())) {
                return trade;
            }

            throw new IllegalStateException(
                    "Trade is not EXIT_PENDING; refusing to apply exit fill again");
        }

        // 4. Verify the order matches the persisted exit order
        if (!orderId.equals(trade.getExitOrderId())) {
            throw new IllegalStateException(
                    "Exit order ID does not match the order claimed for this trade");
        }

        // 5. Validate entry details before calculating P&L
        if (trade.getAverageEntryPrice() == null
                || trade.getAverageEntryPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException(
                    "Cannot calculate realized P&L without a valid entry price");
        }

        if (!"BUY".equalsIgnoreCase(trade.getSide())
                && !"SELL".equalsIgnoreCase(trade.getSide())) {
            throw new IllegalStateException(
                    "Cannot calculate realized P&L for an invalid trade side: "
                            + trade.getSide());
        }

        // 6. Validate the exit fill against the submitted order
        //    quantity and the remaining position.

        int entryFilled = trade.getFilledQuantity() == null
                ? 0
                : trade.getFilledQuantity();

        int previousExitFilled = trade.getExitFilledQuantity() == null
                ? 0
                : trade.getExitFilledQuantity();

        // Validate fill quantities before calculating the remaining position.
        if (entryFilled <= 0
                || previousExitFilled < 0
                || previousExitFilled > entryFilled) {
            throw new IllegalStateException(
                    "Invalid entry or previous exit fill quantities. SignalId="
                            + signalId
                            + ", EntryFilled=" + entryFilled
                            + ", PreviousExitFilled=" + previousExitFilled);
        }

        int remainingBeforeExit = entryFilled - previousExitFilled;

        // Verify that persisted position state agrees with the fill ledger.
        Integer persistedRemaining = trade.getRemainingQuantity();

        if (persistedRemaining == null
                || persistedRemaining != remainingBeforeExit) {
            throw new IllegalStateException(
                    "Persisted remaining quantity is inconsistent with "
                            + "entry and exit fills. SignalId=" + signalId
                            + ", PersistedRemaining=" + persistedRemaining
                            + ", ExpectedRemaining=" + remainingBeforeExit
                            + ". Manual reconciliation required.");
        }

        Integer submittedExitQuantity = trade.getExitOrderQuantity();


        if (submittedExitQuantity == null || submittedExitQuantity <= 0) {
            throw new IllegalStateException(
                    "Persisted exit order quantity is missing or invalid. "
                            + "Manual reconciliation required. SignalId="
                            + signalId);
        }

        if (entryFilled <= 0
                || previousExitFilled < 0
                || previousExitFilled > entryFilled
                || remainingBeforeExit <= 0
                || orderFilled < 0
                || orderFilled > submittedExitQuantity
                || orderFilled > remainingBeforeExit) {

            throw new IllegalStateException(
                    "Exit fill is invalid or exceeds the submitted order "
                            + "quantity or remaining position. SignalId="
                            + signalId
                            + ", OrderId=" + orderId
                            + ", OrderFilled=" + orderFilled
                            + ", SubmittedQuantity=" + submittedExitQuantity
                            + ", RemainingQuantity=" + remainingBeforeExit);
        }

        if (orderFilled > 0
                && (orderAverage == null
                || orderAverage.compareTo(BigDecimal.ZERO) <= 0)) {
            throw new IllegalArgumentException(
                    "A positive exit fill requires a valid average price");
        }

        // 7-8. Calculate cumulative exit quantity, average and realized P&L
        ExitFillOutcome outcome = computeExitFill(trade, previousExitFilled, orderFilled, orderAverage);
        int remaining = entryFilled - outcome.totalExitFilled();
        BigDecimal newAverage = outcome.exitAverage();

        // 9. Update the trade state
        trade.setExitFilledQuantity(outcome.totalExitFilled());
        trade.setRemainingQuantity(remaining);
        trade.setExitAveragePrice(newAverage);
        trade.setRealizedPnl(outcome.realizedPnl());

        trade.setExitReason(
                "COMPLETE".equals(normalizedStatus)
                        ? "EXIT_FILLED"
                        : "EXIT_" + normalizedStatus);

        if (remaining == 0) {
            trade.setStatus("CLOSED");
            trade.setExitPrice(newAverage);
            trade.setClosedAt(java.time.LocalDateTime.now());
        } else {
            trade.setStatus("OPEN");
            trade.setClosedAt(null);
        }

        // 10. Persist the update only if the trade is still EXIT_PENDING
        int updated = jdbcTemplate.update("""
                        UPDATE executed_trades
                        SET exit_filled_quantity = ?,
                            remaining_quantity = ?,
                            exit_average_price = ?,
                            exit_price = ?,
                            realized_pnl = ?,
                            exit_reason = ?,
                            status = ?,
                            closed_at = ?,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE signal_id = ?
                          AND UPPER(status) = 'EXIT_PENDING'
                        """,
                trade.getExitFilledQuantity(),
                trade.getRemainingQuantity(),
                trade.getExitAveragePrice(),
                trade.getExitPrice(),
                trade.getRealizedPnl(),
                trade.getExitReason(),
                trade.getStatus(),
                toTimestamp(trade.getClosedAt()),
                signalId);

        if (updated != 1) {
            throw new IllegalStateException(
                    "Expected to persist one EXIT_PENDING trade for signal ID "
                            + signalId + ", updated rows=" + updated);
        }

        return findBySignalId(signalId)
                .orElseThrow(() -> new IllegalStateException(
                        "Executed trade not found after exit-fill persistence: "
                                + signalId));
    }


    private record ExitFillOutcome(int totalExitFilled, BigDecimal exitAverage, BigDecimal realizedPnl) {
    }

    private static ExitFillOutcome computeExitFill(
            ExecutedTradeRecord trade, int previousExitFilled, int orderFilled, BigDecimal orderAverage) {

        int totalExitFilled = previousExitFilled + orderFilled;
        BigDecimal previousAverage = trade.getExitAveragePrice();
        BigDecimal newAverage = previousAverage;
        BigDecimal realizedDelta = BigDecimal.ZERO;

        if (orderFilled > 0) {
            BigDecimal previousNotional = previousAverage == null
                    ? BigDecimal.ZERO
                    : previousAverage.multiply(BigDecimal.valueOf(previousExitFilled));
            BigDecimal newNotional = previousNotional.add(
                    orderAverage.multiply(BigDecimal.valueOf(orderFilled)));
            newAverage = newNotional.divide(
                    BigDecimal.valueOf(totalExitFilled), 8, RoundingMode.HALF_UP);

            BigDecimal perUnit = "BUY".equalsIgnoreCase(trade.getSide())
                    ? orderAverage.subtract(trade.getAverageEntryPrice())
                    : trade.getAverageEntryPrice().subtract(orderAverage);
            realizedDelta = perUnit.multiply(BigDecimal.valueOf(orderFilled));
        }

        BigDecimal realized = (trade.getRealizedPnl() == null
                ? BigDecimal.ZERO : trade.getRealizedPnl()).add(realizedDelta);
        return new ExitFillOutcome(totalExitFilled, newAverage, realized);
    }

    /**
     * Books a fill of the broker-side protective stop order against an OPEN trade
     * exactly once. The stop is a real exit: without this the position would stay
     * OPEN locally after the broker closed it, and a later exit could reverse it.
     * Re-applying the same stop order is a no-op once FILL_APPLIED is recorded.
     */
    @Transactional
    public ExecutedTradeRecord applyProtectiveStopFill(
            Long signalId,
            String protectiveOrderId,
            int orderFilled,
            BigDecimal orderAverage) {

        if (signalId == null || protectiveOrderId == null || protectiveOrderId.isBlank()) {
            throw new IllegalArgumentException("Signal ID and protective order ID are required");
        }
        if (orderFilled <= 0 || orderAverage == null || orderAverage.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("A protective stop fill requires a positive quantity and price");
        }

        ExecutedTradeRecord trade;
        try {
            trade = jdbcTemplate.queryForObject(
                    "SELECT * FROM executed_trades WHERE signal_id = ? FOR UPDATE", rowMapper, signalId);
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            throw new IllegalStateException("Executed trade not found for signal ID " + signalId, ex);
        }

        if (!protectiveOrderId.equals(trade.getProtectiveOrderId())) {
            throw new IllegalStateException(
                    "Protective order ID does not match the order recorded for signalId=" + signalId);
        }
        if ("FILL_APPLIED".equalsIgnoreCase(trade.getProtectiveOrderStatus())) {
            return trade;
        }
        if (!"OPEN".equalsIgnoreCase(trade.getStatus())) {
            throw new IllegalStateException(
                    "Protective stop fill can only be booked against an OPEN trade. signalId="
                            + signalId + ", status=" + trade.getStatus());
        }
        if (trade.getAverageEntryPrice() == null
                || trade.getAverageEntryPrice().compareTo(BigDecimal.ZERO) <= 0
                || (!"BUY".equalsIgnoreCase(trade.getSide()) && !"SELL".equalsIgnoreCase(trade.getSide()))) {
            throw new IllegalStateException("Trade side or entry price is invalid. signalId=" + signalId);
        }

        int entryFilled = trade.getFilledQuantity() == null ? 0 : trade.getFilledQuantity();
        int previousExitFilled = trade.getExitFilledQuantity() == null ? 0 : trade.getExitFilledQuantity();
        int remainingBefore = entryFilled - previousExitFilled;
        Integer protectedQuantity = trade.getProtectiveOrderQuantity();

        if (entryFilled <= 0 || previousExitFilled < 0 || remainingBefore <= 0
                || trade.getRemainingQuantity() == null || trade.getRemainingQuantity() != remainingBefore
                || orderFilled > remainingBefore
                || (protectedQuantity != null && orderFilled > protectedQuantity)) {
            throw new IllegalStateException(
                    "Protective stop fill exceeds the open position or the ledger is inconsistent. signalId="
                            + signalId + ", orderFilled=" + orderFilled + ", remaining=" + remainingBefore
                            + ", protectedQuantity=" + protectedQuantity
                            + ". Manual reconciliation required.");
        }

        ExitFillOutcome outcome = computeExitFill(trade, previousExitFilled, orderFilled, orderAverage);
        int remaining = entryFilled - outcome.totalExitFilled();
        boolean closed = remaining == 0;

        int updated = jdbcTemplate.update("""
                        UPDATE executed_trades
                        SET exit_filled_quantity = ?,
                            remaining_quantity = ?,
                            exit_average_price = ?,
                            exit_price = ?,
                            realized_pnl = ?,
                            exit_reason = 'BROKER_STOP_LOSS',
                            exit_order_id = ?,
                            exit_order_quantity = ?,
                            status = ?,
                            closed_at = ?,
                            protective_order_status = 'FILL_APPLIED',
                            updated_at = CURRENT_TIMESTAMP
                        WHERE signal_id = ?
                          AND UPPER(status) = 'OPEN'
                          AND protective_order_id = ?
                        """,
                outcome.totalExitFilled(),
                remaining,
                outcome.exitAverage(),
                closed ? outcome.exitAverage() : null,
                outcome.realizedPnl(),
                protectiveOrderId,
                orderFilled,
                closed ? "CLOSED" : "OPEN",
                closed ? toTimestamp(java.time.LocalDateTime.now()) : null,
                signalId,
                protectiveOrderId);

        if (updated != 1) {
            throw new IllegalStateException(
                    "Expected to book one protective stop fill for signalId=" + signalId + ", updated=" + updated);
        }
        return findBySignalId(signalId).orElseThrow(() -> new IllegalStateException(
                "Trade disappeared after protective stop fill: " + signalId));
    }

    /**
     * Claims the right to modify an ACTIVE broker stop. Only one modification may be
     * in flight per position; a concurrent caller gets false and must not modify.
     * The persisted trigger is deliberately left unchanged until the broker confirms.
     */
    public boolean claimProtectiveModification(Long signalId, String protectiveOrderId) {
        int rows = jdbcTemplate.update("""
                UPDATE executed_trades
                SET protective_order_status = 'MODIFY_PENDING',
                    updated_at = CURRENT_TIMESTAMP
                WHERE signal_id = ?
                  AND protective_order_id = ?
                  AND UPPER(status) = 'OPEN'
                  AND UPPER(COALESCE(protective_order_status, '')) = 'ACTIVE'
                """, signalId, protectiveOrderId);
        if (rows > 1) {
            throw new IllegalStateException("Multiple trades found for signal ID " + signalId);
        }
        return rows == 1;
    }

    /**
     * Intraday (product I) positions cannot survive the trading session: the broker
     * squares them off and its order APIs only return the current day's orders.
     * Rows still active from an earlier session are therefore moved out of every
     * active/pending query so they can never trigger a new broker order, and are
     * returned for manual P&L review.
     */
    @Transactional
    public List<ExecutedTradeRecord> expirePriorSessionTrades(java.time.LocalDateTime sessionStart) {
        List<ExecutedTradeRecord> stale = jdbcTemplate.query("""
                SELECT * FROM executed_trades
                WHERE UPPER(status) IN ('OPEN', 'ENTRY_PENDING', 'EXIT_PENDING')
                  AND COALESCE(opened_at, created_at) < ?
                FOR UPDATE
                """, rowMapper, toTimestamp(sessionStart));
        if (stale.isEmpty()) {
            return stale;
        }
        jdbcTemplate.update("""
                UPDATE executed_trades
                SET status = 'EXPIRED_PRIOR_SESSION',
                    exit_reason = COALESCE(exit_reason, 'PRIOR_SESSION_UNRECONCILED'),
                    updated_at = CURRENT_TIMESTAMP
                WHERE UPPER(status) IN ('OPEN', 'ENTRY_PENDING', 'EXIT_PENDING')
                  AND COALESCE(opened_at, created_at) < ?
                """, toTimestamp(sessionStart));
        return stale;
    }

    /** Positions that hold or may soon hold broker exposure. */
    public int countActivePositions() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM executed_trades
                WHERE UPPER(status) IN ('OPEN', 'ENTRY_PENDING', 'EXIT_PENDING')
                """, Integer.class);
        return count == null ? 0 : count;
    }

    public int countActivePositionsForSymbol(String symbol) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM executed_trades
                WHERE UPPER(status) IN ('OPEN', 'ENTRY_PENDING', 'EXIT_PENDING')
                  AND UPPER(symbol) = UPPER(?)
                """, Integer.class, symbol);
        return count == null ? 0 : count;
    }

    /** Every position (any status) opened at or after the given time, newest first. */
    public List<ExecutedTradeRecord> findTradesOpenedSince(java.time.LocalDateTime since) {
        return jdbcTemplate.query("""
                SELECT * FROM executed_trades
                WHERE COALESCE(opened_at, created_at) >= ?
                ORDER BY COALESCE(opened_at, created_at) DESC
                """, rowMapper, toTimestamp(since));
    }

    /**
     * Retrieves trades whose exit processing was claimed but may not
     * have reached a confirmed terminal state.
     */
    public List<ExecutedTradeRecord> findExitPendingTrades() {
        return jdbcTemplate.query("""
                SELECT *
                FROM executed_trades
                WHERE UPPER(status) = 'EXIT_PENDING'
                ORDER BY updated_at ASC
                """, rowMapper);
    }

    private static Timestamp toTimestamp(java.time.LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static java.time.LocalDateTime toLocalDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
