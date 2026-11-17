CREATE TABLE game_action_receipts (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    idempotency_key UUID NOT NULL, action VARCHAR(40) NOT NULL, request_hash CHAR(64) NOT NULL,
    response_json JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(user_id,idempotency_key)
);

CREATE TABLE item_definitions (
    id VARCHAR(60) PRIMARY KEY, name VARCHAR(80) NOT NULL,
    rarity VARCHAR(12) NOT NULL CHECK (rarity IN ('COMMON','RARE','EPIC','LEGENDARY','MYTHIC')),
    slot VARCHAR(12) NOT NULL CHECK (slot IN ('WEAPON','ARMOR','ACCESSORY')),
    hp_bonus INTEGER NOT NULL CHECK (hp_bonus>=0), attack_bonus INTEGER NOT NULL CHECK (attack_bonus>=0),
    duplicate_fragments INTEGER NOT NULL CHECK (duplicate_fragments>0)
);
INSERT INTO item_definitions VALUES
('twig-sword','새싹 검','COMMON','WEAPON',0,3,5),
('cotton-vest','구름 조끼','COMMON','ARMOR',12,0,5),
('seed-ring','씨앗 반지','COMMON','ACCESSORY',4,2,5),
('wave-blade','파도 검','RARE','WEAPON',0,10,15),
('forest-coat','숲의 망토','RARE','ARMOR',40,0,15),
('moon-charm','달빛 부적','RARE','ACCESSORY',20,5,15),
('flame-sword','불꽃 검','EPIC','WEAPON',0,20,40),
('ice-armor','얼음 갑옷','EPIC','ARMOR',80,0,40),
('star-brooch','별빛 브로치','EPIC','ACCESSORY',40,10,40),
('dawn-sword','새벽의 검','LEGENDARY','WEAPON',0,35,100),
('sky-armor','하늘 갑옷','LEGENDARY','ARMOR',140,0,100),
('couple-crown','커플왕의 왕관','LEGENDARY','ACCESSORY',80,15,100),
('star-eater','별을 삼킨 검','MYTHIC','WEAPON',0,40,250),
('galaxy-armor','은하 갑옷','MYTHIC','ARMOR',160,0,250),
('universe-ring','우주의 반지','MYTHIC','ACCESSORY',80,20,250);

CREATE TABLE draw_probability_versions (
    id VARCHAR(30) PRIMARY KEY, draw_type VARCHAR(12) NOT NULL CHECK (draw_type IN ('NORMAL','ADVANCED','LEGENDARY')),
    version INTEGER NOT NULL CHECK (version>0), cost INTEGER NOT NULL CHECK (cost>0),
    common_weight INTEGER NOT NULL, rare_weight INTEGER NOT NULL, epic_weight INTEGER NOT NULL,
    legendary_weight INTEGER NOT NULL, mythic_weight INTEGER NOT NULL,
    pity_threshold INTEGER NOT NULL, pity_rarity VARCHAR(12), effective_at TIMESTAMPTZ NOT NULL,
    UNIQUE(draw_type,version),
    CHECK (common_weight>=0 AND rare_weight>=0 AND epic_weight>=0 AND legendary_weight>=0 AND mythic_weight>=0
        AND common_weight+rare_weight+epic_weight+legendary_weight+mythic_weight=10000)
);
INSERT INTO draw_probability_versions VALUES
('NORMAL-v1','NORMAL',1,100,7500,2000,500,0,0,0,NULL,'2026-01-01T00:00:00Z'),
('ADVANCED-v1','ADVANCED',1,500,0,5000,4000,1000,0,10,'LEGENDARY','2026-01-01T00:00:00Z'),
('LEGENDARY-v1','LEGENDARY',1,1000,0,0,5000,4000,1000,20,'MYTHIC','2026-01-01T00:00:00Z');
CREATE TABLE draw_probability_items (
    probability_version_id VARCHAR(30) NOT NULL REFERENCES draw_probability_versions(id),
    item_id VARCHAR(60) NOT NULL REFERENCES item_definitions(id), PRIMARY KEY(probability_version_id,item_id)
);
INSERT INTO draw_probability_items
SELECT v.id,i.id FROM draw_probability_versions v CROSS JOIN item_definitions i
WHERE (v.draw_type='NORMAL' AND i.rarity IN ('COMMON','RARE','EPIC'))
   OR (v.draw_type='ADVANCED' AND i.rarity IN ('RARE','EPIC','LEGENDARY'))
   OR (v.draw_type='LEGENDARY' AND i.rarity IN ('EPIC','LEGENDARY','MYTHIC'));
