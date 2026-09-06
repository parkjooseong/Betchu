import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';

import { sessionStorage } from './storage';

jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(),
  setItemAsync: jest.fn(),
  deleteItemAsync: jest.fn(),
  WHEN_UNLOCKED_THIS_DEVICE_ONLY: 1,
}));
const originalPlatform = Platform.OS;
const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage');
const browserStore = { getItem: jest.fn(), setItem: jest.fn(), removeItem: jest.fn() };
const attempt = {
  loginId: 'login-one',
  loginSecret: 'test-secret',
  expiresAt: '2099-01-01T00:00:00Z',
  redirectUri: 'http://localhost:8081/auth/callback',
};

beforeEach(() => {
  jest.clearAllMocks();
  Object.defineProperty(Platform, 'OS', { configurable: true, value: 'web' });
  Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: browserStore });
});
afterEach(() => {
  Object.defineProperty(Platform, 'OS', { configurable: true, value: originalPlatform });
  if (originalStorage) Object.defineProperty(globalThis, 'sessionStorage', originalStorage);
  else Reflect.deleteProperty(globalThis, 'sessionStorage');
});

it('persists only the OAuth attempt in per-tab web storage, never bearer tokens', async () => {
  await sessionStorage.setAttempt(attempt);
  await sessionStorage.setRefresh('test-refresh');
  expect(browserStore.setItem).toHaveBeenCalledTimes(1);
  expect(browserStore.setItem).toHaveBeenCalledWith(
    'betchu.oauth-attempt',
    JSON.stringify(attempt),
  );
  expect(await sessionStorage.getRefresh()).toBeNull();
  expect(SecureStore.setItemAsync).not.toHaveBeenCalled();
});

it('reports blocked browser storage instead of navigating without a login secret', async () => {
  browserStore.setItem.mockImplementationOnce(() => {
    throw new Error('Storage denied');
  });
  await expect(sessionStorage.setAttempt(attempt)).rejects.toThrow('안전한 로그인 저장소');
});

it('removes malformed stored login attempts', async () => {
  browserStore.getItem.mockReturnValueOnce('{ invalid json');
  await expect(sessionStorage.getAttempt()).rejects.toThrow('로그인 시도 정보');
  expect(browserStore.removeItem).toHaveBeenCalledWith('betchu.oauth-attempt');
});

it('uses device-only secure native storage for refresh tokens', async () => {
  Object.defineProperty(Platform, 'OS', { configurable: true, value: 'ios' });
  await sessionStorage.setRefresh('test-native-refresh');
  expect(SecureStore.setItemAsync).toHaveBeenCalledWith(
    'betchu.refresh-token',
    'test-native-refresh',
    { keychainAccessible: 1 },
  );
  expect(browserStore.setItem).not.toHaveBeenCalled();
});
