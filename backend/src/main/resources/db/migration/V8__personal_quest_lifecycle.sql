ALTER TABLE quests DROP CONSTRAINT quests_personal_draft_only;
ALTER TABLE quests DROP CONSTRAINT quests_status_check;
ALTER TABLE quests ADD CONSTRAINT quests_status_check CHECK (status IN
    ('DRAFT','DISCARDED','CHANGE_REQUESTED','PENDING_APPROVAL','ACTIVE','AWAITING_RESULT',
     'PENDING_FINAL_APPROVAL','SUCCESS','FAILURE','INVALID','REJECTED','APPROVAL_EXPIRED',
     'CANCELED','CANCELED_RELATIONSHIP_ENDED'));
ALTER TABLE quests ADD COLUMN budget_date_kst DATE;
ALTER TABLE quests ADD COLUMN reward_reserved_amount INTEGER CHECK (reward_reserved_amount BETWEEN 0 AND 300);
ALTER TABLE quests ADD COLUMN activity_slot_reserved_at TIMESTAMPTZ;
ALTER TABLE quests ADD COLUMN coin_slot_reserved_at TIMESTAMPTZ;
ALTER TABLE quest_drafts DROP CONSTRAINT quest_drafts_revision_reason_check;
ALTER TABLE quest_drafts ADD CONSTRAINT quest_drafts_revision_reason_check CHECK
    (revision_reason IN ('INITIAL','CREATOR_RECALL','PARTNER_CHANGE_REQUEST'));
ALTER TABLE quest_drafts ALTER COLUMN revision_reason TYPE VARCHAR(24);

-- Replace the tutorial-only term checks with checks shared by submitted versions.
DO $$ DECLARE c RECORD; BEGIN
    FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='quest_versions'::regclass AND contype='c'
    LOOP EXECUTE format('ALTER TABLE quest_versions DROP CONSTRAINT %I',c.conname); END LOOP;
END $$;
ALTER TABLE quest_versions ADD CONSTRAINT quest_versions_terms_check CHECK (
    version_no>=1 AND char_length(title) BETWEEN 1 AND 80 AND char_length(success_criteria) BETWEEN 1 AND 1000
    AND category IN ('SLEEP','EXERCISE','STUDY','CONTACT','GAMING','SPENDING','HOUSEWORK','EATING','DATE','CUSTOM')
    AND difficulty BETWEEN 1 AND 4 AND stake IN (0,100) AND reward IN (0,50,100) AND xp IN (10,20,40,70)
    AND minimum_duration_minutes BETWEEN 0 AND 10080 AND evidence_method='NONE' AND result_at>due_at
    AND approval_deadline_at=due_at-minimum_duration_minutes*interval '1 minute'
    AND result_confirmation_deadline_at=result_at+interval '24 hours');
ALTER TABLE quest_approvals ALTER COLUMN decision TYPE VARCHAR(16);
ALTER TABLE quest_approvals DROP CONSTRAINT quest_approvals_decision_check;
ALTER TABLE quest_approvals ADD CONSTRAINT quest_approvals_decision_check CHECK
    (decision IN ('APPROVE','REQUEST_CHANGE','REJECT'));
ALTER TABLE quest_approvals ADD COLUMN request_message TEXT CHECK (char_length(request_message) BETWEEN 1 AND 1000);
CREATE TABLE quest_recall_events (
    id UUID PRIMARY KEY, quest_id UUID NOT NULL REFERENCES quests(id), quest_version_id UUID NOT NULL,
    requested_by UUID NOT NULL REFERENCES users(id), idempotency_key UUID NOT NULL,
    recalled_at TIMESTAMPTZ NOT NULL, UNIQUE(quest_id,idempotency_key),
    FOREIGN KEY(quest_id,quest_version_id) REFERENCES quest_versions(quest_id,id)
);
ALTER TABLE quest_request_receipts ALTER COLUMN action TYPE VARCHAR(32);
ALTER TABLE quest_request_receipts DROP CONSTRAINT quest_request_receipts_action_check;
ALTER TABLE quest_request_receipts ADD CONSTRAINT quest_request_receipts_action_check CHECK (action IN
    ('CREATE','DISCARD','SUBMIT','RECALL','REQUEST_CHANGE','REJECT','APPROVE','SELECT_RESULT','FINAL_APPROVE',
     'REJECT_RESULT','CANCEL','CONFIRM_CANCEL','REJECT_CANCEL'));

CREATE TABLE user_quest_counters (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE, active_count INTEGER NOT NULL DEFAULT 0 CHECK (active_count BETWEEN 0 AND 5)
);
CREATE TABLE daily_activity_budgets (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE, budget_date_kst DATE NOT NULL,
    xp_slots_used INTEGER NOT NULL DEFAULT 0 CHECK (xp_slots_used BETWEEN 0 AND 5), PRIMARY KEY(user_id,budget_date_kst)
);
CREATE TABLE daily_reward_budgets (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE, budget_date_kst DATE NOT NULL,
    coin_slots_used INTEGER NOT NULL DEFAULT 0 CHECK (coin_slots_used BETWEEN 0 AND 2),
    reward_reserved INTEGER NOT NULL DEFAULT 0 CHECK (reward_reserved>=0 AND reward_reserved%25=0),
    reward_issued INTEGER NOT NULL DEFAULT 0 CHECK (reward_issued>=0 AND reward_issued%25=0),
    low_reward_reserved INTEGER NOT NULL DEFAULT 0 CHECK (low_reward_reserved>=0 AND low_reward_reserved%25=0),
    low_reward_issued INTEGER NOT NULL DEFAULT 0 CHECK (low_reward_issued>=0 AND low_reward_issued%25=0),
    PRIMARY KEY(user_id,budget_date_kst), CHECK (reward_reserved+reward_issued<=300),
    CHECK (low_reward_reserved+low_reward_issued<=100),
    CHECK (low_reward_reserved<=reward_reserved AND low_reward_issued<=reward_issued)
);

