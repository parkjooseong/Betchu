import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { PropsWithChildren } from 'react';
import { Text as MockText } from 'react-native';
import mockSafeAreaContext from 'react-native-safe-area-context/jest/mock';

import { HomePanel } from '@/features/game/home';
import {
  clearRelationshipData,
  finishRelationshipChange,
  observeRelationship,
} from '@/features/game/relationship-cache';
import { homeFixture, monsterFixture } from '@/features/game/test-fixtures';
import { useAuth } from '@/features/onboarding/auth-provider';
import type { AuthSession } from '@/features/onboarding/auth-session';
import { activeUser } from '@/features/onboarding/test-fixtures';
import { BabyIllustration } from '@/features/starter/baby-illustration';

import { tutorialKey } from './api';
import type { Tutorial } from './contracts';
import { TutorialHub, TutorialInteraction, TutorialStart } from './screen';
import {
  creatorFixture,
  overviewFixture,
  partnerFixture,
  successSettlement,
} from './test-fixtures';

jest.mock('@/features/onboarding/auth-provider', () => ({ useAuth: jest.fn() }));
jest.mock('react-native-safe-area-context', () => mockSafeAreaContext);
jest.mock('expo-router', () => ({
  Link: ({ children }: PropsWithChildren) => <MockText>{children}</MockText>,
  useFocusEffect: () => {},
}));
const request = jest.fn();
const session = { request } as unknown as AuthSession;
const clients: QueryClient[] = [];
async function mount(children: React.ReactNode) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false, gcTime: 0 },
    },
  });
  clients.push(client);
  observeRelationship(client, activeUser.id, 'couple-a', 'home');
  await render(<QueryClientProvider client={client}>{children}</QueryClientProvider>);
  return client;
}
afterEach(async () => {
  await cleanup();
  clients.splice(0).forEach((client) => client.clear());
});
beforeEach(() => {
  jest.clearAllMocks();
  jest
    .mocked(useAuth)
    .mockReturnValue({ session, user: activeUser, restoring: false, busy: false, error: null });
});

it('refreshes the home wallet and baby when an overview poll detects partner settlement', async () => {
  let settled = false;
  request.mockImplementation(async (path) => {
    if (path === '/tutorials')
      return {
        ...overviewFixture,
        canStart: false,
        own: {
          ...creatorFixture,
          status: settled ? 'SUCCESS' : 'PENDING_FINAL_APPROVAL',
          rowVersion: settled ? 4 : 3,
          settlement: settled ? successSettlement : null,
        },
      };
    if (path === '/home')
      return {
        ...homeFixture,
        self: {
          ...homeFixture.self,
          availableCoins: settled ? 1100 : 900,
          lockedCoins: settled ? 0 : 100,
          monster: {
            ...monsterFixture,
            growthStage: settled ? 'BABY' : 'EGG',
            currentLevelExp: settled ? 10 : 0,
          },
        },
      };
    throw new Error('Unexpected request');
  });
  const client = await mount(<HomePanel />);
  expect(await screen.findByText('900C')).toBeOnTheScreen();
  expect(await screen.findByText('최종 확인 대기')).toBeOnTheScreen();
  expect(screen.queryByRole('image', { name: /부화한 .* 아기 배츄/ })).not.toBeOnTheScreen();
  settled = true;
  await act(async () => {
    await client.refetchQueries({
      queryKey: [...tutorialKey(activeUser.id, 'couple-a'), 'overview'],
    });
  });
  expect(await screen.findByText('1,100C')).toBeOnTheScreen();
  expect(screen.getByText('잠긴 코인 0C · 내 계정의 잔액')).toBeOnTheScreen();
  expect(screen.getByRole('image', { name: /부화한 .* 아기 배츄/ })).toBeOnTheScreen();
  expect(screen.getByText('XP 10 / 80')).toBeOnTheScreen();
  const homeReads = request.mock.calls.filter(([path]) => path === '/home').length;
  await act(async () => {
    await client.refetchQueries({
      queryKey: [...tutorialKey(activeUser.id, 'couple-a'), 'overview'],
    });
  });
  expect(request.mock.calls.filter(([path]) => path === '/home')).toHaveLength(homeReads);
});

