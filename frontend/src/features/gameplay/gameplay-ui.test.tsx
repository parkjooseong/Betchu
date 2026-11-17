import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { PropsWithChildren } from 'react';
import { Text as MockText } from 'react-native';
import mockSafeAreaContext from 'react-native-safe-area-context/jest/mock';

import { useAuth } from '@/features/onboarding/auth-provider';
import { AuthSession } from '@/features/onboarding/auth-session';
import { activeUser, pendingUser } from '@/features/onboarding/test-fixtures';
import { ApiError } from '@/features/onboarding/transport';

import { Draw, Economy, economySchema } from './contracts';
import { CoopScreen, DrawPanel, InventoryScreen, RescueScreen } from './economy-screens';
import { noticeDestination } from './notification-screens';
import { DeleteAccountScreen, ReportScreen } from './safety-screens';

jest.mock('@/features/onboarding/auth-provider', () => ({ useAuth: jest.fn() }));
jest.mock('react-native-safe-area-context', () => mockSafeAreaContext);
const mockReplace = jest.fn();
jest.mock('expo-router', () => ({
  Link: ({ children }: PropsWithChildren) => <MockText>{children}</MockText>,
  useRouter: () => ({ replace: mockReplace }),
  useFocusEffect: () => {},
}));
const request = jest.fn(),
  clearSession = jest.fn(),
  logout = jest.fn();
const session = { request, clearSession, logout } as unknown as AuthSession;
const clients: QueryClient[] = [];
const item: Economy['catalog'][number] = {
  id: 'twig-sword',
  name: '새싹 검',
  rarity: 'COMMON',
  slot: 'WEAPON',
  hpBonus: 0,
  attackBonus: 3,
  combatPower: 60,
  duplicateFragments: 5,
};
const economy: Economy = {
  availableCoins: 1000,
  lockedCoins: 100,
  normalTickets: 1,
  catalog: [item],
  draws: [
    {
      versionId: 'NORMAL-v1',
      type: 'NORMAL',
      cost: 100,
      weights: [7500, 2000, 500, 0, 0],
      pityThreshold: 0,
      pityRarity: null,
      misses: 0,
      itemIds: [item.id],
    },
  ],
  inventory: [{ item, fragments: 5 }],
  equipment: [{ slot: 'WEAPON', itemId: null, rowVersion: 2 }],
  recentDraws: [],
};
const draw: Draw = {
  id: 'd8be1cef-e699-431b-803c-a54f0466a834',
  probabilityVersionId: 'NORMAL-v1',
  payment: 'COINS',
  cost: 100,
  item,
  pityApplied: false,
  pityBefore: 0,
  pityAfter: 0,
  duplicate: true,
  fragmentsGranted: 5,
  createdAt: '2026-10-15T00:00:00Z',
};
async function mount(children: React.ReactNode) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false, gcTime: 0 },
    },
  });
  clients.push(client);
  await render(<QueryClientProvider client={client}>{children}</QueryClientProvider>);
  return client;
}
beforeEach(() => {
  jest.clearAllMocks();
  clearSession.mockResolvedValue(undefined);
  logout.mockResolvedValue(undefined);
  jest
    .mocked(useAuth)
    .mockReturnValue({ session, user: activeUser, restoring: false, busy: false, error: null });
});
afterEach(async () => {
  await cleanup();
  clients.splice(0).forEach((client) => client.clear());
});

it('requires explicit payment confirmation, retains retry identity, and starts a new draw only after acknowledgment', async () => {
  let fail = true;
  request.mockImplementation(async () => {
    if (fail) throw new Error('연결 실패');
    return draw;
  });
  await mount(<DrawPanel economy={economy} />);
  await fireEvent.press(screen.getByRole('button', { name: '일반 뽑기 선택' }));
  expect(request).not.toHaveBeenCalled();
  await fireEvent.press(screen.getByRole('button', { name: '사용하고 1회 뽑기' }));
  await screen.findByText('연결 실패');
  const key = request.mock.calls[0][2].headers['Idempotency-Key'];
  expect(screen.getByRole('button', { name: '일반 뽑기권 선택' })).toBeDisabled();
  fail = false;
  await fireEvent.press(screen.getByRole('button', { name: '같은 뽑기 결과 다시 확인' }));
  await screen.findByText('이미 가진 장비예요. 조각 5개를 받았어요.');
  expect(request.mock.calls[1][2].headers['Idempotency-Key']).toBe(key);
  await fireEvent.press(screen.getByRole('button', { name: '결과 확인' }));
  await fireEvent.press(screen.getByRole('button', { name: '일반 뽑기권 선택' }));
  await fireEvent.press(screen.getByRole('button', { name: '사용하고 1회 뽑기' }));
  await waitFor(() => expect(request).toHaveBeenCalledTimes(3));
  expect(request.mock.calls[2][2].headers['Idempotency-Key']).not.toBe(key);
  expect(request.mock.calls[2][2].body.payment).toBe('TICKET');
});

