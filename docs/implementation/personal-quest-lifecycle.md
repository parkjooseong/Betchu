# Personal quest lifecycle

OpenAPI 0.6.0 implements the general PERSONAL/CUSTOM lifecycle on top of the
private draft and tutorial increments. The authoritative rules are in service
plan sections 6.4–6.5, 10–11, 14–15 and backend principle 22. `frontend` contains
the Expo implementation, `backend` the API, and `work` integrates both.

## Submission and privacy

Draft creation and editing do not consume resources and may precede tutorial
completion. Submission requires completed tutorial onboarding and an approval
deadline in the future. Submission snapshots all nine inputs in a new immutable
version. Re-submission must change at least one input. The partner can approve
the exact displayed version, request a revision with a message, or reject it.
Creators may recall a pending submission. Working revisions remain private;
partners can inspect the previous submitted snapshot and history. A recalled
snapshot has a RECALLED audit projection but leaves the partner's active list.
An initial private draft is inaccessible by guessing its UUID.

All lifecycle mutations use UUID Idempotency-Key and expectedRowVersion. Start
approval, rejection, recall and revision requests also bind questVersionId.
Result selection uses PUT, remains private to the partner, and does not settle.
The separate final approval binds selectedResult and selectionRevision as well
as the quest row version. Current relationship access is checked before replay.

## Time and budgets

The approval deadline is dueAt minus minimumDurationMinutes. Result selection
opens at resultAt, strictly after dueAt, and expires 24 hours later. Dates use
explicit UTC offsets, PostgreSQL microsecond precision and the existing draft
2000–2101 bound. General quests do not inherit the tutorial's seven-day horizon.
The API and maintenance worker use the database clock after acquiring locks.
Deadline transitions commit even when a stale action returns 409.

Approval atomically reserves the creator's resources using resultAt's calendar
date in Asia/Seoul, and locks the displayed terms:

- At most five active quests per user, including 0C quests.
- At most five XP slots per result date, including 0C quests.
- At most two quests with coins per result date.
- Reserved plus issued success bonuses at most 300C per result date, with
  at most 100C for custom/low difficulty rewards.
- CUSTOM stakes are 0C or 100C. Success bonus is at most 50%, reserved in 25C
  increments within both remaining limits. Difficulty 1–4 grants 10/20/40/70 XP.

The author-only approval quote exposes remaining personal limits and permitted
stakes. A partner receives a generic revision-required error for insufficient
resources, without the author's wallet balance or remaining private limits.
Quotes are estimates; approval revalidates under the same transaction locks.

## Settlement and cancellation

| Outcome after approval | Principal | Bonus / XP | Active slot | Daily XP / coin slots |
| --- | --- | --- | --- | --- |
| SUCCESS | Returned | Reserved bonus / difficulty XP plus streak bonus | Released | Remain used |
| FAILURE | Burned | None; streak reset | Released | Remain used |
| INVALID | Returned | None; streak unchanged | Released | Returned |
| Mutual cancellation before dueAt | Returned | None | Released | Remain used |
| Relationship ended before dueAt | Returned | None | Released | Remain used |
| Relationship ended at/after dueAt | Returned | None; INVALID | Released | Returned |

Cancellation requires the other partner's confirmation; a pending request does
not pause time and expires at dueAt. Unapproved termination moves no coins and
reserves no slots. A 0C approved quest uses auditable zero-delta ledger entries.
Wallet balances, ledger, budgets, unique settlement, actual result audit,
progression and outbox are one transaction. Retried final requests do not issue
additional rewards. Pending bonus reservations are released on every terminal
outcome; only successful rewards become issued amounts.

Personal success increments recognized success and streak. New streak lengths
3, 5 and 10 activate 10%, 20% and 30% XP bonuses. XP carries over level thresholds
80 + 20 × (level − 1), up to level 50; HP increases by 8 and attack by 2 per level.
Success/failure finalizations count once toward the current couple's weekly
activity target of 10, using Monday-to-Monday Seoul calendar weeks.

Growth milestones are claimed exactly once, including missed lower milestones:
first personal success hatches an egg, 20 gives INTERMEDIATE, 40 FINAL, 60 grants
species-bound aura/title/accessory records with zero combat bonuses, and 80
records a milestone/outbox event for the future new-egg flow. Existing starter
art has static stage variations; equipment interaction is not implemented.

Three coin invalidations within seven days for partner judgment refusal, final
refusal or result timeout suspend new coin approvals. Zero-coin approvals and
existing settlement/cancellation remain available. Both partners must acknowledge
the current suspension generation. The second acknowledgment starts a new risk
window; replaying an old acknowledgment cannot clear a later suspension.

Relationship end revokes access first and snapshots live quests in durable
cleanup jobs. Settlement classifies using the saved end time, even if cleanup
is delayed. Finalized quests remain unchanged. After 30 days, personal text,
revision messages and retained response bodies are redacted/purged; minimal
dates and financial records remain for safe retries. This retention redaction
is the sole exception to submitted-content immutability, after access has ended.

## Verification and boundaries

- PostgreSQL integration tests cover revisions, privacy, time transitions,
  reserved/issued limits, success/failure/invalid/cancel/end, duplicate requests,
  rollback on injected milestone failure, progression and suspension generations.
- Frontend tests cover displayed-version approval, stable retry keys, separate
  selection/final confirmation, role privacy, late relationship responses and
  explicit failure principal destruction.
- Actual HTTP requests validate OpenAPI responses, auth and role checks, CORS,
  revisions, final approval races, persisted wallets and relationship revocation.

This increment accepts CUSTOM quests with evidence NONE. Template catalogs,
photo evidence, OS push delivery, cosmetic equipment, 80-success egg selection,
gacha and boss gameplay remain separate work. Outbox events are durably recorded;
this does not imply external notification delivery. Real Google OAuth and
Android/iOS device flows require configured credentials and device verification.

Run focused backend checks with `./gradlew integrationTest --tests '*PersonalQuestIntegrationTest'`.
Run frontend checks with `corepack pnpm test -- quest-ui.test.tsx`. Full checks are
documented in each package README. PostgreSQL tests must report zero skipped tests
when a test database is available.
