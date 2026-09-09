CREATE TABLE users (
    id UUID PRIMARY KEY,
    nickname VARCHAR(30) NOT NULL,
    profile_image TEXT,
    status VARCHAR(30) NOT NULL CHECK (status IN ('PENDING_ELIGIBILITY', 'ACTIVE', 'DELETION_PENDING', 'DELETED')),
    age_eligible_confirmed_at TIMESTAMPTZ,
    signup_grant_issued_at TIMESTAMPTZ,
    tutorial_completed_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE oauth_identities (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider VARCHAR(20) NOT NULL CHECK (provider = 'GOOGLE'),
    provider_subject VARCHAR(255) NOT NULL,
    PRIMARY KEY (provider, provider_subject),
    UNIQUE (user_id, provider)
);

CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    refresh_token_hash CHAR(64) NOT NULL UNIQUE,
    status VARCHAR(10) NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX auth_sessions_user_id_idx ON auth_sessions(user_id);

CREATE TABLE oauth_login_attempts (
    id UUID PRIMARY KEY,
    login_secret_hash CHAR(64) NOT NULL,
    state_hash CHAR(64) UNIQUE,
    nonce_hash CHAR(64),
    pkce_verifier VARCHAR(128),
    redirect_uri TEXT NOT NULL,
    status VARCHAR(12) NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETE', 'FAILED')),
    provider_subject VARCHAR(255),
    nickname VARCHAR(30),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX oauth_login_attempts_expiry_idx ON oauth_login_attempts(expires_at);

CREATE TABLE policy_versions (
    id UUID PRIMARY KEY,
    policy_type VARCHAR(30) NOT NULL CHECK (policy_type IN ('TERMS', 'PRIVACY', 'EVIDENCE_OPTIONAL')),
    policy_version VARCHAR(100) NOT NULL,
    locale VARCHAR(20) NOT NULL,
    required BOOLEAN NOT NULL,
    document_url TEXT NOT NULL CHECK (document_url LIKE 'https://%'),
    effective_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(10) NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CHECK ((policy_type IN ('TERMS', 'PRIVACY') AND required) OR (policy_type = 'EVIDENCE_OPTIONAL' AND NOT required)),
    UNIQUE (policy_type, policy_version, locale)
);
CREATE UNIQUE INDEX policy_versions_active_idx ON policy_versions(policy_type, locale) WHERE status = 'ACTIVE';

CREATE TABLE policy_consents (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    policy_version_id UUID NOT NULL REFERENCES policy_versions(id),
    agreed_at TIMESTAMPTZ NOT NULL,
    withdrawn_at TIMESTAMPTZ,
    UNIQUE (user_id, policy_version_id)
);

CREATE TABLE wallets (
    user_id UUID PRIMARY KEY REFERENCES users(id),
    available_coins BIGINT NOT NULL CHECK (available_coins >= 0),
    locked_coins BIGINT NOT NULL CHECK (locked_coins >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE wallet_ledger (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    entry_type VARCHAR(40) NOT NULL,
    available_delta BIGINT NOT NULL,
    locked_delta BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (entry_type <> 'SIGNUP_GRANT' OR (available_delta = 1000 AND locked_delta = 0))
);
CREATE UNIQUE INDEX wallet_ledger_signup_grant_idx ON wallet_ledger(user_id) WHERE entry_type = 'SIGNUP_GRANT';
