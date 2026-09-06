import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { PropsWithChildren } from 'react';
import { Text as MockText } from 'react-native';
import mockSafeAreaContext from 'react-native-safe-area-context/jest/mock';

import { launchLogin, useAuth } from './auth-provider';
import type { AuthSession } from './auth-session';
import { Couples } from './couples';
import { Eligibility } from './eligibility';
import { OnboardingScreen } from './onboarding-screen';
import {
  activeUser,
  partnerFixture,
  pendingInviteFixture,
  pendingUser,
  policyFixtures,
} from './test-fixtures';
import { ApiError, request } from './transport';

jest.mock('./auth-provider', () => ({ useAuth: jest.fn(), launchLogin: jest.fn() }));
jest.mock('./transport', () => ({ ...jest.requireActual('./transport'), request: jest.fn() }));
jest.mock('react-native-safe-area-context', () => mockSafeAreaContext);
jest.mock('expo-router', () => ({
  Link: ({ children }: PropsWithChildren) => <MockText>{children}</MockText>,
}));

const requestMock = jest.fn();
const updateUser = jest.fn();
const clearSession = jest.fn();
const session = {
  request: requestMock,
  setUser: updateUser,
  refreshUser: jest.fn(async () => activeUser),
  clearSession,
  logout: jest.fn(),
  setError: jest.fn(),
  cancelLogin: jest.fn(),
} as unknown as AuthSession;
const useAuthMock = jest.mocked(useAuth);
const publicRequest = jest.mocked(request);

async function mount(children: React.ReactNode) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false, gcTime: 0 },
    },
  });
  await render(<QueryClientProvider client={client}>{children}</QueryClientProvider>);
}

beforeEach(() => {
  jest.clearAllMocks();
  useAuthMock.mockReturnValue({
    session,
    user: pendingUser,
    restoring: false,
    busy: false,
    error: null,
  });
  requestMock.mockResolvedValue(undefined);
});

it('keeps required and optional policies unchecked and saves only explicit required consents in order', async () => {
  requestMock.mockImplementation(async (path) =>
    path === '/consents/PRIVACY'
      ? activeUser
      : path === '/consents/TERMS'
        ? { ...pendingUser, ageEligible: true, missingPolicyVersionIds: ['privacy-v1'] }
        : { ...pendingUser, ageEligible: true },
  );
  await mount(<Eligibility ready policies={policyFixtures} refresh={jest.fn()} />);
  for (const checkbox of screen.getAllByRole('checkbox')) expect(checkbox).not.toBeChecked();
  await fireEvent.press(screen.getByRole('button', { name: '확인한 동의 저장' }));
  expect(screen.getByText('만 14세 이상 여부를 직접 확인해 주세요.')).toBeOnTheScreen();
  expect(requestMock).not.toHaveBeenCalled();
  await fireEvent.press(screen.getByRole('checkbox', { name: /저는 만 14세/ }));
  await fireEvent.press(screen.getByRole('checkbox', { name: '[필수] 서비스 이용약관' }));
  await fireEvent.press(screen.getByRole('checkbox', { name: '[필수] 개인정보 처리 동의' }));
  await fireEvent.press(screen.getByRole('button', { name: '확인한 동의 저장' }));
  await waitFor(() => expect(updateUser).toHaveBeenLastCalledWith(activeUser));
  expect(requestMock.mock.calls.map(([path]) => path)).toEqual([
    '/onboarding/age-eligibility',
    '/consents/TERMS',
    '/consents/PRIVACY',
  ]);
  expect(requestMock.mock.calls[1][2].body).toEqual({
    policyVersionId: 'terms-v1',
    locale: 'ko-KR',
  });
  expect(
    screen.getByRole('checkbox', { name: '[선택] 퀘스트 인증 자료 처리 동의' }),
  ).not.toBeChecked();
});

