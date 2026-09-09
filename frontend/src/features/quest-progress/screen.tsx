import { useQueryClient } from '@tanstack/react-query';
import { Link, useLocalSearchParams } from 'expo-router';
import { useEffect, useState } from 'react';
import { Text, TextInput, View } from 'react-native';

import { useRelationshipBoundary } from '@/features/game/relationship-cache';
import { useAuth } from '@/features/onboarding/auth-provider';
import { Button, ui } from '@/features/onboarding/ui';
import { categories } from '@/features/quests/contracts';
import { QuestPage } from '@/features/quests/screens';
import { toSeoulInput } from '@/features/quests/validation';

import { useQuestAction, useQuestQuery } from './api';
import {
  Outcome,
  Quest,
  QuestVersion,
  pageSchema,
  questSchema,
  quoteSchema,
  rulesSchema,
  statusLabels,
  versionsSchema,
} from './contracts';

export function QuestScreen() {
  return (
    <QuestPage>
      <QuestContents />
    </QuestPage>
  );
}
function QuestContents() {
  const { user } = useAuth();
  const boundary = useRelationshipBoundary(user!.id);
  const { questId } = useLocalSearchParams<{ questId?: string }>();
  return typeof questId === 'string' ? (
    <QuestDetail key={questId} coupleId={boundary.coupleId!} id={questId} />
  ) : (
    <QuestHub coupleId={boundary.coupleId!} />
  );
}

function QuestHub({ coupleId }: { coupleId: string }) {
  const [cursors, setCursors] = useState<string[]>([]);
  const cursor = cursors.at(-1);
  const query = useQuestQuery(
    coupleId,
    `/quests?status=ALL&limit=50${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`,
    pageSchema,
  );
  return (
    <View style={{ gap: 16 }}>
      <Text style={ui.title}>우리의 퀘스트</Text>
      <Link href="/quests/new" style={ui.link}>
        새 약속 쓰기 →
      </Link>
      <Link href="/quests" style={ui.link}>
        내 초안 다듬기
      </Link>
      <BettingRules coupleId={coupleId} />
      {query.error ? (
        <Text accessibilityRole="alert" style={ui.error}>
          {query.error.message}
        </Text>
      ) : !query.data ? (
        <Text style={ui.body}>퀘스트를 불러오는 중…</Text>
      ) : (
        <>
          {query.data.quests.length === 0 && (
            <Text style={ui.body}>아직 표시할 퀘스트가 없어요. 초안부터 적어 보세요.</Text>
          )}
          {query.data.quests.map((q) => (
            <View style={ui.card} key={q.id}>
              <Text style={ui.eyebrow}>
                {q.viewerRole === 'CREATOR' ? '나의 약속' : '파트너의 약속'} ·{' '}
                {statusLabels[q.status]}
              </Text>
              <Text style={ui.sectionTitle}>
                {q.viewerRole === 'CREATOR' && q.draft ? q.draft.title : q.version?.terms.title}
              </Text>
              <Link href={{ pathname: '/quest', params: { questId: q.id } }} style={ui.link}>
                내용과 요청 확인 →
              </Link>
            </View>
          ))}
          {query.data.nextCursor && (
            <Button secondary onPress={() => setCursors([...cursors, query.data!.nextCursor!])}>
              다음 퀘스트 보기
            </Button>
          )}
        </>
      )}
      {cursors.length > 0 && (
        <Button secondary onPress={() => setCursors(cursors.slice(0, -1))}>
          이전 퀘스트 보기
        </Button>
      )}
      <Button secondary onPress={() => void query.refetch()}>
        퀘스트 새로고침
      </Button>
    </View>
  );
}

