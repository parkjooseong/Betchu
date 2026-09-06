import { act, render, screen } from '@testing-library/react-native';
import { PropsWithChildren } from 'react';
import { Text as MockText } from 'react-native';

import AuthCallbackScreen from '@/app/auth/callback';

import { useAuth } from './auth-provider';

const mockRouter = { setParams: jest.fn(), replace: jest.fn() };
let mockReady = true;
let mockListeners: Record<string, (() => void) | undefined> = {};
const mockNavigation = {
  isReady: () => mockReady,
  addListener: (event: string, listener: () => void) => {
    mockListeners[event] = listener;
    return () => {
      delete mockListeners[event];
    };
  },
};
let mockParams: Record<string, string> = {};
jest.mock('expo-router', () => ({
  Link: ({ children }: PropsWithChildren) => <MockText>{children}</MockText>,
  useRouter: () => mockRouter,
  useLocalSearchParams: () => mockParams,
  useNavigationContainerRef: () => mockNavigation,
}));
jest.mock('./auth-provider', () => ({ useAuth: jest.fn() }));

beforeEach(() => {
  jest.clearAllMocks();
  mockReady = true;
  mockListeners = {};
});

it('clears Expo navigation parameters while retaining the original callback for one exchange', async () => {
  mockParams = { loginId: 'login-one', status: 'SUCCESS', handoffCode: 'test-callback-code' };
  const completeLogin = jest.fn().mockRejectedValue(new Error('로그인 시도가 일치하지 않아요.'));
  jest
    .mocked(useAuth)
    .mockReturnValue({ session: { completeLogin } } as unknown as ReturnType<typeof useAuth>);
  await render(<AuthCallbackScreen />);
  expect(await screen.findByText('로그인 시도가 일치하지 않아요.')).toBeOnTheScreen();
  expect(mockRouter.setParams).toHaveBeenCalledWith({
    loginId: undefined,
    status: undefined,
    handoffCode: undefined,
  });
  expect(completeLogin).toHaveBeenCalledWith('login-one', 'SUCCESS', 'test-callback-code');
  mockParams = {};
  await screen.rerender(<AuthCallbackScreen />);
  expect(completeLogin).toHaveBeenCalledTimes(1);
});

it('waits for the mounted navigator before cleanup or exchange and handles readiness only once', async () => {
  mockReady = false;
  mockParams = { loginId: 'login-one', status: 'SUCCESS', handoffCode: 'test-callback-code' };
  const completeLogin = jest.fn().mockRejectedValue(new Error('테스트 로그인 오류'));
  jest
    .mocked(useAuth)
    .mockReturnValue({ session: { completeLogin } } as unknown as ReturnType<typeof useAuth>);
  await render(<AuthCallbackScreen />);
  expect(mockRouter.setParams).not.toHaveBeenCalled();
  expect(completeLogin).not.toHaveBeenCalled();
  expect(screen.getByRole('button', { name: '로그인 취소' })).toBeDisabled();
  await act(() => {
    mockReady = true;
    mockListeners.ready?.();
  });
  expect(await screen.findByText('테스트 로그인 오류')).toBeOnTheScreen();
  expect(mockRouter.setParams).toHaveBeenCalledTimes(1);
  expect(completeLogin).toHaveBeenCalledWith('login-one', 'SUCCESS', 'test-callback-code');
  await act(() => {
    mockListeners.state?.();
  });
  expect(completeLogin).toHaveBeenCalledTimes(1);
});