CREATE FUNCTION reject_catalog_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Published game catalog entries are immutable'; END $$;
CREATE TRIGGER immutable_items BEFORE UPDATE OR DELETE ON item_definitions FOR EACH ROW EXECUTE FUNCTION reject_catalog_mutation();
CREATE TRIGGER immutable_draw_versions BEFORE UPDATE OR DELETE ON draw_probability_versions FOR EACH ROW EXECUTE FUNCTION reject_catalog_mutation();
CREATE TRIGGER immutable_draw_pools BEFORE UPDATE OR DELETE ON draw_probability_items FOR EACH ROW EXECUTE FUNCTION reject_catalog_mutation();

CREATE TABLE inventory_items (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    item_id VARCHAR(60) NOT NULL REFERENCES item_definitions(id), acquired_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    fragments BIGINT NOT NULL DEFAULT 0 CHECK (fragments>=0), PRIMARY KEY(user_id,item_id)
);
CREATE TABLE equipment_loadouts (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    slot VARCHAR(12) NOT NULL CHECK (slot IN ('WEAPON','ARMOR','ACCESSORY')),
    item_id VARCHAR(60), row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version>=0),
    PRIMARY KEY(user_id,slot), FOREIGN KEY(user_id,item_id) REFERENCES inventory_items(user_id,item_id)
);
CREATE TABLE draw_pity_counters (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    draw_type VARCHAR(12) NOT NULL CHECK (draw_type IN ('NORMAL','ADVANCED','LEGENDARY')),
    misses INTEGER NOT NULL DEFAULT 0 CHECK (misses>=0 AND misses<20), PRIMARY KEY(user_id,draw_type)
);
CREATE TABLE draw_tickets (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    normal_tickets INTEGER NOT NULL DEFAULT 0 CHECK (normal_tickets>=0)
);
CREATE TABLE draw_results (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    probability_version_id VARCHAR(30) NOT NULL REFERENCES draw_probability_versions(id),
    item_id VARCHAR(60) NOT NULL REFERENCES item_definitions(id), payment VARCHAR(10) NOT NULL CHECK(payment IN ('COINS','TICKET')),
    cost INTEGER NOT NULL CHECK(cost>=0), pity_applied BOOLEAN NOT NULL,
    pity_before INTEGER NOT NULL, pity_after INTEGER NOT NULL, duplicate BOOLEAN NOT NULL,
    fragments_granted INTEGER NOT NULL CHECK(fragments_granted>=0), created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX draw_results_history ON draw_results(user_id,created_at DESC,id);

CREATE TABLE rescue_quests (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    template_version VARCHAR(30) NOT NULL DEFAULT 'TAKE_A_BREAK-v1',
    status VARCHAR(12) NOT NULL CHECK(status IN ('ACTIVE','COMPLETED','EXPIRED')),
    started_at TIMESTAMPTZ NOT NULL, available_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ, CHECK(available_at>started_at AND expires_at>available_at)
);
CREATE UNIQUE INDEX one_active_rescue ON rescue_quests(user_id) WHERE status='ACTIVE';
CREATE INDEX rescue_rewards_window ON rescue_quests(user_id,completed_at) WHERE status='COMPLETED';

CREATE TABLE weekly_coop_bosses (
    id UUID PRIMARY KEY, couple_id UUID NOT NULL REFERENCES couples(id) ON DELETE CASCADE, week_start_kst DATE NOT NULL,
    first_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE, second_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    first_base_power INTEGER NOT NULL, second_base_power INTEGER NOT NULL,
    first_baseline_power INTEGER NOT NULL, second_baseline_power INTEGER NOT NULL,
    required_power INTEGER NOT NULL CHECK(required_power>0),
    status VARCHAR(30) NOT NULL CHECK(status IN ('LOCKED','AVAILABLE','WAITING_PARTNER','CLEARED','FAILED','EXPIRED','CANCELED_RELATIONSHIP_ENDED')),
    first_joined_at TIMESTAMPTZ, second_joined_at TIMESTAMPTZ,
    first_final_power INTEGER, second_final_power INTEGER, decided_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(couple_id,week_start_kst)
);
CREATE INDEX wallet_ledger_history ON wallet_ledger(user_id,created_at DESC,id DESC);
CREATE FUNCTION reject_used_pool_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM draw_results WHERE probability_version_id=NEW.probability_version_id) THEN
        RAISE EXCEPTION 'A used draw pool is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER immutable_used_pool BEFORE INSERT ON draw_probability_items FOR EACH ROW EXECUTE FUNCTION reject_used_pool_insert();
