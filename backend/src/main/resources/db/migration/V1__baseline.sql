CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE app_metadata (
    key VARCHAR(100) PRIMARY KEY,
    value VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO app_metadata (key, value)
VALUES ('schema_baseline', '1');
