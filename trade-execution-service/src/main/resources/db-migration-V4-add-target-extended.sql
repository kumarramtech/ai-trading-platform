-- Apply once to an existing database before deploying the target-extension flow.
-- New installations should use the updated schema.sql instead.
ALTER TABLE executed_trades
    ADD COLUMN target_extended BOOLEAN NOT NULL DEFAULT FALSE AFTER target_price;
