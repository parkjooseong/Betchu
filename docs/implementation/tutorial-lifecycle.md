# Tutorial implementation decisions

This increment completes the one-time tutorial from sending through partner
approval, result confirmation, settlement and relationship cleanup. General
personal quests were private drafts at this increment. The subsequent [personal quest lifecycle](personal-quest-lifecycle.md) implements general submission and budget reservation.

## Product rules

The source is the service plan, sections 6.4, 6.5, 10 and backend principle 22.

- An ACTIVE account with current required consents, a connected couple and a
  personal starter can send one tutorial. A database constraint prevents a
  second tutorial for the same account, including after reconnection.
- The server fixes the title, success criteria, category, difficulty 1, stake
  100C, success bonus 100C, success XP 10, minimum duration 0 and evidence NONE.
  The sender chooses the performance deadline and result-confirmation start.
- Sending alone does not move coins. The partner must select a prediction and
  approve the exact displayed version before the approval deadline. Approval
  atomically moves 100C from available to locked balance.
- After the result start, only the partner chooses the actual result. Selection
  can be revised and does not settle the quest. Final approval is a separate
  action that confirms the displayed selection and row version.
- Success returns the principal and grants 100C plus 10XP. The same transaction
  records HATCH and changes the starter from EGG to BABY. Recognized success
  count remains zero. Tutorial outcomes never change streak, chemistry or
  recurring quest/XP/coin budget slots.
- Failure, invalidation, mutual cancellation or relationship end returns only
  the principal that was actually locked. It gives no bonus or XP and does not
  hatch the monster. An outcome without approval moves no coins.
- Pending cancellation does not stop the quest clock. Both people must agree
  before the performance deadline. Afterwards the normal result flow applies.

## Decisions for unspecified cases

The plan explicitly completes the tutorial on success, failure, invalidation or
relationship end, but does not define completion after start rejection,
approval expiry or mutual cancellation. This implementation also records
`tutorial_completed_at` for REJECTED, APPROVAL_EXPIRED and CANCELED. These paths
give no reward and consume the one-time tutorial. This prevents a permanently
blocked onboarding state when general quest submission later checks completion.
Ending a relationship before a tutorial has ever been created does not consume
the tutorial.

The fixed template uses “자기 전에 물 한 잔 더 마시기” so the sender can choose a
suitable future time. Both supplied dates must include a UTC offset and fall no
later than seven days after the server time; the performance deadline must be
in the future and result start strictly later. Values are truncated to
PostgreSQL microsecond precision. The seven-day bound and fixed wording are
initial implementation choices, not numerical requirements in the plan.

The fixed tutorial does not offer editable drafts, recall or resubmission.
Start rejection, approval expiry, mutual cancellation, result rejection and
relationship end provide explicit exit paths. General draft workflows are
unchanged.

## Time and transaction boundaries

Approval deadline equals performance deadline because minimum duration is zero.
Final confirmation deadline is result start plus 24 hours. The server checks
`clock_timestamp()` after acquiring the relationship and quest locks. Reaching
a deadline takes priority over a competing state change. Expiry is committed
even if the requested action is no longer applicable.

All mutations require a UUID Idempotency-Key. Keys bind the caller, action,
quest and request values. Replays cannot duplicate locks, refunds, bonuses,
XP or milestones. Version checks prevent an outdated prediction or actual
result from being approved. Settlement is unique per quest and its wallet,
completion, growth and audit writes commit together.

Relationship end first commits access revocation and a durable resource list.
The separate cleanup worker uses the saved relationship end time, not its own
execution time. An approved quest ended before its performance deadline is
CANCELED_RELATIONSHIP_ENDED. An approved quest ended at or after that deadline,
including before result start, is INVALID with
RELATIONSHIP_ENDED_BEFORE_FINAL_APPROVAL. Already committed outcomes are kept.
Cleanup failures do not restore access and may be retried without another refund.

## Privacy and current limits

Only the current connected pair can read a tutorial's relationship details.
Personal completion remains available after relationship end, without exposing
the previous relationship's identifiers or content. Partner projections never
contain the sender's total wallet balance or other private quest drafts. The
partner's unconfirmed actual selection is hidden from the challenger.

There are no photo uploads or external notification delivery in this increment.
The app provides current tutorial requests and state refresh while in use.
Personal data export and the full general-quest workflow remain future work.