it('shows irreversible one-time sending and retries the identical schedule with the same key', async () => {
  const open = jest.fn();
  request
    .mockRejectedValueOnce(new Error('응답을 받지 못했어요.'))
    .mockResolvedValue(creatorFixture);
  await mount(
    <TutorialStart
      coupleId="couple-a"
      overview={overviewFixture}
      receivedAt={Date.now()}
      open={open}
    />,
  );
  await fireEvent.changeText(
    screen.getByLabelText('튜토리얼 수행 마감 (서울 시간)'),
    '2026-09-08 22:00',
  );
  await fireEvent.changeText(
    screen.getByLabelText('튜토리얼 결과 확인 시작 (서울 시간)'),
    '2026-09-09 09:00',
  );
  await fireEvent.press(screen.getByRole('button', { name: '보낼 일정 확인' }));
  expect(request).not.toHaveBeenCalled();
  expect(screen.getByText(/보내면 날짜와 조건을 고치거나 회수할 수 없어요/)).toBeOnTheScreen();
  await fireEvent.press(screen.getByRole('button', { name: '확인하고 첫 약속 보내기' }));
  await screen.findByText('응답을 받지 못했어요.');
  expect(open).not.toHaveBeenCalled();
  const first = request.mock.calls[0][2];
  await fireEvent.press(screen.getByRole('button', { name: '같은 일정으로 보내기 재시도' }));
  await waitFor(() => expect(open).toHaveBeenCalledWith('tutorial-a'));
  expect(request.mock.calls[1][2].headers).toEqual(first.headers);
  expect(first.body).toEqual({
    dueAt: '2026-09-08T22:00:00+09:00',
    resultAt: '2026-09-09T09:00:00+09:00',
  });
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '확인하고 첫 약속 보내기' })).not.toBeDisabled(),
  );
});

it('requires a prediction and a distinct approval confirmation with the exact version', async () => {
  request.mockResolvedValue({
    ...partnerFixture,
    status: 'ACTIVE',
    rowVersion: 1,
    approvedQuestVersionId: 'version-a',
    predictedResult: 'FAILURE',
    allowedActions: ['CANCEL'],
  });
  await mount(<TutorialInteraction tutorial={partnerFixture} />);
  for (const radio of screen.getAllByRole('radio')) expect(radio).not.toBeChecked();
  await fireEvent.press(screen.getByRole('button', { name: '예상 선택 후 시작 승인' }));
  expect(await screen.findByText('성공·실패 예상 중 하나를 골라 주세요.')).toBeOnTheScreen();
  expect(request).not.toHaveBeenCalled();
  await fireEvent.press(screen.getByRole('radio', { name: '시작 예상: 실패' }));
  await fireEvent.press(screen.getByRole('button', { name: '예상 선택 후 시작 승인' }));
  expect(request).not.toHaveBeenCalled();
  await fireEvent.press(screen.getByRole('button', { name: '확인하고 진행' }));
  await waitFor(() => expect(request).toHaveBeenCalledTimes(1));
  expect(request.mock.calls[0][2].body).toEqual({
    expectedRowVersion: 0,
    questVersionId: 'version-a',
    predictedResult: 'FAILURE',
  });
});

