import { AuthSession } from './auth-session';
import { userSchema } from './contracts';
import { LoginAttempt, SessionStorage } from './storage';
import { pendingUser, tokensFixture } from './test-fixtures';
import { ApiError } from './transport';

const attempt: LoginAttempt = {
  loginId: 'login-one',
  loginSecret: 'test-one-use-secret',
  expiresAt: '2099-01-01T00:00:00Z',
  redirectUri: 'betchu://auth/callback',
};

function setup() {
  let currentAttempt: LoginAttempt | null = attempt;
  const storage: jest.Mocked<SessionStorage> = {
    getAttempt: jest.fn(async () => currentAttempt),
    setAttempt: jest.fn(async (value) => {
      currentAttempt = value;
    }),
    clearAttempt: jest.fn(async () => {
      currentAttempt = null;
    }),
    getRefresh: jest.fn(async () => null),
    setRefresh: jest.fn<Promise<void>, [string]>().mockResolvedValue(undefined),
    clearRefresh: jest.fn(async () => undefined),
  };
  const send = jest.fn().mockResolvedValue(tokensFixture);
  const clear = jest.fn();
  const session = new AuthSession(clear, storage, send);
  return { session, storage, send, clear };
}

it.each([
  ['wrong-id', 'SUCCESS'],
  ['login-one', 'FAILED'],
  ['login-one', 'unknown'],
])(
  'rejects mismatched or failed callback %s/%s without erasing unrelated attempts',
  async (id, status) => {
    const { session, storage, send } = setup();
    await expect(session.completeLogin(id, status)).rejects.toThrow();
    if (id === 'login-one') expect(storage.clearAttempt).toHaveBeenCalled();
    else expect(storage.clearAttempt).not.toHaveBeenCalled();
    expect(send).not.toHaveBeenCalled();
    expect(session.getSnapshot().user).toBeNull();
  },
);

it('rejects expired login attempts without exchanging credentials', async () => {
  const { session, storage, send } = setup();
  storage.getAttempt.mockResolvedValue({ ...attempt, expiresAt: '2000-01-01T00:00:00Z' });
  await expect(session.completeLogin('login-one', 'SUCCESS', 'test-handoff')).rejects.toThrow(
    '만료',
  );
  expect(send).not.toHaveBeenCalled();
  expect(storage.clearAttempt).toHaveBeenCalled();
});

it('exchanges the stored secret only once even when native and route callbacks overlap', async () => {
  const { session, storage, send } = setup();
  await Promise.all([
    session.completeLogin('login-one', 'SUCCESS', 'test-handoff'),
    session.completeLogin('login-one', 'SUCCESS', 'test-handoff'),
  ]);
  expect(send).toHaveBeenCalledTimes(1);
  expect(send).toHaveBeenCalledWith('/auth/login', expect.anything(), {
    method: 'POST',
    body: { loginId: 'login-one', loginSecret: 'test-one-use-secret', handoffCode: 'test-handoff' },
  });
  expect(storage.setRefresh).toHaveBeenCalledWith('test-refresh');
  expect(session.getSnapshot().user).toEqual(pendingUser);
});

it('does not expose a signed-in user when secure refresh storage fails', async () => {
  const { session, storage, send } = setup();
  storage.setRefresh.mockRejectedValue(new Error('저장소에 접근할 수 없어요.'));
  await expect(session.completeLogin('login-one', 'SUCCESS', 'test-handoff')).rejects.toThrow(
    '저장소',
  );
  expect(session.getSnapshot().user).toBeNull();
  expect(send).toHaveBeenCalledWith(
    '/auth/logout',
    expect.anything(),
    { method: 'POST' },
    'test-access',
  );
});

it('does not open authorization when storing the login secret fails', async () => {
  const { session, storage, send } = setup();
  send.mockResolvedValue({
    ...attempt,
    authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth',
  });
  storage.setAttempt.mockRejectedValue(new Error('저장소에 접근할 수 없어요.'));
  await expect(session.startLogin(attempt.redirectUri)).rejects.toThrow('저장소');
  expect(session.getSnapshot().busy).toBe(false);
  expect(session.getSnapshot().user).toBeNull();
});

