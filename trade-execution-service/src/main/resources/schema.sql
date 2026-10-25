CREATE TABLE IF NOT EXISTS trade_execution_idempotency (
                                                           signal_id BIGINT NOT NULL,
                                                           tag VARCHAR(32) NOT NULL,
    symbol VARCHAR(64) NOT NULL,
    trade_signal VARCHAR(8) NOT NULL,
    quantity INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    order_id VARCHAR(64) NULL,
    filled_quantity INT NULL,
    average_price DECIMAL(20,8) NULL,
    rejection_reason VARCHAR(1000) NULL,
    instrument_key VARCHAR(128) NULL,
    entry_price DECIMAL(20,8) NULL,
    required_margin DECIMAL(20,8) NULL,
    stop_loss DECIMAL(20,8) NULL,
    target_price DECIMAL(20,8) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (signal_id),
    UNIQUE KEY uk_trade_execution_tag (tag),
    KEY idx_trade_execution_order_id (order_id)
    );


CREATE TABLE IF NOT EXISTS executed_trades (
                                               id BIGINT NOT NULL AUTO_INCREMENT,
                                               signal_id BIGINT NOT NULL,
                                               symbol VARCHAR(64) NOT NULL,
    instrument_key VARCHAR(128) NULL,
    trade_signal VARCHAR(8) NOT NULL,

    requested_quantity INT NOT NULL,
    filled_quantity INT NOT NULL DEFAULT 0,

    average_entry_price DECIMAL(20,8) NULL,
    initial_stop_loss DECIMAL(20,8) NULL,
    current_stop_loss DECIMAL(20,8) NULL,
    target_price DECIMAL(20,8) NULL,
    target_extended BOOLEAN NOT NULL DEFAULT FALSE,

    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',

    entry_order_id VARCHAR(64) NULL,
    protective_order_id VARCHAR(64) NULL,
    protective_order_status VARCHAR(32) NULL,
    protective_order_quantity INT NULL,
    protective_trigger_price DECIMAL(20,8) NULL,
    protective_order_tag VARCHAR(32) NULL,
    exit_order_id VARCHAR(64) NULL,
    exit_order_quantity INT NULL,
    exit_attempt INT NOT NULL DEFAULT 0,

    -- Partial-exit tracking
    exit_filled_quantity INT NOT NULL DEFAULT 0,
    remaining_quantity INT NOT NULL DEFAULT 0,
    exit_average_price DECIMAL(20,8) NULL,

    -- Exit summary
    exit_price DECIMAL(20,8) NULL,
    realized_pnl DECIMAL(20,8) NULL,
    exit_reason VARCHAR(32) NULL,

    opened_at DATETIME NULL,
    closed_at DATETIME NULL,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_executed_trade_signal (signal_id),
    KEY idx_executed_trade_status (status),
    KEY idx_executed_trade_symbol_status (symbol, status),
    KEY idx_executed_trade_entry_order (entry_order_id),
    KEY idx_executed_trade_protective_order (protective_order_id),
    KEY idx_executed_trade_exit_order (exit_order_id),
    KEY idx_executed_trade_opened_at (opened_at)
    ) ENGINE=InnoDB
    DEFAULT CHARSET=utf8mb4
    COLLATE=utf8mb4_0900_ai_ci;

