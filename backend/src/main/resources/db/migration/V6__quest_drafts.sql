CREATE TABLE quests (
    id UUID PRIMARY KEY,
    couple_id UUID NOT NULL REFERENCES couples(id),
    creator_id UUID NOT NULL REFERENCES users(id),
    quest_type VARCHAR(12) NOT NULL CHECK (quest_type = 'PERSONAL'),
    source_type VARCHAR(10) NOT NULL CHECK (source_type = 'CUSTOM'),
    status VARCHAR(32) NOT NULL CHECK (status IN ('DRAFT', 'DISCARDED', 'CANCELED_RELATIONSHIP_ENDED')),
    row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    discarded_at TIMESTAMPTZ,
    canceled_reason VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL,
    CHECK ((status = 'DISCARDED') = (discarded_at IS NOT NULL))
);
CREATE INDEX quests_author_drafts_idx ON quests(couple_id, creator_id, created_at DESC, id DESC) WHERE status = 'DRAFT';

CREATE TABLE quest_drafts (
    quest_id UUID PRIMARY KEY REFERENCES quests(id),
    title VARCHAR(320) NOT NULL CHECK (char_length(title) BETWEEN 1 AND 80),
    category VARCHAR(12) NOT NULL CHECK (category IN ('SLEEP','EXERCISE','STUDY','CONTACT','GAMING','SPENDING','HOUSEWORK','EATING','DATE','CUSTOM')),
    success_criteria TEXT NOT NULL CHECK (char_length(success_criteria) BETWEEN 1 AND 1000),
    difficulty SMALLINT NOT NULL CHECK (difficulty BETWEEN 1 AND 4),
    stake INTEGER NOT NULL CHECK (stake IN (0,100)),
    due_at TIMESTAMPTZ NOT NULL,
    result_at TIMESTAMPTZ NOT NULL CHECK (result_at > due_at),
    minimum_duration_minutes INTEGER NOT NULL CHECK (minimum_duration_minutes BETWEEN 0 AND 10080),
    evidence_method VARCHAR(10) NOT NULL CHECK (evidence_method = 'NONE'),
    revision_reason VARCHAR(12) NOT NULL CHECK (revision_reason = 'INITIAL'),
    updated_at TIMESTAMPTZ NOT NULL
);

-- The creation response is retained only while the draft text may be retained.
CREATE TABLE quest_request_receipts (
    user_id UUID NOT NULL REFERENCES users(id),
    idempotency_key UUID NOT NULL,
    action VARCHAR(10) NOT NULL CHECK (action IN ('CREATE','DISCARD')),
    quest_id UUID NOT NULL REFERENCES quests(id),
    request_hash CHAR(64) NOT NULL,
    response_json JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, idempotency_key)
);

CREATE TABLE relationship_end_job_items (
    relationship_end_job_id UUID NOT NULL REFERENCES relationship_end_jobs(id),
    resource_type VARCHAR(12) NOT NULL CHECK (resource_type = 'QUEST'),
    resource_id UUID NOT NULL REFERENCES quests(id),
    status VARCHAR(12) NOT NULL CHECK (status IN ('PENDING','COMPLETED')),
    processed_at TIMESTAMPTZ,
    PRIMARY KEY (relationship_end_job_id, resource_type, resource_id),
    CHECK ((status = 'COMPLETED') = (processed_at IS NOT NULL))
);
CREATE INDEX relationship_end_pending_items_idx ON relationship_end_job_items(relationship_end_job_id) WHERE status = 'PENDING';
