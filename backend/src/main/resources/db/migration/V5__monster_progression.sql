CREATE TABLE user_progressions (
    user_id UUID PRIMARY KEY REFERENCES users(id),
    level INTEGER NOT NULL DEFAULT 1 CHECK (level BETWEEN 1 AND 50),
    current_level_exp INTEGER NOT NULL DEFAULT 0 CHECK (current_level_exp >= 0),
    base_hp INTEGER NOT NULL DEFAULT 100 CHECK (base_hp > 0),
    base_attack INTEGER NOT NULL DEFAULT 10 CHECK (base_attack > 0),
    streak INTEGER NOT NULL DEFAULT 0 CHECK (streak >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE monsters (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(10) NOT NULL CHECK (char_length(name) BETWEEN 1 AND 10),
    species VARCHAR(20) NOT NULL CHECK (species IN ('STARLIGHT', 'WAVE', 'SUNSET', 'FOREST')),
    acquisition_source VARCHAR(20) NOT NULL CHECK (acquisition_source IN ('STARTER', 'EGG_CHOICE')),
    source_egg_choice_offer_id UUID UNIQUE,
    growth_stage VARCHAR(20) NOT NULL DEFAULT 'EGG' CHECK (growth_stage IN ('EGG', 'BABY', 'INTERMEDIATE', 'FINAL')),
    recognized_success_count INTEGER NOT NULL DEFAULT 0 CHECK (recognized_success_count >= 0),
    hatched_at TIMESTAMPTZ,
    intermediate_at TIMESTAMPTZ,
    final_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, id),
    CHECK ((acquisition_source='STARTER' AND source_egg_choice_offer_id IS NULL)
        OR (acquisition_source='EGG_CHOICE' AND source_egg_choice_offer_id IS NOT NULL))
);
CREATE UNIQUE INDEX one_starter_per_user ON monsters(user_id) WHERE acquisition_source='STARTER';

CREATE TABLE user_active_monsters (
    user_id UUID PRIMARY KEY REFERENCES users(id),
    monster_id UUID NOT NULL UNIQUE,
    selected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    FOREIGN KEY (user_id, monster_id) REFERENCES monsters(user_id, id)
);

CREATE TABLE starter_creation_receipts (
    user_id UUID NOT NULL REFERENCES users(id),
    idempotency_key UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    monster_id UUID NOT NULL,
    response_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, idempotency_key),
    FOREIGN KEY (user_id, monster_id) REFERENCES monsters(user_id, id)
);
