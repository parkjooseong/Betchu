ALTER TABLE quests ADD COLUMN template_code VARCHAR(40);
ALTER TABLE quests DROP CONSTRAINT quests_type_source_check;
ALTER TABLE quests ADD CONSTRAINT quests_type_source_check CHECK
    ((quest_type='PERSONAL' AND ((source_type='CUSTOM' AND template_code IS NULL) OR (source_type='TEMPLATE' AND template_code IS NOT NULL)))
      OR (quest_type='TUTORIAL' AND source_type='SYSTEM' AND template_code IS NULL));
ALTER TABLE quest_drafts DROP CONSTRAINT quest_drafts_stake_check;
ALTER TABLE quest_drafts ADD CONSTRAINT quest_drafts_stake_check CHECK(stake IN (0,100,200,300) AND stake<=CASE difficulty WHEN 4 THEN 300 WHEN 3 THEN 200 ELSE 100 END);
ALTER TABLE quest_versions DROP CONSTRAINT quest_versions_terms_check;
ALTER TABLE quest_versions ADD CONSTRAINT quest_versions_terms_check CHECK (
    version_no>=1 AND char_length(title) BETWEEN 1 AND 80 AND char_length(success_criteria) BETWEEN 1 AND 1000
    AND category IN ('SLEEP','EXERCISE','STUDY','CONTACT','GAMING','SPENDING','HOUSEWORK','EATING','DATE','CUSTOM')
    AND difficulty BETWEEN 1 AND 4 AND stake IN (0,100,200,300) AND stake<=CASE difficulty WHEN 4 THEN 300 WHEN 3 THEN 200 ELSE 100 END
    AND reward BETWEEN 0 AND 300 AND reward%25=0 AND xp IN (10,20,40,70)
    AND minimum_duration_minutes BETWEEN 0 AND 10080 AND evidence_method='NONE' AND result_at>due_at
    AND approval_deadline_at=due_at-minimum_duration_minutes*interval '1 minute'
    AND result_confirmation_deadline_at=result_at+interval '24 hours');
ALTER TABLE quest_settlements DROP CONSTRAINT quest_settlements_outcome_check;
ALTER TABLE quest_settlements ADD CONSTRAINT quest_settlements_outcome_check CHECK (
    resolved_result IN ('SUCCESS','FAILURE','INVALID','CANCELED','CANCELED_RELATIONSHIP_ENDED')
    AND (resolved_result='INVALID')=(invalid_reason IS NOT NULL) AND xp BETWEEN 0 AND 91
    AND ((quest_type='TUTORIAL' AND stake=100 AND reward IN (0,100) AND xp IN (0,10)
          AND recognized_success_before IS NULL AND recognized_success_after IS NULL)
      OR (quest_type='PERSONAL' AND stake IN (0,100,200,300) AND reward BETWEEN 0 AND 300 AND reward<=stake AND reward%25=0))
    AND ((resolved_result='SUCCESS' AND credited_monster_id IS NOT NULL AND growth_stage_after IS NOT NULL
          AND ((quest_type='TUTORIAL' AND reward=100 AND growth_stage_before='EGG' AND growth_stage_after='BABY')
            OR (quest_type='PERSONAL' AND recognized_success_before IS NOT NULL AND recognized_success_after IS NOT NULL AND recognized_success_before>=0
                AND recognized_success_after=recognized_success_before+1 AND growth_stage_before IS NOT NULL)))
      OR (resolved_result<>'SUCCESS' AND reward=0 AND xp=0 AND credited_monster_id IS NULL
          AND recognized_success_before IS NULL AND recognized_success_after IS NULL)));