it('keeps result selection separate from final confirmation and shows growth only after settlement', async () => {
  let current: Tutorial = {
    ...partnerFixture,
    status: 'AWAITING_RESULT',
    rowVersion: 2,
    approvedQuestVersionId: 'version-a',
    predictedResult: 'SUCCESS',
    allowedActions: ['SELECT_RESULT', 'REJECT_RESULT'],
  };
  let release!: (value: Tutorial) => void;
  request.mockImplementation(async (path, _schema, options) => {
    if (path === '/tutorials') return { ...overviewFixture, canStart: false, partner: current };
    if (path === '/tutorials/tutorial-a') return current;
    if (path.endsWith('/select-result')) {
      current = {
        ...current,
        viewerRole: 'PARTNER',
        status: 'PENDING_FINAL_APPROVAL',
        rowVersion: 3,
        partnerSelection: {
          selectedResult: options.body.selectedResult,
          selectionRevision: 1,
          selectedAt: '2026-09-09T00:00:00Z',
        },
        allowedActions: ['SELECT_RESULT', 'FINAL_APPROVE', 'REJECT_RESULT'],
      };
      return current;
    }
    if (path.endsWith('/final-approve'))
      return new Promise<Tutorial>((resolve) => {
        release = resolve;
      });
    throw new Error('Unexpected request');
  });
  await mount(<TutorialHub coupleId="couple-a" selected="tutorial-a" open={jest.fn()} />);
  await fireEvent.press(await screen.findByRole('radio', { name: '실제 결과: 성공' }));
  expect(screen.queryByRole('button', { name: '저장된 결과 최종 확인' })).not.toBeOnTheScreen();
  await fireEvent.press(screen.getByRole('button', { name: '선택한 결과 저장' }));
  await fireEvent.press(await screen.findByRole('button', { name: '저장된 결과 최종 확인' }));
  expect(request.mock.calls.some(([path]) => path.endsWith('/final-approve'))).toBe(false);
  expect(
    screen.queryByText(
      '배츄가 아기로 부화했어요. 튜토리얼 성공은 일반 인정 성공 수에 포함되지 않아요.',
    ),
  ).not.toBeOnTheScreen();
  await fireEvent.press(screen.getByRole('button', { name: '확인하고 진행' }));
  await waitFor(() =>
    expect(request.mock.calls.some(([path]) => path.endsWith('/final-approve'))).toBe(true),
  );
  const final = request.mock.calls.find(([path]) => path.endsWith('/final-approve'));
  expect(final?.[2].body).toEqual({ expectedRowVersion: 3, selectedResult: 'SUCCESS' });
  expect(screen.queryByText('첫 약속을 마쳤어요')).not.toBeOnTheScreen();
  current = {
    ...current,
    status: 'SUCCESS',
    rowVersion: 4,
    allowedActions: [],
    settlement: successSettlement,
  };
  await act(async () => release(current));
  expect(await screen.findByText('반환한 원금 100C · 보너스 100C · 10XP')).toBeOnTheScreen();
  expect(
    screen.getByText(
      '배츄가 아기로 부화했어요. 튜토리얼 성공은 일반 인정 성공 수에 포함되지 않아요.',
    ),
  ).toBeOnTheScreen();
});

it('keeps creators in a waiting view without partner-only actions or an unconfirmed result', async () => {
  await mount(
    <TutorialInteraction
      tutorial={{
        ...creatorFixture,
        status: 'PENDING_FINAL_APPROVAL',
        rowVersion: 3,
        allowedActions: ['SELECT_RESULT', 'FINAL_APPROVE', 'REJECT_RESULT'],
      }}
    />,
  );
  expect(screen.getByText('파트너 확인 대기')).toBeOnTheScreen();
  expect(screen.queryByRole('radio')).not.toBeOnTheScreen();
  expect(screen.queryByRole('button', { name: '저장된 결과 최종 확인' })).not.toBeOnTheScreen();
  expect(request).not.toHaveBeenCalled();
});

it('does not end an active tutorial merely because cancellation was requested', async () => {
  let current: Tutorial = {
    ...creatorFixture,
    status: 'ACTIVE',
    rowVersion: 1,
    approvedQuestVersionId: 'version-a',
    predictedResult: 'SUCCESS',
    allowedActions: ['CANCEL'],
  };
  request.mockImplementation(async (path) => {
    if (path === '/tutorials') return { ...overviewFixture, canStart: false, own: current };
    if (path === '/tutorials/tutorial-a') return current;
    if (path.endsWith('/cancel')) {
      current = {
        ...current,
        rowVersion: 2,
        allowedActions: [],
        cancelRequest: {
          id: 'cancel-a',
          requestedBy: activeUser.id,
          status: 'PENDING',
          requestedAt: '2026-09-07T00:00:00Z',
          respondedAt: null,
        },
      };
      return current;
    }
    throw new Error('Unexpected request');
  });
  await mount(<TutorialHub coupleId="couple-a" selected="tutorial-a" open={jest.fn()} />);
  await fireEvent.press(await screen.findByRole('button', { name: '함께 취소 요청' }));
  expect(request.mock.calls.some(([path]) => path.endsWith('/cancel'))).toBe(false);
  await fireEvent.press(screen.getByRole('button', { name: '확인하고 진행' }));
  expect(await screen.findByText('상대방의 취소 동의를 기다려요')).toBeOnTheScreen();
  expect(screen.getByText(/취소 요청만으로 약속이나 마감 시각이 멈추지 않아요/)).toBeOnTheScreen();
  expect(screen.queryByText('첫 약속을 마쳤어요')).not.toBeOnTheScreen();
});

