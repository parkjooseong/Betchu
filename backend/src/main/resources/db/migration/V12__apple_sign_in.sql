ALTER TABLE oauth_identities DROP CONSTRAINT oauth_identities_provider_check;
ALTER TABLE oauth_identities ADD CONSTRAINT oauth_identities_provider_check CHECK(provider IN ('GOOGLE','APPLE'));
ALTER TABLE oauth_identities ADD COLUMN encrypted_provider_token TEXT;
ALTER TABLE oauth_identities ADD COLUMN provider_token_revoked_at TIMESTAMPTZ;
ALTER TABLE oauth_login_attempts ADD COLUMN provider VARCHAR(20) NOT NULL DEFAULT 'GOOGLE' CHECK(provider IN ('GOOGLE','APPLE'));
ALTER TABLE oauth_login_attempts ADD COLUMN encrypted_provider_token TEXT;
CREATE TABLE apple_token_revocations (
    token_hash CHAR(64) PRIMARY KEY, encrypted_token TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    attempts INTEGER NOT NULL DEFAULT 0, completed_at TIMESTAMPTZ
);
