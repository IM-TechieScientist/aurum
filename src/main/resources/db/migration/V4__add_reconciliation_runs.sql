CREATE TABLE reconciliation_run (
    id UUID PRIMARY KEY,
    status VARCHAR(12) NOT NULL,
    accounts_scanned INTEGER NOT NULL,
    mismatch_count INTEGER NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT reconciliation_run_status_valid
        CHECK (status IN ('CONSISTENT', 'MISMATCHED')),
    CONSTRAINT reconciliation_run_counts_valid
        CHECK (accounts_scanned >= 0 AND mismatch_count >= 0
            AND mismatch_count <= accounts_scanned),
    CONSTRAINT reconciliation_run_status_matches_count
        CHECK ((status = 'CONSISTENT' AND mismatch_count = 0)
            OR (status = 'MISMATCHED' AND mismatch_count > 0)),
    CONSTRAINT reconciliation_run_time_valid CHECK (completed_at >= started_at)
);

CREATE INDEX reconciliation_run_recent
    ON reconciliation_run (completed_at DESC, id DESC);

CREATE TABLE reconciliation_run_mismatch (
    run_id UUID NOT NULL REFERENCES reconciliation_run(id),
    account_id UUID NOT NULL,
    currency CHAR(3) NOT NULL,
    projected_balance_minor BIGINT NOT NULL,
    ledger_balance_minor BIGINT NOT NULL,
    PRIMARY KEY (run_id, account_id),
    CONSTRAINT reconciliation_mismatch_currency_uppercase CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT reconciliation_mismatch_is_real
        CHECK (projected_balance_minor <> ledger_balance_minor)
);

CREATE TRIGGER reconciliation_run_immutable
    BEFORE UPDATE OR DELETE ON reconciliation_run
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER reconciliation_run_mismatch_immutable
    BEFORE UPDATE OR DELETE ON reconciliation_run_mismatch
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