function BettingRules({ coupleId }: { coupleId: string }) {
  const query = useQuestQuery(coupleId, '/couples/me/settlement-rules', rulesSchema);
  const action = useQuestAction(coupleId, rulesSchema);
  if (query.error) return <Text style={ui.error}>{query.error.message}</Text>;
  if (!query.data?.suspended) return null;
  return (
    <View style={ui.note}>
      <Text style={ui.sectionTitle}>정산 규칙을 함께 확인해 주세요</Text>
      <Text style={ui.body}>
        최근 코인 퀘스트가 반복해서 무효로 끝나 새 코인 승인을 잠시 멈췄어요. 0C 퀘스트와 진행 중인
        약속의 정산은 계속할 수 있어요.
      </Text>
      <Text style={ui.body}>
        성공하면 원금과 예약 보상을 받고, 실패하면 원금을 잃어요. 결과는 확인 시작 후 24시간 안에
        파트너가 최종 확정해요.
      </Text>
      <Text style={ui.caption}>
        {query.data.partnerAcknowledged ? '파트너 확인 완료' : '파트너 확인 대기'} ·{' '}
        {query.data.acknowledgedAt ? '내 확인 완료' : '내 확인 필요'}
      </Text>
      {!query.data.acknowledgedAt && (
        <Button
          busy={action.isPending}
          onPress={() =>
            action.run({
              path: '/couples/me/settlement-rules/acknowledge',
              body: { expectedGeneration: query.data!.generation },
            })
          }
        >
          정산 규칙을 이해했어요
        </Button>
      )}
      {action.error && (
        <Text accessibilityRole="alert" style={ui.error}>
          {action.error.message}
        </Text>
      )}
    </View>
  );
}

function QuestDetail({ coupleId, id }: { coupleId: string; id: string }) {
  const query = useQuestQuery(coupleId, `/quests/${encodeURIComponent(id)}/progress`, questSchema);
  const { user } = useAuth();
  const client = useQueryClient();
  const stamp = query.data ? `${query.data.id}:${query.data.rowVersion}` : null;
  useEffect(() => {
    if (stamp) void client.invalidateQueries({ queryKey: ['home', user!.id] });
  }, [stamp, client, user]);
  return (
    <View style={{ gap: 16 }}>
      <Link href="/quest" style={ui.link}>
        퀘스트 목록으로
      </Link>
      {query.error ? (
        <Text accessibilityRole="alert" style={ui.error}>
          {query.error.message}
        </Text>
      ) : query.data ? (
        <>
          <QuestPanel key={`${id}:${query.data.rowVersion}`} quest={query.data} />
          <History coupleId={coupleId} id={id} />
          <BettingRules coupleId={coupleId} />
        </>
      ) : (
        <Text style={ui.body}>약속을 확인하고 있어요…</Text>
      )}
      <Button secondary onPress={() => void query.refetch()}>
        최신 내용 다시 확인
      </Button>
    </View>
  );
}

