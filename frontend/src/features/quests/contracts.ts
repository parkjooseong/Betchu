import { z } from 'zod';

import type { components } from '@/api/generated/schema';

export const categories = {
  SLEEP: '취침',
  EXERCISE: '운동',
  STUDY: '공부',
  CONTACT: '연락',
  GAMING: '게임',
  SPENDING: '소비',
  HOUSEWORK: '집안일',
  EATING: '식습관',
  DATE: '데이트',
  CUSTOM: '자유 입력',
} as const;
export const categorySchema = z.enum([
  'SLEEP',
  'EXERCISE',
  'STUDY',
  'CONTACT',
  'GAMING',
  'SPENDING',
  'HOUSEWORK',
  'EATING',
  'DATE',
  'CUSTOM',
]);
export const draftSchema = z.object({
  id: z.string(),
  coupleId: z.string(),
  status: z.enum(['DRAFT', 'CHANGE_REQUESTED']),
  questType: z.literal('PERSONAL'),
  sourceType: z.literal('CUSTOM'),
  rowVersion: z.number().int().nonnegative(),
  title: z.string(),
  category: categorySchema,
  successCriteria: z.string(),
  difficulty: z.number().int().min(1).max(4),
  stake: z.union([z.literal(0), z.literal(100)]),
  dueAt: z.iso.datetime({ offset: true }),
  resultAt: z.iso.datetime({ offset: true }),
  minimumDurationMinutes: z.number().int().min(0).max(10080),
  evidenceMethod: z.literal('NONE'),
  approvalDeadlineAt: z.iso.datetime({ offset: true }),
  resultConfirmationDeadlineAt: z.iso.datetime({ offset: true }),
  createdAt: z.iso.datetime({ offset: true }),
  updatedAt: z.iso.datetime({ offset: true }),
}) satisfies z.ZodType<components['schemas']['QuestDraft']>;
export type Draft = z.infer<typeof draftSchema>;
export type DraftInput = Pick<
  Draft,
  | 'title'
  | 'category'
  | 'successCriteria'
  | 'difficulty'
  | 'stake'
  | 'dueAt'
  | 'resultAt'
  | 'minimumDurationMinutes'
  | 'evidenceMethod'
>;
export const draftsSchema = z.object({
  quests: z.array(draftSchema),
  nextCursor: z.string().nullable(),
});
export const discardedSchema = z.object({
  id: z.string(),
  status: z.literal('DISCARDED'),
  rowVersion: z.number().int().nonnegative(),
});
