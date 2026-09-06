import { z } from 'zod';

import type { components } from '@/api/generated/schema';

const count = z.number().int().nonnegative();
const milestone = z.enum(['HATCH', 'SUCCESS_20', 'SUCCESS_40', 'SUCCESS_60', 'SUCCESS_80']);
export const monsterSchema = z.object({
  id: z.string(),
  name: z.string(),
  species: z.enum(['STARLIGHT', 'WAVE', 'SUNSET', 'FOREST']),
  growthStage: z.enum(['EGG', 'BABY', 'INTERMEDIATE', 'FINAL']),
  recognizedSuccessCount: count,
  nextGrowthMilestone: milestone.nullable(),
  reachedMilestones: z.array(milestone),
  masteryRewards: z.array(z.string()).length(0),
  accountLevel: z.number().int().positive(),
  currentLevelExp: count,
  nextLevelRequiredExp: z.number().int().positive().nullable(),
  baseHp: count,
  baseAttack: count,
  equipmentBonusHp: z.literal(0),
  equipmentBonusAttack: z.literal(0),
  finalHp: count,
  finalAttack: count,
  combatPower: count,
  streak: count,
}) satisfies z.ZodType<components['schemas']['MonsterView']>;
export type Monster = z.infer<typeof monsterSchema>;
export const homeSchema = z.object({
  serverTime: z.string(),
  coupleId: z.string().nullable(),
  self: z.object({
    id: z.string(),
    nickname: z.string(),
    availableCoins: count,
    lockedCoins: count,
    monster: monsterSchema.nullable(),
  }),
  partner: z
    .object({
      id: z.string(),
      nickname: z.string(),
      profileImage: z.string().nullable(),
      monster: monsterSchema.nullable(),
    })
    .nullable(),
  ownDraftCount: count,
  questStatusSummary: z.object({ pendingApproval: count, active: count, awaitingResult: count }),
}) satisfies z.ZodType<components['schemas']['GameHome']>;
export const endStatusSchema = z.object({
  job: z
    .object({
      id: z.string(),
      status: z.enum(['PROCESSING', 'COMPLETED']),
      targetResourceCount: count,
      processedResourceCount: count,
      createdAt: z.string(),
      completedAt: z.string().nullable(),
    })
    .nullable(),
});
