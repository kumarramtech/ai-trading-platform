-- Apply once to an existing database before deploying Step 8.
-- New installations should use the updated schema.sql instead.
ALTER TABLE executed_trades
    ADD COLUMN exit_attempt INT NOT NULL DEFAULT 0 AFTER exit_order_quantity;

-- Existing rows stay at attempt 0. Pending legacy exits therefore keep their
-- original AI-EXIT-{signalId}-{previousExitFilled}-{quantity} recovery tag.
-- New exit claims increment exit_attempt before generating a new broker tag.
