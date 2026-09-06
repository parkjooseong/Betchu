import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useFocusEffect } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState, Pressable, Text, TextInput, View } from 'react-native';

import { useAuth } from '@/features/onboarding/auth-provider';
import { createRequestId } from '@/features/onboarding/request-id';
import { ApiError } from '@/features/onboarding/transport';
import { Button, ui } from '@/features/onboarding/ui';
import { getStarterCatalog } from '@/features/starter/api';
import { BabyIllustration } from '@/features/starter/baby-illustration';
import { EggIllustration } from '@/features/starter/egg-illustration';
import { normalizeStarterName, validateStarterName } from '@/features/starter/name-validation';
import { TutorialHomeCard } from '@/features/tutorial/home-card';
import { colors } from '@/theme/tokens';

import { homeSchema, Monster, monsterSchema } from './contracts';
import {
  observeRelationship,
  relationshipRequest,
  useRelationshipBoundary,
} from './relationship-cache';

export function useHome() {
  const { session, user } = useAuth();
  const client = useQueryClient();
  const boundary = useRelationshipBoundary(user?.id ?? 'signed-out');
  const enabled =
    user?.status === 'ACTIVE' && !user.missingPolicyVersionIds.length && !boundary.ending;
  const query = useQuery({
    queryKey: ['home', user?.id],
    enabled: user?.status === 'ACTIVE' && !user.missingPolicyVersionIds.length && !boundary.ending,
    queryFn: async () => {
      const result = await relationshipRequest(client, session, user!.id, '/home', homeSchema);
      observeRelationship(client, user!.id, result.coupleId, 'home');
      return result;
    },
    retry: false,
    staleTime: 0,
  });
  const { refetch } = query;
  useFocusEffect(
    useCallback(() => {
      if (enabled) void refetch();
    }, [enabled, refetch]),
  );
  useEffect(() => {
    const subscription = AppState.addEventListener('change', (state) => {
      if (state === 'active' && enabled) void refetch();
    });
    return () => subscription.remove();
  }, [enabled, refetch]);
  return query;
}

export function HomePanel() {
  const { user } = useAuth();
  const boundary = useRelationshipBoundary(user!.id);
  const home = useHome();
  if (boundary.ending)
    return (
      <View style={ui.card}>
        <Text style={ui.body}>상대 정보 접근을 닫고 연결을 정리하고 있어요…</Text>
      </View>
    );
  if (home.isPending)
    return (
      <View style={ui.card}>
        <Text style={ui.body}>내 배츄의 하루를 불러오고 있어요…</Text>
      </View>
    );
  if (home.isError)
    return (
      <View style={ui.card}>
        <Text accessibilityRole="alert" style={ui.error}>
          {home.error.message}
        </Text>
        <Button onPress={() => void home.refetch()}>홈 다시 불러오기</Button>
      </View>
    );
  const data = home.data;
  const connected = data.coupleId !== null && data.coupleId === boundary.coupleId;
  return (
    <>
      <View style={ui.card}>
        <Text style={ui.eyebrow}>내 츄코인</Text>
        <Text style={ui.sectionTitle}>{data.self.availableCoins.toLocaleString('ko-KR')}C</Text>
        <Text style={ui.caption}>
          잠긴 코인 {data.self.lockedCoins.toLocaleString('ko-KR')}C · 내 계정의 잔액
        </Text>
        {data.self.monster ? (
          <MonsterCard monster={data.self.monster} own />
        ) : connected ? (
          <StarterRegistration onSaved={() => void home.refetch()} />
        ) : (
          <Text style={ui.body}>서로 연결을 확인한 뒤 나의 스타팅 배츄를 만날 수 있어요.</Text>
        )}
      </View>
      {connected && <TutorialHomeCard coupleId={data.coupleId!} />}
      {data.self.monster && (
        <View style={ui.card}>
          <Text accessibilityRole="header" style={ui.sectionTitle}>
            작은 약속을 적어볼까요?
          </Text>
          <Text style={ui.body}>내 초안 {data.ownDraftCount}개</Text>
          <Text style={ui.caption}>
            승인 대기 {data.questStatusSummary.pendingApproval} · 진행 중{' '}
            {data.questStatusSummary.active} · 결과 대기 {data.questStatusSummary.awaitingResult}
          </Text>
          {connected ? (
            <>
              <Link href="/quests/new" style={ui.link}>
                새 퀘스트 초안 쓰기 →
              </Link>
              <Link href="/quests" style={ui.link}>
                내 초안 모아보기
              </Link>
              <Text style={ui.caption}>
                초안은 나만 볼 수 있어요. 저장해도 코인이나 경험치는 바뀌지 않아요.
              </Text>
            </>
          ) : (
            <Text style={ui.caption}>
              배츄와 내 코인은 그대로예요. 새로운 연결을 마치면 초안을 작성할 수 있어요.
            </Text>
          )}
        </View>
      )}
      {data.partner && data.coupleId === boundary.coupleId && (
        <View style={ui.card}>
          <Text style={ui.eyebrow}>{data.partner.nickname}님의 배츄</Text>
          {data.partner.monster ? (
            <MonsterCard monster={data.partner.monster} />
          ) : (
            <Text style={ui.body}>파트너가 스타팅 배츄를 고르고 있어요.</Text>
          )}
        </View>
      )}
      <Button secondary disabled={home.isFetching} onPress={() => void home.refetch()}>
        홈 새로고침
      </Button>
    </>
  );
}

