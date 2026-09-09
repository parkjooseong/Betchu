ALTER TABLE quests DROP CONSTRAINT quests_quest_type_check;
ALTER TABLE quests DROP CONSTRAINT quests_source_type_check;
ALTER TABLE quests DROP CONSTRAINT quests_status_check;
ALTER TABLE quests ADD CONSTRAINT quests_type_source_check CHECK
    ((quest_type='PERSONAL' AND source_type='CUSTOM') OR (quest_type='TUTORIAL' AND source_type='SYSTEM'));
ALTER TABLE quests ADD CONSTRAINT quests_status_check CHECK
    (status IN ('DRAFT','DISCARDED','CANCELED_RELATIONSHIP_ENDED','PENDING_APPROVAL','ACTIVE',
      'AWAITING_RESULT','PENDING_FINAL_APPROVAL','SUCCESS','FAILURE','INVALID','REJECTED','APPROVAL_EXPIRED','CANCELED'));
ALTER TABLE quests ADD CONSTRAINT quests_personal_draft_only CHECK
    (quest_type<>'PERSONAL' OR status IN ('DRAFT','DISCARDED','CANCELED_RELATIONSHIP_ENDED'));
ALTER TABLE quests ADD COLUMN current_quest_version_id UUID;
ALTER TABLE quests ADD COLUMN approved_quest_version_id UUID;
ALTER TABLE quests ADD COLUMN approved_at TIMESTAMPTZ;
ALTER TABLE quests ADD COLUMN stake_locked_at TIMESTAMPTZ;
ALTER TABLE quests ADD COLUMN invalid_reason VARCHAR(60);
CREATE UNIQUE INDEX one_tutorial_per_user ON quests(creator_id) WHERE quest_type='TUTORIAL';

CREATE TABLE quest_versions (
    id UUID PRIMARY KEY,
    quest_id UUID NOT NULL REFERENCES quests(id),
    version_no INTEGER NOT NULL CHECK (version_no=1),
    title TEXT NOT NULL,
    category VARCHAR(12) NOT NULL CHECK (category='EATING'),
    success_criteria TEXT NOT NULL,
    difficulty INTEGER NOT NULL CHECK (difficulty=1),
    stake INTEGER NOT NULL CHECK (stake=100),
    reward INTEGER NOT NULL CHECK (reward=100),
    xp INTEGER NOT NULL CHECK (xp=10),
    minimum_duration_minutes INTEGER NOT NULL CHECK (minimum_duration_minutes=0),
    evidence_method VARCHAR(10) NOT NULL CHECK (evidence_method='NONE'),
    due_at TIMESTAMPTZ NOT NULL,
    result_at TIMESTAMPTZ NOT NULL CHECK (result_at>due_at),
    approval_deadline_at TIMESTAMPTZ NOT NULL CHECK (approval_deadline_at=due_at),
    result_confirmation_deadline_at TIMESTAMPTZ NOT NULL CHECK (result_confirmation_deadline_at=result_at+interval '24 hours'),
    submitted_by UUID NOT NULL REFERENCES users(id),
    submitted_at TIMESTAMPTZ NOT NULL,
    UNIQUE(quest_id,version_no),
    UNIQUE(quest_id,id)
);
ALTER TABLE quests ADD FOREIGN KEY(id,current_quest_version_id) REFERENCES quest_versions(quest_id,id);
ALTER TABLE quests ADD FOREIGN KEY(id,approved_quest_version_id) REFERENCES quest_versions(quest_id,id);

CREATE TABLE quest_approvals (
    id UUID PRIMARY KEY,
    quest_id UUID NOT NULL REFERENCES quests(id),
    quest_version_id UUID NOT NULL,
    partner_id UUID NOT NULL REFERENCES users(id),
    decision VARCHAR(10) NOT NULL CHECK (decision IN ('APPROVE','REJECT')),
    predicted_result VARCHAR(10) CHECK (predicted_result IN ('SUCCESS','FAILURE')),
    decided_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY(quest_id,quest_version_id) REFERENCES quest_versions(quest_id,id),
    UNIQUE(quest_id,partner_id,quest_version_id),
    CHECK ((decision='APPROVE')=(predicted_result IS NOT NULL))
);

