import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useFocusEffect } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';

import { relationshipRequest, useRelationshipBoundary } from '@/features/game/relationship-cache';
import { useAuth } from '@/features/onboarding/auth-provider';
import { createRequestId } from '@/features/onboarding/request-id';
import { ApiError } from '@/features/onboarding/transport';

import { isTerminal, overviewSchema, Tutorial, tutorialSchema } from './contracts';

export const tutorialKey = (userId: string, coupleId: string) => [
  'quests',
  userId,
  coupleId,
  'tutorial',
];

function useVisible() {
  const [foreground, setForeground] = useState(AppState.currentState !== 'background');
  const [focused, setFocused] = useState(false);
  useEffect(() => {
    const subscription = AppState.addEventListener('change', (state) =>
      setForeground(state === 'active'),
    );
    return () => subscription.remove();
  }, []);
  useFocusEffect(
    useCallback(() => {
      setFocused(true);
      return () => setFocused(false);
    }, []),
  );
  return foreground && focused;
}

export function useTutorialOverview(coupleId: string) {
  const { session, user } = useAuth();
  const client = useQueryClient();
  const boundary = useRelationshipBoundary(user!.id);
  const visible = useVisible();
  const query = useQuery({
    queryKey: [...tutorialKey(user!.id, coupleId), 'overview'],
    enabled: !boundary.ending && boundary.coupleId === coupleId,
    queryFn: async () => {
      const data = await relationshipRequest(
        client,
        session,
        user!.id,
        '/tutorials',
        overviewSchema,
      );
      if (
        (data.own && data.own.coupleId !== coupleId) ||
        (data.partner && data.partner.coupleId !== coupleId)
      )
        throw new Error('연결 상태가 바뀌었어요. 홈에서 다시 확인해 주세요.');
      return data;
    },
    retry: false,
    staleTime: 0,
    refetchInterval: visible ? 5000 : false,
  });
  const { refetch } = query;
  useEffect(() => {
    if (visible && !boundary.ending) void refetch();
  }, [visible, boundary.ending, refetch]);
  const stateStamp = [query.data?.own, query.data?.partner]
    .filter((tutorial) => tutorial != null)
    .map((tutorial) => `${tutorial.id}:${tutorial.rowVersion}`)
    .join('|');
  useEffect(() => {
    if (stateStamp) void client.invalidateQueries({ queryKey: ['home', user!.id] });
  }, [stateStamp, client, user]);
  return query;
}

export function useTutorialDetail(coupleId: string, id: string) {
  const { session, user } = useAuth();
  const client = useQueryClient();
  const boundary = useRelationshipBoundary(user!.id);
  const visible = useVisible();
  const query = useQuery({
    queryKey: [...tutorialKey(user!.id, coupleId), 'detail', id],
    enabled: !boundary.ending && boundary.coupleId === coupleId,
    queryFn: async () => {
      const data = await relationshipRequest(
        client,
        session,
        user!.id,
        `/tutorials/${encodeURIComponent(id)}`,
        tutorialSchema,
      );
      if (data.coupleId !== coupleId)
        throw new Error('현재 연결에서 확인할 수 없는 튜토리얼이에요.');
      return data;
    },
    retry: false,
    staleTime: 0,
    refetchInterval: (current) =>
      visible && current.state.data && !isTerminal(current.state.data.status) ? 5000 : false,
  });
  const { refetch } = query;
  useEffect(() => {
    if (visible && !boundary.ending) void refetch();
  }, [visible, boundary.ending, refetch]);
  const settled = query.data?.settlement?.id;
  useEffect(() => {
    if (settled) void client.invalidateQueries({ queryKey: ['home', user!.id] });
  }, [settled, client, user]);
  return query;
}

export type TutorialCommand = { path: string; body: Record<string, unknown> };
export function useTutorialAction(coupleId: string, onComplete?: (tutorial: Tutorial) => void) {
  const { session, user } = useAuth();
  const client = useQueryClient();
  const attempt = useRef<{ signature: string; key: string } | null>(null);
  const inFlight = useRef(false);
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);
  const refresh = () =>
    Promise.all([
      client.invalidateQueries({ queryKey: tutorialKey(user!.id, coupleId) }),
      client.invalidateQueries({ queryKey: ['home', user!.id] }),
    ]);
  const mutation = useMutation({
    mutationFn: async (command: TutorialCommand) => {
      const signature = JSON.stringify(command);
      if (attempt.current?.signature !== signature)
        attempt.current = { signature, key: createRequestId() };
      return relationshipRequest(client, session, user!.id, command.path, tutorialSchema, {
        method: 'POST',
        body: command.body,
        headers: { 'Idempotency-Key': attempt.current.key },
      });
    },
    onSuccess: async (result) => {
      if (alive.current) onComplete?.(result);
      await refresh();
    },
    onError: (error) => {
      if (error instanceof ApiError && [403, 404, 409].includes(error.status)) void refresh();
    },
    onSettled: () => {
      inFlight.current = false;
    },
  });
  function run(command: TutorialCommand) {
    if (inFlight.current) return;
    inFlight.current = true;
    mutation.mutate(command);
  }
  return { ...mutation, run };
}
