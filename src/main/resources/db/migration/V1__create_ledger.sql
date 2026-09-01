CREATE TABLE account (
    id UUID PRIMARY KEY,
    owner_name VARCHAR(120),
    currency CHAR(3) NOT NULL,
    account_type VARCHAR(20) NOT NULL,
    normal_side VARCHAR(6) NOT NULL,
    status VARCHAR(10) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT account_currency_uppercase CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT account_type_valid CHECK (account_type IN ('CUSTOMER', 'SETTLEMENT')),
    CONSTRAINT account_normal_side_valid CHECK (normal_side IN ('DEBIT', 'CREDIT')),
    CONSTRAINT account_status_valid CHECK (status IN ('ACTIVE', 'FROZEN')),
    CONSTRAINT customer_has_owner CHECK (account_type <> 'CUSTOMER' OR owner_name IS NOT NULL),
    CONSTRAINT account_type_normal_side CHECK (
        (account_type = 'CUSTOMER' AND normal_side = 'CREDIT') OR
        (account_type = 'SETTLEMENT' AND normal_side = 'DEBIT')
    )
);

CREATE UNIQUE INDEX one_settlement_account_per_currency
    ON account (currency) WHERE account_type = 'SETTLEMENT';

CREATE TABLE account_balance (
    account_id UUID PRIMARY KEY REFERENCES account(id),
    balance_minor BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE ledger_transaction (
    id UUID PRIMARY KEY,
    transaction_type VARCHAR(12) NOT NULL,
    reference VARCHAR(200),
    reversal_of UUID REFERENCES ledger_transaction(id),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ledger_transaction_type_valid
        CHECK (transaction_type IN ('FUNDING', 'TRANSFER', 'REVERSAL')),
    CONSTRAINT reversal_link_valid CHECK (
        (transaction_type = 'REVERSAL' AND reversal_of IS NOT NULL) OR
        (transaction_type <> 'REVERSAL' AND reversal_of IS NULL)
    ),
    CONSTRAINT one_reversal_per_transaction UNIQUE (reversal_of)
);

CREATE TABLE ledger_entry (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES ledger_transaction(id),
    account_id UUID NOT NULL REFERENCES account(id),
    direction VARCHAR(6) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ledger_entry_direction_valid CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ledger_entry_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ledger_entry_currency_uppercase CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX ledger_entry_account_history
    ON ledger_entry (account_id, created_at DESC, transaction_id);
CREATE INDEX ledger_entry_transaction
    ON ledger_entry (transaction_id);

CREATE TABLE idempotency_record (
    scope VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    transaction_id UUID REFERENCES ledger_transaction(id),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (scope, idempotency_key)
);

CREATE OR REPLACE FUNCTION validate_ledger_entry_currency()
RETURNS TRIGGER AS $$
DECLARE
    account_currency CHAR(3);
BEGIN
    SELECT currency INTO account_currency FROM account WHERE id = NEW.account_id;
    IF account_currency IS NULL OR account_currency <> NEW.currency THEN
        RAISE EXCEPTION 'ledger entry currency does not match account currency';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entry_currency_guard
    BEFORE INSERT ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION validate_ledger_entry_currency();

CREATE OR REPLACE FUNCTION validate_balanced_transaction()
RETURNS TRIGGER AS $$
DECLARE
    target_id UUID;
    entry_count BIGINT;
    currency_count BIGINT;
    debit_total NUMERIC;
    credit_total NUMERIC;
BEGIN
    target_id := NEW.id;

    SELECT COUNT(*), COUNT(DISTINCT currency),
           COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'CREDIT'), 0)
      INTO entry_count, currency_count, debit_total, credit_total
      FROM ledger_entry
     WHERE transaction_id = target_id;

    IF entry_count < 2 OR currency_count <> 1 OR debit_total <> credit_total THEN
        RAISE EXCEPTION 'transaction % is not a balanced single-currency posting', target_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ledger_transaction_balanced
    AFTER INSERT ON ledger_transaction
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_balanced_transaction();

CREATE OR REPLACE FUNCTION reject_ledger_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_transaction_immutable
    BEFORE UPDATE OR DELETE ON ledger_transaction
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER ledger_entry_immutable
    BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

INSERT INTO account (id, owner_name, currency, account_type, normal_side, status, created_at)
VALUES
    ('00000000-0000-0000-0000-000000000001', NULL, 'INR', 'SETTLEMENT', 'DEBIT', 'ACTIVE', NOW()),
    ('00000000-0000-0000-0000-000000000002', NULL, 'USD', 'SETTLEMENT', 'DEBIT', 'ACTIVE', NOW());

INSERT INTO account_balance (account_id, balance_minor, updated_at)
SELECT id, 0, NOW() FROM account WHERE account_type = 'SETTLEMENT';