CREATE TABLE quest_partner_results (
    id UUID PRIMARY KEY,
    quest_id UUID NOT NULL UNIQUE REFERENCES quests(id),
    partner_id UUID NOT NULL REFERENCES users(id),
    selected_result VARCHAR(10) CHECK (selected_result IN ('SUCCESS','FAILURE')),
    selection_revision INTEGER NOT NULL DEFAULT 0 CHECK (selection_revision>=0),
    selected_at TIMESTAMPTZ,
    final_decision VARCHAR(10) CHECK (final_decision IN ('APPROVE','REJECT')),
    final_decided_at TIMESTAMPTZ,
    invalid_reason VARCHAR(60),
    resolved_at TIMESTAMPTZ,
    CHECK ((selected_result IS NULL AND selection_revision=0 AND selected_at IS NULL)
        OR (selected_result IS NOT NULL AND selection_revision>=1 AND selected_at IS NOT NULL))
);
CREATE TABLE quest_partner_result_events (
    id UUID PRIMARY KEY,
    quest_partner_result_id UUID NOT NULL REFERENCES quest_partner_results(id),
    actor_user_id UUID REFERENCES users(id),
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN ('SELECT','CHANGE','JUDGMENT_REJECT','FINAL_APPROVE','FINAL_REJECT',
        'TIMEOUT_INVALIDATE','SYSTEM_INVALIDATE','RELATIONSHIP_INVALIDATE','CANCELED')),
    selected_result VARCHAR(10) CHECK (selected_result IN ('SUCCESS','FAILURE')),
    idempotency_key UUID NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE quest_cancel_requests (
    id UUID PRIMARY KEY,
    quest_id UUID NOT NULL REFERENCES quests(id),
    requested_by UUID NOT NULL REFERENCES users(id),
    requested_at TIMESTAMPTZ NOT NULL,
    responded_by UUID REFERENCES users(id),
    responded_at TIMESTAMPTZ,
    status VARCHAR(12) NOT NULL CHECK (status IN ('PENDING','CONFIRMED','REJECTED','EXPIRED')),
    CHECK (responded_by IS NULL OR responded_by<>requested_by)
);
CREATE UNIQUE INDEX one_pending_quest_cancel ON quest_cancel_requests(quest_id) WHERE status='PENDING';

ALTER TABLE wallet_ledger ADD COLUMN quest_id UUID REFERENCES quests(id);
ALTER TABLE wallet_ledger ADD COLUMN available_balance_after BIGINT CHECK (available_balance_after>=0);
ALTER TABLE wallet_ledger ADD COLUMN locked_balance_after BIGINT CHECK (locked_balance_after>=0);
CREATE UNIQUE INDEX wallet_ledger_quest_phase ON wallet_ledger(quest_id,entry_type) WHERE quest_id IS NOT NULL;
CREATE TABLE quest_settlements (
    id UUID PRIMARY KEY,
    quest_id UUID NOT NULL UNIQUE REFERENCES quests(id),
    resolved_result VARCHAR(32) NOT NULL CHECK (resolved_result IN ('SUCCESS','FAILURE','INVALID','CANCELED','CANCELED_RELATIONSHIP_ENDED')),
    resolution_source VARCHAR(50) NOT NULL,
    invalid_reason VARCHAR(60),
    stake INTEGER NOT NULL CHECK (stake=100),
    reward INTEGER NOT NULL CHECK (reward IN (0,100)),
    xp INTEGER NOT NULL CHECK (xp IN (0,10)),
    wallet_transaction_id UUID NOT NULL REFERENCES wallet_ledger(id),
    credited_monster_id UUID REFERENCES monsters(id),
    recognized_success_before INTEGER,
    recognized_success_after INTEGER,
    growth_stage_before VARCHAR(20),
    growth_stage_after VARCHAR(20),
    settled_at TIMESTAMPTZ NOT NULL,
    UNIQUE(id,credited_monster_id),
    CHECK ((resolved_result='INVALID')=(invalid_reason IS NOT NULL)),
    CHECK ((resolved_result='SUCCESS' AND reward=100 AND xp IN (0,10) AND credited_monster_id IS NOT NULL
        AND growth_stage_before='EGG' AND growth_stage_after='BABY') OR
        (resolved_result<>'SUCCESS' AND reward=0 AND xp=0 AND credited_monster_id IS NULL)),
    CHECK (recognized_success_before IS NULL AND recognized_success_after IS NULL)
);
CREATE TABLE monster_growth_milestone_claims (
    id UUID PRIMARY KEY,
    monster_id UUID NOT NULL REFERENCES monsters(id),
    milestone VARCHAR(20) NOT NULL CHECK (milestone IN ('HATCH','SUCCESS_20','SUCCESS_40','SUCCESS_60','SUCCESS_80')),
    source_quest_settlement_id UUID NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL,
    UNIQUE(monster_id,milestone),
    FOREIGN KEY(source_quest_settlement_id,monster_id) REFERENCES quest_settlements(id,credited_monster_id)
);
CREATE TABLE tutorial_request_receipts (
    user_id UUID NOT NULL REFERENCES users(id),
    idempotency_key UUID NOT NULL,
    quest_id UUID NOT NULL REFERENCES quests(id),
    action VARCHAR(32) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_json JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(user_id,idempotency_key)
);
CREATE INDEX tutorial_pending_deadlines ON quests(status,id) WHERE quest_type='TUTORIAL'
    AND status IN ('PENDING_APPROVAL','ACTIVE','AWAITING_RESULT','PENDING_FINAL_APPROVAL');