it('never marks activation on a partially failed consent save and keeps the failure actionable', async () => {
  requestMock.mockImplementation(async (path) => {
    if (path === '/consents/PRIVACY') throw new Error('정책 버전이 바뀌었어요.');
    return { ...pendingUser, ageEligible: true };
  });
  await mount(<Eligibility ready policies={policyFixtures} refresh={jest.fn()} />);
  await fireEvent.press(screen.getByRole('checkbox', { name: /저는 만 14세/ }));
  await fireEvent.press(screen.getByRole('checkbox', { name: '[필수] 서비스 이용약관' }));
  await fireEvent.press(screen.getByRole('checkbox', { name: '[필수] 개인정보 처리 동의' }));
  await fireEvent.press(screen.getByRole('button', { name: '확인한 동의 저장' }));
  expect(await screen.findByText('정책 버전이 바뀌었어요.')).toBeOnTheScreen();
  expect(updateUser).not.toHaveBeenCalledWith(activeUser);
  expect(screen.getByRole('button', { name: '확인한 동의 저장' })).not.toBeDisabled();
});

it('requires explicit confirmation before revoking an underage temporary account', async () => {
  await mount(<Eligibility ready={false} policies={[]} refresh={jest.fn()} />);
  await fireEvent.press(screen.getByRole('button', { name: '만 14세 미만이에요' }));
  expect(requestMock).not.toHaveBeenCalled();
  await fireEvent.press(screen.getByRole('button', { name: '임시 가입 삭제하기' }));
  await waitFor(() => expect(clearSession).toHaveBeenCalled());
  expect(requestMock).toHaveBeenCalledWith('/onboarding/age-eligibility', expect.anything(), {
    method: 'POST',
    body: { eligible: false },
  });
});

it('does not offer temporary account deletion to active accounts renewing policies', async () => {
  useAuthMock.mockReturnValue({
    session,
    user: { ...activeUser, missingPolicyVersionIds: ['terms-v1'] },
    restoring: false,
    busy: false,
    error: null,
  });
  await mount(<Eligibility ready policies={policyFixtures} refresh={jest.fn()} />);
  expect(screen.queryByRole('button', { name: '가입 취소' })).not.toBeOnTheScreen();
  expect(screen.queryByRole('button', { name: '만 14세 미만이에요' })).not.toBeOnTheScreen();
  expect(screen.getByText('현재 필수 정책을 다시 확인해 주세요')).toBeOnTheScreen();
});

it('keeps signup unavailable when the login provider is not configured', async () => {
  useAuthMock.mockReturnValue({ session, user: null, restoring: false, busy: false, error: null });
  publicRequest.mockImplementation(
    async (path) =>
      (path === '/auth/providers'
        ? {
            providers: [{ provider: 'GOOGLE', displayName: 'Google', enabled: false }],
            registrationAvailable: false,
          }
        : { ready: false, policies: [] }) as never,
  );
  await mount(<OnboardingScreen />);
  expect(await screen.findByText('지금은 가입을 준비 중이에요.')).toBeOnTheScreen();
  expect(screen.queryByRole('button', { name: 'Google로 계속하기' })).not.toBeOnTheScreen();
  expect(screen.getByText('스타팅 배츄 먼저 만나보기 →')).toBeOnTheScreen();
  expect(launchLogin).not.toHaveBeenCalled();
});

it('allows configured Google login while explaining that unpublished policies block activation', async () => {
  useAuthMock.mockReturnValue({ session, user: null, restoring: false, busy: false, error: null });
  publicRequest.mockImplementation(
    async (path) =>
      (path === '/auth/providers'
        ? {
            providers: [{ provider: 'GOOGLE', displayName: 'Google', enabled: true }],
            registrationAvailable: false,
          }
        : { ready: false, policies: [] }) as never,
  );
  await mount(<OnboardingScreen />);
  expect(await screen.findByRole('button', { name: 'Google로 계속하기' })).not.toBeDisabled();
  expect(screen.getByText(/신규 가입 완료와 초대는 필수 정책/)).toBeOnTheScreen();
});

it('preserves active-account safety actions when published policies are unavailable', async () => {
  useAuthMock.mockReturnValue({
    session,
    user: activeUser,
    restoring: false,
    busy: false,
    error: null,
  });
  publicRequest.mockImplementation(
    async (path) =>
      (path === '/auth/providers'
        ? {
            providers: [{ provider: 'GOOGLE', displayName: 'Google', enabled: true }],
            registrationAvailable: false,
          }
        : { ready: false, policies: [] }) as never,
  );
  await mount(<OnboardingScreen />);
  expect(await screen.findByRole('button', { name: '현재 연결 종료' })).toBeOnTheScreen();
  expect(requestMock).not.toHaveBeenCalled();
  expect(screen.queryByRole('button', { name: '임시 가입 삭제하기' })).not.toBeOnTheScreen();
});

