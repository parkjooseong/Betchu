# BETCHU API contract

`openapi.yaml` is the shared OpenAPI 3.1 source for the mobile app and API.
Version 0.5.0 covers account onboarding, mutual couple pairing, permanent starter
registration, home, private quest drafts, the one-time tutorial lifecycle, and the
public starter preview. The server is authoritative for account status, coins,
policy versions, invite expiry, and relationship access.

## Account onboarding

| Method | Path (under /api/v1) | Behavior |
| --- | --- | --- |
| GET | /auth/providers | Google availability and registration readiness |
| POST | /auth/login/start | Start a server-generated OAuth state, nonce and PKCE attempt |
| GET | /auth/oauth/google/callback | Validate Google and return loginId, status and a one-time handoffCode to the app |
| POST | /auth/login | Exchange loginId, device-held loginSecret and callback-only handoffCode for a session |
| POST | /auth/refresh | Atomically rotate the opaque refresh token |
| POST | /auth/logout | Immediately revoke the current session |
| GET | /users/me | Current account eligibility, missing policy IDs and private coin balances |
| GET | /policy-versions/current | Published current ko-KR policies and document URLs |
| POST | /onboarding/age-eligibility | Record a boolean eligibility confirmation, or cancel a pending account |
| PUT | /consents/{policyType} | Record consent to an exact current policy version |

The app starts login before opening Google and keeps the loginSecret on the
initiating device. Successful login also requires the one-time handoffCode
delivered only to the callback device, so possessing a login attempt's starting
secret alone cannot claim a completed login on a different device. Both secrets
are stored only as hashes on the server. Access/refresh tokens never appear in
a callback URL; the app removes callback parameters after receipt. Access tokens expire
after five minutes and are also checked against online session revocation.
Pending accounts can only access onboarding/self/session operations. The last
required consent and age confirmation activate the account, wallet and single
1,000C signup ledger entry in one database transaction. Optional evidence
consent is not required. Cancelled/ineligible pending accounts are deleted
immediately; expired pending accounts are purged within 24 hours while the
server cleanup runs.

Google and signing settings are supplied through the backend environment.
Published policy documents are deliberately not seeded. Missing configuration
or required policies must be shown as unavailable, not replaced with fake login
or preaccepted consent. General APIs require an ACTIVE account and all current
required consents; logout and unilateral relationship end/block remain available
when policies need renewed consent.

## Couple pairing

| Method | Path (under /api/v1) | Behavior |
| --- | --- | --- |
| GET | /couples/me | Current partner or pending invite, scoped to the caller |
| POST | /couples/invites | Issue/rotate a 24-hour invite; requires Idempotency-Key |
| DELETE | /couples/invites/{inviteId} | Revoke an owned unused invite |
| POST | /couples/invites/preview | Show the inviter's permitted profile fields |
| POST | /couples/join | Claim an invite; does not yet establish a couple |
| POST | /couples/pending/{inviteId}/confirm | Record this user's independent confirmation |
| POST | /couples/pending/{inviteId}/reject | Reject the pending connection |
| POST | /couples/me/end | Unilaterally end the connection; requires Idempotency-Key |
| POST | /couples/me/block | End and block the current partner; requires Idempotency-Key |
| GET | /couples/me/end-status | Current cleanup progress for the caller, also available during policy reconsent |

Invite codes contain 16 cryptographically random characters and are stored only
as HMACs. A raw code is shown once; repeating its creation key returns the same
invite metadata with `code: null`. Use a new UUID key to rotate an invite. Preview
and join attempts share a persisted per-user rate limit. Both users must confirm
before the connection becomes active. Profiles never include wallet balances.

End/block receipts are bound to the original relationship and action, so retrying
an old UUID cannot terminate a later relationship. Blocking without a current
partner returns an explicit conflict. Relationship operations currently use a
PostgreSQL transaction advisory lock plus unique membership constraints;
partitioning that lock is a later performance improvement. End/block first commits
access revocation and a durable cleanup job. Draft cancellation then runs in
separate transactions; a failed item cannot restore relationship access. A new
connection is blocked until cleanup completes. End/block may return PROCESSING;
end-status reports target and processed counts for the caller's own resources only,
while status reflects completion of the whole job. Partner draft counts are never
returned. Retrying the original key
returns that original job's current status. Ended-relationship draft text, including any retained creation response, is purged
after 30 days even when cancellation is still retrying. Personal text export is not
implemented yet; former-relationship drafts remain unavailable in ordinary APIs.

