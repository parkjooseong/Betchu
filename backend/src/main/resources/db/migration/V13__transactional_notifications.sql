CREATE TABLE notification_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_key VARCHAR(160) NOT NULL, kind VARCHAR(40) NOT NULL, route VARCHAR(160) NOT NULL,
    couple_id UUID REFERENCES couples(id) ON DELETE CASCADE, invite_id UUID REFERENCES couple_invites(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), expires_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ, UNIQUE(user_id,source_key)
);
CREATE INDEX notification_inbox ON notification_events(user_id,created_at DESC);
CREATE TABLE push_devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id UUID NOT NULL REFERENCES auth_sessions(id) ON DELETE CASCADE, token VARCHAR(200) NOT NULL UNIQUE,
    platform VARCHAR(10) NOT NULL CHECK(platform IN ('ANDROID','IOS')), updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE push_deliveries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), event_id UUID NOT NULL REFERENCES notification_events(id) ON DELETE CASCADE,
    device_id UUID NOT NULL REFERENCES push_devices(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','ACCEPTED','PROVIDER_ACCEPTED','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    ticket_id VARCHAR(100), accepted_at TIMESTAMPTZ, UNIQUE(event_id,device_id)
);
