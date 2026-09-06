import { apiClient } from '@/api/client';

import { getStarterCatalog, getStarterPreview } from './api';
import { catalogFixture, previewFixture } from './test-fixtures';

jest.mock('@/api/client', () => ({ apiClient: { GET: jest.fn(), POST: jest.fn() } }));

const getMock = jest.mocked(apiClient.GET);
const postMock = jest.mocked(apiClient.POST);

beforeEach(() => jest.clearAllMocks());
afterEach(() => jest.useRealTimers());

it('reads the actual catalog route and validates the response', async () => {
  getMock.mockResolvedValue({
    data: catalogFixture,
    response: new Response(null, { status: 200 }),
  });
  expect(await getStarterCatalog()).toEqual(catalogFixture);
  expect(getMock).toHaveBeenCalledWith('/monsters/starters', { signal: expect.any(AbortSignal) });
});

it('posts species and name to the preview endpoint', async () => {
  postMock.mockResolvedValue({
    data: previewFixture,
    response: new Response(null, { status: 200 }),
  });
  expect(await getStarterPreview({ species: 'WAVE', name: '말랑이' })).toEqual(previewFixture);
  expect(postMock).toHaveBeenCalledWith('/monsters/starter-preview', {
    body: { species: 'WAVE', name: '말랑이' },
    signal: expect.any(AbortSignal),
  });
});

it('shows the typed validation detail returned by the server', async () => {
  postMock.mockResolvedValue({
    error: {
      type: 'about:blank',
      status: 400,
      title: 'Invalid starter selection',
      errorCode: 'INVALID_STARTER_SELECTION',
      detail: '이름을 확인해 주세요.',
    },
    response: new Response(null, { status: 400 }),
  });
  await expect(getStarterPreview({ species: 'WAVE', name: '말랑이' })).rejects.toThrow(
    '이름을 확인해 주세요.',
  );
});

it('does not show a successful preview when the response violates preview-only semantics', async () => {
  postMock.mockResolvedValue({
    data: { ...previewFixture, previewOnly: false } as unknown as typeof previewFixture,
    response: new Response(null, { status: 200 }),
  });
  await expect(getStarterPreview({ species: 'WAVE', name: '말랑이' })).rejects.toThrow(
    '배츄 정보를 확인하지 못했어요.',
  );
});

it('rejects a catalog with missing growth milestones', async () => {
  getMock.mockResolvedValue({
    data: { ...catalogFixture, growthMilestones: [] },
    response: new Response(null, { status: 200 }),
  });
  await expect(getStarterCatalog()).rejects.toThrow('배츄 정보를 확인하지 못했어요.');
});

it.each([new TypeError('Failed to fetch'), new SyntaxError('Unexpected token < in HTML response')])(
  'replaces transport and malformed JSON errors with safe Korean text',
  async (error) => {
    getMock.mockRejectedValue(error);
    await expect(getStarterCatalog()).rejects.toThrow(
      /(서버에 연결할 수 없어요|배츄 정보를 확인하지 못했어요)/,
    );
  },
);

it('aborts slow requests so the user can retry', async () => {
  jest.useFakeTimers();
  getMock.mockImplementation(
    (_path, options) =>
      new Promise((_resolve, reject) => {
        (options as { signal?: AbortSignal } | undefined)?.signal?.addEventListener('abort', () => {
          const error = new Error('Aborted');
          error.name = 'AbortError';
          reject(error);
        });
      }),
  );
  const request = expect(getStarterCatalog()).rejects.toThrow('응답이 늦어지고 있어요.');
  await jest.advanceTimersByTimeAsync(10_000);
  await request;
});
