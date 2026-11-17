import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { z } from 'zod';

import { relationshipRequest, useRelationshipBoundary } from '@/features/game/relationship-cache';
import { useAuth } from '@/features/onboarding/auth-provider';
import { createRequestId } from '@/features/onboarding/request-id';
import { ApiError, RequestOptions } from '@/features/onboarding/transport';
import { useVisible } from '@/features/tutorial/api';

export function useFeatureQuery<T>(path: string, schema: z.ZodType<T>, related = false) {
  const { session, user } = useAuth();
  const client = useQueryClient();
  const boundary = useRelationshipBoundary(user!.id);
  const visible = useVisible();
  const active = !related || !boundary.ending;
  const query = useQuery({
    queryKey: [
      related ? 'relationship-features' : 'account-features',
      user!.id,
      related ? boundary.generation : 'own',
      path,
    ],
    queryFn: () =>
      related
        ? relationshipRequest(client, session, user!.id, path, schema)
        : session.request(path, schema),
    enabled: active,
    retry: false,
    staleTime: 0,
    refetchInterval: visible ? 15000 : false,
  });
  const { refetch } = query;
  useEffect(() => {
    if (active && visible) void refetch();
  }, [active, visible, refetch]);
  return { ...query, data: related && (query.isError || boundary.ending) ? undefined : query.data };
}

export type Command = { path: string; body?: unknown; method?: RequestOptions['method'] };
export function useFeatureAction<T>(schema: z.ZodType<T>, related = false) {
  const { session, user } = useAuth();
  const client = useQueryClient();
  const attempt = useRef<{ signature: string; key: string } | null>(null);
  const inFlight = useRef(false);
  const refresh = () => {
    for (const prefix of ['account-features', 'relationship-features', 'home', 'quests'])
      void client.invalidateQueries({ queryKey: [prefix, user!.id] });
  };
  const mutation = useMutation({
    mutationFn: (command: Command) => {
      const signature = JSON.stringify(command);
      if (attempt.current?.signature !== signature)
        attempt.current = { signature, key: createRequestId() };
      const options = {
        method: command.method ?? 'POST',
        body: command.body,
        headers: { 'Idempotency-Key': attempt.current.key },
      };
      return related
        ? relationshipRequest(client, session, user!.id, command.path, schema, options)
        : session.request(command.path, schema, options);
    },
    onSuccess: refresh,
    onError: (error) => {
      if (error instanceof ApiError && [403, 404, 409].includes(error.status)) refresh();
    },
    onSettled: () => {
      inFlight.current = false;
    },
  });
  return {
    ...mutation,
    run: async (command: Command) => {
      if (inFlight.current) return undefined;
      inFlight.current = true;
      try {
        return await mutation.mutateAsync(command);
      } catch {
        return undefined;
      }
    },
    acknowledge: () => {
      if (!mutation.isSuccess || inFlight.current) return;
      attempt.current = null;
      mutation.reset();
    },
  };
}
