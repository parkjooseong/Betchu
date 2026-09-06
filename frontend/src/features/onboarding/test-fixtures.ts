import type { components } from '@/api/generated/schema';

export const pendingUser: components['schemas']['UserMe'] = {
  id: 'user-a',
  nickname: '배츄친구',
  status: 'PENDING_ELIGIBILITY',
  ageEligible: false,
  requiredPolicyVersionIds: ['terms-v1', 'privacy-v1'],
  missingPolicyVersionIds: ['terms-v1', 'privacy-v1'],
  evidenceConsent: false,
  availableCoins: null,
  lockedCoins: null,
};
export const activeUser: components['schemas']['UserMe'] = {
  ...pendingUser,
  status: 'ACTIVE',
  ageEligible: true,
  missingPolicyVersionIds: [],
  availableCoins: 1000,
  lockedCoins: 0,
};
export const tokensFixture: components['schemas']['AuthTokens'] = {
  accessToken: 'test-access',
  refreshToken: 'test-refresh',
  expiresIn: 300,
  user: pendingUser,
};
export const policyFixtures: components['schemas']['PolicyVersion'][] = [
  {
    id: 'terms-v1',
    policyType: 'TERMS',
    version: 'v1',
    locale: 'ko-KR',
    required: true,
    documentUrl: 'https://example.test/terms-v1',
    effectiveAt: '2026-09-01T00:00:00Z',
  },
  {
    id: 'privacy-v1',
    policyType: 'PRIVACY',
    version: 'v1',
    locale: 'ko-KR',
    required: true,
    documentUrl: 'https://example.test/privacy-v1',
    effectiveAt: '2026-09-01T00:00:00Z',
  },
  {
    id: 'evidence-v1',
    policyType: 'EVIDENCE_OPTIONAL',
    version: 'v1',
    locale: 'ko-KR',
    required: false,
    documentUrl: 'https://example.test/evidence-v1',
    effectiveAt: '2026-09-01T00:00:00Z',
  },
];
export const partnerFixture = { id: 'user-b', nickname: '파트너', profileImage: null };
export const pendingInviteFixture: components['schemas']['PendingCoupleInvite'] = {
  id: 'invite-a',
  status: 'PENDING_CONFIRMATION',
  role: 'INVITEE',
  expiresAt: '2099-01-01T00:00:00Z',
  partner: partnerFixture,
  myConfirmed: false,
  partnerConfirmed: false,
};
