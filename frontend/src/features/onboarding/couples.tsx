import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { AppState, Text, TextInput, View } from 'react-native';
import { z } from 'zod';

import { endStatusSchema } from '@/features/game/contracts';
import {
  clearRelationshipData,
  finishRelationshipChange,
  observeRelationship,
  relationshipRequest,
  useRelationshipBoundary,
} from '@/features/game/relationship-cache';
import { colors } from '@/theme/tokens';

import { useAuth } from './auth-provider';
import {
  completedSchema,
  coupleSchema,
  emptySchema,
  invitePreviewSchema,
  inviteSchema,
  pendingInviteSchema,
  profileSchema,
} from './contracts';
import { createRequestId } from './request-id';
import { Button, ui } from './ui';

type InvitePreview = z.infer<typeof invitePreviewSchema>;
type IssuedInvite = z.infer<typeof inviteSchema>;

export function Couples({ safetyOnly = false }: { safetyOnly?: boolean }) {
  const { session, user } = useAuth();
  const client = useQueryClient();
  const boundary = useRelationshipBoundary(user!.id);
  const [foreground, setForeground] = useState(AppState.currentState !== 'background');
  const [code, setCode] = useState('');
  const [preview, setPreview] = useState<InvitePreview>();
  const [issued, setIssued] = useState<IssuedInvite>();
  const [confirmation, setConfirmation] = useState<'end' | 'block' | 'revoke' | 'reject' | null>(
    null,
  );
  const createKey = useRef<string | null>(null);
  const endKey = useRef<string | null>(null);
  const query = useQuery({
    queryKey: ['couples', user?.id],
    enabled: user?.status === 'ACTIVE' && !safetyOnly && !boundary.ending,
    queryFn: async () => {
      const result = await relationshipRequest(
        client,
        session,
        user!.id,
        '/couples/me',
        coupleSchema,
      );
      observeRelationship(client, user!.id, result.couple?.id ?? null, 'couples');
      return result;
    },
    retry: false,
    refetchInterval: (current) => (foreground && current.state.data?.pendingInvite ? 5000 : false),
  });
  const endStatus = useQuery({
    queryKey: ['relationship-end', user!.id],
    enabled: !boundary.ending,
    queryFn: () => session.request('/couples/me/end-status', endStatusSchema),
    retry: false,
    refetchInterval: (current) =>
      foreground && current.state.data?.job?.status === 'PROCESSING' ? 5000 : false,
  });
  const processing = endStatus.data?.job?.status === 'PROCESSING';
  useEffect(() => {
    if (endStatus.data?.job?.status === 'COMPLETED') {
      void client.invalidateQueries({ queryKey: ['couples', user!.id] });
      void client.invalidateQueries({ queryKey: ['home', user!.id] });
    }
  }, [endStatus.data?.job?.status, client, user]);
  const { refetch } = query;
  useEffect(() => {
    const subscription = AppState.addEventListener('change', (state) => {
      setForeground(state === 'active');
      if (state === 'active' && !safetyOnly) void refetch();
    });
    return () => subscription.remove();
  }, [refetch, safetyOnly]);
  const action = useMutation({
    mutationFn: (operation: () => Promise<void>) => operation(),
    onSettled: () => (safetyOnly ? undefined : query.refetch()),
  });
  const busy = action.isPending;

  function createInvite() {
    action.mutate(async () => {
      createKey.current ??= createRequestId();
      const result = await session.request('/couples/invites', inviteSchema, {
        method: 'POST',
        headers: { 'Idempotency-Key': createKey.current },
      });
      setIssued(result);
      createKey.current = null;
    });
  }

  function previewInvite() {
    action.mutate(async () => {
      if (!code.trim()) throw new Error('상대방에게 받은 초대 코드를 입력해 주세요.');
      setPreview(
        await session.request('/couples/invites/preview', invitePreviewSchema, {
          method: 'POST',
          body: { code: code.trim() },
        }),
      );
    });
  }

  function joinInvite() {
    action.mutate(async () => {
      await session.request('/couples/join', pendingInviteSchema, {
        method: 'POST',
        body: { code: code.trim() },
      });
      setPreview(undefined);
      setCode('');
    });
  }

  function confirmPartner() {
    const pending = query.data?.pendingInvite;
    if (!pending) return;
    action.mutate(async () => {
      const result = await session.request(
        `/couples/pending/${encodeURIComponent(pending.id)}/confirm`,
        coupleSchema,
        { method: 'POST' },
      );
      if (result.couple) {
        setIssued(undefined);
        setPreview(undefined);
        void client.invalidateQueries({ queryKey: ['home', user!.id] });
      }
    });
  }

  function performConfirmedAction() {
    const pending = query.data?.pendingInvite;
    action.mutate(async () => {
      if (confirmation === 'end' || confirmation === 'block') {
        endKey.current ??= createRequestId();
        setIssued(undefined);
        setPreview(undefined);
        await clearRelationshipData(client, user!.id);
        try {
          await session.request(`/couples/me/${confirmation}`, completedSchema, {
            method: 'POST',
            headers: { 'Idempotency-Key': endKey.current },
          });
        } finally {
          finishRelationshipChange(client, user!.id);
        }
        endKey.current = null;
      } else if (confirmation === 'revoke' && pending) {
        await session.request(`/couples/invites/${encodeURIComponent(pending.id)}`, emptySchema, {
          method: 'DELETE',
        });
      } else if (confirmation === 'reject' && pending) {
        await session.request(
          `/couples/pending/${encodeURIComponent(pending.id)}/reject`,
          emptySchema,
          { method: 'POST' },
        );
      } else {
        throw new Error('연결 상태를 다시 확인해 주세요.');
      }
      setConfirmation(null);
      setIssued(undefined);
      setPreview(undefined);
      setCode('');
    });
  }

  if (boundary.ending)
    return (
      <View style={ui.card}>
        <Text style={ui.body}>상대 정보 접근을 닫고 연결을 정리하고 있어요…</Text>
      </View>
    );
  if (query.isPending && !safetyOnly)
    return (
      <View style={ui.card}>
        <Text style={ui.body}>연결 상태를 확인하고 있어요…</Text>
      </View>
    );
  if (query.isError && !safetyOnly)
    return (
      <View style={ui.card}>
        <Text accessibilityRole="alert" style={ui.error}>
          {query.error.message}
        </Text>
        <Button onPress={() => void query.refetch()}>연결 상태 다시 확인</Button>
      </View>
    );
  const pending = query.data?.pendingInvite;
  const couple = query.data?.couple?.id === boundary.coupleId ? query.data?.couple : null;
  const visibleIssued = pending && issued?.inviteId === pending.id ? issued : undefined;

  return (
    <View style={ui.card}>
      {endStatus.data?.job && (
        <View style={ui.note}>
          <Text style={ui.body}>
            {processing ? '연결 종료 후 정리 중이에요' : '연결 정리가 완료됐어요'}
          </Text>
          <Text style={ui.caption}>
            내 초안 정리 {endStatus.data.job.processedResourceCount} /{' '}
            {endStatus.data.job.targetResourceCount}
          </Text>
          {processing && (
            <Text style={ui.caption}>
              상대 정보는 이미 숨겼어요. 내 초안 수와 별개로 전체 정리가 끝나면 다시 연결할 수
              있어요.
            </Text>
          )}
        </View>
      )}
      {endStatus.error && (
        <View style={ui.note}>
          <Text accessibilityRole="alert" style={ui.error}>
            {endStatus.error.message}
          </Text>
          <Button secondary onPress={() => void endStatus.refetch()}>
            연결 정리 상태 다시 확인
          </Button>
        </View>
      )}
      <Text style={ui.eyebrow}>02 · 커플 연결</Text>
      <Text accessibilityRole="header" style={ui.sectionTitle}>
        {safetyOnly ? '연결 안전 설정' : couple ? '우리 둘, 연결됐어요' : '함께할 사람을 초대해요'}
      </Text>
      {safetyOnly ? (
        <>
          <Text style={ui.body}>
            정책을 다시 확인하는 동안에도 현재 연결을 종료하거나 상대방을 차단할 수 있어요.
          </Text>
          <Button
            secondary
            disabled={busy}
            onPress={() => {
              endKey.current = null;
              setConfirmation('end');
            }}
          >
            현재 연결 종료
          </Button>
          <Button
            secondary
            disabled={busy}
            onPress={() => {
              endKey.current = null;
              setConfirmation('block');
            }}
          >
            현재 상대방 차단
          </Button>
          {action.isSuccess && <Text style={ui.body}>요청이 완료됐어요.</Text>}
        </>
      ) : couple ? (
        <>
          <Profile profile={couple.partner} />
          <Text style={ui.caption}>연결한 날 {formatDate(couple.connectedAt)}</Text>
          <Text style={ui.body}>상대방의 지갑과 개인 내역은 공유하지 않아요.</Text>
          <Button
            secondary
            disabled={busy}
            onPress={() => {
              endKey.current = null;
              setConfirmation('end');
            }}
          >
            연결 종료
          </Button>
          <Button
            secondary
            disabled={busy}
            onPress={() => {
              endKey.current = null;
              setConfirmation('block');
            }}
          >
            상대방 차단
          </Button>
        </>
      ) : pending ? (
        <>
          {pending.status === 'ACTIVE' ? (
            <>
              <Text style={ui.body}>상대방이 초대 코드를 입력하기를 기다리고 있어요.</Text>
              {visibleIssued?.code ? (
                <View style={ui.note}>
                  <Text style={ui.caption}>상대방에게만 전달해 주세요</Text>
                  <Text
                    selectable
                    accessibilityLabel={`초대 코드 ${visibleIssued.code}`}
                    style={ui.code}
                  >
                    {visibleIssued.code}
                  </Text>
                  <Text style={ui.caption}>이 코드는 발급 직후에만 표시돼요.</Text>
                </View>
              ) : (
                <Text style={ui.caption}>
                  보안을 위해 기존 코드는 다시 보여드릴 수 없어요. 필요하면 새 코드를 만들어 주세요.
                </Text>
              )}
              <Text style={ui.caption}>사용 기한 {formatDate(pending.expiresAt)}</Text>
              <Button busy={busy} onPress={createInvite}>
                새 코드 만들기 · 기존 코드 만료
              </Button>
              <Button secondary disabled={busy} onPress={() => setConfirmation('revoke')}>
                초대 취소
              </Button>
            </>
          ) : (
            <>
              <Text style={ui.body}>두 사람이 서로를 확인해야 연결돼요.</Text>
              {pending.partner && <Profile profile={pending.partner} />}
              <Text style={ui.caption}>확인 기한 {formatDate(pending.expiresAt)}</Text>
              <View style={ui.note}>
                <Text style={ui.body}>내 확인: {pending.myConfirmed ? '완료' : '아직이에요'}</Text>
                <Text style={ui.body}>
                  상대방 확인: {pending.partnerConfirmed ? '완료' : '기다리는 중'}
                </Text>
              </View>
              {pending.myConfirmed ? (
                <Text style={ui.body}>확인했어요. 상대방의 최종 확인을 기다려 주세요.</Text>
              ) : (
                <Button busy={busy} onPress={confirmPartner}>
                  이 사람과 연결 확인
                </Button>
              )}
              <Button secondary disabled={busy} onPress={() => setConfirmation('reject')}>
                연결 요청 거절
              </Button>
            </>
          )}
          <Button
            secondary
            disabled={busy || query.isFetching}
            onPress={() => void query.refetch()}
          >
            현재 상태 확인
          </Button>
        </>
      ) : (
        <>
          <Text style={ui.body}>
            초대 코드는 24시간 동안 한 번 사용할 수 있어요. 코드 입력 후에도 서로의 프로필을 보고
            각각 확인해야 연결돼요.
          </Text>
          <Button busy={busy} disabled={processing} onPress={createInvite}>
            내 초대 코드 만들기
          </Button>
          <View style={ui.divider} />
          <Text style={ui.sectionTitle}>초대 코드를 받았나요?</Text>
          <TextInput
            accessibilityLabel="받은 초대 코드"
            aria-disabled={busy}
            accessibilityState={{ disabled: busy }}
            editable={!busy}
            value={code}
            onChangeText={(value) => {
              setCode(value);
              setPreview(undefined);
              action.reset();
            }}
            placeholder="초대 코드 입력"
            placeholderTextColor={colors.textSecondary}
            autoCapitalize="characters"
            autoCorrect={false}
            style={ui.input}
          />
          <Button secondary busy={busy} disabled={processing} onPress={previewInvite}>
            초대한 사람 먼저 확인
          </Button>
          {preview && (
            <View style={ui.note}>
              <Text style={ui.body}>나를 초대한 사람이 맞나요?</Text>
              <Profile profile={preview.inviter} />
              <Text style={ui.caption}>초대 기한 {formatDate(preview.expiresAt)}</Text>
              <Button busy={busy} onPress={joinInvite}>
                이 초대로 연결 요청
              </Button>
              <Button secondary disabled={busy} onPress={() => setPreview(undefined)}>
                다른 코드 확인
              </Button>
            </View>
          )}
        </>
      )}
      {action.isError && (
        <Text accessibilityRole="alert" style={ui.error}>
          {action.error.message}
        </Text>
      )}
      {confirmation && (
        <View accessibilityRole="alert" style={ui.note}>
          <Text style={ui.sectionTitle}>
            {confirmation === 'block'
              ? '상대방을 차단할까요?'
              : confirmation === 'end'
                ? '연결을 종료할까요?'
                : confirmation === 'revoke'
                  ? '초대를 취소할까요?'
                  : '연결 요청을 거절할까요?'}
          </Text>
          <Text style={ui.body}>
            {confirmation === 'block'
              ? '연결이 즉시 종료되고 이 사람과 다시 연결할 수 없어요. 상대방의 동의는 필요하지 않아요.'
              : confirmation === 'end'
                ? '상대 프로필과 공동 화면 접근이 즉시 차단돼요. 상대방의 동의는 필요하지 않아요.'
                : '상대방도 이 초대로 더 이상 연결할 수 없어요.'}
          </Text>
          <Button busy={busy} onPress={performConfirmedAction}>
            확인하고 진행
          </Button>
          <Button secondary disabled={busy} onPress={() => setConfirmation(null)}>
            돌아가기
          </Button>
        </View>
      )}
    </View>
  );
}

function Profile({ profile }: { profile: z.infer<typeof profileSchema> }) {
  return (
    <View style={ui.row}>
      <View style={ui.avatar}>
        <Text style={ui.avatarText}>{Array.from(profile.nickname)[0] || '배'}</Text>
      </View>
      <View style={ui.profileCopy}>
        <Text style={ui.sectionTitle}>{profile.nickname}</Text>
        <Text selectable style={ui.caption}>
          계정 {profile.id}
        </Text>
      </View>
    </View>
  );
}

function formatDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '시간을 확인할 수 없어요' : date.toLocaleString('ko-KR');
}
