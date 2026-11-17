import { Link, useRouter } from 'expo-router';
import { useState } from 'react';
import { Linking, Platform, Share, Text, TextInput, View } from 'react-native';
import { z } from 'zod';

import { useAuth } from '@/features/onboarding/auth-provider';
import { policiesSchema, userSchema } from '@/features/onboarding/contracts';
import { ApiError } from '@/features/onboarding/transport';
import { Button, Check, ui } from '@/features/onboarding/ui';

import { useFeatureAction, useFeatureQuery } from './api';
import { deletionSchema, exportSchema, reportSchema } from './contracts';
import { PushSettings } from './notification-screens';
import { ActionError, dateLabel, FeaturePage, QueryState } from './page';

export function SettingsScreen() {
  return (
    <FeaturePage title="설정과 안전" safety>
      <Settings />
    </FeaturePage>
  );
}
function Settings() {
  const { user, session } = useAuth();
  const policies = useFeatureQuery('/policy-versions/current', policiesSchema);
  const consent = useFeatureAction(userSchema);
  const [error, setError] = useState<Error | null>(null);
  return (
    <>
      <View style={ui.card}>
        <Text style={ui.sectionTitle}>내 계정</Text>
        <Text style={ui.body}>{user!.nickname}</Text>
        <Link href="/account-export" style={ui.link}>
          내 기록 내보내기
        </Link>
        <Link href="/report" style={ui.link}>
          파트너 신고하기
        </Link>
        <Link href="/account-delete" style={ui.link}>
          회원 탈퇴
        </Link>
        <Text style={ui.caption}>
          연결 종료·차단은 홈의 연결 관리에서 할 수 있어요. 신고 내용은 상대에게 전달되지 않아요.
        </Text>
      </View>
      {user!.status === 'ACTIVE' && !user!.missingPolicyVersionIds.length && <PushSettings />}
      <View style={ui.card}>
        <Text style={ui.sectionTitle}>약관과 개인정보</Text>
        <QueryState pending={policies.isPending} error={policies.error} retry={policies.refetch} />
        <ActionError error={error ?? consent.error} />
        {policies.data?.policies.map((policy) => (
          <Button
            key={policy.id}
            secondary
            onPress={() =>
              void Linking.openURL(policy.documentUrl).catch(() =>
                setError(new Error('정책 문서를 열지 못했어요. 다시 시도해 주세요.')),
              )
            }
          >
            {
              {
                TERMS: '서비스 이용약관',
                PRIVACY: '개인정보 처리 동의',
                EVIDENCE_OPTIONAL: '인증 자료 처리 동의',
              }[policy.policyType]
            }{' '}
            · {policy.version}
          </Button>
        ))}
        <Text style={ui.caption}>
          인증 자료 처리: {user!.evidenceConsent ? '동의함' : '동의하지 않음'}
        </Text>
        {user!.evidenceConsent && (
          <Button
            secondary
            busy={consent.isPending}
            onPress={() =>
              void consent
                .run({ path: '/consents/EVIDENCE_OPTIONAL', method: 'DELETE' })
                .then((result) => {
                  if (result) session.setUser(result);
                })
            }
          >
            인증 자료 동의 철회
          </Button>
        )}
      </View>
    </>
  );
}

