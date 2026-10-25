-- Broker-side protective stop order tracking. Apply before deploying this version.
ALTER TABLE executed_trades
    ADD COLUMN protective_order_id VARCHAR(64) NULL,
    ADD COLUMN protective_order_status VARCHAR(32) NULL,
    ADD COLUMN protective_order_quantity INT NULL,
    ADD COLUMN protective_trigger_price DECIMAL(20,8) NULL,
    ADD COLUMN protective_order_tag VARCHAR(32) NULL,
    ADD KEY idx_executed_trade_protective_order (protective_order_id);
