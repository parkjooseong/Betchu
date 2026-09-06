ALTER TABLE oauth_login_attempts
    ADD COLUMN handoff_hash CHAR(64),
    ADD COLUMN handoff_expires_at TIMESTAMPTZ,
    ADD CONSTRAINT oauth_handoff_pair CHECK ((handoff_hash IS NULL) = (handoff_expires_at IS NULL));
