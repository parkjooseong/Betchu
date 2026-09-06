import { z } from 'zod';

import { env } from '@/config/env';

const messages: Record<string, string> = {
  INVALID_TUTORIAL_REQUEST:
    '첫 약속의 날짜와 선택 내용을 확인해 주세요. 일정은 현재부터 7일 안이어야 해요.',
  TUTORIAL_ALREADY_EXISTS: '이미 첫 약속을 보냈어요. 현재 튜토리얼을 다시 확인해 주세요.',
  TUTORIAL_NOT_FOUND:
    '현재 연결에서 확인할 수 없는 첫 약속이에요. 홈에서 연결 상태를 확인해 주세요.',
  TUTORIAL_STATE_CONFLICT: '첫 약속의 상태나 기한이 바뀌었어요. 새로 확인한 뒤 진행해 주세요.',
  STARTER_ALREADY_EXISTS: '이미 나의 배츄가 있어요. 저장된 배츄를 다시 불러올게요.',
  INVALID_MONSTER_REQUEST: '배츄 종류와 이름을 확인해 주세요.',
  INVALID_QUEST_REQUEST: '제목·조건·금액·날짜를 확인한 뒤 다시 저장해 주세요.',
  QUEST_NOT_FOUND: '이 초안은 확인할 수 없어요. 내 초안 목록과 연결 상태를 확인해 주세요.',
  QUEST_VERSION_CONFLICT: '약속의 내용이나 상태가 바뀌었어요. 최신 내용을 확인해 주세요.',
  COUPLE_REQUIRED: '커플 연결을 완료한 뒤 다시 시도해 주세요.',
  STARTER_REQUIRED: '홈에서 스타팅 배츄를 먼저 골라 주세요.',
  IDEMPOTENCY_KEY_REUSED: '앞선 요청과 내용이 달라요. 현재 상태를 새로 확인해 주세요.',
  INVALID_INVITE: '초대 코드를 확인해 주세요. 사용할 수 없거나 만료된 코드예요.',
  PAIRING_CONFLICT: '연결 상태가 바뀌었어요. 현재 상태를 확인한 뒤 다시 시도해 주세요.',
  INVITE_EXPIRED: '초대 시간이 만료됐어요. 새 초대로 다시 시작해 주세요.',
  INVITE_UNAVAILABLE: '이 초대는 더 이상 사용할 수 없어요.',
  INVITE_NOT_FOUND: '확인할 수 없는 초대예요.',
  INVITE_RATE_LIMITED: '초대 확인을 여러 번 시도했어요. 잠시 후 다시 시도해 주세요.',
  COUPLE_INVITES_UNAVAILABLE: '초대 기능을 준비 중이에요. 잠시 후 다시 확인해 주세요.',
};

export class ApiError extends Error {
  constructor(
    public status: number,
    public code?: string,
  ) {
    super(
      code && messages[code]
        ? messages[code]
        : status === 401
          ? '로그인이 만료됐어요. 다시 로그인해 주세요.'
          : status === 403
            ? '현재 계정 상태에서는 진행할 수 없어요. 가입 조건을 확인해 주세요.'
            : status === 503
              ? '서비스 연결을 준비 중이에요. 잠시 후 다시 시도해 주세요.'
              : status === 429
                ? '요청이 많아요. 잠시 후 다시 시도해 주세요.'
                : '요청을 처리하지 못했어요. 현재 상태를 확인한 뒤 다시 시도해 주세요.',
    );
  }
}

export type RequestOptions = {
  assertCurrent?: () => void;
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  headers?: Record<string, string>;
};

export async function request<T>(
  path: string,
  schema: z.ZodType<T>,
  options: RequestOptions = {},
  token?: string,
): Promise<T> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 10_000);
  try {
    const response = await fetch(`${env.apiBaseUrl}${path}`, {
      method: options.method ?? 'GET',
      signal: controller.signal,
      headers: {
        Accept: 'application/json',
        ...(options.body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...options.headers,
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      ...(options.body === undefined ? {} : { body: JSON.stringify(options.body) }),
    });
    if (!response.ok) {
      let code: string | undefined;
      try {
        const problem: unknown = await response.json();
        const parsed = z
          .object({ errorCode: z.string().optional(), code: z.string().optional() })
          .safeParse(problem);
        if (parsed.success) code = parsed.data.errorCode ?? parsed.data.code;
      } catch {
        /* Response bodies are never exposed to the UI or logs. */
      }
      throw new ApiError(response.status, code);
    }
    const data: unknown = response.status === 204 ? undefined : await response.json();
    const parsed = schema.safeParse(data);
    if (!parsed.success)
      throw new Error('서버 응답을 확인하지 못했어요. 잠시 후 다시 시도해 주세요.');
    return parsed.data;
  } catch (error) {
    const name = error && typeof error === 'object' && 'name' in error ? error.name : undefined;
    if (name === 'SyntaxError')
      throw new Error('서버 응답을 확인하지 못했어요. 잠시 후 다시 시도해 주세요.');
    if (name === 'TypeError')
      throw new Error('서버에 연결할 수 없어요. 연결을 확인한 뒤 다시 시도해 주세요.');
    if (name === 'AbortError')
      throw new Error('응답이 늦어지고 있어요. 잠시 후 다시 시도해 주세요.');
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}