it('allows unilateral safety actions during re-consent without requesting the restricted partner profile', async () => {
  useAuthMock.mockReturnValue({
    session,
    user: { ...activeUser, missingPolicyVersionIds: ['terms-v1'] },
    restoring: false,
    busy: false,
    error: null,
  });
  requestMock.mockResolvedValue({ status: 'COMPLETED' });
  await mount(<Couples safetyOnly />);
  expect(requestMock).not.toHaveBeenCalled();
  await fireEvent.press(screen.getByRole('button', { name: '현재 상대방 차단' }));
  expect(requestMock).not.toHaveBeenCalled();
  await fireEvent.press(screen.getByRole('button', { name: '확인하고 진행' }));
  expect(await screen.findByText('요청이 완료됐어요.')).toBeOnTheScreen();
  expect(requestMock).toHaveBeenCalledTimes(1);
  expect(requestMock).toHaveBeenCalledWith('/couples/me/block', expect.anything(), {
    method: 'POST',
    headers: { 'Idempotency-Key': expect.stringMatching(/^[0-9a-f-]{36}$/) },
  });
});

it('previews a real partner before joining and waits for both server-confirmed participants', async () => {
  useAuthMock.mockReturnValue({
    session,
    user: activeUser,
    restoring: false,
    busy: false,
    error: null,
  });
  let state: { couple: null; pendingInvite: typeof pendingInviteFixture | null } = {
    couple: null,
    pendingInvite: null,
  };
  requestMock.mockImplementation(async (path) => {
    if (path === '/couples/me') return state;
    if (path === '/couples/invites/preview')
      return {
        inviteId: 'invite-a',
        inviter: partnerFixture,
        expiresAt: pendingInviteFixture.expiresAt,
      };
    if (path === '/couples/join') {
      state = { couple: null, pendingInvite: pendingInviteFixture };
      return pendingInviteFixture;
    }
    if (path.includes('/confirm')) {
      state = { couple: null, pendingInvite: { ...pendingInviteFixture, myConfirmed: true } };
      return state;
    }
    throw new Error('Unexpected route');
  });
  await mount(<Couples />);
  await fireEvent.changeText(await screen.findByLabelText('받은 초대 코드'), 'CODE-CODE-CODE-CODE');
  await fireEvent.press(screen.getByRole('button', { name: '초대한 사람 먼저 확인' }));
  expect(await screen.findByText('나를 초대한 사람이 맞나요?')).toBeOnTheScreen();
  expect(requestMock.mock.calls.some(([path]) => path === '/couples/join')).toBe(false);
  await fireEvent.press(screen.getByRole('button', { name: '이 초대로 연결 요청' }));
  await fireEvent.press(await screen.findByRole('button', { name: '이 사람과 연결 확인' }));
  expect(
    await screen.findByText('확인했어요. 상대방의 최종 확인을 기다려 주세요.'),
  ).toBeOnTheScreen();
  expect(screen.queryByText('우리 둘, 연결됐어요')).not.toBeOnTheScreen();
});

it('shows an invalid-code error and retains the entered code for correction', async () => {
  useAuthMock.mockReturnValue({
    session,
    user: activeUser,
    restoring: false,
    busy: false,
    error: null,
  });
  requestMock.mockImplementation(async (path) => {
    if (path === '/couples/me') return { couple: null, pendingInvite: null };
    throw new ApiError(400, 'INVALID_INVITE');
  });
  await mount(<Couples />);
  await fireEvent.changeText(await screen.findByLabelText('받은 초대 코드'), 'WRONG');
  await fireEvent.press(screen.getByRole('button', { name: '초대한 사람 먼저 확인' }));
  expect(
    await screen.findByText('초대 코드를 확인해 주세요. 사용할 수 없거나 만료된 코드예요.'),
  ).toBeOnTheScreen();
  expect(screen.getByLabelText('받은 초대 코드')).toHaveDisplayValue('WRONG');
  expect(screen.queryByRole('button', { name: '이 초대로 연결 요청' })).not.toBeOnTheScreen();
});
