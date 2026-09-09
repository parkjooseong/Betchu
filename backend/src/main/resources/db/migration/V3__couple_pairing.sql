CREATE TABLE couple_invites (
    id UUID PRIMARY KEY,
    inviter_id UUID NOT NULL REFERENCES users(id),
    code_hmac VARCHAR(64) NOT NULL UNIQUE,
    creation_key UUID NOT NULL,
    status VARCHAR(30) NOT NULL CHECK (status IN ('ACTIVE', 'PENDING_CONFIRMATION', 'USED', 'REVOKED', 'REJECTED', 'EXPIRED')),
    expires_at TIMESTAMPTZ NOT NULL,
    claimed_by UUID REFERENCES users(id),
    claimed_at TIMESTAMPTZ,
    inviter_confirmed_at TIMESTAMPTZ,
    invitee_confirmed_at TIMESTAMPTZ,
    used_by UUID REFERENCES users(id),
    used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (inviter_id, creation_key),
    CHECK (claimed_by IS NULL OR claimed_by <> inviter_id),
    CHECK (expires_at > created_at),
    CHECK (status NOT IN ('PENDING_CONFIRMATION', 'USED') OR claimed_by IS NOT NULL)
);
CREATE UNIQUE INDEX one_open_invitation_per_inviter ON couple_invites(inviter_id)
    WHERE status IN ('ACTIVE', 'PENDING_CONFIRMATION');
CREATE UNIQUE INDEX one_pending_invitation_per_invitee ON couple_invites(claimed_by)
    WHERE status = 'PENDING_CONFIRMATION';
CREATE INDEX expiring_couple_invites ON couple_invites(expires_at)
    WHERE status IN ('ACTIVE', 'PENDING_CONFIRMATION');

CREATE TABLE couple_pairing_slots (
    user_id UUID PRIMARY KEY REFERENCES users(id),
    invite_id UUID NOT NULL REFERENCES couple_invites(id),
    role VARCHAR(10) NOT NULL CHECK (role IN ('INVITER', 'INVITEE')),
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE (invite_id, role)
);

CREATE TABLE couples (
    id UUID PRIMARY KEY,
    source_invite_id UUID NOT NULL UNIQUE REFERENCES couple_invites(id),
    status VARCHAR(10) NOT NULL CHECK (status IN ('CONNECTED', 'ENDED')),
    connected_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    ended_by UUID REFERENCES users(id),
    CHECK ((status = 'CONNECTED' AND ended_at IS NULL AND ended_by IS NULL)
        OR (status = 'ENDED' AND ended_at IS NOT NULL AND ended_by IS NOT NULL))
);
CREATE TABLE couple_members (
    couple_id UUID NOT NULL REFERENCES couples(id),
    user_id UUID NOT NULL REFERENCES users(id),
    status VARCHAR(10) NOT NULL CHECK (status IN ('ACTIVE', 'ENDED')),
    joined_at TIMESTAMPTZ NOT NULL,
    left_at TIMESTAMPTZ,
    PRIMARY KEY (couple_id, user_id),
    CHECK ((status = 'ACTIVE' AND left_at IS NULL) OR (status = 'ENDED' AND left_at IS NOT NULL))
);
CREATE UNIQUE INDEX one_active_couple_per_user ON couple_members(user_id) WHERE status = 'ACTIVE';

CREATE TABLE user_blocks (
    blocker_id UUID NOT NULL REFERENCES users(id),
    blocked_id UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (blocker_id, blocked_id),
    CHECK (blocker_id <> blocked_id)
);

CREATE TABLE relationship_end_jobs (
    id UUID PRIMARY KEY,
    couple_id UUID NOT NULL UNIQUE REFERENCES couples(id),
    initiated_by UUID NOT NULL REFERENCES users(id),
    status VARCHAR(12) NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED')),
    target_resource_count INTEGER NOT NULL DEFAULT 0 CHECK (target_resource_count >= 0),
    processed_resource_count INTEGER NOT NULL DEFAULT 0 CHECK (processed_resource_count >= 0 AND processed_resource_count <= target_resource_count),
    idempotency_key UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CHECK (status <> 'COMPLETED' OR (completed_at IS NOT NULL AND processed_resource_count = target_resource_count))
);

-- Receipts remain bound to their original operation, even after a user connects again.
CREATE TABLE couple_action_receipts (
    user_id UUID NOT NULL REFERENCES users(id),
    idempotency_key UUID NOT NULL,
    action VARCHAR(10) NOT NULL CHECK (action IN ('END', 'BLOCK')),
    couple_id UUID REFERENCES couples(id),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, idempotency_key)
);

CREATE TABLE couple_code_attempts (
    user_id UUID PRIMARY KEY REFERENCES users(id),
    window_started_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts BETWEEN 1 AND 11)
);
