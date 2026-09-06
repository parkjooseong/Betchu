import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { PropsWithChildren } from 'react';
import { Text as MockText } from 'react-native';
import mockSafeAreaContext from 'react-native-safe-area-context/jest/mock';

import { useAuth } from '@/features/onboarding/auth-provider';
import type { AuthSession } from '@/features/onboarding/auth-session';
import { activeUser } from '@/features/onboarding/test-fixtures';
import { ApiError } from '@/features/onboarding/transport';
import { DraftEditor, EditDraftScreen, NewDraftScreen } from '@/features/quests/screens';
import { getStarterCatalog } from '@/features/starter/api';
import { catalogFixture } from '@/features/starter/test-fixtures';

import { HomePanel, StarterRegistration } from './home';
import { draftFixture, homeFixture, monsterFixture } from './test-fixtures';

jest.mock('@/features/onboarding/auth-provider', () => ({ useAuth: jest.fn() }));
jest.mock('@/features/starter/api', () => ({ getStarterCatalog: jest.fn() }));
jest.mock('react-native-safe-area-context', () => mockSafeAreaContext);
const mockReplace = jest.fn();
jest.mock('expo-router', () => ({
  Link: ({ children }: PropsWithChildren) => <MockText>{children}</MockText>,
  useRouter: () => ({ replace: mockReplace }),
  useLocalSearchParams: () => ({ questId: 'quest-a' }),
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
  jest.mocked(getStarterCatalog).mockResolvedValue(catalogFixture);
  request.mockImplementation(async (path) => {
    if (path === '/home') return homeFixture;
    if (path === '/quests/quest-a') return draftFixture;
    throw new Error('Unexpected route');
  });
});

it('only confirms a real starter after response and reuses the same key on retry', async () => {
  const onSaved = jest.fn();
  let fail = true;
  let resolve!: (value: typeof monsterFixture) => void;
  request.mockImplementation(async () => {
    if (fail) throw new Error('연결 실패');
    return new Promise((done) => {
      resolve = done;
    });
  });
  await mount(<StarterRegistration onSaved={onSaved} />);
  await fireEvent.press(await screen.findByRole('radio', { name: '숲 배츄' }));
  await fireEvent.changeText(screen.getByLabelText('배츄 이름'), '말랑이');
  await fireEvent.press(screen.getByRole('button', { name: '이 배츄와 시작하기' }));
  await screen.findByText('연결 실패');
  const firstKey = request.mock.calls[0][2].headers['Idempotency-Key'];
  fail = false;
  await fireEvent.press(screen.getByRole('button', { name: '배츄 저장 다시 시도' }));
  expect(onSaved).not.toHaveBeenCalled();
  await waitFor(() => expect(screen.getByLabelText('배츄 이름').props.editable).toBe(false));
  await act(async () => resolve(monsterFixture));
  await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1));
  expect(request.mock.calls[1][2].headers['Idempotency-Key']).toBe(firstKey);
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '이 배츄와 시작하기' })).not.toBeDisabled(),
  );
});
it('loads the saved monster when a second registration reports an existing starter', async () => {
  const saved = jest.fn();
  request.mockRejectedValue(new ApiError(409, 'STARTER_ALREADY_EXISTS'));
  await mount(<StarterRegistration onSaved={saved} />);
  await fireEvent.press(await screen.findByRole('radio', { name: '숲 배츄' }));
  await fireEvent.changeText(screen.getByLabelText('배츄 이름'), '말랑이');
  await fireEvent.press(screen.getByRole('button', { name: '이 배츄와 시작하기' }));
  await waitFor(() => expect(saved).toHaveBeenCalled());
  await screen.findByText('이미 나의 배츄가 있어요. 저장된 배츄를 다시 불러올게요.');
});
it('shows the real wallet and keeps owned monster after relationship end', async () => {
  request.mockResolvedValue({ ...homeFixture, coupleId: null, partner: null });
  await mount(<HomePanel />);
  expect(await screen.findByText('말랑이')).toBeOnTheScreen();
  expect(screen.getByText('1,000C')).toBeOnTheScreen();
  expect(screen.queryByText('새 퀘스트 초안 쓰기 →')).not.toBeOnTheScreen();
});
it('preserves edits on revision conflict and sends the original expected version', async () => {
  request.mockRejectedValue(new ApiError(409, 'QUEST_VERSION_CONFLICT'));
  await mount(<DraftEditor initial={draftFixture} />);
  await fireEvent.changeText(screen.getByLabelText('퀘스트 제목'), '내가 쓰던 제목');
  await fireEvent.press(screen.getByRole('button', { name: '내 초안 저장' }));
  expect(
    await screen.findByText('다른 곳에서 수정된 초안이에요. 지금 입력한 내용은 그대로 남겨뒀어요.'),
  ).toBeOnTheScreen();
  expect(screen.getByLabelText('퀘스트 제목')).toHaveDisplayValue('내가 쓰던 제목');
  expect(request.mock.calls[0][2]).toMatchObject({
    method: 'PATCH',
    body: { expectedRowVersion: 0, title: '내가 쓰던 제목', evidenceMethod: 'NONE' },
  });
  await fireEvent.press(screen.getByRole('button', { name: '서버의 최신 초안 불러오기' }));
  await fireEvent.press(screen.getByRole('button', { name: '현재 입력 유지' }));
  expect(screen.getByLabelText('퀘스트 제목')).toHaveDisplayValue('내가 쓰던 제목');
});
it('requires discard confirmation and sends version plus stable deletion key', async () => {
  request.mockResolvedValue({ id: 'quest-a', status: 'DISCARDED', rowVersion: 1 });
  await mount(<DraftEditor initial={draftFixture} />);
  await fireEvent.press(screen.getByRole('button', { name: '초안 폐기' }));
  expect(request).not.toHaveBeenCalled();
  await fireEvent.press(screen.getByRole('button', { name: '확인하고 초안 폐기' }));
  await waitFor(() => expect(mockReplace).toHaveBeenCalledWith('/quests'));
  expect(request.mock.calls[0][0]).toBe('/quests/quest-a/draft?expectedRowVersion=0');
  expect(request.mock.calls[0][2]).toMatchObject({
    method: 'DELETE',
    headers: { 'Idempotency-Key': expect.stringMatching(/^[0-9a-f-]{36}$/) },
  });
});
it('blocks disconnected deep links without requesting private drafts', async () => {
  request.mockResolvedValue({ ...homeFixture, coupleId: null, partner: null });
  await mount(<EditDraftScreen />);
  expect(
    await screen.findByText('커플 연결을 완료한 뒤 초안을 작성할 수 있어요.'),
  ).toBeOnTheScreen();
  expect(request.mock.calls.map(([path]) => path)).toEqual(['/home']);
});
it('does not retrieve draft data before active eligibility', async () => {
  jest.mocked(useAuth).mockReturnValue({
    session,
    user: { ...activeUser, missingPolicyVersionIds: ['new-policy'] },
    restoring: false,
    busy: false,
    error: null,
  });
  await mount(<NewDraftScreen />);
  expect(screen.getByText('홈에서 로그인과 가입 조건을 확인해 주세요.')).toBeOnTheScreen();
  expect(request).not.toHaveBeenCalled();
});

