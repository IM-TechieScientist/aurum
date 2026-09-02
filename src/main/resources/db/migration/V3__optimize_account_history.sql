DROP INDEX IF EXISTS ledger_entry_account_history;

CREATE INDEX ledger_entry_account_history
    ON ledger_entry (account_id, created_at DESC, transaction_id DESC);
