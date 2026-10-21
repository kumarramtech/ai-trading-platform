-- Apply once to existing databases before deploying the startup-recovery gate.
-- New installations should use the updated schema.sql instead.
ALTER TABLE trade_execution_idempotency
    ADD COLUMN instrument_key VARCHAR(128) NULL,
    ADD COLUMN entry_price DECIMAL(20,8) NULL,
    ADD COLUMN required_margin DECIMAL(20,8) NULL,
    ADD COLUMN stop_loss DECIMAL(20,8) NULL,
    ADD COLUMN target_price DECIMAL(20,8) NULL;

-- Legacy unresolved rows will have NULL recovery metadata. Do not fabricate these
-- values: reconcile those orders against broker history and repair/close them
-- manually before expecting readiness to become READY.