it('shares one rotating refresh for concurrent unauthorized requests', async () => {
  const { session, send } = setup();
  await session.completeLogin('login-one', 'SUCCESS', 'test-handoff');
  let release!: () => void;
  let entered!: () => void;
  const refreshing = new Promise<void>((resolve) => {
    release = resolve;
  });
  const started = new Promise<void>((resolve) => {
    entered = resolve;
  });
  send.mockImplementation(async (path, _schema, _options, token) => {
    if (path === '/auth/refresh') {
      entered();
      await refreshing;
      return { ...tokensFixture, accessToken: 'rotated-access', refreshToken: 'rotated-refresh' };
    }
    if (token === 'test-access') throw new ApiError(401);
    return pendingUser;
  });
  const first = session.request('/users/me', userSchema);
  const second = session.request('/users/me', userSchema);
  await started;
  release();
  await expect(Promise.all([first, second])).resolves.toEqual([pendingUser, pendingUser]);
  expect(send.mock.calls.filter(([path]) => path === '/auth/refresh')).toHaveLength(1);
});

it('clears user data immediately on logout and prevents late refresh from restoring it', async () => {
  const { session, send, clear, storage } = setup();
  await session.completeLogin('login-one', 'SUCCESS', 'test-handoff');
  let release!: () => void;
  let entered!: () => void;
  const deferred = new Promise<void>((resolve) => {
    release = resolve;
  });
  const started = new Promise<void>((resolve) => {
    entered = resolve;
  });
  send.mockImplementation(async (path) => {
    if (path === '/auth/refresh') {
      entered();
      await deferred;
      return { ...tokensFixture, accessToken: 'late-access' };
    }
    if (path === '/auth/logout') return undefined;
    throw new ApiError(401);
  });
  const protectedResult = expect(session.request('/users/me', userSchema)).rejects.toThrow();
  await started;
  const loggingOut = session.logout();
  expect(session.getSnapshot().user).toBeNull();
  expect(clear).toHaveBeenCalledTimes(2);
  await loggingOut;
  release();
  await protectedResult;
  expect(session.getSnapshot().user).toBeNull();
  expect(storage.setRefresh).toHaveBeenCalledTimes(1);
});

it('invalidates the session and cached personal data when refresh is revoked', async () => {
  const { session, send, clear, storage } = setup();
  await session.completeLogin('login-one', 'SUCCESS', 'test-handoff');
  send.mockRejectedValue(new ApiError(401));
  await expect(session.request('/users/me', userSchema)).rejects.toThrow('만료');
  expect(session.getSnapshot().user).toBeNull();
  expect(storage.clearRefresh).toHaveBeenCalled();
  expect(clear).toHaveBeenCalledTimes(2);
});

it('removes a canceled login attempt', async () => {
  const { session, storage } = setup();
  await session.cancelLogin();
  expect(await storage.getAttempt()).toBeNull();
  expect(session.getSnapshot().busy).toBe(false);
});

it('cannot restore a token read from storage after logout has cleared the session', async () => {
  const { session, storage, send } = setup();
  let release!: (value: string) => void;
  storage.getRefresh.mockImplementation(
    () =>
      new Promise((resolve) => {
        release = resolve;
      }),
  );
  const restoring = session.restore();
  await session.logout();
  release('stale-stored-refresh');
  await restoring;
  expect(send).not.toHaveBeenCalled();
  expect(session.getSnapshot().user).toBeNull();
});

it('requires the one-time code delivered only through the successful callback', async () => {
  const { session, send, storage } = setup();
  await expect(session.completeLogin('login-one', 'SUCCESS')).rejects.toThrow('로그인 완료 코드');
  expect(send).not.toHaveBeenCalled();
  expect(storage.clearAttempt).toHaveBeenCalled();
});

