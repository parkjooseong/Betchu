import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';
import { z } from 'zod';

const attemptSchema = z.object({
  loginId: z.string(),
  loginSecret: z.string(),
  expiresAt: z.string(),
  redirectUri: z.string(),
});
export type LoginAttempt = z.infer<typeof attemptSchema>;
export interface SessionStorage {
  getAttempt(): Promise<LoginAttempt | null>;
  setAttempt(attempt: LoginAttempt): Promise<void>;
  clearAttempt(): Promise<void>;
  getRefresh(): Promise<string | null>;
  setRefresh(token: string): Promise<void>;
  clearRefresh(): Promise<void>;
}
const attemptKey = 'betchu.oauth-attempt';
const refreshKey = 'betchu.refresh-token';
const options = { keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY };

async function storageOperation<T>(operation: () => T | Promise<T>): Promise<T> {
  try {
    return await operation();
  } catch {
    throw new Error(
      '기기의 안전한 로그인 저장소에 접근할 수 없어요. 브라우저 설정이나 기기 잠금을 확인해 주세요.',
    );
  }
}

export const sessionStorage: SessionStorage = {
  async getAttempt() {
    const value = await storageOperation(() =>
      Platform.OS === 'web'
        ? globalThis.sessionStorage.getItem(attemptKey)
        : SecureStore.getItemAsync(attemptKey),
    );
    if (!value) return null;
    try {
      return attemptSchema.parse(JSON.parse(value));
    } catch {
      await this.clearAttempt();
      throw new Error('로그인 시도 정보를 확인할 수 없어요. 처음부터 다시 로그인해 주세요.');
    }
  },
  async setAttempt(attempt) {
    await storageOperation(() =>
      Platform.OS === 'web'
        ? globalThis.sessionStorage.setItem(attemptKey, JSON.stringify(attempt))
        : SecureStore.setItemAsync(attemptKey, JSON.stringify(attempt), options),
    );
  },
  async clearAttempt() {
    await storageOperation(() =>
      Platform.OS === 'web'
        ? globalThis.sessionStorage.removeItem(attemptKey)
        : SecureStore.deleteItemAsync(attemptKey),
    );
  },
  async getRefresh() {
    return Platform.OS === 'web'
      ? null
      : storageOperation(() => SecureStore.getItemAsync(refreshKey));
  },
  async setRefresh(token) {
    if (Platform.OS !== 'web')
      await storageOperation(() => SecureStore.setItemAsync(refreshKey, token, options));
  },
  async clearRefresh() {
    if (Platform.OS !== 'web')
      await storageOperation(() => SecureStore.deleteItemAsync(refreshKey));
  },
};
