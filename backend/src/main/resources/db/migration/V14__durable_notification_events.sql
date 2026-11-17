CREATE INDEX quest_outbox_collection ON quest_outbox(created_at);
CREATE INDEX push_delivery_pending ON push_deliveries(next_attempt_at) WHERE status IN ('PENDING','ACCEPTED');
CREATE INDEX notification_expiry ON notification_events(expires_at);

CREATE FUNCTION record_tutorial_notification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.quest_type='TUTORIAL' AND NEW.current_quest_version_id IS NOT NULL
     AND (OLD.row_version<>NEW.row_version OR OLD.current_quest_version_id IS NULL) THEN
    INSERT INTO quest_outbox(id,quest_id,event_type,row_version,recipient_id,created_at)
    VALUES(gen_random_uuid(),NEW.id,'TUTORIAL',NEW.row_version,
      CASE WHEN NEW.status='PENDING_FINAL_APPROVAL' THEN
        (SELECT user_id FROM couple_members WHERE couple_id=NEW.couple_id AND user_id<>NEW.creator_id AND status='ACTIVE' LIMIT 1)
      ELSE NULL END,clock_timestamp()) ON CONFLICT DO NOTHING;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER tutorial_notification_event AFTER UPDATE ON quests FOR EACH ROW EXECUTE FUNCTION record_tutorial_notification();

CREATE FUNCTION record_boss_notification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status<>NEW.status AND NEW.status IN ('AVAILABLE','WAITING_PARTNER','CLEARED','FAILED') THEN
    INSERT INTO notification_events(user_id,source_key,kind,route,couple_id,expires_at)
    SELECT user_id,'boss:'||NEW.id||':'||NEW.row_version,'BOSS','/coop',NEW.couple_id,
      (NEW.week_start_kst+7)::timestamp AT TIME ZONE 'Asia/Seoul'
    FROM couple_members WHERE couple_id=NEW.couple_id AND status='ACTIVE'
    ON CONFLICT(user_id,source_key) DO NOTHING;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER boss_notification_event AFTER UPDATE ON weekly_coop_bosses FOR EACH ROW EXECUTE FUNCTION record_boss_notification();
