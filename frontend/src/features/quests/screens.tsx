import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useLocalSearchParams, useRouter } from 'expo-router';
import { PropsWithChildren, useEffect, useRef, useState } from 'react';
import { Pressable, ScrollView, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useHome } from '@/features/game/home';
import { relationshipRequest, useRelationshipBoundary } from '@/features/game/relationship-cache';
import { useAuth } from '@/features/onboarding/auth-provider';
import { createRequestId } from '@/features/onboarding/request-id';
import { ApiError } from '@/features/onboarding/transport';
import { Button, ui } from '@/features/onboarding/ui';
import { colors } from '@/theme/tokens';

import { categories, Draft, discardedSchema, draftSchema, draftsSchema } from './contracts';
import { DraftForm, emptyDraft, formFromDraft, toSeoulInput, validateDraft } from './validation';

export function QuestPage({ children }: PropsWithChildren) {
  const { user, restoring } = useAuth();
  const boundary = useRelationshipBoundary(user?.id ?? 'signed-out');
  const home = useHome();
  const eligible = user?.status === 'ACTIVE' && !user.missingPolicyVersionIds.length;
  return (
    <SafeAreaView style={ui.root}>
      <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={ui.scroll}>
        <View style={ui.page}>
          <View style={ui.header}>
            <Text style={ui.brand}>BETCHU</Text>
            <Link href="/" style={ui.link}>
              홈으로
            </Link>
          </View>
          {restoring ? (
            <Text style={ui.body}>로그인 상태를 확인하고 있어요…</Text>
          ) : !eligible ? (
            <Text style={ui.body}>홈에서 로그인과 가입 조건을 확인해 주세요.</Text>
          ) : boundary.ending ? (
            <Text style={ui.body}>연결 정리 중에는 퀘스트를 볼 수 없어요.</Text>
          ) : home.isPending ? (
            <Text style={ui.body}>작성 가능한 상태를 확인하고 있어요…</Text>
          ) : home.error ? (
            <>
              <Text accessibilityRole="alert" style={ui.error}>
                {home.error.message}
              </Text>
              <Button onPress={() => void home.refetch()}>현재 상태 다시 확인</Button>
            </>
          ) : !home.data?.coupleId || home.data.coupleId !== boundary.coupleId ? (
            <Text style={ui.body}>커플 연결을 완료한 뒤 초안을 작성할 수 있어요.</Text>
          ) : !home.data.self.monster ? (
            <Text style={ui.body}>홈에서 스타팅 배츄를 먼저 골라 주세요.</Text>
          ) : (
            <View key={`${user.id}:${home.data.coupleId}:${boundary.generation}`}>{children}</View>
          )}
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

export function DraftListScreen() {
  return (
    <QuestPage>
      <DraftList />
    </QuestPage>
  );
}
function DraftList() {
  const { session, user } = useAuth();
  const client = useQueryClient();
  const boundary = useRelationshipBoundary(user!.id);
  const query = useInfiniteQuery({
    queryKey: ['quests', user!.id, boundary.coupleId, 'list'],
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) =>
      relationshipRequest(
        client,
        session,
        user!.id,
        `/quests?status=DRAFT&limit=20${pageParam ? `&cursor=${encodeURIComponent(pageParam)}` : ''}`,
        draftsSchema,
      ),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    retry: false,
    staleTime: 0,
  });
  return (
    <View style={{ gap: 16 }}>
      <Text accessibilityRole="header" style={ui.title}>
        나만 보는 초안
      </Text>
      <Text style={ui.body}>
        수정 중인 내용은 나만 볼 수 있어요. 파트너는 마지막 제출본을 확인해요.
      </Text>
      <Link href="/quests/new" style={ui.link}>
        새 초안 쓰기 →
      </Link>
      <Link href="/quest" style={ui.link}>
        제출·진행·결과 보기
      </Link>
      {query.isPending ? (
        <Text style={ui.body}>초안을 불러오는 중…</Text>
      ) : query.error ? (
        <>
          <Text accessibilityRole="alert" style={ui.error}>
            {query.error.message}
          </Text>
          <Button onPress={() => void query.refetch()}>초안 다시 불러오기</Button>
        </>
      ) : (
        <>
          {!query.data?.pages[0].quests.length && (
            <View style={ui.card}>
              <Text style={ui.body}>아직 저장한 초안이 없어요.</Text>
            </View>
          )}
          {query.data?.pages
            .flatMap((page) => page.quests)
            .map((draft) => (
              <View key={draft.id} style={ui.card}>
                <Text style={ui.eyebrow}>{categories[draft.category]} · 나만 보기</Text>
                <Text style={ui.sectionTitle}>{draft.title}</Text>
                <Text style={ui.caption}>
                  ★{draft.difficulty} · 제안 {draft.stake}C · 수행 마감 {toSeoulInput(draft.dueAt)}{' '}
                  (서울)
                </Text>
                <Link
                  href={{ pathname: '/quests/[questId]', params: { questId: draft.id } }}
                  style={ui.link}
                >
                  이 초안 다듬기
                </Link>
              </View>
            ))}
          {query.hasNextPage && (
            <Button
              secondary
              busy={query.isFetchingNextPage}
              onPress={() => void query.fetchNextPage()}
            >
              초안 더 보기
            </Button>
          )}
        </>
      )}
    </View>
  );
}
export function NewDraftScreen() {
  return (
    <QuestPage>
      <DraftEditor />
    </QuestPage>
  );
}
export function EditDraftScreen() {
  const { questId } = useLocalSearchParams<{ questId: string }>();
  return (
    <QuestPage>
      {typeof questId === 'string' ? (
        <DraftLoader key={questId} id={questId} />
      ) : (
        <Text style={ui.error}>초안 주소를 확인해 주세요.</Text>
      )}
    </QuestPage>
  );
}
function DraftLoader({ id }: { id: string }) {
  const { session, user } = useAuth();
  const client = useQueryClient();
  const boundary = useRelationshipBoundary(user!.id);
  const query = useQuery({
    queryKey: ['quests', user!.id, boundary.coupleId, id],
    queryFn: () =>
      relationshipRequest(
        client,
        session,
        user!.id,
        `/quests/${encodeURIComponent(id)}`,
        draftSchema,
      ),
    retry: false,
    staleTime: 0,
  });
  if (query.isPending) return <Text style={ui.body}>초안을 불러오는 중…</Text>;
  if (query.error)
    return (
      <View style={ui.card}>
        <Text accessibilityRole="alert" style={ui.error}>
          {query.error.message}
        </Text>
        <Button onPress={() => void query.refetch()}>초안 다시 확인</Button>
        <Link href="/quests" style={ui.link}>
          내 초안 목록
        </Link>
      </View>
    );
  return <DraftEditor initial={query.data} />;
}

export function DraftEditor({ initial }: { initial?: Draft }) {
  const { session, user } = useAuth();
  const client = useQueryClient();
  const router = useRouter();
  const [form, setForm] = useState<DraftForm>(() =>
    initial ? formFromDraft(initial) : { ...emptyDraft },
  );
  const [saved, setSaved] = useState(initial);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const [confirmReload, setConfirmReload] = useState(false);
  const [unavailable, setUnavailable] = useState(false);
  const [notice, setNotice] = useState('');
  const attempt = useRef<{ payload: string; key: string } | null>(null);
  const discardKey = useRef<string | null>(null);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  function changed<K extends keyof DraftForm>(key: K, value: DraftForm[K]) {
    setForm((current) => ({ ...current, [key]: value }));
    setNotice('');
    save.reset();
  }
  const refreshLists = () => {
    void client.invalidateQueries({ queryKey: ['quests', user!.id] });
    void client.invalidateQueries({ queryKey: ['home', user!.id] });
  };
  const save = useMutation({
    mutationFn: async () => {
      const body = validateDraft(form, saved);
      const payload = JSON.stringify(body);
      if (attempt.current?.payload !== payload)
        attempt.current = { payload, key: createRequestId() };
      return saved
        ? relationshipRequest(
            client,
            session,
            user!.id,
            `/quests/${encodeURIComponent(saved.id)}/draft`,
            draftSchema,
            { method: 'PATCH', body: { ...body, expectedRowVersion: saved.rowVersion } },
          )
        : relationshipRequest(client, session, user!.id, '/quests', draftSchema, {
            method: 'POST',
            body,
            headers: { 'Idempotency-Key': attempt.current.key },
          });
    },
    onSuccess: (result) => {
      if (!mounted.current) return;
      setSaved(result);
      setForm(formFromDraft(result));
      setNotice('내 초안으로 저장했어요. 파트너에게는 보이지 않아요.');
      refreshLists();
      if (!initial)
        router.replace({ pathname: '/quests/[questId]', params: { questId: result.id } });
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 404) {
        setUnavailable(true);
        refreshLists();
      }
    },
  });
  const reload = useMutation({
    mutationFn: () =>
      relationshipRequest(
        client,
        session,
        user!.id,
        `/quests/${encodeURIComponent(saved!.id)}`,
        draftSchema,
      ),
    onSuccess: (result) => {
      setSaved(result);
      setForm(formFromDraft(result));
      setConfirmReload(false);
      save.reset();
      discard.reset();
      setNotice('서버의 최신 초안을 불러왔어요.');
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 404) setUnavailable(true);
    },
  });
  const discard = useMutation({
    mutationFn: async () => {
      discardKey.current ??= createRequestId();
      return relationshipRequest(
        client,
        session,
        user!.id,
        `/quests/${encodeURIComponent(saved!.id)}/draft?expectedRowVersion=${saved!.rowVersion}`,
        discardedSchema,
        { method: 'DELETE', headers: { 'Idempotency-Key': discardKey.current } },
      );
    },
    onSuccess: () => {
      if (!mounted.current) return;
      refreshLists();
      router.replace('/quests');
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 404) {
        setUnavailable(true);
        refreshLists();
      }
    },
  });
  const busy = save.isPending || discard.isPending || reload.isPending;
  const error = save.error ?? discard.error ?? reload.error;
  const conflict = error instanceof ApiError && error.code === 'QUEST_VERSION_CONFLICT';
  if (unavailable)
    return (
      <View style={ui.card}>
        <Text accessibilityRole="alert" style={ui.error}>
          이 초안은 더 이상 확인할 수 없어요. 연결 상태가 바뀌었거나 폐기된 초안이에요.
        </Text>
        <Link href="/" style={ui.link}>
          홈에서 현재 상태 확인
        </Link>
      </View>
    );
  return (
    <View style={{ gap: 16 }}>
      <Text accessibilityRole="header" style={ui.title}>
        {saved ? '약속 다듬기' : '작은 약속 적기'}
      </Text>
      <Text style={ui.body}>
        나만 보는 개인 퀘스트 초안이에요. 저장은 코인·슬롯을 잠그거나 파트너에게 알리지 않아요.
      </Text>
      <View style={ui.card}>
        <Field
          label="퀘스트 제목"
          value={form.title}
          onChange={(value) => changed('title', value)}
          busy={busy}
          hint="1~80자"
        />
        <Text style={ui.body}>카테고리</Text>
        <View accessibilityRole="radiogroup" accessibilityLabel="카테고리" style={ui.row}>
          {Object.entries(categories).map(([value, label]) => (
            <Choice
              key={value}
              label={label}
              selected={form.category === value}
              disabled={busy}
              onPress={() => changed('category', value as DraftForm['category'])}
            />
          ))}
        </View>
        <Field
          label="성공 조건"
          value={form.successCriteria}
          onChange={(value) => changed('successCriteria', value)}
          busy={busy}
          hint="무엇을 하면 성공인지 구체적으로 적어 주세요. 1~1,000자"
          multiline
        />
        <Text style={ui.body}>제안 난이도</Text>
        <View accessibilityRole="radiogroup" accessibilityLabel="제안 난이도" style={ui.row}>
          {[1, 2, 3, 4].map((value) => (
            <Choice
              key={value}
              label={`★${value}`}
              selected={form.difficulty === value}
              disabled={busy}
              onPress={() => changed('difficulty', value)}
            />
          ))}
        </View>
        <Text style={ui.body}>제안 코인</Text>
        <View accessibilityRole="radiogroup" accessibilityLabel="제안 코인" style={ui.row}>
          {([0, 100] as const).map((value) => (
            <Choice
              key={value}
              label={`${value}C`}
              selected={form.stake === value}
              disabled={busy}
              onPress={() => changed('stake', value)}
            />
          ))}
        </View>
        <Text style={ui.caption}>
          자유 입력 퀘스트는 최대 100C를 제안할 수 있어요. 지금은 코인이 잠기지 않아요.
        </Text>
      </View>
      <View style={ui.card}>
        <Text accessibilityRole="header" style={ui.sectionTitle}>
          언제까지 해볼까요?
        </Text>
        <Text style={ui.caption}>
          모든 날짜는 서울 시간(UTC+09:00)이에요. 초안에는 지난 날짜도 저장할 수 있어요.
        </Text>
        <Field
          label="수행 마감 (서울 시간)"
          hint="YYYY-MM-DD HH:mm · 예: 2026-09-15 22:00"
          value={form.dueAt}
          onChange={(value) => changed('dueAt', value)}
          busy={busy}
        />
        <Field
          label="최소 수행 시간 (분)"
          hint="0~10,080분 · 특정 시점의 행동은 0분"
          value={form.minimumDurationMinutes}
          onChange={(value) => changed('minimumDurationMinutes', value)}
          busy={busy}
          numeric
        />
        <Field
          label="결과 확인 시작 (서울 시간)"
          hint="수행 마감보다 뒤 · YYYY-MM-DD HH:mm"
          value={form.resultAt}
          onChange={(value) => changed('resultAt', value)}
          busy={busy}
        />
        <Text style={ui.caption}>인증 방식: 사용 안 함. 이 초안에서는 사진을 받지 않아요.</Text>
      </View>
      {saved && (
        <View style={ui.note}>
          <Text style={ui.eyebrow}>서버에 저장된 일정</Text>
          <Text style={ui.body}>승인 마감 {toSeoulInput(saved.approvalDeadlineAt)} (서울)</Text>
          <Text style={ui.caption}>
            최종 결과 확인 마감 {toSeoulInput(saved.resultConfirmationDeadlineAt)} (서울)
          </Text>
          <Text style={ui.caption}>작성 내용을 바꾸었다면 다시 저장해야 위 일정에 반영돼요.</Text>
          <Link href={{ pathname: '/quest', params: { questId: saved.id } }} style={ui.link}>
            저장한 내용 확인하고 제출 →
          </Link>
        </View>
      )}
      {notice && (
        <Text accessibilityRole="alert" style={ui.body}>
          {notice}
        </Text>
      )}
      {error && (
        <Text accessibilityRole="alert" style={ui.error}>
          {error.message}
        </Text>
      )}
      {conflict && (
        <View style={ui.note}>
          <Text style={ui.body}>
            다른 곳에서 수정된 초안이에요. 지금 입력한 내용은 그대로 남겨뒀어요.
          </Text>
          <Button secondary disabled={busy} onPress={() => setConfirmReload(true)}>
            서버의 최신 초안 불러오기
          </Button>
        </View>
      )}
      {confirmReload && (
        <View style={ui.note}>
          <Text style={ui.body}>저장하지 않은 현재 입력을 버리고 최신 내용으로 바꿀까요?</Text>
          <Button busy={busy} onPress={() => reload.mutate()}>
            최신 내용으로 바꾸기
          </Button>
          <Button secondary disabled={busy} onPress={() => setConfirmReload(false)}>
            현재 입력 유지
          </Button>
        </View>
      )}
      <Button busy={busy} onPress={() => save.mutate()}>
        {save.isError ? '초안 저장 다시 시도' : '내 초안 저장'}
      </Button>
      {saved && (
        <Button secondary disabled={busy} onPress={() => setConfirmDiscard(true)}>
          초안 폐기
        </Button>
      )}
      {confirmDiscard && (
        <View style={ui.note}>
          <Text style={ui.sectionTitle}>이 초안을 폐기할까요?</Text>
          <Text style={ui.body}>저장한 내용을 지우며 되돌릴 수 없어요. 코인은 변하지 않아요.</Text>
          <Button busy={busy} onPress={() => discard.mutate()}>
            확인하고 초안 폐기
          </Button>
          <Button secondary disabled={busy} onPress={() => setConfirmDiscard(false)}>
            계속 작성하기
          </Button>
        </View>
      )}
      <Text style={ui.caption}>저장하지 않은 입력은 화면을 나가면 사라져요.</Text>
      <Link href="/quests" style={ui.link}>
        내 초안 목록으로
      </Link>
    </View>
  );
}
function Field({
  label,
  value,
  onChange,
  busy,
  hint,
  multiline = false,
  numeric = false,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  busy: boolean;
  hint: string;
  multiline?: boolean;
  numeric?: boolean;
}) {
  return (
    <View style={{ gap: 8 }}>
      <Text style={ui.body}>{label}</Text>
      <TextInput
        accessibilityLabel={label}
        accessibilityHint={hint}
        aria-disabled={busy}
        value={value}
        onChangeText={onChange}
        editable={!busy}
        multiline={multiline}
        keyboardType={numeric ? 'number-pad' : 'default'}
        autoCapitalize="none"
        style={[ui.input, multiline && { minHeight: 140, textAlignVertical: 'top' }]}
      />
      <Text style={ui.caption}>{hint}</Text>
    </View>
  );
}
function Choice({
  label,
  selected,
  disabled,
  onPress,
}: {
  label: string;
  selected: boolean;
  disabled: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable
      accessibilityRole="radio"
      accessibilityLabel={label}
      aria-checked={selected}
      aria-disabled={disabled}
      accessibilityState={{ checked: selected, disabled }}
      disabled={disabled}
      onPress={onPress}
      style={[
        ui.secondary,
        {
          minHeight: 48,
          padding: 12,
          borderRadius: 12,
          backgroundColor: selected ? colors.brandSoft : colors.surface,
        },
      ]}
    >
      <Text style={ui.body}>
        {selected ? '✓ ' : ''}
        {label}
      </Text>
    </Pressable>
  );
}
