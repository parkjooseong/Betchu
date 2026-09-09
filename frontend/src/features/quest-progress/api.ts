import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { z } from 'zod';

import { relationshipRequest, useRelationshipBoundary } from '@/features/game/relationship-cache';
import { useAuth } from '@/features/onboarding/auth-provider';
import { createRequestId } from '@/features/onboarding/request-id';
import { ApiError } from '@/features/onboarding/transport';
import { useVisible } from '@/features/tutorial/api';

export function useQuestQuery<T>(
  coupleId: string,
  path: string,
  schema: z.ZodType<T>,
  enabled = true,
) {
  const { user, session } = useAuth();
  const client = useQueryClient();
  const boundary = useRelationshipBoundary(user!.id);
  const visible = useVisible();
  const active = enabled && !boundary.ending && boundary.coupleId === coupleId;
  const query = useQuery({
    queryKey: ['quests', user!.id, coupleId, 'personal', path],
    enabled: active,
    queryFn: () => relationshipRequest(client, session, user!.id, path, schema),
    retry: false,
    staleTime: 0,
    refetchInterval: visible ? 5000 : false,
  });
  const { refetch } = query;
  useEffect(() => {
    if (visible && active) void refetch();
  }, [visible, active, refetch]);
  return query;
}
export type QuestCommand = { path: string; body: Record<string, unknown>; method?: 'POST' | 'PUT' };
export function useQuestAction<T>(coupleId: string, schema: z.ZodType<T>) {
  const { user, session } = useAuth();
  const client = useQueryClient();
  const attempt = useRef<{ signature: string; key: string } | null>(null);
  const inFlight = useRef(false);
  const refresh = () =>
    Promise.all([
      client.invalidateQueries({ queryKey: ['quests', user!.id, coupleId] }),
      client.invalidateQueries({ queryKey: ['home', user!.id] }),
    ]);
  const mutation = useMutation({
    mutationFn: async (command: QuestCommand) => {
      const signature = JSON.stringify({ coupleId, ...command });
      if (attempt.current?.signature !== signature)
        attempt.current = { signature, key: createRequestId() };
      return relationshipRequest(client, session, user!.id, command.path, schema, {
        method: command.method ?? 'POST',
        body: command.body,
        headers: { 'Idempotency-Key': attempt.current.key },
      });
    },
    onSuccess: refresh,
    onError: (error) => {
      if (error instanceof ApiError && [403, 404, 409].includes(error.status)) void refresh();
    },
    onSettled: () => {
      inFlight.current = false;
    },
  });
  return {
    ...mutation,
    run: (command: QuestCommand) => {
      if (inFlight.current) return;
      inFlight.current = true;
      mutation.mutate(command);
    },
  };
}
