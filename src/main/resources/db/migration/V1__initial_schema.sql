-- Farmer Accounts: initial schema.
--
-- Conventions
--   * Farmer-owned rows use client-generated UUIDs so offline-created records keep their identity.
--   * Every farmer-owned row carries farmer_id for strict data isolation and cheap sync queries.
--   * server_version is taken from a single global sequence on every insert/update; the mobile
--     app pulls "everything with server_version > cursor".
--   * Money is stored as integer paise (BIGINT) - never floating point.
--   * Financial transactions, wage rates and admin audit logs are append-only (enforced by triggers).

CREATE SEQUENCE change_seq;

CREATE FUNCTION forbid_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------------------------
-- Farmers (users) and authentication
-- ---------------------------------------------------------------------------------------------

CREATE TABLE users (
    id                  UUID PRIMARY KEY,
    phone               VARCHAR(13)  NOT NULL UNIQUE,          -- +91XXXXXXXXXX
    name                VARCHAR(100),
    pin_hash            VARCHAR(100),
    pin_failed_attempts INT          NOT NULL DEFAULT 0,
    pin_locked_until    TIMESTAMPTZ,
    preferred_language  VARCHAR(5)   NOT NULL DEFAULT 'kn' CHECK (preferred_language IN ('kn', 'en')),
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'BLOCKED')),
    block_reason        TEXT,
    blocked_at          TIMESTAMPTZ,
    last_active_at      TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_users_status ON users (status);
CREATE INDEX idx_users_name_lower ON users (lower(name));

