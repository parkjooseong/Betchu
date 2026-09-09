import { z } from 'zod';

import type { components } from '@/api/generated/schema';
import { categorySchema, draftSchema } from '@/features/quests/contracts';
import { statusLabels as tutorialLabels } from '@/features/tutorial/contracts';

const time = z.iso.datetime({ offset: true });
const count = z.number().int().nonnegative();
const result = z.enum(['SUCCESS', 'FAILURE']);
export const statusLabels = {
  ...tutorialLabels,
  DRAFT: '초안',
  CHANGE_REQUESTED: '수정 요청',
  DISCARDED: '폐기됨',
} as const;
const terms = z
  .object({
    title: z.string(),
    category: categorySchema,
    successCriteria: z.string(),
    difficulty: count.min(1).max(4),
    stake: z.union([z.literal(0), z.literal(100)]),
    reward: z.union([z.literal(0), z.literal(50)]),
    xp: z.union([z.literal(10), z.literal(20), z.literal(40), z.literal(70)]),
    minimumDurationMinutes: count.max(10080),
    evidenceMethod: z.literal('NONE'),
    dueAt: time,
    resultAt: time,
    approvalDeadlineAt: time,
    resultConfirmationDeadlineAt: time,
  })
  .strict();
export const versionSchema = z
  .object({
    id: z.string(),
    versionNo: count.min(1),
    terms,
    changedFields: z.array(
      z.enum([
        'title',
        'category',
        'successCriteria',
        'difficulty',
        'stake',
        'minimumDurationMinutes',
        'evidenceMethod',
        'dueAt',
        'resultAt',
      ]),
    ),
    submittedAt: time,
  })
  .strict();
const selection = z
  .object({ selectedResult: result, selectionRevision: count.min(1), selectedAt: time })
  .strict();
const settlement = z
  .object({
    id: z.string(),
    resolvedResult: z.enum([
      'SUCCESS',
      'FAILURE',
      'INVALID',
      'CANCELED',
      'CANCELED_RELATIONSHIP_ENDED',
    ]),
    invalidReason: z.string().nullable(),
    stake: z.union([z.literal(0), z.literal(100)]),
    reward: z.union([z.literal(0), z.literal(25), z.literal(50)]),
    xp: count.max(91),
    creditedMonsterId: z.string().nullable(),
    recognizedSuccessBefore: count.nullable(),
    recognizedSuccessAfter: count.nullable(),
    growthStageBefore: z.enum(['EGG', 'BABY', 'INTERMEDIATE', 'FINAL']).nullable(),
    growthStageAfter: z.enum(['BABY', 'INTERMEDIATE', 'FINAL']).nullable(),
    settledAt: time,
  })
  .strict();
const fields = {
  id: z.string(),
  coupleId: z.string(),
  creatorId: z.string(),
  status: z.enum([
    'DRAFT',
    'CHANGE_REQUESTED',
    'DISCARDED',
    'PENDING_APPROVAL',
    'ACTIVE',
    'AWAITING_RESULT',
    'PENDING_FINAL_APPROVAL',
    'SUCCESS',
    'FAILURE',
    'INVALID',
    'REJECTED',
    'APPROVAL_EXPIRED',
    'CANCELED',
    'CANCELED_RELATIONSHIP_ENDED',
  ]),
  auditProjectionStatus: z.literal('RECALLED').nullable(),
  rowVersion: count,
  questVersionId: z.string().nullable(),
  approvedQuestVersionId: z.string().nullable(),
  version: versionSchema.nullable(),
  predictedResult: result.nullable(),
  budgetDateKst: z.iso.date().nullable(),
  lockedStake: z.union([z.literal(0), z.literal(100)]).nullable(),
  rewardReservedAmount: z.union([z.literal(0), z.literal(25), z.literal(50)]).nullable(),
  decision: z
    .object({
      decision: z.enum(['APPROVE', 'REQUEST_CHANGE', 'REJECT']),
      requestMessage: z.string().nullable(),
      decidedAt: time,
    })
    .strict()
    .nullable(),
  cancelRequest: z
    .object({
      id: z.string(),
      requestedBy: z.string(),
      status: z.enum(['PENDING', 'CONFIRMED', 'REJECTED', 'EXPIRED']),
      requestedAt: time,
      respondedAt: time.nullable(),
    })
    .strict()
    .nullable(),
  settlement: settlement.nullable(),
  allowedActions: z.array(
    z.enum([
      'EDIT',
      'SUBMIT',
      'DISCARD',
      'RECALL',
      'REQUEST_CHANGE',
      'REJECT',
      'APPROVE',
      'SELECT_RESULT',
      'FINAL_APPROVE',
      'REJECT_RESULT',
      'CANCEL',
      'CONFIRM_CANCEL',
      'REJECT_CANCEL',
    ]),
  ),
  serverTime: time,
};
export const questSchema = z.discriminatedUnion('viewerRole', [
  z.object({ ...fields, viewerRole: z.literal('CREATOR'), draft: draftSchema.optional() }).strict(),
  z
    .object({ ...fields, viewerRole: z.literal('PARTNER'), partnerSelection: selection.optional() })
    .strict(),
]) satisfies z.ZodType<components['schemas']['PersonalQuestView']>;
export const pageSchema = z
  .object({ quests: z.array(questSchema), nextCursor: z.string().nullable(), serverTime: time })
  .strict();
export const versionsSchema = z
  .object({ versions: z.array(versionSchema), rowVersion: count })
  .strict();
export const quoteSchema = z
  .object({
    questId: z.string(),
    rowVersion: count,
    budgetDateKst: z.iso.date(),
    availableCoins: count,
    activeSlotsRemaining: count.max(5),
    xpSlotsRemaining: count.max(5),
    coinSlotsRemaining: count.max(2),
    totalBonusRemaining: count.max(300),
    lowBonusRemaining: count.max(100),
    expectedReward: z.union([z.literal(0), z.literal(25), z.literal(50)]),
    allowedStakes: z.array(z.union([z.literal(0), z.literal(100)])),
    canApprove: z.boolean(),
    blockingReason: z.string().nullable(),
  })
  .strict() satisfies z.ZodType<components['schemas']['PersonalQuestQuote']>;
export const rulesSchema = z
  .object({
    coupleId: z.string(),
    suspended: z.boolean(),
    generation: count,
    suspendedAt: time.nullable(),
    acknowledgedAt: time.nullable(),
    partnerAcknowledged: z.boolean(),
    serverTime: time,
  })
  .strict() satisfies z.ZodType<components['schemas']['PersonalQuestBettingRules']>;
export const summarySchema = z
  .object({
    weekStartAt: time,
    weekEndAt: time,
    current: count,
    target: z.literal(10),
    unlocked: z.boolean(),
  })
  .strict();
export type Quest = z.infer<typeof questSchema>;
export type QuestVersion = z.infer<typeof versionSchema>;
export type Outcome = z.infer<typeof result>;