it('saves a real personal draft without a tutorial gate and without awarding coins or XP', async () => {
  request.mockImplementation(async (path) =>
    path === '/home'
      ? homeFixture
      : path === '/quests'
        ? draftFixture
        : Promise.reject(new Error('Unexpected route')),
  );
  await mount(<NewDraftScreen />);
  await fireEvent.changeText(await screen.findByLabelText('퀘스트 제목'), '물 한 잔 마시기');
  await fireEvent.changeText(screen.getByLabelText('성공 조건'), '자기 전에 물 한 잔 마시기');
  await fireEvent.changeText(screen.getByLabelText('수행 마감 (서울 시간)'), '2026-09-15 22:00');
  await fireEvent.changeText(
    screen.getByLabelText('결과 확인 시작 (서울 시간)'),
    '2026-09-16 09:00',
  );
  await fireEvent.press(screen.getByRole('button', { name: '내 초안 저장' }));
  await waitFor(() =>
    expect(mockReplace).toHaveBeenCalledWith({
      pathname: '/quests/[questId]',
      params: { questId: 'quest-a' },
    }),
  );
  const create = request.mock.calls.find(([path]) => path === '/quests');
  expect(create?.[2]).toMatchObject({
    method: 'POST',
    body: {
      title: '물 한 잔 마시기',
      stake: 0,
      evidenceMethod: 'NONE',
      dueAt: '2026-09-15T22:00:00+09:00',
    },
  });
  expect(
    request.mock.calls.some(
      ([path]) => path.includes('wallet') || path.includes('tutorial') || path.includes('submit'),
    ),
  ).toBe(false);
});
