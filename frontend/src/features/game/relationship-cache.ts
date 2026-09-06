import { QueryClient, useQuery } from '@tanstack/react-query';
import { z } from 'zod';

import { AuthSession } from '@/features/onboarding/auth-session';
import { RequestOptions } from '@/features/onboarding/transport';

type Boundary = { generation: number; ending: boolean; coupleId?: string | null };
const key = (userId: string) => ['relationship-boundary', userId];
const initial: Boundary = { generation: 0, ending: false };

export function useRelationshipBoundary(userId: string) {
  return useQuery({
    queryKey: key(userId),
    queryFn: () => initial,
    initialData: initial,
    enabled: false,
  }).data;
}

export async function relationshipRequest<T>(
  client: QueryClient,
  session: AuthSession,
  userId: string,
  path: string,
  schema: z.ZodType<T>,
  options?: RequestOptions,
) {
  const before = client.getQueryData<Boundary>(key(userId)) ?? initial;
  if (before.ending) throw new Error('연결 정리 상태를 확인한 뒤 다시 시도해 주세요.');
  const assertCurrent = () => {
    const after = client.getQueryData<Boundary>(key(userId)) ?? initial;
    if (before.generation !== after.generation || after.ending)
      throw new Error('연결 상태가 바뀌었어요. 현재 상태를 다시 확인해 주세요.');
  };
  const result = await session.request(path, schema, { ...options, assertCurrent });
  assertCurrent();
  return result;
}

export async function clearRelationshipData(client: QueryClient, userId: string) {
  client.setQueryData<Boundary>(key(userId), (value = initial) => ({
    generation: value.generation + 1,
    ending: true,
  }));
  for (const prefix of ['home', 'quests', 'couples']) {
    await client.cancelQueries({ queryKey: [prefix, userId] });
    client.removeQueries({ queryKey: [prefix, userId] });
  }
}

export function finishRelationshipChange(client: QueryClient, userId: string) {
  client.setQueryData<Boundary>(key(userId), (value = initial) => ({ ...value, ending: false }));
  for (const prefix of ['home', 'quests', 'couples', 'relationship-end'])
    void client.invalidateQueries({ queryKey: [prefix, userId] });
}

export function observeRelationship(
  client: QueryClient,
  userId: string,
  coupleId: string | null,
  source: 'home' | 'couples',
) {
  const previous = client.getQueryData<Boundary>(key(userId)) ?? initial;
  if (previous.coupleId === coupleId) return;
  const changed = previous.coupleId !== undefined;
  client.setQueryData<Boundary>(key(userId), {
    ...previous,
    coupleId,
    generation: previous.generation + (changed ? 1 : 0),
  });
  if (!changed) return;
  for (const prefix of ['quests', source === 'home' ? 'couples' : 'home']) {
    void client.cancelQueries({ queryKey: [prefix, userId] });
    client.removeQueries({ queryKey: [prefix, userId] });
    void client.invalidateQueries({ queryKey: [prefix, userId] });
  }
}
