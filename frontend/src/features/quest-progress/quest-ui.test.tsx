import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { PropsWithChildren } from 'react';
import { Text as MockText } from 'react-native';

import { monsterSchema } from '@/features/game/contracts';
import { clearRelationshipData, observeRelationship } from '@/features/game/relationship-cache';
import { monsterFixture } from '@/features/game/test-fixtures';
import { useAuth } from '@/features/onboarding/auth-provider';
import type { AuthSession } from '@/features/onboarding/auth-session';
import { activeUser } from '@/features/onboarding/test-fixtures';
import { creatorFixture as tutorial } from '@/features/tutorial/test-fixtures';

import { Quest, questSchema } from './contracts';
import { QuestPanel } from './screen';

jest.mock('@/features/onboarding/auth-provider', () => ({ useAuth: jest.fn() }));
jest.mock('expo-router', () => ({
  Link: ({ children }: PropsWithChildren) => <MockText>{children}</MockText>,
  useFocusEffect: () => {},
}));
const request = jest.fn();
const clients: QueryClient[] = [];
const base: Quest = {
  id: 'quest-a',
  coupleId: 'couple-a',
  creatorId: 'user-b',
  viewerRole: 'PARTNER',
  status: 'PENDING_APPROVAL',
  auditProjectionStatus: null,
  rowVersion: 1,
  questVersionId: 'version-a',
  approvedQuestVersionId: null,
  version: {
    id: 'version-a',
    versionNo: 1,
    terms: { ...tutorial.terms, title: 'Read a chapter', category: 'STUDY', reward: 50 },
    changedFields: [],
    submittedAt: tutorial.serverTime,
  },
  predictedResult: null,
  budgetDateKst: null,
  lockedStake: null,
  rewardReservedAmount: null,
  decision: null,
  cancelRequest: null,
  settlement: null,
  allowedActions: ['APPROVE', 'REQUEST_CHANGE', 'REJECT'],
  serverTime: tutorial.serverTime,
};
async function mount(q: Quest) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false, gcTime: 0 },
    },
  });
  clients.push(client);
  observeRelationship(client, activeUser.id, 'couple-a', 'home');
  await render(
    <QueryClientProvider client={client}>
      <QuestPanel quest={q} />
    </QueryClientProvider>,
  );
  return client;
}
beforeEach(() => {
  jest.clearAllMocks();
  jest.mocked(useAuth).mockReturnValue({
    session: { request } as unknown as AuthSession,
    user: activeUser,
    restoring: false,
    busy: false,
    error: null,
  });
});
afterEach(async () => {
  await cleanup();
  clients.splice(0).forEach((c) => c.clear());
});
it('approves the displayed immutable version only after an explicit prediction', async () => {
  request.mockResolvedValue({
    ...base,
    status: 'ACTIVE',
    rowVersion: 2,
    allowedActions: ['CANCEL'],
  });
  await mount(base);
  expect(screen.getByText('이 제출본으로 시작 승인')).toBeDisabled();
  await fireEvent.press(screen.getByText('예상 결과 실패'));
  await fireEvent.press(screen.getByText('이 제출본으로 시작 승인'));
  await waitFor(() => expect(request).toHaveBeenCalledTimes(1));
  expect(request.mock.calls[0][0]).toBe('/quests/quest-a/approve');
  expect(request.mock.calls[0][2].body).toEqual({
    expectedRowVersion: 1,
    questVersionId: 'version-a',
    predictedResult: 'FAILURE',
  });
  expect(request.mock.calls[0][2].headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  await waitFor(() => expect(screen.getByText('이 제출본으로 시작 승인')).not.toBeDisabled());
});
it('reuses the same idempotency key after losing a response', async () => {
  request
    .mockRejectedValueOnce(new Error('Connection interrupted'))
    .mockResolvedValue({ ...base, status: 'ACTIVE', rowVersion: 2, allowedActions: [] });
  await mount(base);
  await fireEvent.press(screen.getByText('예상 결과 성공'));
  await fireEvent.press(screen.getByText('이 제출본으로 시작 승인'));
  await screen.findByText('Connection interrupted');
  await fireEvent.press(screen.getByText('이 제출본으로 시작 승인'));
  await waitFor(() => expect(request).toHaveBeenCalledTimes(2));
  expect(request.mock.calls[0][2].headers).toEqual(request.mock.calls[1][2].headers);
  await waitFor(() => expect(screen.getByText('이 제출본으로 시작 승인')).not.toBeDisabled());
});
it('stores a selection with PUT without finalizing or changing the wallet', async () => {
  request.mockResolvedValue({
    ...base,
    status: 'PENDING_FINAL_APPROVAL',
    partnerSelection: {
      selectedResult: 'SUCCESS',
      selectionRevision: 1,
      selectedAt: base.serverTime,
    },
  });
  await mount({
    ...base,
    status: 'AWAITING_RESULT',
    allowedActions: ['SELECT_RESULT', 'REJECT_RESULT'],
  });
  expect(screen.queryByText('저장된 결과 최종 확정')).not.toBeOnTheScreen();
  await fireEvent.press(screen.getByText('실제 결과 성공'));
  await fireEvent.press(screen.getByText('선택한 결과 저장'));
  await waitFor(() => expect(request).toHaveBeenCalledTimes(1));
  expect(request.mock.calls[0][0]).toBe('/quests/quest-a/partner-result');
  expect(request.mock.calls[0][2].method).toBe('PUT');
  await waitFor(() => expect(screen.getByText('선택한 결과 저장')).not.toBeDisabled());
});
it('blocks final approval when the local choice differs from the stored revision', async () => {
  await mount({
    ...base,
    status: 'PENDING_FINAL_APPROVAL',
    lockedStake: 100,
    rewardReservedAmount: 50,
    allowedActions: ['SELECT_RESULT', 'FINAL_APPROVE', 'REJECT_RESULT'],
    partnerSelection: {
      selectedResult: 'SUCCESS',
      selectionRevision: 2,
      selectedAt: base.serverTime,
    },
  });
  await fireEvent.press(screen.getByText('실제 결과 실패'));
  expect(screen.getByText('저장된 결과 최종 확정')).toBeDisabled();
  expect(request).not.toHaveBeenCalled();
  await fireEvent.press(screen.getByText('실제 결과 성공'));
  await fireEvent.press(screen.getByText('저장된 결과 최종 확정'));
  expect(request).not.toHaveBeenCalled();
  request.mockResolvedValue({ ...base, status: 'SUCCESS' });
  await fireEvent.press(screen.getByText('확인하고 진행'));
  await waitFor(() => expect(request).toHaveBeenCalledTimes(1));
  expect(request.mock.calls[0][2].body).toEqual({
    expectedRowVersion: 1,
    selectedResult: 'SUCCESS',
    selectionRevision: 2,
  });
  await waitFor(() => expect(screen.getByText('확인하고 진행')).not.toBeDisabled());
});
it('does not render result selectors for the creator', async () => {
  await mount({
    ...base,
    creatorId: activeUser.id,
    viewerRole: 'CREATOR',
    status: 'PENDING_FINAL_APPROVAL',
    allowedActions: [],
  });
  expect(screen.queryByText('실제 결과 확인')).not.toBeOnTheScreen();
  expect(
    screen.getByText('파트너가 실제 결과를 확인하고 있어요. 최종 확정 후 정산 내역이 표시돼요.'),
  ).toBeOnTheScreen();
});
it('rejects creator payloads leaking partner selection and partner payloads leaking drafts', () => {
  expect(
    questSchema.safeParse({
      ...base,
      viewerRole: 'CREATOR',
      partnerSelection: {
        selectedResult: 'SUCCESS',
        selectionRevision: 1,
        selectedAt: base.serverTime,
      },
    }).success,
  ).toBe(false);
  expect(questSchema.safeParse({ ...base, draft: {} }).success).toBe(false);
  expect(questSchema.safeParse(base).success).toBe(true);
});
it('shows failure as principal destruction rather than a refund', async () => {
  await mount({
    ...base,
    status: 'FAILURE',
    allowedActions: [],
    settlement: {
      id: 'settled',
      resolvedResult: 'FAILURE',
      invalidReason: null,
      stake: 100,
      reward: 0,
      xp: 0,
      creditedMonsterId: null,
      recognizedSuccessBefore: null,
      recognizedSuccessAfter: null,
      growthStageBefore: null,
      growthStageAfter: null,
      settledAt: base.serverTime,
    },
  });
  expect(screen.getByText('원금 100C 소멸 · 보상 0C · 경험치 0 XP')).toBeOnTheScreen();
});
it('discards a late action response after the relationship ends', async () => {
  let resolve!: (data: Quest) => void;
  request.mockImplementation(
    () =>
      new Promise<Quest>((r) => {
        resolve = r;
      }),
  );
  const client = await mount(base);
  await fireEvent.press(screen.getByText('예상 결과 성공'));
  await fireEvent.press(screen.getByText('이 제출본으로 시작 승인'));
  await waitFor(() => expect(request).toHaveBeenCalledTimes(1));
  await act(async () => {
    await clearRelationshipData(client, activeUser.id);
    resolve({ ...base, status: 'ACTIVE' });
  });
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/연결/));
  expect(client.getQueriesData({ queryKey: ['quests', activeUser.id] })).toEqual([]);
});
it('accepts awarded cosmetics and final growth in the home contract', () => {
  expect(
    monsterSchema.safeParse({
      ...monsterFixture,
      growthStage: 'FINAL',
      recognizedSuccessCount: 60,
      masteryRewards: ['STARLIGHT_AURA', 'STARLIGHT_TITLE', 'STARLIGHT_ACCESSORY'],
    }).success,
  ).toBe(true);
});
