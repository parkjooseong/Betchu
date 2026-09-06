# BETCHU API contract

`openapi.yaml` is the shared OpenAPI 3.1 source for the mobile app and API.
The first implementation increment is a public starter preview.

| Method | Path (under /api/v1) | Behavior |
| --- | --- | --- |
| GET | /system/health | Existing health response |
| GET | /monsters/starters | Four starter species, identical initial stats, name rules and growth milestones |
| POST | /monsters/starter-preview | Validate species/name and return an egg preview without writing state |

The preview never creates an account, connects a couple, grants coins or saves
a monster. Actual starter creation must follow account activation and mutual
couple confirmation in the later onboarding implementation. No authentication
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