it('does not accept two taps while a draw request is in flight', async () => {
  let resolve!: (value: Draw) => void;
  request.mockReturnValue(
    new Promise<Draw>((done) => {
      resolve = done;
    }),
  );
  await mount(<DrawPanel economy={economy} />);
  await fireEvent.press(screen.getByRole('button', { name: '일반 뽑기 선택' }));
  const button = screen.getByRole('button', { name: '사용하고 1회 뽑기' });
  await fireEvent.press(button);
  await fireEvent.press(button);
  expect(request).toHaveBeenCalledTimes(1);
  await act(async () => resolve(draw));
});

it('uses the displayed equipment revision and only offers owned items', async () => {
  request.mockResolvedValue(economy);
  await mount(<InventoryScreen />);
  await fireEvent.press(await screen.findByRole('button', { name: '장착하기' }));
  await waitFor(() =>
    expect(request).toHaveBeenCalledWith(
      '/equipment',
      expect.anything(),
      expect.objectContaining({
        method: 'PUT',
        body: { slot: 'WEAPON', itemId: 'twig-sword', rowVersion: 2 },
      }),
    ),
  );
});

it('keeps rescue completion disabled before the server reports its minimum time', async () => {
  request.mockResolvedValue({
    serverTime: '2026-10-15T00:00:00Z',
    eligible: true,
    reason: null,
    nextEligibleAt: null,
    quest: {
      id: draw.id,
      status: 'ACTIVE',
      title: '화면에서 눈을 떼고 1분 쉬기',
      startedAt: '2026-10-15T00:00:00Z',
      availableAt: '2026-10-15T00:01:00Z',
      expiresAt: '2026-10-16T00:00:00Z',
      completedAt: null,
    },
  });
  await mount(<RescueScreen />);
  await fireEvent.press(await screen.findByRole('checkbox'));
  expect(screen.getByRole('button', { name: '완료하고 100C 받기' })).toBeDisabled();
  expect(request.mock.calls.every(([path]) => path === '/rescue')).toBe(true);
});

it('conceals stale partner data when cooperation access fails', async () => {
  const member = {
    userId: draw.id,
    baselinePower: 700,
    currentPower: 900,
    weeklyGrowth: 200,
    joined: false,
    finalPower: null,
  };
  request.mockResolvedValue({
    serverTime: '2026-10-17T00:00:00Z',
    boss: {
      id: draw.id,
      weekStartKst: '2026-10-12',
      status: 'AVAILABLE',
      rowVersion: 1,
      requiredPower: 1700,
      chemistry: 10,
      opensAt: '2026-10-16T15:00:00Z',
      closesAt: '2026-10-18T15:00:00Z',
      self: member,
      partner: member,
    },
  });
  const client = await mount(<CoopScreen />);
  await screen.findByText('케미 10/10 · 목표 전투력 1700');
  request.mockRejectedValue(new ApiError(403));
  await act(async () => {
    await client.invalidateQueries({ queryKey: ['relationship-features'] });
  });
  await waitFor(() => expect(screen.queryByText('케미 10/10 · 목표 전투력 1700')).toBeNull());
});

it('requires both deletion confirmations and reauthentication without clearing a failed session', async () => {
  request.mockRejectedValue(new ApiError(403, 'REAUTHENTICATION_REQUIRED'));
  await mount(<DeleteAccountScreen />);
  expect(screen.getByRole('button', { name: '계정 삭제 요청' })).toBeDisabled();
  await fireEvent.press(screen.getByRole('checkbox'));
  await fireEvent.changeText(screen.getByLabelText('탈퇴 확인 문구'), '탈퇴합니다');
  await fireEvent.press(screen.getByRole('button', { name: '계정 삭제 요청' }));
  await screen.findByRole('button', { name: '로그아웃 후 다시 로그인하기' });
  expect(clearSession).not.toHaveBeenCalled();
  expect(request.mock.calls[0][2].body).toEqual({ confirmation: 'DELETE_MY_ACCOUNT' });
});

it('keeps reporting available to an account awaiting policy consent', async () => {
  jest
    .mocked(useAuth)
    .mockReturnValue({ session, user: pendingUser, restoring: false, busy: false, error: null });
  request.mockResolvedValue([]);
  await mount(<ReportScreen />);
  await screen.findByText('신고할 연결 기록이 없어요.');
  expect(request).toHaveBeenCalledWith('/reports/targets', expect.anything());
});

it('rejects invalid probability totals and unsafe notification links', () => {
  expect(economySchema.safeParse(economy).success).toBe(true);
  expect(
    economySchema.safeParse({
      ...economy,
      draws: [{ ...economy.draws[0], weights: [1, 2, 3, 4, 5] }],
    }).success,
  ).toBe(false);
  expect(noticeDestination('https://external.test/path')).toBe('/');
  expect(noticeDestination('/quest?questId=bad&token=private')).toBe('/');
  expect(noticeDestination(`/quest?questId=${draw.id}`)).toEqual({
    pathname: '/quest',
    params: { questId: draw.id },
  });
});
