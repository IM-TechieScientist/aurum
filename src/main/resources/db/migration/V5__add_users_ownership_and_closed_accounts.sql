CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    username VARCHAR(80) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT app_user_username_not_blank CHECK (BTRIM(username) <> ''),
    CONSTRAINT app_user_role_valid CHECK (role IN ('CUSTOMER', 'OPERATOR', 'AUDITOR', 'ADMIN'))
);

-- Fixed IDs keep account ownership stable when demo usernames/passwords are overridden.
INSERT INTO app_user (id, username, password_hash, role, enabled, created_at)
VALUES
    ('00000000-0000-0000-0000-000000000101', 'customer', 'bootstrap-pending', 'CUSTOMER', TRUE, NOW()),
    ('00000000-0000-0000-0000-000000000102', 'operator', 'bootstrap-pending', 'OPERATOR', TRUE, NOW()),
    ('00000000-0000-0000-0000-000000000103', 'auditor', 'bootstrap-pending', 'AUDITOR', TRUE, NOW()),
    ('00000000-0000-0000-0000-000000000104', 'admin', 'bootstrap-pending', 'ADMIN', TRUE, NOW());

ALTER TABLE account ADD COLUMN owner_user_id UUID REFERENCES app_user(id);

-- Legacy customer accounts predate durable identities; assign them to the demo customer.
UPDATE account
   SET owner_user_id = '00000000-0000-0000-0000-000000000101'
 WHERE account_type = 'CUSTOMER';

ALTER TABLE account
    ADD CONSTRAINT account_ownership_valid CHECK (
        (account_type = 'CUSTOMER' AND owner_user_id IS NOT NULL) OR
        (account_type = 'SETTLEMENT' AND owner_user_id IS NULL)
    );

CREATE INDEX account_owner_user ON account (owner_user_id)
    WHERE owner_user_id IS NOT NULL;

ALTER TABLE account DROP CONSTRAINT account_status_valid;
ALTER TABLE account ADD CONSTRAINT account_status_valid
    CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'));