function MonsterCard({ monster, own = false }: { monster: Monster; own?: boolean }) {
  const { session, user } = useAuth();
  const client = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(monster.name);
  const mutation = useMutation({
    mutationFn: async () => {
      const error = validateStarterName(name, { minLength: 1, maxLength: 10 });
      if (error) throw new Error(error);
      return relationshipRequest(client, session, user!.id, '/monsters/me/name', monsterSchema, {
        method: 'PATCH',
        body: { name: normalizeStarterName(name) },
      });
    },
    onSuccess: () => {
      setEditing(false);
      void client.invalidateQueries({ queryKey: ['home', user!.id] });
    },
  });
  return (
    <View style={{ gap: 16 }}>
      <Text accessibilityRole="header" style={ui.sectionTitle}>
        {monster.name}
      </Text>
      {monster.growthStage === 'EGG' && <EggIllustration />}
      {monster.growthStage === 'BABY' && <BabyIllustration species={monster.species} />}
      <Text style={ui.body}>
        Lv.{monster.accountLevel} ·{' '}
        {monster.growthStage === 'EGG' ? '아직 알이에요' : '자라고 있어요'}
      </Text>
      <Text style={ui.caption}>
        선택한 종 ·{' '}
        {{ STARLIGHT: '별빛', WAVE: '파도', SUNSET: '노을', FOREST: '숲' }[monster.species]}
      </Text>
      {monster.nextLevelRequiredExp === null ? (
        <Text style={ui.body}>최고 레벨에 도달했어요</Text>
      ) : (
        <View
          accessibilityRole="progressbar"
          accessibilityLabel="계정 경험치"
          aria-valuemin={0}
          aria-valuemax={monster.nextLevelRequiredExp}
          aria-valuenow={monster.currentLevelExp}
          style={ui.note}
        >
          <Text style={ui.body}>
            XP {monster.currentLevelExp} / {monster.nextLevelRequiredExp}
          </Text>
        </View>
      )}
      <Text style={ui.body}>
        HP {monster.finalHp} · ATK {monster.finalAttack} · 전투력 {monster.combatPower}
      </Text>
      <Text style={ui.caption}>
        인정 성공 {monster.recognizedSuccessCount}회 · 연승 {monster.streak}회
      </Text>
      {monster.growthStage === 'EGG' && (
        <Text style={ui.caption}>
          튜토리얼 성공 또는 이후 첫 일반 퀘스트 성공으로 부화해요. 레벨과 외형 성장은 따로 쌓여요.
        </Text>
      )}
      {own &&
        (editing ? (
          <>
            <TextInput
              accessibilityLabel="새 배츄 이름"
              value={name}
              onChangeText={setName}
              editable={!mutation.isPending}
              style={ui.input}
            />
            {mutation.error && (
              <Text accessibilityRole="alert" style={ui.error}>
                {mutation.error.message}
              </Text>
            )}
            <Button busy={mutation.isPending} onPress={() => mutation.mutate()}>
              이름 저장
            </Button>
            <Button secondary disabled={mutation.isPending} onPress={() => setEditing(false)}>
              이름 변경 취소
            </Button>
          </>
        ) : (
          <Button
            secondary
            onPress={() => {
              setName(monster.name);
              mutation.reset();
              setEditing(true);
            }}
          >
            이름 바꾸기
          </Button>
        ))}
    </View>
  );
}

