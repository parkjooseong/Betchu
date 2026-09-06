import type { Tutorial, TutorialOverview } from './contracts';

export const templateFixture = {
  title: '자기 전에 물 한 잔 더 마시기',
  category: 'EATING',
  successCriteria: '자기 전에 물 한 잔을 더 마셔요.',
  difficulty: 1,
  stake: 100,
  reward: 100,
  xp: 10,
  minimumDurationMinutes: 0,
  evidenceMethod: 'NONE',
} as const;
export const creatorFixture = {
  id: 'tutorial-a',
  coupleId: 'couple-a',
  creatorId: 'user-a',
  viewerRole: 'CREATOR',
  status: 'PENDING_APPROVAL',
  rowVersion: 0,
  questVersionId: 'version-a',
  approvedQuestVersionId: null,
  predictedResult: null,
  terms: {
    ...templateFixture,
    dueAt: '2026-09-08T13:00:00Z',
    resultAt: '2026-09-09T00:00:00Z',
    approvalDeadlineAt: '2026-09-08T13:00:00Z',
    resultConfirmationDeadlineAt: '2026-09-10T00:00:00Z',
  },
  allowedActions: [],
  cancelRequest: null,
  settlement: null,
  serverTime: '2026-09-07T00:00:00Z',
} satisfies Tutorial;
export const partnerFixture = {
  ...creatorFixture,
  creatorId: 'user-b',
  viewerRole: 'PARTNER',
  partnerSelection: null,
  allowedActions: ['APPROVE', 'REJECT'],
} satisfies Tutorial;
export const overviewFixture = {
  serverTime: creatorFixture.serverTime,
  completedAt: null,
  template: templateFixture,
  canStart: true,
  own: null,
  partner: null,
} satisfies TutorialOverview;
export const successSettlement = {
  id: 'settlement-a',
  resolvedResult: 'SUCCESS',
  invalidReason: null,
  stake: 100,
  reward: 100,
  xp: 10,
  creditedMonsterId: 'monster-a',
  recognizedSuccessBefore: null,
  recognizedSuccessAfter: null,
  growthStageBefore: 'EGG',
  growthStageAfter: 'BABY',
  settledAt: '2026-09-09T01:00:00Z',
} as const;
