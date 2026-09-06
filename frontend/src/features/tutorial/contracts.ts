import { z } from 'zod';

import type { components } from '@/api/generated/schema';

const dateTime = z.iso.datetime({ offset: true });
const count = z.number().int().nonnegative();
const result = z.enum(['SUCCESS', 'FAILURE']);
export const templateSchema = z
  .object({
    title: z.literal('자기 전에 물 한 잔 더 마시기'),
    category: z.literal('EATING'),
    successCriteria: z.literal('자기 전에 물 한 잔을 더 마셔요.'),
    difficulty: z.literal(1),
    stake: z.literal(100),
    reward: z.literal(100),
    xp: z.literal(10),
    minimumDurationMinutes: z.literal(0),
    evidenceMethod: z.literal('NONE'),
  })
  .strict() satisfies z.ZodType<components['schemas']['TutorialTemplate']>;
const termsSchema = templateSchema.extend({
  dueAt: dateTime,
  resultAt: dateTime,
  approvalDeadlineAt: dateTime,
  resultConfirmationDeadlineAt: dateTime,
});
export const statusLabels = {
  PENDING_APPROVAL: '시작 승인 대기',
  ACTIVE: '진행 중',
  AWAITING_RESULT: '결과 확인 대기',
  PENDING_FINAL_APPROVAL: '최종 확인 대기',
  SUCCESS: '성공',
  FAILURE: '실패',
  INVALID: '무효',
  REJECTED: '시작 거절',
  APPROVAL_EXPIRED: '시작 승인 기한 종료',
  CANCELED: '함께 취소함',
  CANCELED_RELATIONSHIP_ENDED: '연결 종료로 마침',
} as const;
export const statusSchema = z.enum([
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
]);
const actionsSchema = z.enum([
  'APPROVE',
  'REJECT',
  'SELECT_RESULT',
  'FINAL_APPROVE',
  'REJECT_RESULT',
  'CANCEL',
  'CONFIRM_CANCEL',
  'REJECT_CANCEL',
]);
const selectionSchema = z
  .object({ selectedResult: result, selectionRevision: count.min(1), selectedAt: dateTime })
  .strict();
const cancelSchema = z
  .object({
    id: z.string(),
    requestedBy: z.string(),
    status: z.enum(['PENDING', 'CONFIRMED', 'REJECTED', 'EXPIRED']),
    requestedAt: dateTime,
    respondedAt: dateTime.nullable(),
  })
  .strict();
const settlementSchema = z
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
    stake: count,
    reward: count,
    xp: count,
    creditedMonsterId: z.string().nullable(),
    recognizedSuccessBefore: count.nullable(),
    recognizedSuccessAfter: count.nullable(),
    growthStageBefore: z.string().nullable(),
    growthStageAfter: z.string().nullable(),
    settledAt: dateTime,
  })
  .strict();
const viewFields = {
  id: z.string(),
  coupleId: z.string(),
  creatorId: z.string(),
  status: statusSchema,
  rowVersion: count,
  questVersionId: z.string(),
  approvedQuestVersionId: z.string().nullable(),
  predictedResult: result.nullable(),
  terms: termsSchema,
  allowedActions: z.array(actionsSchema),
  cancelRequest: cancelSchema.nullable(),
  settlement: settlementSchema.nullable(),
  serverTime: dateTime,
};
export const tutorialSchema = z.discriminatedUnion('viewerRole', [
  z.object({ ...viewFields, viewerRole: z.literal('CREATOR') }).strict(),
  z
    .object({
      ...viewFields,
      viewerRole: z.literal('PARTNER'),
      partnerSelection: selectionSchema.nullable().optional(),
    })
    .strict(),
]) satisfies z.ZodType<components['schemas']['TutorialView']>;
export const overviewSchema = z
  .object({
    serverTime: dateTime,
    completedAt: dateTime.nullable(),
    template: templateSchema,
    canStart: z.boolean(),
    own: tutorialSchema.nullable(),
    partner: tutorialSchema.nullable(),
  })
  .strict()
  .superRefine((value, context) => {
    if (value.own && value.own.viewerRole !== 'CREATOR')
      context.addIssue({ code: 'custom', message: 'Unexpected own tutorial role' });
    if (value.partner && value.partner.viewerRole !== 'PARTNER')
      context.addIssue({ code: 'custom', message: 'Unexpected partner tutorial role' });
  }) satisfies z.ZodType<components['schemas']['TutorialOverview']>;
export type Tutorial = z.infer<typeof tutorialSchema>;
export type TutorialOverview = z.infer<typeof overviewSchema>;
export type TutorialAction = z.infer<typeof actionsSchema>;
export type Outcome = z.infer<typeof result>;
export const isTerminal = (status: Tutorial['status']) =>
  !['PENDING_APPROVAL', 'ACTIVE', 'AWAITING_RESULT', 'PENDING_FINAL_APPROVAL'].includes(status);
