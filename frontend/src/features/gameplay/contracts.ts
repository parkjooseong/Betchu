import { z } from 'zod';

import type { components } from '@/api/generated/schema';

const count = z.number().int().nonnegative();
const instant = z.iso.datetime({ offset: true });
export const rarities = ['COMMON', 'RARE', 'EPIC', 'LEGENDARY', 'MYTHIC'] as const;
export const rarityNames = ['일반', '희귀', '영웅', '전설', '신화'];
export const slotNames = { WEAPON: '무기', ARMOR: '방어구', ACCESSORY: '장신구' };
const slot = z.enum(['WEAPON', 'ARMOR', 'ACCESSORY']);
export const itemSchema = z.object({
  id: z.string(),
  name: z.string(),
  rarity: z.enum(rarities),
  slot,
  hpBonus: count,
  attackBonus: count,
  combatPower: count,
  duplicateFragments: count,
}) satisfies z.ZodType<components['schemas']['GameplayItem']>;
export const drawSchema = z.object({
  id: z.uuid(),
  probabilityVersionId: z.string(),
  payment: z.enum(['COINS', 'TICKET']),
  cost: count,
  item: itemSchema,
  pityApplied: z.boolean(),
  pityBefore: count,
  pityAfter: count,
  duplicate: z.boolean(),
  fragmentsGranted: count,
  createdAt: instant,
}) satisfies z.ZodType<components['schemas']['DrawResult']>;
export const economySchema = z.object({
  availableCoins: count,
  lockedCoins: count,
  normalTickets: count,
  catalog: z.array(itemSchema),
  draws: z.array(
    z.object({
      versionId: z.string(),
      type: z.enum(['NORMAL', 'ADVANCED', 'LEGENDARY']),
      cost: count,
      weights: z
        .array(count)
        .length(5)
        .refine((weights) => weights.reduce((a, b) => a + b, 0) === 10000),
      pityThreshold: count,
      pityRarity: z.enum(rarities).nullable(),
      misses: count,
      itemIds: z.array(z.string()),
    }),
  ),
  inventory: z.array(z.object({ item: itemSchema, fragments: count })),
  equipment: z.array(z.object({ slot, itemId: z.string().nullable(), rowVersion: count })),
  recentDraws: z.array(drawSchema),
}) satisfies z.ZodType<components['schemas']['Economy']>;
export type Economy = z.infer<typeof economySchema>;
export type Draw = z.infer<typeof drawSchema>;
export type Item = z.infer<typeof itemSchema>;
export const rescueSchema = z.object({
  serverTime: instant,
  eligible: z.boolean(),
  reason: z.string().nullable(),
  nextEligibleAt: instant.nullable(),
  quest: z
    .object({
      id: z.uuid(),
      status: z.enum(['ACTIVE', 'COMPLETED', 'EXPIRED']),
      title: z.string(),
      startedAt: instant,
      availableAt: instant,
      expiresAt: instant,
      completedAt: instant.nullable(),
    })
    .nullable(),
}) satisfies z.ZodType<components['schemas']['RescueStatus']>;
const member = z.object({
  userId: z.uuid(),
  baselinePower: count,
  currentPower: count,
  weeklyGrowth: z.number().int(),
  joined: z.boolean(),
  finalPower: count.nullable(),
});
export const weekSchema = z.object({
  serverTime: instant,
  boss: z
    .object({
      id: z.uuid(),
      weekStartKst: z.iso.date(),
      status: z.enum([
        'LOCKED',
        'AVAILABLE',
        'WAITING_PARTNER',
        'CLEARED',
        'FAILED',
        'EXPIRED',
        'CANCELED_RELATIONSHIP_ENDED',
      ]),
      rowVersion: count,
      requiredPower: count,
      chemistry: count,
      opensAt: instant,
      closesAt: instant,
      self: member,
      partner: member,
    })
    .nullable(),
}) satisfies z.ZodType<components['schemas']['CoopWeek']>;
export const walletSchema = z.object({
  entries: z.array(
    z.object({
      id: z.uuid(),
      type: z.string(),
      availableDelta: z.number().int(),
      lockedDelta: z.number().int(),
      createdAt: instant,
    }),
  ),
  nextCursor: z.uuid().nullable(),
}) satisfies z.ZodType<components['schemas']['WalletHistory']>;
export const inboxSchema = z.object({
  notifications: z.array(
    z.object({
      id: z.uuid(),
      kind: z.string(),
      title: z.string(),
      route: z.string(),
      createdAt: instant,
      expiresAt: instant,
      read: z.boolean(),
    }),
  ),
  unreadCount: count,
}) satisfies z.ZodType<components['schemas']['NotificationInbox']>;
export const preferencesSchema = z.object({
  pushEnabled: z.boolean(),
  registeredDevices: count,
}) satisfies z.ZodType<components['schemas']['NotificationPreferences']>;
export const reportSchema = z.object({
  id: z.uuid(),
  status: z.string(),
  createdAt: instant,
}) satisfies z.ZodType<components['schemas']['ReportReceipt']>;
export const deletionSchema = z.object({
  status: z.enum(['PROCESSING', 'COMPLETED']),
  requestedAt: instant,
}) satisfies z.ZodType<components['schemas']['AccountDeletion']>;
export const exportSchema = z.object({
  formatVersion: z.literal(1),
  section: z.string(),
  generatedAt: instant,
  records: z.array(z.record(z.string(), z.unknown())),
  nextCursor: z.string().nullable(),
}) satisfies z.ZodType<components['schemas']['AccountExportPage']>;
