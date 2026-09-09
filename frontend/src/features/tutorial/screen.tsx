import { Link, useLocalSearchParams, useRouter } from 'expo-router';
import { useState } from 'react';
import { Pressable, ScrollView, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useHome } from '@/features/game/home';
import { useRelationshipBoundary } from '@/features/game/relationship-cache';
import { useAuth } from '@/features/onboarding/auth-provider';
import { Button, ui } from '@/features/onboarding/ui';
import { toSeoulInput } from '@/features/quests/validation';
import { colors, spacing } from '@/theme/tokens';

import { TutorialCommand, useTutorialAction, useTutorialDetail, useTutorialOverview } from './api';
import {
  isTerminal,
  Outcome,
  statusLabels,
  Tutorial,
  TutorialAction,
  TutorialOverview,
} from './contracts';
import { validateTutorialSchedule } from './schedule';

export function TutorialScreen() {
  const { user, restoring } = useAuth();
  const home = useHome();
  const boundary = useRelationshipBoundary(user?.id ?? 'signed-out');
  const params = useLocalSearchParams<{ questId?: string | string[] }>();
  const router = useRouter();
  const selected = typeof params.questId === 'string' ? params.questId : undefined;
  const eligible = user?.status === 'ACTIVE' && !user.missingPolicyVersionIds.length;
  return (
    <SafeAreaView style={ui.root}>
      <ScrollView contentContainerStyle={ui.scroll} keyboardShouldPersistTaps="handled">
        <View style={ui.page}>
          <View style={ui.header}>
            <Text style={ui.brand}>BETCHU</Text>
            <Link href="/" style={ui.link}>
              홈으로
            </Link>
          </View>
          {restoring ? (
            <Text style={ui.body}>로그인을 확인하고 있어요…</Text>
          ) : !eligible ? (
            <Text style={ui.body}>홈에서 로그인과 가입 조건을 확인해 주세요.</Text>
          ) : boundary.ending ? (
            <Text style={ui.body}>연결을 정리하고 있어요. 이전 약속은 더 이상 볼 수 없어요.</Text>
          ) : home.isPending ? (
            <Text style={ui.body}>함께할 사람을 확인하고 있어요…</Text>
          ) : home.error ? (
            <ErrorCard error={home.error} retry={() => void home.refetch()} />
          ) : !home.data?.coupleId || home.data.coupleId !== boundary.coupleId ? (
            <Text style={ui.body}>서로의 연결을 확인한 뒤 첫 약속을 시작할 수 있어요.</Text>
          ) : (
            <TutorialHub
              key={`${user.id}:${home.data.coupleId}:${boundary.generation}`}
              coupleId={home.data.coupleId}
              selected={selected}
              open={(id) => router.setParams({ questId: id })}
            />
          )}
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

export function TutorialHub({
  coupleId,
  selected,
  open,
}: {
  coupleId: string;
  selected?: string;
  open: (id: string | undefined) => void;
}) {
  const overview = useTutorialOverview(coupleId);
  if (selected)
    return (
      <>
        <Button secondary onPress={() => open(undefined)}>
          첫 약속 목록으로
        </Button>
        <TutorialDetail coupleId={coupleId} id={selected} />
      </>
    );
  if (overview.isPending) return <Text style={ui.body}>첫 약속을 불러오고 있어요…</Text>;
  if (overview.error)
    return <ErrorCard error={overview.error} retry={() => void overview.refetch()} />;
  const data = overview.data;
  return (
    <View style={{ gap: spacing.lg }}>
      <View style={ui.hero}>
        <Text style={ui.eyebrow}>처음 함께하는 약속</Text>
        <Text accessibilityRole="header" style={ui.title}>
          물 한 잔부터,{'\n'}우리의 첫 성장.
        </Text>
        <Text style={ui.body}>서로 약속을 확인하고, 실제 결과를 함께 마무리해 봐요.</Text>
      </View>
      {data.own && <SummaryCard tutorial={data.own} open={open} />}
      {data.partner && <SummaryCard tutorial={data.partner} open={open} />}
      {data.canStart ? (
        <TutorialStart
          coupleId={coupleId}
          overview={data}
          receivedAt={overview.dataUpdatedAt}
          open={open}
        />
      ) : (
        !data.own && (
          <View style={ui.card}>
            <Text style={ui.body}>
              {data.completedAt
                ? '나의 첫 약속은 이미 마쳤어요. 튜토리얼은 다시 시작하지 않아요.'
                : '홈에서 배츄와 연결 상태를 확인해 주세요. 아직 첫 약속을 보낼 수 없어요.'}
            </Text>
          </View>
        )
      )}
      <View style={ui.note}>
        <Text style={ui.caption}>
          첫 약속을 마쳤다면 일반 퀘스트를 제출할 수 있어요. 파트너가 시작과 실제 결과를 확인해요.
        </Text>
        <Link href="/quest" style={ui.link}>
          일반 퀘스트 보기
        </Link>
      </View>
      <Button secondary disabled={overview.isFetching} onPress={() => void overview.refetch()}>
        첫 약속 새로고침
      </Button>
    </View>
  );
}

function SummaryCard({ tutorial, open }: { tutorial: Tutorial; open: (id: string) => void }) {
  return (
    <View style={ui.card}>
      <Text style={ui.eyebrow}>
        {tutorial.viewerRole === 'CREATOR' ? '내가 지킬 첫 약속' : '파트너가 지킬 첫 약속'}
      </Text>
      <Text style={ui.sectionTitle}>{statusLabels[tutorial.status]}</Text>
      <Text style={ui.body}>{tutorial.terms.title}</Text>
      {tutorial.allowedActions.length > 0 && (
        <Text style={ui.caption}>지금 내가 확인할 내용이 있어요.</Text>
      )}
      <Button secondary onPress={() => open(tutorial.id)}>
        {tutorial.viewerRole === 'CREATOR' ? '내 첫 약속 확인' : '파트너 첫 약속 확인'}
      </Button>
    </View>
  );
}

export function TutorialStart({
  coupleId,
  overview,
  receivedAt,
  open,
}: {
  coupleId: string;
  overview: TutorialOverview;
  receivedAt: number;
  open: (id: string) => void;
}) {
  const [due, setDue] = useState('');
  const [result, setResult] = useState('');
  const [review, setReview] = useState<{ dueAt: string; resultAt: string } | null>(null);
  const [validation, setValidation] = useState('');
  const action = useTutorialAction(coupleId, (tutorial) => open(tutorial.id));
  function prepare() {
    try {
      setReview(
        validateTutorialSchedule(
          due,
          result,
          Date.parse(overview.serverTime) + Math.max(0, Date.now() - receivedAt),
        ),
      );
      setValidation('');
    } catch (error) {
      setValidation(error instanceof Error ? error.message : '날짜를 확인해 주세요.');
    }
  }
  return (
    <View style={ui.card}>
      <Text accessibilityRole="header" style={ui.sectionTitle}>
        나의 첫 약속 보내기
      </Text>
      <TemplateCopy template={overview.template} />
      <Text style={ui.body}>
        계정당 한 번이에요. 보내면 날짜와 조건을 고치거나 회수할 수 없어요. 거절·기한 종료·취소로
        마쳐도 새 튜토리얼은 만들지 않아요.
      </Text>
      <Text style={ui.caption}>
        서울 시간(UTC+09:00)으로 지금부터 7일 안의 일정을 적어 주세요. 결과 확인 시작은 수행
        마감보다 뒤여야 해요.
      </Text>
      <Text style={ui.body}>수행 마감 (서울 시간)</Text>
      <TextInput
        accessibilityLabel="튜토리얼 수행 마감 (서울 시간)"
        accessibilityHint="YYYY-MM-DD HH:mm"
        value={due}
        editable={!review && !action.isPending}
        aria-disabled={!!review || action.isPending}
        onChangeText={setDue}
        placeholder="YYYY-MM-DD HH:mm"
        style={ui.input}
      />
      <Text style={ui.body}>결과 확인 시작 (서울 시간)</Text>
      <TextInput
        accessibilityLabel="튜토리얼 결과 확인 시작 (서울 시간)"
        accessibilityHint="YYYY-MM-DD HH:mm"
        value={result}
        editable={!review && !action.isPending}
        aria-disabled={!!review || action.isPending}
        onChangeText={setResult}
        placeholder="YYYY-MM-DD HH:mm"
        style={ui.input}
      />
      <Text style={ui.caption}>서버 확인 시각 {toSeoulInput(overview.serverTime)} (서울)</Text>
      {validation && (
        <Text accessibilityRole="alert" style={ui.error}>
          {validation}
        </Text>
      )}
      {action.error && (
        <Text accessibilityRole="alert" style={ui.error}>
          {action.error.message}
        </Text>
      )}
      {review ? (
        <View style={ui.note}>
          <Text style={ui.sectionTitle}>이 일정으로 한 번 보내볼까요?</Text>
          <Text style={ui.body}>
            수행 마감 {toSeoulInput(review.dueAt)}
            {'\n'}결과 확인 시작 {toSeoulInput(review.resultAt)} (서울)
          </Text>
          <Text style={ui.caption}>
            파트너가 시작 승인하면 내 {overview.template.stake}C가 잠겨요. 보내는 것만으로는 코인이
            잠기지 않아요.
          </Text>
          <Button
            busy={action.isPending}
            onPress={() => action.run({ path: '/tutorials', body: review })}
          >
            {action.error ? '같은 일정으로 보내기 재시도' : '확인하고 첫 약속 보내기'}
          </Button>
          <Button
            secondary
            disabled={action.isPending}
            onPress={() => {
              setReview(null);
              action.reset();
            }}
          >
            날짜 다시 정하기
          </Button>
        </View>
      ) : (
        <Button onPress={prepare}>보낼 일정 확인</Button>
      )}
    </View>
  );
}

function TutorialDetail({ coupleId, id }: { coupleId: string; id: string }) {
  const query = useTutorialDetail(coupleId, id);
  if (query.isPending) return <Text style={ui.body}>약속의 현재 상태를 확인하고 있어요…</Text>;
  if (query.error) return <ErrorCard error={query.error} retry={() => void query.refetch()} />;
  return (
    <>
      <TutorialInteraction
        key={`${query.data.id}:${query.data.rowVersion}`}
        tutorial={query.data}
      />
      <Button secondary disabled={query.isFetching} onPress={() => void query.refetch()}>
        약속 상태 새로고침
      </Button>
    </>
  );
}

type Confirmation = {
  action: TutorialAction;
  command: TutorialCommand;
  title: string;
  description: string;
};
const partnerActions = new Set<TutorialAction>([
  'APPROVE',
  'REJECT',
  'SELECT_RESULT',
  'FINAL_APPROVE',
  'REJECT_RESULT',
]);
export function TutorialInteraction({ tutorial }: { tutorial: Tutorial }) {
  const { user } = useAuth();
  const [prediction, setPrediction] = useState<Outcome | null>(null);
  const storedSelection = tutorial.viewerRole === 'PARTNER' ? tutorial.partnerSelection : null;
  const [selection, setSelection] = useState<Outcome | null>(
    storedSelection?.selectedResult ?? null,
  );
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null);
  const [validation, setValidation] = useState('');
  const action = useTutorialAction(tutorial.coupleId, () => setConfirmation(null));
  const can = (name: TutorialAction) =>
    tutorial.allowedActions.includes(name) &&
    (!partnerActions.has(name) || tutorial.viewerRole === 'PARTNER');
  const command = (path: string, body: Record<string, unknown> = {}): TutorialCommand => ({
    path: `/tutorials/${encodeURIComponent(tutorial.id)}/${path}`,
    body: { expectedRowVersion: tutorial.rowVersion, ...body },
  });
  function confirm(
    name: TutorialAction,
    path: string,
    title: string,
    description: string,
    body: Record<string, unknown> = {},
  ) {
    setValidation('');
    setConfirmation({ action: name, command: command(path, body), title, description });
    action.reset();
  }
  function approve() {
    if (!prediction) {
      setValidation('성공·실패 예상 중 하나를 골라 주세요.');
      return;
    }
    confirm(
      'APPROVE',
      'approve',
      '시작을 승인할까요?',
      `예상: ${outcomeLabel(prediction)}. 승인하면 파트너의 ${tutorial.terms.stake}C가 잠기고 약속이 시작돼요. 예상은 실제 결과와 별개예요.`,
      { questVersionId: tutorial.questVersionId, predictedResult: prediction },
    );
  }
  function select() {
    if (!selection) {
      setValidation('실제 결과를 먼저 골라 주세요.');
      return;
    }
    setValidation('');
    action.run(command('select-result', { selectedResult: selection }));
  }
  const dirtySelection = storedSelection && selection !== storedSelection.selectedResult;
  return (
    <View style={{ gap: spacing.lg }}>
      <View style={ui.hero}>
        <Text style={ui.eyebrow}>
          {tutorial.viewerRole === 'CREATOR' ? '내가 지킬 첫 약속' : '파트너가 지킬 첫 약속'}
        </Text>
        <Text accessibilityRole="header" style={ui.title}>
          {statusLabels[tutorial.status]}
        </Text>
        <Text style={ui.body}>{tutorial.terms.title}</Text>
      </View>
      <View style={ui.card}>
        <TemplateCopy template={tutorial.terms} />
        <Text style={ui.body}>수행 마감 {toSeoulInput(tutorial.terms.dueAt)} (서울)</Text>
        <Text style={ui.caption}>
          시작 승인 마감 {toSeoulInput(tutorial.terms.approvalDeadlineAt)}
          {'\n'}결과 확인 {toSeoulInput(tutorial.terms.resultAt)} ~{' '}
          {toSeoulInput(tutorial.terms.resultConfirmationDeadlineAt)} (서울)
        </Text>
        {tutorial.predictedResult && (
          <Text style={ui.body}>시작할 때의 예상: {outcomeLabel(tutorial.predictedResult)}</Text>
        )}
      </View>
      {tutorial.status === 'PENDING_APPROVAL' && tutorial.viewerRole === 'CREATOR' && (
        <View style={ui.note}>
          <Text style={ui.body}>
            파트너의 시작 승인을 기다리고 있어요. 아직 내 코인은 잠기지 않았어요.
          </Text>
          <Text style={ui.caption}>
            승인 마감이 지나거나 파트너가 거절하면 첫 약속은 끝나며 다시 보내지 않아요.
          </Text>
        </View>
      )}
      {tutorial.status === 'ACTIVE' && (
        <View style={ui.note}>
          <Text style={ui.body}>
            약속을 실천하고 있어요. 결과 확인 시작 뒤 파트너가 실제 결과를 확인할 수 있어요.
          </Text>
          <Text style={ui.caption}>
            {tutorial.terms.stake}C는 승인 때 잠겼어요. 결과 선택만으로 정산되지는 않아요.
          </Text>
        </View>
      )}
      {['AWAITING_RESULT', 'PENDING_FINAL_APPROVAL'].includes(tutorial.status) &&
        tutorial.viewerRole === 'CREATOR' && (
          <View style={ui.note}>
            <Text style={ui.body}>파트너 확인 대기</Text>
            <Text style={ui.caption}>파트너가 실제 결과를 최종 확인하면 정산 결과가 표시돼요.</Text>
          </View>
        )}
      {tutorial.status === 'PENDING_APPROVAL' && can('APPROVE') && (
        <View style={ui.card}>
          <Text style={ui.sectionTitle}>어떻게 예상하나요?</Text>
          <Text style={ui.caption}>예상은 실제 결과나 보상 계산을 바꾸지 않아요.</Text>
          <OutcomeChoices
            label="시작 예상"
            value={prediction}
            disabled={action.isPending}
            onChange={(value) => {
              setPrediction(value);
              setConfirmation(null);
              setValidation('');
              action.reset();
            }}
          />
          <Button busy={action.isPending} onPress={approve}>
            예상 선택 후 시작 승인
          </Button>
        </View>
      )}
      {tutorial.status === 'PENDING_APPROVAL' && can('REJECT') && (
        <Button
          secondary
          disabled={action.isPending}
          onPress={() =>
            confirm(
              'REJECT',
              'reject',
              '시작 요청을 거절할까요?',
              '코인을 잠그지 않고 파트너의 첫 약속이 끝나요. 이 튜토리얼을 다시 보내지는 않아요.',
              { questVersionId: tutorial.questVersionId },
            )
          }
        >
          시작 요청 거절
        </Button>
      )}
      {['AWAITING_RESULT', 'PENDING_FINAL_APPROVAL'].includes(tutorial.status) &&
        can('SELECT_RESULT') && (
          <View style={ui.card}>
            <Text style={ui.sectionTitle}>실제 결과를 확인해 주세요</Text>
            <Text style={ui.body}>{tutorial.terms.successCriteria}</Text>
            <OutcomeChoices
              label="실제 결과"
              value={selection}
              disabled={action.isPending}
              onChange={(value) => {
                setSelection(value);
                setValidation('');
                setConfirmation(null);
                action.reset();
              }}
            />
            <Button busy={action.isPending} onPress={select}>
              {storedSelection ? '바꾼 결과 저장' : '선택한 결과 저장'}
            </Button>
            <Text style={ui.caption}>
              결과를 저장한 다음 별도로 최종 확인해야 해요. 선택만으로 코인·XP·부화가 처리되지는
              않아요.
            </Text>
          </View>
        )}
      {tutorial.status === 'PENDING_FINAL_APPROVAL' && can('FINAL_APPROVE') && storedSelection && (
        <View style={ui.card}>
          <Text style={ui.sectionTitle}>
            저장된 실제 결과: {outcomeLabel(storedSelection.selectedResult)}
          </Text>
          {dirtySelection && (
            <Text style={ui.caption}>선택을 바꿨다면 먼저 바꾼 결과를 저장해 주세요.</Text>
          )}
          <Button
            disabled={!!dirtySelection}
            busy={action.isPending}
            onPress={() =>
              confirm(
                'FINAL_APPROVE',
                'final-approve',
                '이 결과를 최종 확인할까요?',
                `실제 결과: ${outcomeLabel(storedSelection.selectedResult)}. 최종 확인이 완료되면 이 결과는 바꿀 수 없어요.`,
                { selectedResult: storedSelection.selectedResult },
              )
            }
          >
            저장된 결과 최종 확인
          </Button>
        </View>
      )}
      {['AWAITING_RESULT', 'PENDING_FINAL_APPROVAL'].includes(tutorial.status) &&
        can('REJECT_RESULT') && (
          <Button
            secondary
            disabled={action.isPending}
            onPress={() =>
              confirm(
                'REJECT_RESULT',
                'reject-result',
                '결과 판정을 거절할까요?',
                '첫 약속을 무효로 마치고 잠긴 원금만 반환해요. 보너스·XP·부화는 없고, 튜토리얼을 다시 시작하지 않아요.',
              )
            }
          >
            결과 판정 거절
          </Button>
        )}
      {tutorial.cancelRequest?.status === 'PENDING' && (
        <View style={ui.note}>
          <Text style={ui.sectionTitle}>
            {tutorial.cancelRequest.requestedBy === user!.id
              ? '상대방의 취소 동의를 기다려요'
              : '상대방이 함께 취소하길 요청했어요'}
          </Text>
          <Text style={ui.body}>
            취소 요청만으로 약속이나 마감 시각이 멈추지 않아요. 수행 마감 전에 상대방이 동의해야
            취소돼요.
          </Text>
        </View>
      )}
      {tutorial.status === 'ACTIVE' && can('CANCEL') && (
        <Button
          secondary
          disabled={action.isPending}
          onPress={() =>
            confirm(
              'CANCEL',
              'cancel',
              '함께 취소를 요청할까요?',
              '상대방이 수행 마감 전에 동의해야 취소돼요. 요청하는 동안에도 약속과 마감은 계속돼요.',
            )
          }
        >
          함께 취소 요청
        </Button>
      )}
      {tutorial.status === 'ACTIVE' &&
        tutorial.cancelRequest?.status === 'PENDING' &&
        tutorial.cancelRequest.requestedBy !== user!.id && (
          <>
            {can('CONFIRM_CANCEL') && (
              <Button
                secondary
                disabled={action.isPending}
                onPress={() =>
                  confirm(
                    'CONFIRM_CANCEL',
                    'confirm-cancel',
                    '취소에 동의할까요?',
                    '잠긴 원금만 반환하고 첫 약속을 마쳐요. 이 튜토리얼은 다시 시작하지 않아요.',
                  )
                }
              >
                함께 취소에 동의
              </Button>
            )}
            {can('REJECT_CANCEL') && (
              <Button
                secondary
                disabled={action.isPending}
                onPress={() =>
                  confirm(
                    'REJECT_CANCEL',
                    'reject-cancel',
                    '취소 요청을 거절할까요?',
                    '첫 약속은 원래 조건과 일정대로 계속돼요.',
                  )
                }
              >
                취소 요청 거절
              </Button>
            )}
          </>
        )}
      {validation && (
        <Text accessibilityRole="alert" style={ui.error}>
          {validation}
        </Text>
      )}
      {action.error && (
        <Text accessibilityRole="alert" style={ui.error}>
          {action.error.message}
        </Text>
      )}
      {confirmation && can(confirmation.action) && (
        <View accessibilityRole="alert" style={ui.note}>
          <Text style={ui.sectionTitle}>{confirmation.title}</Text>
          <Text style={ui.body}>{confirmation.description}</Text>
          <Button busy={action.isPending} onPress={() => action.run(confirmation.command)}>
            {action.error ? '같은 요청 다시 시도' : '확인하고 진행'}
          </Button>
          <Button secondary disabled={action.isPending} onPress={() => setConfirmation(null)}>
            돌아가기
          </Button>
        </View>
      )}
      {isTerminal(tutorial.status) && <Settlement tutorial={tutorial} />}
    </View>
  );
}

function Settlement({ tutorial }: { tutorial: Tutorial }) {
  const settlement = tutorial.settlement;
  const hatched =
    settlement?.resolvedResult === 'SUCCESS' &&
    settlement.growthStageBefore === 'EGG' &&
    settlement.growthStageAfter === 'BABY';
  return (
    <View style={ui.card}>
      <Text accessibilityRole="header" style={ui.sectionTitle}>
        첫 약속을 마쳤어요
      </Text>
      {settlement ? (
        <>
          <Text style={ui.body}>
            반환한 원금 {settlement.stake}C · 보너스 {settlement.reward}C · {settlement.xp}XP
          </Text>
          <Text style={ui.caption}>서버 정산 시각 {toSeoulInput(settlement.settledAt)} (서울)</Text>
          {hatched && (
            <Text style={ui.body}>
              배츄가 아기로 부화했어요. 튜토리얼 성공은 일반 인정 성공 수에 포함되지 않아요.
            </Text>
          )}
        </>
      ) : (
        <Text style={ui.body}>코인을 잠그지 않고 마쳤어요. 보너스와 XP는 지급되지 않아요.</Text>
      )}
      {!hatched && (
        <Text style={ui.caption}>
          튜토리얼을 다시 시작하지 않아요. 아직 알인 배츄는 이후 첫 일반 개인 퀘스트 성공으로 부화할
          수 있어요.
        </Text>
      )}
      <Link href="/" style={ui.link}>
        홈에서 배츄와 잔액 확인
      </Link>
    </View>
  );
}
function TemplateCopy({ template }: { template: TutorialOverview['template'] }) {
  return (
    <>
      <Text style={ui.sectionTitle}>{template.title}</Text>
      <Text style={ui.body}>{template.successCriteria}</Text>
      <Text style={ui.caption}>
        식습관 · ★{template.difficulty} · 걸기 {template.stake}C · 인증 사용 안 함
      </Text>
      <Text style={ui.caption}>
        성공 최종 확인 시 원금 반환 + 최초 보너스 {template.reward}C + {template.xp}XP. 성공하지
        못하면 원금만 반환해요.
      </Text>
    </>
  );
}
function OutcomeChoices({
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
    <View accessibilityRole="radiogroup" accessibilityLabel={label} style={{ gap: spacing.sm }}>
      {(['SUCCESS', 'FAILURE'] as const).map((outcome) => (
        <Pressable
          key={outcome}
          accessibilityRole="radio"
          accessibilityLabel={`${label}: ${outcomeLabel(outcome)}`}
          aria-checked={value === outcome}
          aria-disabled={disabled}
          accessibilityState={{ checked: value === outcome, disabled }}
          disabled={disabled}
          onPress={() => onChange(outcome)}
          style={[
            ui.note,
            {
              minHeight: 56,
              borderWidth: 2,
              borderColor: value === outcome ? colors.brandStrong : 'transparent',
            },
          ]}
        >
          <Text style={ui.body}>
            {value === outcome ? '✓' : '○'} {outcomeLabel(outcome)}
          </Text>
        </Pressable>
      ))}
    </View>
  );
}
const outcomeLabel = (value: Outcome) => (value === 'SUCCESS' ? '성공' : '실패');
function ErrorCard({ error, retry }: { error: Error; retry: () => void }) {
  return (
    <View style={ui.card}>
      <Text accessibilityRole="alert" style={ui.error}>
        {error.message}
      </Text>
      <Button secondary onPress={retry}>
        현재 상태 다시 확인
      </Button>
    </View>
  );
}