const targetsSchema = z.array(z.object({ id: z.uuid(), nickname: z.string() }));
export function ReportScreen() {
  return (
    <FeaturePage title="안전하게 신고하기" safety>
      <Report />
    </FeaturePage>
  );
}
function Report() {
  const targets = useFeatureQuery('/reports/targets', targetsSchema);
  const action = useFeatureAction(reportSchema);
  const [target, setTarget] = useState('');
  const [reason, setReason] = useState('HARASSMENT');
  const [description, setDescription] = useState('');
  const reasons = {
    HARASSMENT: '괴롭힘·압박',
    UNSAFE_REQUEST: '위험하거나 부적절한 요구',
    PRIVACY: '개인정보 침해',
    OTHER: '기타',
  };
  return (
    <>
      <Text style={ui.body}>
        현재 또는 이전 파트너를 신고할 수 있어요. 내용은 상대에게 알리지 않아요. 신고 접수와 연결
        종료는 별개예요.
      </Text>
      <QueryState pending={targets.isPending} error={targets.error} retry={targets.refetch} />
      {action.data ? (
        <View style={ui.note}>
          <Text accessibilityRole="alert" style={ui.body}>
            신고를 접수했어요.
          </Text>
          <Text selectable style={ui.caption}>
            접수 번호: {action.data.id}
          </Text>
          <Text style={ui.caption}>{dateLabel(action.data.createdAt)}</Text>
          <Link href="/" style={ui.link}>
            홈에서 연결 관리
          </Link>
        </View>
      ) : (
        <>
          {targets.data?.length === 0 && <Text style={ui.body}>신고할 연결 기록이 없어요.</Text>}
          {targets.data?.map((person) => (
            <Check
              key={person.id}
              checked={target === person.id}
              disabled={action.isPending}
              onPress={() => setTarget(person.id)}
            >
              {person.nickname}
            </Check>
          ))}
          <Text style={ui.sectionTitle}>어떤 일이 있었나요?</Text>
          {Object.entries(reasons).map(([key, label]) => (
            <Check
              key={key}
              checked={reason === key}
              disabled={action.isPending}
              onPress={() => setReason(key)}
            >
              {label}
            </Check>
          ))}
          <TextInput
            accessibilityLabel="신고 내용"
            multiline
            maxLength={2000}
            value={description}
            onChangeText={setDescription}
            editable={!action.isPending}
            style={[ui.input, { minHeight: 140 }]}
            placeholder="발생한 일을 적어 주세요. 비밀번호나 불필요한 개인정보는 적지 마세요."
          />
          <Text style={ui.caption}>{description.length}/2,000자 · 하루 최대 5건</Text>
          <ActionError error={action.error} />
          <Button
            disabled={!target || !description.trim()}
            busy={action.isPending}
            onPress={() =>
              void action.run({
                path: '/reports',
                body: { reportedUserId: target, reason, description: description.trim() },
              })
            }
          >
            신고 제출
          </Button>
        </>
      )}
    </>
  );
}

export function DeleteAccountScreen() {
  return (
    <FeaturePage title="회원 탈퇴" safety>
      <DeleteAccount />
    </FeaturePage>
  );
}
function DeleteAccount() {
  const { session } = useAuth();
  const router = useRouter();
  const action = useFeatureAction(deletionSchema);
  const [confirmed, setConfirmed] = useState(false);
  const [text, setText] = useState('');
  const [error, setError] = useState<Error | null>(null);
  async function remove() {
    setError(null);
    const result =
      action.data ??
      (await action.run({
        path: '/account/delete',
        body: { confirmation: 'DELETE_MY_ACCOUNT' },
      }));
    if (result) {
      try {
        await session.clearSession();
        router.replace('/');
      } catch {
        setError(new Error('탈퇴가 접수됐어요. 기기 로그인 정보 정리를 다시 시도해 주세요.'));
      }
    }
  }
  return (
    <View style={ui.card}>
      <Text style={ui.body}>
        탈퇴하면 연결이 즉시 종료되고 모든 기기에서 로그아웃돼요. 진행 중인 약속을 정리한 뒤
        프로필과 로그인 정보를 삭제해요.
      </Text>
      <Text style={ui.caption}>
        삭제된 계정은 복구할 수 없어요. 부정 이용 방지를 위해 같은 로그인 계정의 재가입은 30일간
        제한돼요. 코인 정산 내역과 신고 기록은 개인정보 처리 정책에 따른 제한된 기록으로 보관돼요.
      </Text>
      <Link href="/account-export" style={ui.link}>
        탈퇴 전 내 기록 내보내기
      </Link>
      <Check
        checked={confirmed}
        disabled={action.isPending}
        onPress={() => setConfirmed(!confirmed)}
      >
        연결 종료와 계정 삭제 내용을 확인했어요.
      </Check>
      <TextInput
        accessibilityLabel="탈퇴 확인 문구"
        style={ui.input}
        value={text}
        onChangeText={setText}
        editable={!action.isPending}
        placeholder="탈퇴합니다"
      />
      <ActionError error={error ?? action.error} />
      {action.error instanceof ApiError && action.error.code === 'REAUTHENTICATION_REQUIRED' && (
        <Button
          secondary
          onPress={() =>
            void session
              .logout()
              .then(() => router.replace('/'))
              .catch(() => setError(new Error('로그아웃하지 못했어요. 다시 시도해 주세요.')))
          }
        >
          로그아웃 후 다시 로그인하기
        </Button>
      )}
      <Button
        busy={action.isPending}
        disabled={!confirmed || text !== '탈퇴합니다'}
        onPress={() => void remove()}
      >
        {action.data ? '탈퇴 접수 후 기기 정보 정리' : '계정 삭제 요청'}
      </Button>
      <Text style={ui.caption}>최근 10분 안에 로그인한 계정에서만 요청할 수 있어요.</Text>
    </View>
  );
}

