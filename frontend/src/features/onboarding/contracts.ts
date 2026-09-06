import { z } from 'zod';

import type { components } from '@/api/generated/schema';

export const userSchema = z.object({
  id: z.string(),
  nickname: z.string(),
  status: z.enum(['PENDING_ELIGIBILITY', 'ACTIVE', 'DELETION_PENDING', 'DELETED']),
  ageEligible: z.boolean(),
  requiredPolicyVersionIds: z.array(z.string()),
  missingPolicyVersionIds: z.array(z.string()),
  evidenceConsent: z.boolean(),
  availableCoins: z.number().int().nonnegative().nullable(),
  lockedCoins: z.number().int().nonnegative().nullable(),
}) satisfies z.ZodType<components['schemas']['UserMe']>;
export type UserMe = components['schemas']['UserMe'];
export const tokensSchema = z.object({
  accessToken: z.string().min(1),
  refreshToken: z.string().min(1),
  expiresIn: z.literal(300),
  user: userSchema,
}) satisfies z.ZodType<components['schemas']['AuthTokens']>;
export const providersSchema = z.object({
  providers: z.array(
    z.object({ provider: z.literal('GOOGLE'), displayName: z.string(), enabled: z.boolean() }),
  ),
  registrationAvailable: z.boolean(),
}) satisfies z.ZodType<components['schemas']['AuthProviders']>;
export const policySchema = z.object({
  id: z.string(),
  policyType: z.enum(['TERMS', 'PRIVACY', 'EVIDENCE_OPTIONAL']),
  version: z.string(),
  locale: z.literal('ko-KR'),
  required: z.boolean(),
  documentUrl: z.url(),
  effectiveAt: z.string(),
}) satisfies z.ZodType<components['schemas']['PolicyVersion']>;
export type Policy = components['schemas']['PolicyVersion'];
export const policiesSchema = z.object({
  ready: z.boolean(),
  policies: z.array(policySchema),
}) satisfies z.ZodType<components['schemas']['CurrentPolicies']>;
export const loginStartSchema = z.object({
  loginId: z.string().min(1),
  loginSecret: z.string().min(1),
  authorizationUrl: z.url(),
  expiresAt: z.string(),
}) satisfies z.ZodType<components['schemas']['LoginStart']>;
export const profileSchema = z.object({
  id: z.string(),
  nickname: z.string(),
  profileImage: z.string().nullable(),
}) satisfies z.ZodType<components['schemas']['CouplePublicProfile']>;
export const pendingInviteSchema = z.object({
  id: z.string(),
  status: z.enum(['ACTIVE', 'PENDING_CONFIRMATION']),
  role: z.enum(['INVITER', 'INVITEE']),
  expiresAt: z.string(),
  partner: profileSchema.nullable(),
  myConfirmed: z.boolean(),
  partnerConfirmed: z.boolean(),
}) satisfies z.ZodType<components['schemas']['PendingCoupleInvite']>;
export const coupleSchema = z.object({
  couple: z.object({ id: z.string(), connectedAt: z.string(), partner: profileSchema }).nullable(),
  pendingInvite: pendingInviteSchema.nullable(),
}) satisfies z.ZodType<components['schemas']['CoupleState']>;
export const inviteSchema = z.object({
  inviteId: z.string(),
  code: z.string().nullable(),
  expiresAt: z.string(),
}) satisfies z.ZodType<components['schemas']['CreatedCoupleInvite']>;
export const invitePreviewSchema = z.object({
  inviteId: z.string(),
  inviter: profileSchema,
  expiresAt: z.string(),
}) satisfies z.ZodType<components['schemas']['CoupleInvitePreview']>;
export const completedSchema = z.object({ status: z.literal('COMPLETED') }) satisfies z.ZodType<
  components['schemas']['CoupleEndResult']
>;
export const emptySchema = z.undefined();