ALTER TABLE quest_settlements ADD COLUMN quest_type VARCHAR(12) NOT NULL DEFAULT 'TUTORIAL';
DO $$ DECLARE c RECORD; BEGIN
    FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='quest_settlements'::regclass AND contype='c'
    LOOP EXECUTE format('ALTER TABLE quest_settlements DROP CONSTRAINT %I',c.conname); END LOOP;
END $$;
ALTER TABLE quest_settlements ADD CONSTRAINT quest_settlements_outcome_check CHECK (
    resolved_result IN ('SUCCESS','FAILURE','INVALID','CANCELED','CANCELED_RELATIONSHIP_ENDED')
    AND (resolved_result='INVALID')=(invalid_reason IS NOT NULL) AND xp BETWEEN 0 AND 91
    AND ((quest_type='TUTORIAL' AND stake=100 AND reward IN (0,100) AND xp IN (0,10)
          AND recognized_success_before IS NULL AND recognized_success_after IS NULL)
      OR (quest_type='PERSONAL' AND stake IN (0,100) AND reward BETWEEN 0 AND 50 AND reward<=stake/2 AND reward%25=0))
    AND ((resolved_result='SUCCESS' AND credited_monster_id IS NOT NULL AND growth_stage_after IS NOT NULL
          AND ((quest_type='TUTORIAL' AND reward=100 AND growth_stage_before='EGG' AND growth_stage_after='BABY')
            OR (quest_type='PERSONAL' AND recognized_success_before IS NOT NULL AND recognized_success_after IS NOT NULL AND recognized_success_before>=0
                AND recognized_success_after=recognized_success_before+1 AND growth_stage_before IS NOT NULL)))
      OR (resolved_result<>'SUCCESS' AND reward=0 AND xp=0 AND credited_monster_id IS NULL
          AND recognized_success_before IS NULL AND recognized_success_after IS NULL)));
CREATE TABLE monster_mastery_rewards (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id), monster_id UUID NOT NULL REFERENCES monsters(id),
    species VARCHAR(10) NOT NULL CHECK (species IN ('STARLIGHT','WAVE','SUNSET','FOREST')),
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('AURA','TITLE','ACCESSORY')),
    reward_code VARCHAR(60) NOT NULL, granted_at TIMESTAMPTZ NOT NULL,
    hp_bonus INTEGER NOT NULL DEFAULT 0 CHECK (hp_bonus=0), attack_bonus INTEGER NOT NULL DEFAULT 0 CHECK (attack_bonus=0),
    bound BOOLEAN NOT NULL DEFAULT true CHECK (bound), UNIQUE(user_id,species,kind)
);
CREATE TABLE quest_outbox (
    id UUID PRIMARY KEY, quest_id UUID NOT NULL REFERENCES quests(id) ON DELETE CASCADE, event_type VARCHAR(40) NOT NULL,
    row_version BIGINT NOT NULL, recipient_id UUID REFERENCES users(id), created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(quest_id,event_type,row_version)
);
ALTER TABLE couples ADD COLUMN betting_suspension_generation INTEGER NOT NULL DEFAULT 0 CHECK (betting_suspension_generation>=0);
ALTER TABLE couples ADD COLUMN betting_suspended_at TIMESTAMPTZ;
ALTER TABLE couples ADD COLUMN betting_risk_window_started_at TIMESTAMPTZ;
CREATE TABLE couple_betting_incidents (
    quest_id UUID PRIMARY KEY REFERENCES quests(id), couple_id UUID NOT NULL REFERENCES couples(id),
    reason VARCHAR(60) NOT NULL, occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX couple_betting_recent ON couple_betting_incidents(couple_id,occurred_at);
CREATE TABLE betting_rule_acknowledgments (
    couple_id UUID NOT NULL REFERENCES couples(id), generation INTEGER NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id), acknowledged_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(couple_id,generation,user_id)
);
CREATE TABLE betting_rule_receipts (
    user_id UUID NOT NULL REFERENCES users(id), idempotency_key UUID NOT NULL, couple_id UUID NOT NULL REFERENCES couples(id),
    generation INTEGER NOT NULL, response_json JSONB NOT NULL, PRIMARY KEY(user_id,idempotency_key)
);
CREATE INDEX personal_pending_deadlines ON quests(status,id) WHERE quest_type='PERSONAL'
    AND status IN ('PENDING_APPROVAL','ACTIVE','AWAITING_RESULT','PENDING_FINAL_APPROVAL');
