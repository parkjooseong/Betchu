import { useQueryClient } from '@tanstack/react-query';
import * as WebBrowser from 'expo-web-browser';
import {
  createContext,
  PropsWithChildren,
  useContext,
  useEffect,
  useState,
  useSyncExternalStore,
} from 'react';
import { Platform } from 'react-native';

import { AuthSession } from './auth-session';

const AuthContext = createContext<AuthSession | null>(null);

export function AuthProvider({ children }: PropsWithChildren) {
  const queryClient = useQueryClient();
  const [session] = useState(() => new AuthSession(() => queryClient.clear()));
  useEffect(() => {
    void session.restore();
  }, [session]);
  return <AuthContext.Provider value={session}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const session = useContext(AuthContext);
  if (!session) throw new Error('AuthProvider is required.');
  return {
    session,
    ...useSyncExternalStore(session.subscribe, session.getSnapshot, session.getSnapshot),
  };
}

export async function launchLogin(session: AuthSession) {
  const redirectUri =
    Platform.OS === 'web' ? `${window.location.origin}/auth/callback` : 'betchu://auth/callback';
  try {
    const authorizationUrl = await session.startLogin(redirectUri);
    if (Platform.OS === 'web') {
      window.location.assign(authorizationUrl);
      return;
    }
    const result = await WebBrowser.openAuthSessionAsync(authorizationUrl, redirectUri);
    if (result.type !== 'success') {
      await session.cancelLogin();
      return;
    }
    const callback = new URL(result.url);
    if (`${callback.protocol}//${callback.host}${callback.pathname}` !== redirectUri) {
      await session.cancelLogin();
      throw new Error('로그인 응답 주소를 확인할 수 없어요. 다시 로그인해 주세요.');
    }
    if (
      callback.searchParams.getAll('loginId').length !== 1 ||
      callback.searchParams.getAll('status').length !== 1 ||
      (callback.searchParams.get('status') === 'SUCCESS'
        ? callback.searchParams.getAll('handoffCode').length !== 1
        : callback.searchParams.has('handoffCode'))
    ) {
      await session.cancelLogin();
      throw new Error('로그인 응답을 확인할 수 없어요. 다시 로그인해 주세요.');
    }
    await session.completeLogin(
      callback.searchParams.get('loginId') ?? '',
      callback.searchParams.get('status') ?? '',
      callback.searchParams.get('handoffCode') ?? undefined,
    );
  } catch (error) {
    session.setError(error);
  }
}
