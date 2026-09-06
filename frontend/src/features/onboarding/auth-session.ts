import { z } from 'zod';

import { emptySchema, loginStartSchema, tokensSchema, UserMe, userSchema } from './contracts';
import { SessionStorage, sessionStorage } from './storage';
import { ApiError, request, RequestOptions } from './transport';

type State = { user: UserMe | null; restoring: boolean; busy: boolean; error: string | null };
type Tokens = z.infer<typeof tokensSchema>;

export class AuthSession {
  private state: State = { user: null, restoring: true, busy: false, error: null };
  private listeners = new Set<() => void>();
  private accessToken: string | null = null;
  private refreshToken: string | null = null;
  private refreshing: Promise<void> | null = null;
  private completing: { id: string; promise: Promise<void> } | null = null;
  private generation = 0;
  private loginGeneration = 0;
  private refreshStorageQueue: Promise<unknown> = Promise.resolve();
  private attemptStorageQueue: Promise<unknown> = Promise.resolve();

  constructor(
    private clearUserData: () => void,
    private storage: SessionStorage = sessionStorage,
    private send: typeof request = request,
  ) {}

  getSnapshot = () => this.state;
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };
  private update(values: Partial<State>) {
    this.state = { ...this.state, ...values };
    this.listeners.forEach((listener) => listener());
  }
  setError(error: unknown) {
    this.update({
      error:
        error instanceof Error ? error.message : '요청을 처리하지 못했어요. 다시 시도해 주세요.',
    });
  }
  clearError() {
    this.update({ error: null });
  }
  setUser(user: UserMe) {
    if (this.accessToken && this.state.user?.id === user.id) this.update({ user });
  }

  private saveRefresh(token: string | null, generation?: number, loginGeneration?: number) {
    const operation = this.refreshStorageQueue.then(async () => {
      if (generation !== undefined && generation !== this.generation) return;
      if (loginGeneration !== undefined && loginGeneration !== this.loginGeneration) return;
      if (token) await this.storage.setRefresh(token);
      else await this.storage.clearRefresh();
    });
    this.refreshStorageQueue = operation.catch(() => undefined);
    return operation;
  }

  private attemptOperation<T>(operation: () => Promise<T>): Promise<T> {
    const result = this.attemptStorageQueue.then(operation);
    this.attemptStorageQueue = result.catch(() => undefined);
    return result;
  }

  private async install(
    tokens: Tokens,
    generation: number,
    loginGeneration?: number,
  ): Promise<boolean> {
    const current = () =>
      generation === this.generation &&
      (loginGeneration === undefined || loginGeneration === this.loginGeneration);
    if (!current()) return false;
    try {
      await this.saveRefresh(tokens.refreshToken, generation, loginGeneration);
    } catch (error) {
      await this.send('/auth/logout', emptySchema, { method: 'POST' }, tokens.accessToken).catch(
        () => undefined,
      );
      throw error;
    }
    if (!current()) return false;
    this.accessToken = tokens.accessToken;
    this.refreshToken = tokens.refreshToken;
    this.update({ user: tokens.user, error: null });
    return true;
  }

  async restore() {
    const generation = this.generation;
    try {
      const stored = await this.storage.getRefresh();
      if (generation !== this.generation) return;
      this.refreshToken = stored;
      if (this.refreshToken) await this.refresh();
    } catch (error) {
      if (generation === this.generation) this.setError(error);
    } finally {
      this.update({ restoring: false });
    }
  }

  async startLogin(redirectUri: string): Promise<string> {
    const generation = ++this.loginGeneration;
    this.update({ busy: true, error: null });
    try {
      await this.attemptOperation(() => this.storage.clearAttempt());
      const result = await this.send('/auth/login/start', loginStartSchema, {
        method: 'POST',
        body: { provider: 'GOOGLE', redirectUri },
      });
      const authorization = new URL(result.authorizationUrl);
      if (generation !== this.loginGeneration) throw new Error('로그인을 취소했어요.');
      if (authorization.protocol !== 'https:' || authorization.hostname !== 'accounts.google.com')
        throw new Error('로그인 연결 주소를 확인할 수 없어요. 다시 시도해 주세요.');
      await this.attemptOperation(async () => {
        if (generation !== this.loginGeneration) return;
        await this.storage.setAttempt({
          loginId: result.loginId,
          loginSecret: result.loginSecret,
          expiresAt: result.expiresAt,
          redirectUri,
        });
      });
      if (generation !== this.loginGeneration) throw new Error('로그인을 취소했어요.');
      return result.authorizationUrl;
    } catch (error) {
      if (generation === this.loginGeneration) {
        this.update({ busy: false });
        this.setError(error);
      }
      throw error;
    }
  }

  async cancelLogin() {
    this.loginGeneration += 1;
    try {
      await this.attemptOperation(() => this.storage.clearAttempt());
      if (!this.accessToken) await this.saveRefresh(null);
      this.update({ busy: false });
    } catch (error) {
      this.setError(error);
      throw error;
    }
  }

  completeLogin(loginId: string, status: string, handoffCode?: string): Promise<void> {
    if (this.completing?.id === loginId) return this.completing.promise;
    const promise = this.finishLogin(loginId, status, handoffCode);
    this.completing = { id: loginId, promise };
    return promise;
  }

  private async finishLogin(loginId: string, status: string, handoffCode?: string) {
    const generation = this.generation;
    const loginGeneration = this.loginGeneration;
    const current = () =>
      generation === this.generation && loginGeneration === this.loginGeneration;
    let ownsAttempt = false;
    try {
      const attempt = await this.attemptOperation(async () => {
        if (!current()) return null;
        const stored = await this.storage.getAttempt();
        if (stored?.loginId === loginId) await this.storage.clearAttempt();
        return stored;
      });
      if (!current()) throw new Error('로그인을 취소했어요.');
      if (!attempt || !loginId || attempt.loginId !== loginId)
        throw new Error(
          '이 기기에서 시작한 로그인과 일치하지 않아요. 처음부터 다시 로그인해 주세요.',
        );
      ownsAttempt = true;
      this.update({ busy: true, error: null });
      const expires = Date.parse(attempt.expiresAt);
      if (!Number.isFinite(expires) || expires <= Date.now())
        throw new Error('로그인 시간이 만료됐어요. 처음부터 다시 로그인해 주세요.');
      if (status !== 'SUCCESS')
        throw new Error('로그인을 완료하지 못했어요. 처음부터 다시 시도해 주세요.');
      if (!handoffCode)
        throw new Error('로그인 완료 코드를 확인하지 못했어요. 처음부터 다시 로그인해 주세요.');
      const tokens = await this.send('/auth/login', tokensSchema, {
        method: 'POST',
        body: { loginId: attempt.loginId, loginSecret: attempt.loginSecret, handoffCode },
      });
      if (current()) this.clearUserData();
      if (!(await this.install(tokens, generation, loginGeneration))) {
        try {
          await this.send('/auth/logout', emptySchema, { method: 'POST' }, tokens.accessToken);
        } catch {
          throw new Error(
            '로그인은 취소했지만 서버 세션 정리를 확인하지 못했어요. 잠시 후 다시 로그인해 주세요.',
          );
        }
        throw new Error('로그인을 취소했어요. 처음부터 다시 로그인해 주세요.');
      }
    } catch (error) {
      if (current() && ownsAttempt) this.setError(error);
      throw error;
    } finally {
      if (current() && ownsAttempt) this.update({ busy: false });
    }
  }

  private async refresh() {
    if (this.refreshing) return this.refreshing;
    const generation = this.generation;
    this.refreshing = (async () => {
      try {
        if (!this.refreshToken) throw new ApiError(401);
        const tokens = await this.send('/auth/refresh', tokensSchema, {
          method: 'POST',
          body: { refreshToken: this.refreshToken },
        });
        if (!(await this.install(tokens, generation))) {
          try {
            await this.send('/auth/logout', emptySchema, { method: 'POST' }, tokens.accessToken);
          } catch (error) {
            if (!(error instanceof ApiError) || error.status !== 401) throw error;
          }
        }
      } catch (error) {
        if (generation === this.generation) {
          await this.clearSession();
          this.setError(error);
        }
        throw error;
      }
    })();
    try {
      await this.refreshing;
    } finally {
      this.refreshing = null;
    }
  }

  async request<T>(path: string, schema: z.ZodType<T>, options: RequestOptions = {}): Promise<T> {
    const generation = this.generation;
    const originalToken = this.accessToken;
    if (!originalToken) throw new ApiError(401);
    options.assertCurrent?.();
    try {
      const result = await this.send(path, schema, options, originalToken);
      if (generation !== this.generation) throw new ApiError(401);
      return result;
    } catch (error) {
      if (generation !== this.generation || !(error instanceof ApiError) || error.status !== 401)
        throw error;
      if (this.accessToken === originalToken) await this.refresh();
      if (generation !== this.generation) throw new ApiError(401);
      if (!this.accessToken) throw new ApiError(401);
      options.assertCurrent?.();
      try {
        const result = await this.send(path, schema, options, this.accessToken);
        if (generation !== this.generation) throw new ApiError(401);
        return result;
      } catch (retryError) {
        if (
          generation === this.generation &&
          retryError instanceof ApiError &&
          retryError.status === 401
        ) {
          await this.clearSession();
          this.setError(retryError);
        }
        throw retryError;
      }
    }
  }

  async refreshUser() {
    const user = await this.request('/users/me', userSchema);
    this.setUser(user);
    return user;
  }

  async clearSession() {
    this.generation += 1;
    this.loginGeneration += 1;
    this.accessToken = null;
    this.refreshToken = null;
    this.clearUserData();
    this.update({ user: null, busy: false });
    try {
      await this.saveRefresh(null);
      await this.attemptOperation(() => this.storage.clearAttempt());
    } catch (error) {
      this.setError(error);
      throw error;
    }
  }

  async logout() {
    const accessToken = this.accessToken;
    const refreshToken = this.refreshToken;
    this.update({ error: null });
    let storageError: unknown;
    try {
      await this.clearSession();
    } catch (error) {
      storageError = error;
    }
    const generation = this.generation;
    let logoutError: unknown;
    try {
      if (accessToken) {
        try {
          await this.send('/auth/logout', emptySchema, { method: 'POST' }, accessToken);
        } catch (error) {
          if (!(error instanceof ApiError) || error.status !== 401 || !refreshToken) throw error;
          const fresh = await this.send('/auth/refresh', tokensSchema, {
            method: 'POST',
            body: { refreshToken },
          });
          await this.send('/auth/logout', emptySchema, { method: 'POST' }, fresh.accessToken);
        }
      }
    } catch (error) {
      logoutError = error;
    }
    if (storageError) throw storageError;
    if (logoutError && generation === this.generation)
      this.update({
        error:
          '이 기기의 로그인 정보는 지웠어요. 서버 로그아웃을 확인하지 못해 기존 세션은 만료까지 남을 수 있어요.',
      });
  }
}