it('keeps failed handoff exchanges signed out and requires a new login attempt', async () => {
  const { session, send, storage } = setup();
  send.mockRejectedValue(new ApiError(401));
  await expect(
    session.completeLogin('login-one', 'SUCCESS', 'wrong-or-used-code'),
  ).rejects.toThrow();
  expect(session.getSnapshot().user).toBeNull();
  expect(await storage.getAttempt()).toBeNull();
});

it('revokes a late login result when the user cancels during exchange', async () => {
  const { session, send, storage } = setup();
  let release!: () => void;
  let enter!: () => void;
  const deferred = new Promise<void>((resolve) => {
    release = resolve;
  });
  const entered = new Promise<void>((resolve) => {
    enter = resolve;
  });
  send.mockImplementation(async (path) => {
    if (path === '/auth/login') {
      enter();
      await deferred;
      return tokensFixture;
    }
    return undefined;
  });
  const completing = expect(
    session.completeLogin('login-one', 'SUCCESS', 'test-handoff'),
  ).rejects.toThrow('취소');
  await entered;
  await session.cancelLogin();
  release();
  await completing;
  expect(session.getSnapshot().user).toBeNull();
  expect(storage.setRefresh).not.toHaveBeenCalled();
  expect(send).toHaveBeenCalledWith(
    '/auth/logout',
    expect.anything(),
    { method: 'POST' },
    'test-access',
  );
});

it('does not erase a newer login attempt when an old canceled callback arrives', async () => {
  const { session, storage, send } = setup();
  await session.cancelLogin();
  const newer = { ...attempt, loginId: 'login-two', loginSecret: 'new-secret' };
  await storage.setAttempt(newer);
  await expect(session.completeLogin('login-one', 'SUCCESS', 'old-handoff')).rejects.toThrow(
    '일치',
  );
  expect(await storage.getAttempt()).toEqual(newer);
  expect(send).not.toHaveBeenCalled();
});

it('never retries a previous user mutation with a newly signed-in user token', async () => {
  const { session, storage, send } = setup();
  await session.completeLogin('login-one', 'SUCCESS', 'test-handoff');
  let release!: () => void;
  let enter!: () => void;
  const deferred = new Promise<void>((resolve) => {
    release = resolve;
  });
  const entered = new Promise<void>((resolve) => {
    enter = resolve;
  });
  const secondUser = { ...pendingUser, id: 'user-b' };
  send.mockImplementation(async (path, _schema, _options, token) => {
    if (path === '/auth/refresh') {
      enter();
      await deferred;
      return { ...tokensFixture, accessToken: 'late-a' };
    }
    if (path === '/auth/logout') return undefined;
    if (path === '/auth/login')
      return { ...tokensFixture, accessToken: 'user-b-access', user: secondUser };
    if (token === 'test-access') throw new ApiError(401);
    return secondUser;
  });
  const oldRequest = expect(
    session.request('/onboarding/age-eligibility', userSchema, {
      method: 'POST',
      body: { eligible: true },
    }),
  ).rejects.toThrow();
  await entered;
  await session.logout();
  await storage.setAttempt({ ...attempt, loginId: 'login-two' });
  await session.completeLogin('login-two', 'SUCCESS', 'second-handoff');
  release();
  await oldRequest;
  expect(session.getSnapshot().user?.id).toBe('user-b');
  expect(
    send.mock.calls.filter(
      ([path, , , token]) => path === '/onboarding/age-eligibility' && token === 'user-b-access',
    ),
  ).toHaveLength(0);
  expect(send).toHaveBeenCalledWith(
    '/auth/logout',
    expect.anything(),
    { method: 'POST' },
    'late-a',
  );
});

it('reports failed native cleanup while keeping personal state cleared', async () => {
  const { session, storage, clear } = setup();
  await session.completeLogin('login-one', 'SUCCESS', 'test-handoff');
  storage.clearRefresh.mockRejectedValue(new Error('기기 저장소 삭제 실패'));
  await expect(session.logout()).rejects.toThrow('저장소');
  expect(session.getSnapshot().user).toBeNull();
  expect(clear).toHaveBeenCalledTimes(2);
  expect(session.getSnapshot().error).toContain('저장소');
});
