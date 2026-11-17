CREATE TABLE user_reports (
    id UUID PRIMARY KEY, reporter_id UUID NOT NULL REFERENCES users(id), reported_user_id UUID NOT NULL REFERENCES users(id),
    reason VARCHAR(30) NOT NULL CHECK(reason IN ('HARASSMENT','UNSAFE_REQUEST','PRIVACY','OTHER')),
    description TEXT NOT NULL CHECK(char_length(description) BETWEEN 1 AND 2000),
    status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED' CHECK(status IN ('RECEIVED','REVIEWING','RESOLVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()+interval '90 days',
    CHECK(reporter_id<>reported_user_id)
);
CREATE INDEX user_reports_queue ON user_reports(status,created_at);
CREATE TABLE account_deletion_jobs (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE, idempotency_key UUID NOT NULL,
    status VARCHAR(12) NOT NULL CHECK(status IN ('PROCESSING','COMPLETED')),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), completed_at TIMESTAMPTZ
);
CREATE TABLE deleted_identity_cooldowns (
    identity_hash CHAR(64) PRIMARY KEY, expires_at TIMESTAMPTZ NOT NULL
);