CREATE TABLE otp_challenges (
    id           UUID PRIMARY KEY,
    phone        VARCHAR(13)  NOT NULL,
    code_hash    VARCHAR(128) NOT NULL,                       -- HMAC-SHA256, never the code itself
    expires_at   TIMESTAMPTZ  NOT NULL,
    attempts     INT          NOT NULL DEFAULT 0,
    consumed_at  TIMESTAMPTZ,
    request_ip   VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_otp_phone_created ON otp_challenges (phone, created_at DESC);
CREATE INDEX idx_otp_ip_created ON otp_challenges (request_ip, created_at DESC);

CREATE TABLE user_devices (
    user_id        UUID        NOT NULL REFERENCES users (id),
    device_id      VARCHAR(64) NOT NULL,
    verified_at    TIMESTAMPTZ NOT NULL DEFAULT now(),         -- device passed OTP once
    last_login_at  TIMESTAMPTZ,
    PRIMARY KEY (user_id, device_id)
);

CREATE TABLE refresh_tokens (
    id           UUID PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES users (id),
    device_id    VARCHAR(64)  NOT NULL,
    token_hash   VARCHAR(128) NOT NULL UNIQUE,                 -- SHA-256 of the opaque token
    expires_at   TIMESTAMPTZ  NOT NULL,
    revoked_at   TIMESTAMPTZ,
    replaced_by  UUID,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_user ON refresh_tokens (user_id);

-- ---------------------------------------------------------------------------------------------
-- Farm data
-- ---------------------------------------------------------------------------------------------

CREATE TABLE farms (
    id              UUID PRIMARY KEY,
    farmer_id       UUID          NOT NULL REFERENCES users (id),
    name            VARCHAR(100)  NOT NULL,
    village         VARCHAR(100),
    district        VARCHAR(100),
    state           VARCHAR(100),
    total_acreage   NUMERIC(10, 2) CHECK (total_acreage IS NULL OR total_acreage >= 0),
    primary_crop    VARCHAR(40)   NOT NULL DEFAULT 'ARECANUT',
    notes           TEXT,
    deleted_at      TIMESTAMPTZ,
    server_version  BIGINT        NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_farms_farmer_version ON farms (farmer_id, server_version);

CREATE TABLE fields (
    id              UUID PRIMARY KEY,
    farmer_id       UUID          NOT NULL REFERENCES users (id),
    farm_id         UUID          NOT NULL REFERENCES farms (id),
    name            VARCHAR(100)  NOT NULL,
    area_acres      NUMERIC(10, 2) CHECK (area_acres IS NULL OR area_acres >= 0),
    crop            VARCHAR(40)   NOT NULL DEFAULT 'ARECANUT',
    description     TEXT,
    deleted_at      TIMESTAMPTZ,
    server_version  BIGINT        NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_fields_farmer_version ON fields (farmer_id, server_version);
CREATE INDEX idx_fields_farm ON fields (farm_id);

CREATE TABLE workers (
    id                UUID PRIMARY KEY,
    farmer_id         UUID         NOT NULL REFERENCES users (id),
    farm_id           UUID         NOT NULL REFERENCES farms (id),
    name              VARCHAR(100) NOT NULL,
    phone             VARCHAR(13),
    daily_wage_paise  BIGINT       NOT NULL CHECK (daily_wage_paise > 0),  -- current rate (history in wage_rates)
    joining_date      DATE         NOT NULL,
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    notes             TEXT,
    server_version    BIGINT       NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_workers_farmer_version ON workers (farmer_id, server_version);
CREATE INDEX idx_workers_farm ON workers (farm_id);

-- Historical daily wage rates. The rate applicable on a date is the row with the latest
-- effective_from <= date (ties broken by created_at). Rows are never updated or deleted.
CREATE TABLE wage_rates (
    id                UUID PRIMARY KEY,
    farmer_id         UUID        NOT NULL REFERENCES users (id),
    worker_id         UUID        NOT NULL REFERENCES workers (id),
    daily_wage_paise  BIGINT      NOT NULL CHECK (daily_wage_paise > 0),
    effective_from    DATE        NOT NULL,
    server_version    BIGINT      NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_wage_rates_worker ON wage_rates (worker_id, effective_from);
CREATE INDEX idx_wage_rates_farmer_version ON wage_rates (farmer_id, server_version);
CREATE TRIGGER wage_rates_append_only BEFORE UPDATE OR DELETE ON wage_rates
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();

CREATE TABLE attendance (
    id              UUID PRIMARY KEY,
    farmer_id       UUID        NOT NULL REFERENCES users (id),
    farm_id         UUID        NOT NULL REFERENCES farms (id),
    worker_id       UUID        NOT NULL REFERENCES workers (id),
    work_date       DATE        NOT NULL,
    status          VARCHAR(10) NOT NULL CHECK (status IN ('PRESENT', 'HALF_DAY', 'ABSENT')),
    server_version  BIGINT      NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_attendance_worker_farm_date UNIQUE (worker_id, farm_id, work_date)
);
CREATE INDEX idx_attendance_farmer_version ON attendance (farmer_id, server_version);
CREATE INDEX idx_attendance_farm_date ON attendance (farm_id, work_date);

-- Advances, settlements (payments) and adjustments. Append-only: a mistaken entry is corrected
-- by an ADJUSTMENT that references it via reverses_transaction_id.
--   ADVANCE / SETTLEMENT: amount > 0, reduces what the farmer owes.
--   ADJUSTMENT: signed amount, positive increases what the farmer owes the worker.
CREATE TABLE worker_transactions (
    id                       UUID PRIMARY KEY,
    farmer_id                UUID        NOT NULL REFERENCES users (id),
    farm_id                  UUID        NOT NULL REFERENCES farms (id),
    worker_id                UUID        NOT NULL REFERENCES workers (id),
    type                     VARCHAR(12) NOT NULL CHECK (type IN ('ADVANCE', 'SETTLEMENT', 'ADJUSTMENT')),
    amount_paise             BIGINT      NOT NULL,
    txn_date                 DATE        NOT NULL,
    note                     TEXT,
    reverses_transaction_id  UUID UNIQUE REFERENCES worker_transactions (id),
    server_version           BIGINT      NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_txn_amount CHECK (
        (type IN ('ADVANCE', 'SETTLEMENT') AND amount_paise > 0)
        OR (type = 'ADJUSTMENT' AND amount_paise <> 0)),
    CONSTRAINT chk_txn_reversal_is_adjustment CHECK (reverses_transaction_id IS NULL OR type = 'ADJUSTMENT')
);
CREATE INDEX idx_txn_worker_date ON worker_transactions (worker_id, txn_date);
CREATE INDEX idx_txn_farmer_version ON worker_transactions (farmer_id, server_version);
CREATE TRIGGER worker_transactions_append_only BEFORE UPDATE OR DELETE ON worker_transactions
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();

CREATE TABLE expenses (
    id              UUID PRIMARY KEY,
    farmer_id       UUID         NOT NULL REFERENCES users (id),
    farm_id         UUID         NOT NULL REFERENCES farms (id),
    field_id        UUID         REFERENCES fields (id),
    category        VARCHAR(20)  NOT NULL CHECK (category IN
                        ('FERTILIZER', 'PESTICIDE', 'SPRAY', 'TRACTOR', 'MACHINERY', 'TRANSPORT', 'LABOUR', 'OTHER')),
    description     VARCHAR(200) NOT NULL,
    amount_paise    BIGINT       NOT NULL CHECK (amount_paise > 0),
    expense_date    DATE         NOT NULL,
    notes           TEXT,
    deleted_at      TIMESTAMPTZ,
    server_version  BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_expenses_farmer_version ON expenses (farmer_id, server_version);
CREATE INDEX idx_expenses_farm_date ON expenses (farm_id, expense_date);

CREATE TABLE sales (
    id              UUID PRIMARY KEY,
    farmer_id       UUID           NOT NULL REFERENCES users (id),
    farm_id         UUID           NOT NULL REFERENCES farms (id),
    crop            VARCHAR(40)    NOT NULL DEFAULT 'ARECANUT',
    quantity        NUMERIC(12, 3) NOT NULL CHECK (quantity > 0),
    unit            VARCHAR(10)    NOT NULL CHECK (unit IN ('KG', 'QUINTAL', 'TONNE')),
    rate_paise      BIGINT         NOT NULL CHECK (rate_paise >= 0),
    total_paise     BIGINT         NOT NULL CHECK (total_paise >= 0),
    received_paise  BIGINT         NOT NULL DEFAULT 0 CHECK (received_paise >= 0),
    buyer_name      VARCHAR(100)   NOT NULL,
    sale_date       DATE           NOT NULL,
    notes           TEXT,
    deleted_at      TIMESTAMPTZ,
    server_version  BIGINT         NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT chk_sales_received_le_total CHECK (received_paise <= total_paise)
);
CREATE INDEX idx_sales_farmer_version ON sales (farmer_id, server_version);
CREATE INDEX idx_sales_farm_date ON sales (farm_id, sale_date);

-- Audit trail of every change to farmer financial/attendance data (old and new values).
CREATE TABLE record_audit_log (
    id           BIGSERIAL PRIMARY KEY,
    farmer_id    UUID        NOT NULL REFERENCES users (id),
    entity_type  VARCHAR(30) NOT NULL,
    entity_id    UUID        NOT NULL,
    action       VARCHAR(20) NOT NULL,
    old_data     JSONB,
    new_data     JSONB,
    device_id    VARCHAR(64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_record_audit_entity ON record_audit_log (entity_type, entity_id);
CREATE TRIGGER record_audit_log_append_only BEFORE UPDATE OR DELETE ON record_audit_log
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();

-- ---------------------------------------------------------------------------------------------
-- Synchronization
-- ---------------------------------------------------------------------------------------------

-- Idempotency log: every applied (or permanently rejected) operation id with its response.
CREATE TABLE sync_operations (
    op_id          UUID PRIMARY KEY,
    farmer_id      UUID        NOT NULL REFERENCES users (id),
    device_id      VARCHAR(64),
    op_type        VARCHAR(40) NOT NULL,
    entity_id      UUID,
    result_status  VARCHAR(12) NOT NULL,
    response       JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sync_ops_status_created ON sync_operations (result_status, created_at);

CREATE TABLE synchronization_metadata (
    farmer_id         UUID        NOT NULL REFERENCES users (id),
    device_id         VARCHAR(64) NOT NULL,
    app_version       VARCHAR(20),
    last_seen_at      TIMESTAMPTZ,
    last_push_at      TIMESTAMPTZ,
    last_pull_at      TIMESTAMPTZ,
    last_pull_cursor  BIGINT      NOT NULL DEFAULT 0,
    ops_applied       BIGINT      NOT NULL DEFAULT 0,
    ops_failed        BIGINT      NOT NULL DEFAULT 0,
    last_error        TEXT,
    PRIMARY KEY (farmer_id, device_id)
);

-- ---------------------------------------------------------------------------------------------
-- Platform control (kill switch) and administration
-- ---------------------------------------------------------------------------------------------

CREATE TABLE admin_users (
    id               UUID PRIMARY KEY,
    username         VARCHAR(60)  NOT NULL UNIQUE,
    password_hash    VARCHAR(100) NOT NULL,
    role             VARCHAR(20)  NOT NULL CHECK (role IN ('OWNER', 'ADMIN')),
    totp_secret      VARCHAR(64),
    totp_enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_attempts  INT          NOT NULL DEFAULT 0,
    locked_until     TIMESTAMPTZ,
    last_login_at    TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE admin_sessions (
    id            UUID PRIMARY KEY,
    admin_id      UUID         NOT NULL REFERENCES admin_users (id),
    token_hash    VARCHAR(128) NOT NULL UNIQUE,
    ip            VARCHAR(64),
    user_agent    VARCHAR(300),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ  NOT NULL,
    revoked_at    TIMESTAMPTZ
);
CREATE INDEX idx_admin_sessions_admin ON admin_sessions (admin_id);

CREATE TABLE admin_audit_logs (
    id           BIGSERIAL PRIMARY KEY,
    admin_id     UUID         REFERENCES admin_users (id),
    admin_username VARCHAR(60),
    action       VARCHAR(40)  NOT NULL,
    target_type  VARCHAR(30),
    target_id    VARCHAR(64),
    reason       TEXT,
    details      JSONB,
    ip           VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_admin_audit_created ON admin_audit_logs (created_at DESC);
CREATE TRIGGER admin_audit_logs_append_only BEFORE UPDATE OR DELETE ON admin_audit_logs
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();

-- Single-row remote configuration / global kill switch.
CREATE TABLE app_configuration (
    id                          SMALLINT PRIMARY KEY CHECK (id = 1),
    app_status                  VARCHAR(12) NOT NULL CHECK (app_status IN ('ACTIVE', 'MAINTENANCE', 'SUSPENDED')),
    suspension_message_kn       TEXT        NOT NULL,
    suspension_message_en       TEXT        NOT NULL,
    maintenance_message_kn      TEXT        NOT NULL,
    maintenance_message_en      TEXT        NOT NULL,
    minimum_supported_version   VARCHAR(20) NOT NULL,
    force_update                BOOLEAN     NOT NULL DEFAULT FALSE,
    offline_grace_period_hours  INT         NOT NULL CHECK (offline_grace_period_hours BETWEEN 1 AND 720),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by                  UUID REFERENCES admin_users (id)
);

INSERT INTO app_configuration (id, app_status, suspension_message_kn, suspension_message_en,
                               maintenance_message_kn, maintenance_message_en,
                               minimum_supported_version, force_update, offline_grace_period_hours)
VALUES (1, 'ACTIVE',
        'ಈ ಅಪ್ಲಿಕೇಶನ್‌ನ ಸೇವೆಯನ್ನು ತಾತ್ಕಾಲಿಕವಾಗಿ ಸ್ಥಗಿತಗೊಳಿಸಲಾಗಿದೆ.',
        'This application has been temporarily suspended by the administrator.',
        'ನಿರ್ವಹಣಾ ಕಾರ್ಯ ನಡೆಯುತ್ತಿದೆ. ದಯವಿಟ್ಟು ಸ್ವಲ್ಪ ಸಮಯದ ನಂತರ ಪ್ರಯತ್ನಿಸಿ.',
        'The service is under maintenance. Please try again later.',
        '1.0.0', FALSE, 24);