it('does not show approval controls or claim hatching after approval expiry', async () => {
  await mount(
    <TutorialInteraction
      tutorial={{
        ...partnerFixture,
        status: 'APPROVAL_EXPIRED',
        rowVersion: 1,
        allowedActions: [],
      }}
    />,
  );
  expect(screen.getByText('시작 승인 기한 종료')).toBeOnTheScreen();
  expect(screen.queryByRole('button', { name: '예상 선택 후 시작 승인' })).not.toBeOnTheScreen();
  expect(
    screen.getByText('코인을 잠그지 않고 마쳤어요. 보너스와 XP는 지급되지 않아요.'),
  ).toBeOnTheScreen();
  expect(screen.queryByText(/배츄가 아기로 부화했어요/)).not.toBeOnTheScreen();
});

it('requires the other participant to confirm a cancellation with its current row version', async () => {
  request.mockResolvedValue({ ...creatorFixture, status: 'CANCELED', rowVersion: 3 });
  await mount(
    <TutorialInteraction
      tutorial={{
        ...creatorFixture,
        status: 'ACTIVE',
        rowVersion: 2,
        allowedActions: ['CONFIRM_CANCEL', 'REJECT_CANCEL'],
        cancelRequest: {
          id: 'cancel-a',
          requestedBy: 'user-b',
          status: 'PENDING',
          requestedAt: '2026-09-07T00:00:00Z',
          respondedAt: null,
        },
      }}
    />,
  );
  await fireEvent.press(screen.getByRole('button', { name: '함께 취소에 동의' }));
  expect(request).not.toHaveBeenCalled();
  await fireEvent.press(screen.getByRole('button', { name: '확인하고 진행' }));
  await waitFor(() => expect(request).toHaveBeenCalled());
  expect(request.mock.calls[0][0]).toBe('/tutorials/tutorial-a/confirm-cancel');
  expect(request.mock.calls[0][2].body).toEqual({ expectedRowVersion: 2 });
});

it.each(['FAILURE', 'INVALID'] as const)(
  'shows only the authoritative principal refund for %s without a hatch',
  async (status) => {
    await mount(
      <TutorialInteraction
        tutorial={{
          ...creatorFixture,
          status,
          rowVersion: 4,
          settlement: {
            ...successSettlement,
            resolvedResult: status,
            reward: 0,
            xp: 0,
            growthStageBefore: 'EGG',
            growthStageAfter: 'EGG',
          },
        }}
      />,
    );
    expect(screen.getByText('반환한 원금 100C · 보너스 0C · 0XP')).toBeOnTheScreen();
    expect(screen.queryByText(/배츄가 아기로 부화했어요/)).not.toBeOnTheScreen();
  },
);

it('purges tutorial details and ignores a late send response after relationship change', async () => {
  const open = jest.fn();
  let release!: (value: Tutorial) => void;
  request.mockImplementation(
    () =>
      new Promise<Tutorial>((resolve) => {
        release = resolve;
      }),
  );
  const client = await mount(
    <TutorialStart
      coupleId="couple-a"
      overview={overviewFixture}
      receivedAt={Date.now()}
      open={open}
    />,
  );
  client.setQueryData([...tutorialKey(activeUser.id, 'couple-a'), 'detail', 'old'], partnerFixture);
  await fireEvent.changeText(
    screen.getByLabelText('튜토리얼 수행 마감 (서울 시간)'),
    '2026-09-08 22:00',
  );
  await fireEvent.changeText(
    screen.getByLabelText('튜토리얼 결과 확인 시작 (서울 시간)'),
    '2026-09-09 09:00',
  );
  await fireEvent.press(screen.getByRole('button', { name: '보낼 일정 확인' }));
  await fireEvent.press(screen.getByRole('button', { name: '확인하고 첫 약속 보내기' }));
  await waitFor(() => expect(request).toHaveBeenCalled());
  await act(async () => {
    await clearRelationshipData(client, activeUser.id);
    finishRelationshipChange(client, activeUser.id);
    release(creatorFixture);
  });
  expect(
    await screen.findByText('연결 상태가 바뀌었어요. 현재 상태를 다시 확인해 주세요.'),
  ).toBeOnTheScreen();
  expect(open).not.toHaveBeenCalled();
  expect(
    client.getQueryData([...tutorialKey(activeUser.id, 'couple-a'), 'detail', 'old']),
  ).toBeUndefined();
});

it.each(['STARLIGHT', 'WAVE', 'SUNSET', 'FOREST'] as const)(
  'labels a distinct hatched baby for %s',
  async (species) => {
    await mount(<BabyIllustration species={species} />);
    expect(screen.getByRole('image', { name: /부화한 .* 아기 배츄/ })).toBeOnTheScreen();
  },
);