export function StarterRegistration({ onSaved }: { onSaved: () => void }) {
  const { session, user } = useAuth();
  const client = useQueryClient();
  const catalog = useQuery({
    queryKey: ['starter-catalog'],
    queryFn: getStarterCatalog,
    retry: false,
  });
  const [species, setSpecies] = useState<Monster['species']>();
  const [name, setName] = useState('');
  const attempt = useRef<{ payload: string; key: string } | null>(null);
  const mutation = useMutation({
    mutationFn: async () => {
      if (!species) throw new Error('함께할 배츄 종류를 골라 주세요.');
      const error = validateStarterName(name, { minLength: 1, maxLength: 10 });
      if (error) throw new Error(error);
      const body = { species, name: normalizeStarterName(name) };
      const payload = JSON.stringify(body);
      if (attempt.current?.payload !== payload)
        attempt.current = { payload, key: createRequestId() };
      return relationshipRequest(client, session, user!.id, '/monsters/starter', monsterSchema, {
        method: 'POST',
        body,
        headers: { 'Idempotency-Key': attempt.current.key },
      });
    },
    onSuccess: onSaved,
    onError: (error) => {
      if (error instanceof ApiError && error.code === 'STARTER_ALREADY_EXISTS') onSaved();
    },
  });
  return (
    <View style={{ gap: 16 }}>
      <Text accessibilityRole="header" style={ui.sectionTitle}>
        나의 첫 배츄를 만나세요
      </Text>
      <Text style={ui.caption}>
        종류와 이름을 계정에 저장해요. 네 친구의 시작 능력치는 같고, 모두 같은 알에서 시작해요.
      </Text>
      <EggIllustration />
      {catalog.isPending ? (
        <Text style={ui.body}>배츄 종류를 불러오는 중…</Text>
      ) : catalog.isError ? (
        <>
          <Text style={ui.error}>{catalog.error.message}</Text>
          <Button secondary onPress={() => void catalog.refetch()}>
            종류 다시 불러오기
          </Button>
        </>
      ) : (
        <>
          <View
            accessibilityRole="radiogroup"
            accessibilityLabel="스타팅 배츄 종류"
            style={{ gap: 8 }}
          >
            {catalog.data.starters.map((starter) => (
              <Pressable
                key={starter.species}
                accessibilityRole="radio"
                accessibilityLabel={`${starter.displayName} 배츄`}
                aria-checked={species === starter.species}
                aria-disabled={mutation.isPending}
                disabled={mutation.isPending}
                onPress={() => {
                  setSpecies(starter.species);
                  mutation.reset();
                }}
                style={[
                  ui.note,
                  {
                    borderWidth: 2,
                    borderColor: species === starter.species ? colors.brandStrong : 'transparent',
                    minHeight: 56,
                  },
                ]}
              >
                <Text style={ui.body}>
                  {species === starter.species ? '✓' : '○'} {starter.displayName}
                </Text>
                <Text style={ui.caption}>{starter.description}</Text>
              </Pressable>
            ))}
          </View>
          <Text style={ui.body}>배츄 이름 · 1~10자</Text>
          <TextInput
            accessibilityLabel="배츄 이름"
            value={name}
            editable={!mutation.isPending}
            onChangeText={(value) => {
              setName(value);
              mutation.reset();
            }}
            style={ui.input}
            placeholder="예: 말랑이"
          />
          <Text style={ui.caption}>
            {Array.from(normalizeStarterName(name)).length}/10 · 이름은 나중에 무료로 바꿀 수
            있어요.
          </Text>
          {mutation.error && (
            <Text accessibilityRole="alert" style={ui.error}>
              {mutation.error.message}
            </Text>
          )}
          <Button busy={mutation.isPending} onPress={() => mutation.mutate()}>
            {mutation.isError ? '배츄 저장 다시 시도' : '이 배츄와 시작하기'}
          </Button>
        </>
      )}
    </View>
  );
}
