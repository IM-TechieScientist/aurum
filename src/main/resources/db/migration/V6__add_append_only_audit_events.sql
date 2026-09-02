CREATE TABLE audit_event (
    id BIGSERIAL PRIMARY KEY,
    actor_user_id UUID REFERENCES app_user(id),
    actor_username VARCHAR(80) NOT NULL,
    action VARCHAR(40) NOT NULL,
    target_type VARCHAR(40) NOT NULL,
    target_id VARCHAR(128) NOT NULL,
    correlation_id VARCHAR(128),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT audit_actor_not_blank CHECK (BTRIM(actor_username) <> ''),
    CONSTRAINT audit_target_not_blank CHECK (BTRIM(target_type) <> '' AND BTRIM(target_id) <> ''),
    CONSTRAINT audit_action_valid CHECK (action IN (
        'CREATE_USER', 'CHANGE_USER_ROLE', 'CREATE_ACCOUNT',
        'FREEZE_ACCOUNT', 'UNFREEZE_ACCOUNT', 'CLOSE_ACCOUNT',
        'FUND', 'WITHDRAW', 'TRANSFER', 'REVERSAL', 'REBUILD_PROJECTIONS'
    ))
);

CREATE INDEX audit_event_recent ON audit_event (id DESC);
CREATE INDEX audit_event_actor_recent ON audit_event (actor_user_id, id DESC)
    WHERE actor_user_id IS NOT NULL;

CREATE TRIGGER audit_event_immutable
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
