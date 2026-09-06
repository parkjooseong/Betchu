# BETCHU API contract

`openapi.yaml` is the shared OpenAPI 3.1 source for the mobile app and API.
Version 0.3.0 covers account onboarding, mutual couple pairing, and the public
starter preview. The server is authoritative for account status, coins,
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

Invite codes contain 16 cryptographically random characters and are stored only
as HMACs. A raw code is shown once; repeating its creation key returns the same
invite metadata with `code: null`. Use a new UUID key to rotate an invite. Preview
and join attempts share a persisted per-user rate limit. Both users must confirm
before the connection becomes active. Profiles never include wallet balances.

End/block receipts are bound to the original relationship and action, so retrying
an old UUID cannot terminate a later relationship. Blocking without a current
partner returns an explicit conflict. Relationship operations currently use a
PostgreSQL transaction advisory lock plus unique membership constraints;
partitioning that lock is a later performance improvement. Quest cleanup is not
claimed: quests and their settlement have not been implemented yet.

## Public starter preview

| Method | Path (under /api/v1) | Behavior |
| --- | --- | --- |
| GET | /system/health | Existing health response |
| GET | /monsters/starters | Four starter species, identical initial stats, name rules and growth milestones |
| POST | /monsters/starter-preview | Validate species/name and return an egg preview without writing state |

The preview never creates an account, connects a couple, grants coins or saves
a monster. Actual starter creation is a later implementation and must follow
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
