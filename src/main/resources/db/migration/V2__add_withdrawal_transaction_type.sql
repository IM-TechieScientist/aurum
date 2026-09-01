ALTER TABLE ledger_transaction
    DROP CONSTRAINT ledger_transaction_type_valid;

ALTER TABLE ledger_transaction
    ADD CONSTRAINT ledger_transaction_type_valid
        CHECK (transaction_type IN ('FUNDING', 'WITHDRAWAL', 'TRANSFER', 'REVERSAL'));
