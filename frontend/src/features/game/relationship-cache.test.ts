import { QueryClient } from '@tanstack/react-query';
import { z } from 'zod';

import type { AuthSession } from '@/features/onboarding/auth-session';

import {
  clearRelationshipData,
  finishRelationshipChange,
  observeRelationship,
  relationshipRequest,
} from './relationship-cache';

it('rejects a late private response after local relationship termination', async () => {
  const client = new QueryClient();
  let resolve!: (value: string) => void;
  const session = {
    request: () =>
      new Promise<string>((done) => {
        resolve = done;
      }),
  } as unknown as AuthSession;
  const pending = relationshipRequest(client, session, 'a', '/quests', z.string());
  client.setQueryData(['quests', 'a', 'old'], { title: 'private' });
  await clearRelationshipData(client, 'a');
  finishRelationshipChange(client, 'a');
  resolve('old private response');
  await expect(pending).rejects.toThrow('연결 상태가 바뀌었어요');
  expect(client.getQueryData(['quests', 'a', 'old'])).toBeUndefined();
  client.clear();
});
it('removes former relationship drafts and rejects in-flight responses when remote pairing changes', async () => {
  const client = new QueryClient();
  observeRelationship(client, 'a', 'old', 'home');
  client.setQueryData(['quests', 'a', 'old', 'draft-id'], { title: 'old secret' });
  client.setQueryData(['home', 'a'], { coupleId: 'old' });
  let resolve!: (value: string) => void;
  const session = {
    request: () =>
      new Promise<string>((done) => {
        resolve = done;
      }),
  } as unknown as AuthSession;
  const pending = relationshipRequest(client, session, 'a', '/quests/old', z.string());
  observeRelationship(client, 'a', null, 'couples');
  observeRelationship(client, 'a', 'new', 'couples');
  resolve('old');
  await expect(pending).rejects.toThrow();
  expect(client.getQueryData(['quests', 'a', 'old', 'draft-id'])).toBeUndefined();
  expect(client.getQueryData(['home', 'a'])).toBeUndefined();
  client.clear();
});