## Public starter preview

| Method | Path (under /api/v1) | Behavior |
| --- | --- | --- |
| GET | /system/health | Existing health response |
| GET | /monsters/starters | Four starter species, identical initial stats, name rules and growth milestones |
| POST | /monsters/starter-preview | Validate species/name and return an egg preview without writing state |

The preview never creates an account, connects a couple, grants coins or saves
a monster. Permanent registration uses the authenticated endpoint below after
account activation and mutual couple confirmation. No authentication
token or idempotency key is required for these public preview endpoints.

Names are normalized to NFC, then trimmed using Unicode White_Space at both
ends. The normalized name must contain 1–10 Unicode code points. Internal
control/format characters and line separators are rejected. Extra JSON fields,
non-string fields, unsupported species and malformed JSON return HTTP 400 with
`application/problem+json` and `errorCode: INVALID_STARTER_SELECTION`.
User input must not appear in logs or error details.

The hatch threshold is the first recognized ordinary quest success, **or** a
successful tutorial with the recognized count still at zero. Later thresholds
are 20 (intermediate), 40 (final), 60 (cosmetic mastery), and 80 (MVP eligibility
record; egg choice is a P2 feature). Previewing never applies these milestones.

## Permanent starter and home

| Method | Path (under /api/v1) | Behavior |
| --- | --- | --- |
| POST | /monsters/starter | Atomically create one starter per user; UUID Idempotency-Key required |
| GET | /monsters/me | Personal active monster, or null |
| GET | /monsters/partner | Only the current connected partner's monster, or null |
| PATCH | /monsters/me/name | Free name change with the same Unicode rules as preview |
| GET | /home | Personal coins and monster, current partner, own draft count and shared quest status counts |

Registration stores level 1, 0 XP, 100 HP, 10 attack, 700 combat power and an egg
with zero recognized successes. It does not grant coins, XP or tutorial rewards.
The same key and normalized request replay the original creation response; a
different body returns IDEMPOTENCY_KEY_REUSED. A second creation key returns
STARTER_ALREADY_EXISTS. Personal monster ownership, name changes and wallet
access persist after relationship end. Partner data is always scoped to the
current relationship; partner balances and private draft counts are omitted.

## Author-only quest drafts

| Method | Path (under /api/v1) | Behavior |
| --- | --- | --- |
| POST | /quests | Create a PERSONAL/CUSTOM draft; UUID Idempotency-Key required |
| GET | /quests?status=DRAFT | Current author's drafts, cursor pagination; default 20, maximum 50 |
| GET | /quests/{questId} | Own current-relationship draft; all inaccessible IDs return 404 |
| PATCH | /quests/{questId}/draft | Replace the nine inputs using expectedRowVersion |
| DELETE | /quests/{questId}/draft?expectedRowVersion=N | Discard and delete text; UUID Idempotency-Key required |

Drafts require an active account, current required consents, a connected couple
and a personal starter. They can be written before tutorial completion and do
not reserve or deduct coins. The nine inputs are title, category,
successCriteria, difficulty, stake, dueAt, resultAt, minimumDurationMinutes and
evidenceMethod. This increment accepts only evidenceMethod NONE, difficulties
1–4 and CUSTOM stakes 0 or 100. General personal drafts do not yet offer
submission, approval, evidence upload or settlement. The separate tutorial
workflow below includes its own approval and settlement.

Initial engineering input limits are 1–80 Unicode code points for titles,
1–1000 for success criteria, and 0–10080 minimum-duration minutes. These limits
are implementation bounds, not numerical requirements from the product plan.
Text is normalized to NFC and edge-trimmed using Unicode White_Space; control
and format characters are rejected except internal LF in success criteria.
Dates require an explicit UTC offset and must be within 2000-01-01 inclusive
and 2101-01-01 exclusive in UTC. resultAt must be strictly later than dueAt.
Timestamps are truncated to PostgreSQL microsecond precision before comparison, hashing and storage. Past dates can be saved in drafts. Approval deadline is dueAt minus the minimum
duration; result confirmation deadline is resultAt plus 24 hours. The mobile
editor labels its date inputs as Korean time (UTC+09:00).