const sections = {
  ACCOUNT: '계정',
  CONSENTS: '동의 기록',
  LEDGER: '코인 기록',
  INVENTORY: '보유 장비',
  DRAWS: '뽑기 기록',
  MONSTERS: '배츄',
  PROGRESSION: '성장 기록',
  EQUIPMENT: '장착 상태',
  RESCUE: '구제 기록',
  REPORTS: '내 신고',
  QUESTS: '내 퀘스트',
};
export function AccountExportScreen() {
  return (
    <FeaturePage title="내 기록 내보내기" safety>
      <AccountExport />
    </FeaturePage>
  );
}
function AccountExport() {
  const { session } = useAuth();
  const [section, setSection] = useState<keyof typeof sections>('ACCOUNT');
  const [page, setPage] = useState<z.infer<typeof exportSchema> | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  async function load(cursor?: string) {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      setPage(
        await session.request(
          `/account/export?section=${section}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`,
          exportSchema,
        ),
      );
    } catch (reason) {
      setError(reason instanceof Error ? reason : new Error('기록을 가져오지 못했어요.'));
    } finally {
      setBusy(false);
    }
  }
  async function save() {
    if (!page) return;
    try {
      const message = JSON.stringify(page, null, 2);
      if (Platform.OS === 'web') {
        const url = URL.createObjectURL(new Blob([message], { type: 'application/json' }));
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = `betchu-${section.toLowerCase()}-${Date.now()}.json`;
        anchor.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      } else await Share.share({ message, title: `BETCHU ${sections[section]} 내 기록` });
    } catch {
      setError(new Error('내보내지 못했어요. 다시 시도해 주세요.'));
    }
  }
  return (
    <>
      <Text style={ui.body}>
        내 기록만 100개씩 가져와요. 개인정보가 포함되므로 저장하거나 공유할 위치를 직접 확인해
        주세요.
      </Text>
      <View style={ui.row}>
        {Object.entries(sections).map(([key, label]) => (
          <Button
            key={key}
            secondary
            disabled={busy || key === section}
            onPress={() => {
              setSection(key as keyof typeof sections);
              setPage(null);
            }}
          >
            {label}
          </Button>
        ))}
      </View>
      <Button busy={busy} onPress={() => void load()}>
        {sections[section]} 처음부터 불러오기
      </Button>
      <ActionError error={error} />
      {page && (
        <View style={ui.card}>
          <Text style={ui.body}>
            {page.records.length}개 기록 · {dateLabel(page.generatedAt)}
          </Text>
          <Button secondary onPress={() => void save()}>
            이 페이지 저장·공유
          </Button>
          <Text selectable style={ui.caption}>
            {JSON.stringify(page.records, null, 2)}
          </Text>
          {page.nextCursor && (
            <Button busy={busy} onPress={() => void load(page.nextCursor!)}>
              다음 페이지 불러오기
            </Button>
          )}
        </View>
      )}
    </>
  );
}