export function QuestPanel({ quest: q }: { quest: Quest }) {
  const action = useQuestAction(q.coupleId, questSchema);
  const [prediction, setPrediction] = useState<Outcome | null>(null);
  const selected = q.viewerRole === 'PARTNER' ? q.partnerSelection : null;
  const [choice, setChoice] = useState<Outcome | null>(selected?.selectedResult ?? null);
  const [message, setMessage] = useState('');
  const [confirm, setConfirm] = useState<string | null>(null);
  const can = (name: Quest['allowedActions'][number]) => q.allowedActions.includes(name);
  const run = (suffix: string, body: Record<string, unknown> = {}, method?: 'PUT') =>
    action.run({
      path: `/quests/${encodeURIComponent(q.id)}/${suffix}`,
      method,
      body: { expectedRowVersion: q.rowVersion, ...body },
    });
  const terms = q.viewerRole === 'CREATOR' && q.draft ? q.draft : q.version?.terms;
  const actions: Record<string, () => void> = {
    submit: () => run('submit'),
    reject: () => run('reject', { questVersionId: q.questVersionId }),
    'partner-result/reject': () => run('partner-result/reject'),
    cancel: () => run('cancel'),
    'confirm-cancel': () => run('confirm-cancel'),
  };
  return (
    <View style={{ gap: 16 }}>
      <Text style={ui.eyebrow}>
        {q.viewerRole === 'CREATOR' ? '나의 약속' : '파트너의 약속'} ·{' '}
        {q.auditProjectionStatus === 'RECALLED' ? '작성자가 회수함' : statusLabels[q.status]}
      </Text>
      <Text style={ui.title}>{terms?.title ?? '퀘스트 기록'}</Text>
      {terms && <TermsView terms={terms} />}
      <Text style={ui.caption}>
        서버 확인 {toSeoulInput(q.serverTime)} (서울) · 기한이 되면 상태를 새로 확인해요.
      </Text>
      {q.decision?.requestMessage && (
        <View style={ui.note}>
          <Text style={ui.eyebrow}>파트너의 수정 요청</Text>
          <Text style={ui.body}>{q.decision.requestMessage}</Text>
        </View>
      )}
      {q.viewerRole === 'CREATOR' && q.draft && (
        <>
          <Text style={ui.caption}>
            현재 저장한 초안이에요. 다시 제출하기 전까지 파트너는 직전 제출본을 봐요.
          </Text>
          <OwnQuote quest={q} />
          <Link href={{ pathname: '/quests/[questId]', params: { questId: q.id } }} style={ui.link}>
            초안 수정·폐기
          </Link>
        </>
      )}
      {can('SUBMIT') && (
        <Button disabled={action.isPending} onPress={() => setConfirm('submit')}>
          파트너에게 제출
        </Button>
      )}
      {can('RECALL') && (
        <Button
          secondary
          busy={action.isPending}
          onPress={() => run('recall', { questVersionId: q.questVersionId })}
        >
          제출 회수하고 수정
        </Button>
      )}
      {can('APPROVE') && (
        <View style={ui.card}>
          <Text style={ui.sectionTitle}>제출본 {q.version?.versionNo} 시작 승인</Text>
          <Text style={ui.body}>
            승인하면 이 조건으로 코인과 슬롯을 예약해요. 예상 결과는 보상에 영향을 주지 않아요.
          </Text>
          <Outcomes
            label="예상 결과"
            value={prediction}
            onChange={setPrediction}
            disabled={action.isPending}
          />
          <Button
            disabled={!prediction}
            busy={action.isPending}
            onPress={() =>
              run('approve', { questVersionId: q.questVersionId, predictedResult: prediction })
            }
          >
            이 제출본으로 시작 승인
          </Button>
          <TextInput
            style={ui.input}
            accessibilityLabel="수정 요청 내용"
            placeholder="어떤 조건을 바꾸면 좋을까요?"
            value={message}
            onChangeText={setMessage}
            multiline
            editable={!action.isPending}
          />
          <Button
            secondary
            disabled={!message.trim()}
            busy={action.isPending}
            onPress={() =>
              run('request-change', { questVersionId: q.questVersionId, requestMessage: message })
            }
          >
            수정 요청 보내기
          </Button>
          <Button secondary disabled={action.isPending} onPress={() => setConfirm('reject')}>
            시작 거절
          </Button>
        </View>
      )}
      {q.lockedStake !== null && !q.settlement && (
        <Text style={ui.body}>
          잠긴 원금 {q.lockedStake}C · 성공 보상 예약 {q.rewardReservedAmount}C
        </Text>
      )}
      {can('SELECT_RESULT') && (
        <View style={ui.card}>
          <Text style={ui.sectionTitle}>실제 결과 확인</Text>
          <Text style={ui.body}>먼저 결과를 저장하고, 아래에서 최종 확정해 주세요.</Text>
          <Outcomes
            label="실제 결과"
            value={choice}
            onChange={setChoice}
            disabled={action.isPending}
          />
          <Button
            disabled={!choice || choice === selected?.selectedResult}
            busy={action.isPending}
            onPress={() => run('partner-result', { selectedResult: choice }, 'PUT')}
          >
            선택한 결과 저장
          </Button>
          {selected && (
            <>
              <Text style={ui.caption}>
                서버에 저장된 결과: {selected.selectedResult === 'SUCCESS' ? '성공' : '실패'} · 선택{' '}
                {selected.selectionRevision}회
              </Text>
              <Button
                disabled={!can('FINAL_APPROVE') || choice !== selected.selectedResult}
                busy={action.isPending}
                onPress={() => setConfirm('final')}
              >
                저장된 결과 최종 확정
              </Button>
            </>
          )}
          <Button
            secondary
            disabled={action.isPending}
            onPress={() => setConfirm('partner-result/reject')}
          >
            판단할 수 없어 무효로 종료
          </Button>
        </View>
      )}
      {q.viewerRole === 'CREATOR' &&
        ['AWAITING_RESULT', 'PENDING_FINAL_APPROVAL'].includes(q.status) && (
          <Text style={ui.body}>
            파트너가 실제 결과를 확인하고 있어요. 최종 확정 후 정산 내역이 표시돼요.
          </Text>
        )}
      {can('CANCEL') && (
        <Button secondary disabled={action.isPending} onPress={() => setConfirm('cancel')}>
          함께 취소 요청
        </Button>
      )}
      {q.cancelRequest?.status === 'PENDING' && (
        <View style={ui.note}>
          <Text style={ui.body}>취소 요청 대기 중에도 수행 기한은 계속돼요.</Text>
          {can('CONFIRM_CANCEL') && (
            <>
              <Button disabled={action.isPending} onPress={() => setConfirm('confirm-cancel')}>
                취소에 동의
              </Button>
              <Button secondary busy={action.isPending} onPress={() => run('reject-cancel')}>
                계속 진행하기
              </Button>
            </>
          )}
        </View>
      )}
      {q.settlement && (
        <View style={ui.card}>
          <Text style={ui.sectionTitle}>정산 완료 · {statusLabels[q.status]}</Text>
          <Text style={ui.body}>
            {q.settlement.resolvedResult === 'FAILURE'
              ? `원금 ${q.settlement.stake}C 소멸`
              : `원금 ${q.settlement.stake}C 반환`}{' '}
            · 보상 {q.settlement.reward}C · 경험치 {q.settlement.xp} XP
          </Text>
          {q.settlement.recognizedSuccessAfter !== null && (
            <Text style={ui.body}>인정 성공 {q.settlement.recognizedSuccessAfter}회</Text>
          )}
          <Text style={ui.caption}>{toSeoulInput(q.settlement.settledAt)} (서울)</Text>
        </View>
      )}
      {confirm && (
        <View style={ui.note}>
          <Text style={ui.sectionTitle}>
            {confirm === 'final'
              ? '저장된 결과로 정산할까요?'
              : confirm === 'submit'
                ? '이 초안을 파트너에게 보낼까요?'
                : confirm === 'cancel'
                  ? '파트너에게 취소를 요청할까요?'
                  : '이 선택으로 진행할까요?'}
          </Text>
          <Text style={ui.body}>
            {confirm === 'final'
              ? `확정 후 바꿀 수 없어요. ${selected?.selectedResult === 'FAILURE' ? `실패하면 원금 ${q.lockedStake}C가 사라져요.` : `성공하면 원금과 예약 보상을 받아요.`}`
              : confirm === 'submit'
                ? '위에 표시된 저장 내용이 제출돼요. 튜토리얼을 마쳐야 제출할 수 있어요.'
                : confirm === 'confirm-cancel'
                  ? '원금은 반환되지만 이 날짜의 코인·경험치 횟수는 사용한 것으로 남아요.'
                  : confirm === 'partner-result/reject'
                    ? '보상 없이 원금을 반환하고 슬롯을 풀어요.'
                    : '이 요청이 서버에 기록돼요.'}
          </Text>
          <Button
            busy={action.isPending}
            onPress={() => {
              if (confirm === 'final' && selected)
                run('partner-result/final-approve', {
                  selectedResult: selected.selectedResult,
                  selectionRevision: selected.selectionRevision,
                });
              else actions[confirm]?.();
            }}
          >
            확인하고 진행
          </Button>
          <Button secondary disabled={action.isPending} onPress={() => setConfirm(null)}>
            돌아가기
          </Button>
        </View>
      )}
      {action.error && (
        <Text accessibilityRole="alert" style={ui.error}>
          {action.error.message}
        </Text>
      )}
    </View>
  );
}
function TermsView({
  terms: t,
}: {
  terms: Pick<
    QuestVersion['terms'],
    | 'title'
    | 'category'
    | 'successCriteria'
    | 'difficulty'
    | 'stake'
    | 'dueAt'
    | 'resultAt'
    | 'approvalDeadlineAt'
    | 'resultConfirmationDeadlineAt'
    | 'minimumDurationMinutes'
  >;
}) {
  return (
    <View style={ui.card}>
      <Text style={ui.eyebrow}>
        {categories[t.category]} · ★{t.difficulty} · {t.stake}C
      </Text>
      <Text style={ui.body}>{t.successCriteria}</Text>
      <Text style={ui.caption}>시작 승인 마감 {toSeoulInput(t.approvalDeadlineAt)} (서울)</Text>
      <Text style={ui.body}>수행 마감 {toSeoulInput(t.dueAt)} (서울)</Text>
      <Text style={ui.body}>결과 확인 시작 {toSeoulInput(t.resultAt)} (서울)</Text>
      <Text style={ui.caption}>
        최종 확인 마감 {toSeoulInput(t.resultConfirmationDeadlineAt)} (서울) · 최소 수행{' '}
        {t.minimumDurationMinutes}분
      </Text>
      <Text style={ui.caption}>
        자유 입력 성공 보상은 최대 원금의 50%이며 승인 시 남은 한도에서 확정돼요. 실패하면 원금이
        사라져요. 사진 인증은 사용하지 않아요.
      </Text>
    </View>
  );
}
function OwnQuote({ quest: q }: { quest: Quest }) {
  const query = useQuestQuery(q.coupleId, `/quests/${q.id}/approval-quote`, quoteSchema);
  return (
    <View style={ui.note}>
      <Text style={ui.eyebrow}>나만 보는 승인 예상</Text>
      {query.error ? (
        <Text style={ui.error}>{query.error.message}</Text>
      ) : query.data ? (
        <>
          <Text style={ui.body}>
            {query.data.budgetDateKst} (서울) · 예상 성공 보상 {query.data.expectedReward}C
          </Text>
          <Text style={ui.caption}>
            남은 동시 진행 {query.data.activeSlotsRemaining} / 5 · 경험치{' '}
            {query.data.xpSlotsRemaining} / 5 · 코인 {query.data.coinSlotsRemaining} / 2
          </Text>
          <Text style={ui.caption}>
            남은 전체 보상 {query.data.totalBonusRemaining}C · 자유 입력·낮은 난이도 보상{' '}
            {query.data.lowBonusRemaining}C
          </Text>
          {!query.data.canApprove && (
            <Text style={ui.body}>
              현재 조건으로 승인할 수 없어요. 튜토리얼·잔액·슬롯·정산 규칙과 제안 코인을 확인해
              주세요.
            </Text>
          )}
          <Text style={ui.caption}>
            다른 약속이 승인되면 바뀔 수 있어요. 시작 승인 시점에 다시 확인해요.
          </Text>
        </>
      ) : (
        <Text style={ui.body}>한도를 확인하고 있어요…</Text>
      )}
    </View>
  );
}
function History({ coupleId, id }: { coupleId: string; id: string }) {
  const [open, setOpen] = useState(false);
  const query = useQuestQuery(coupleId, `/quests/${id}/versions`, versionsSchema, open);
  const labels: Record<string, string> = {
    title: '제목',
    category: '분류',
    successCriteria: '성공 조건',
    difficulty: '난이도',
    stake: '코인',
    minimumDurationMinutes: '최소 수행 시간',
    evidenceMethod: '인증',
    dueAt: '수행 마감',
    resultAt: '결과 확인 시작',
  };
  return (
    <View style={{ gap: 12 }}>
      <Button secondary onPress={() => setOpen(!open)}>
        {open ? '제출 이력 접기' : '제출 이력 보기'}
      </Button>
      {open &&
        (query.error ? (
          <Text style={ui.error}>{query.error.message}</Text>
        ) : (
          query.data?.versions.map((v) => (
            <View style={ui.card} key={v.id}>
              <Text style={ui.sectionTitle}>
                제출본 {v.versionNo} · {v.terms.title}
              </Text>
              <Text style={ui.caption}>
                {v.changedFields.length
                  ? `바뀐 항목: ${v.changedFields.map((f) => labels[f]).join(', ')}`
                  : '첫 제출본'}
              </Text>
              <TermsView terms={v.terms} />
            </View>
          ))
        ))}
    </View>
  );
}
function Outcomes({
  label,
  value,
  onChange,
  disabled,
}: {
  label: string;
  value: Outcome | null;
  onChange: (value: Outcome) => void;
  disabled: boolean;
}) {
  return (
    <View accessibilityRole="radiogroup" accessibilityLabel={label} style={ui.row}>
      {(['SUCCESS', 'FAILURE'] as const).map((r) => (
        <Button key={r} secondary={value !== r} disabled={disabled} onPress={() => onChange(r)}>
          {label} {r === 'SUCCESS' ? '성공' : '실패'}
          {value === r ? ' ✓' : ''}
        </Button>
      ))}
    </View>
  );
}