Version conflicts return QUEST_VERSION_CONFLICT without replacing newer data.
Repeated creation returns the original response without overwriting later edits.
Discard removes draft text and the retained creation response; creation retry
after discard returns 404. Repeated discard returns the original version once.
Reads, updates and idempotency retries cannot expose another user's draft or a
draft from a former relationship. Closing a relationship hides those drafts
immediately, even while cleanup retries are still pending.

## One-time tutorial

| Method | Path (under /api/v1) | Behavior |
| --- | --- | --- |
| GET | /tutorials | Fixed template, personal completion, ability to start and current pair's tutorials |
| POST | /tutorials | Send one fixed tutorial with dueAt/resultAt and a UUID Idempotency-Key |
| GET | /tutorials/{questId} | Current relationship and role-scoped tutorial state |
| POST | /tutorials/{questId}/approve | Partner approves the displayed version and prediction, locking 100C |
| POST | /tutorials/{questId}/reject | Partner rejects start before the approval deadline |
| POST | /tutorials/{questId}/select-result | Partner chooses or revises the actual result after result start |
| POST | /tutorials/{questId}/final-approve | Separately approve the displayed result and settle exactly once |
| POST | /tutorials/{questId}/reject-result | Reject judgment/final approval and refund the principal |
| POST | /tutorials/{questId}/cancel | Request mutual cancellation before the performance deadline |
| POST | /tutorials/{questId}/confirm-cancel | The other person confirms cancellation and refunds principal |
| POST | /tutorials/{questId}/reject-cancel | The other person declines cancellation; time continues |

All tutorial mutations require UUID Idempotency-Key. Version-sensitive actions
include expectedRowVersion; start approval/rejection also require questVersionId.
Final approval requires the currently selected result as well as its row version.
The server exposes allowedActions from the current state and role, and checks
the same conditions again under database locks. Timeouts take priority once a
deadline is reached, including while a request was waiting for its lock.

Sending does not charge coins. Start approval moves 100C from available to
locked balance. Final SUCCESS returns that 100C, grants 100C and 10XP, and
atomically hatches the starter with a unique HATCH milestone. Tutorial outcomes
never change recognized-success count, streak, chemistry or recurring budgets.
Other approved terminal outcomes return only the locked principal. Unapproved
terminal outcomes do not create coin transactions.

The actual pending result selection is present only in the partner projection;
the challenger sees the final settlement once approved. Neither role receives
the other person's full wallet balance or private drafts. Once the relationship
ends, ordinary tutorial detail and idempotent retries are inaccessible; personal
completion remains available without former relationship identifiers.

Each account can create one tutorial. Rejection, approval expiry and mutual
cancellation also record completion without rewards to prevent a permanent
onboarding lockout. This is an implementation decision for cases unspecified
in the plan. The fixed tutorial cannot be edited, recalled or resubmitted.
Its date inputs require explicit UTC offsets, a future dueAt, later resultAt,
and a maximum seven-day scheduling horizon. Result confirmation ends 24 hours
after resultAt. See [tutorial implementation decisions](../../docs/implementation/tutorial-lifecycle.md).

Relationship cleanup uses the stored end time: an approved tutorial ended
before dueAt is CANCELED_RELATIONSHIP_ENDED; at or after dueAt it is INVALID
with RELATIONSHIP_ENDED_BEFORE_FINAL_APPROVAL. All locked principal is returned
once. Access revocation commits before settlement, so a retryable settlement
failure cannot restore partner access. Personal monster and wallet survive.

## Branch workflow

Maintain the same contract on `frontend` and `backend`. Only the frontend branch
contains the mobile implementation/generated types; only the backend branch
contains the new controller/service/tests until the branches are integrated.
Do not merge an unrelated branch simply to synchronize the contract.

## Generate TypeScript types

Run from the repository root with Node.js and Corepack available:

```bash
corepack pnpm dlx openapi-typescript@7.13.0 packages/api-contract/openapi.yaml --output frontend/src/api/generated/schema.d.ts
cd frontend
corepack pnpm exec prettier --write src/api/generated/schema.d.ts
corepack pnpm typecheck
```

The pinned generator also validates references while loading the OpenAPI file.
Generation may download the development tool on first use. It does not add a
production dependency. Do not edit the generated declarations directly.
