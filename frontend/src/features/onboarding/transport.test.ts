import { z } from 'zod';

import { request } from './transport';

afterEach(() => jest.restoreAllMocks());

it('uses safe local error messages instead of echoing server detail payloads', async () => {
  jest
    .spyOn(globalThis, 'fetch')
    .mockResolvedValue(
      new Response(
        JSON.stringify({ errorCode: 'INVALID_INVITE', detail: 'server-private-test-detail' }),
        { status: 400 },
      ),
    );
  await expect(request('/couples/invites/preview', z.object({}))).rejects.toThrow(
    '초대 코드를 확인해 주세요.',
  );
  await expect(request('/couples/invites/preview', z.object({}))).rejects.not.toThrow(
    'server-private',
  );
});

it('preserves unauthorized status so the session controller can refresh once', async () => {
  jest.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 401 }));
  await expect(request('/users/me', z.object({}))).rejects.toMatchObject({ status: 401 });
});

it('turns malformed success JSON into a retryable message', async () => {
  jest
    .spyOn(globalThis, 'fetch')
    .mockResolvedValue(new Response('<html>proxy error</html>', { status: 200 }));
  await expect(request('/auth/providers', z.object({}))).rejects.toThrow(
    '서버 응답을 확인하지 못했어요.',
  );
});
