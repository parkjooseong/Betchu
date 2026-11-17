import { useInfiniteQuery } from '@tanstack/react-query';
import { Link } from 'expo-router';
import { useState } from 'react';
import { Text, View } from 'react-native';

import { useAuth } from '@/features/onboarding/auth-provider';
import { Button, Check, ui } from '@/features/onboarding/ui';

import { useFeatureAction, useFeatureQuery } from './api';
import {
  Draw,
  drawSchema,
  Economy,
  economySchema,
  Item,
  rarities,
  rarityNames,
  rescueSchema,
  slotNames,
  walletSchema,
  weekSchema,
} from './contracts';
import { ActionError, dateLabel, FeaturePage, QueryState } from './page';

const drawNames = { NORMAL: '일반 뽑기', ADVANCED: '고급 뽑기', LEGENDARY: '전설 뽑기' };
function ItemDescription({ item }: { item: Item }) {
  return (
    <View style={{ gap: 6 }}>
      <Text style={ui.sectionTitle}>{item.name}</Text>
      <Text style={ui.caption}>
        {rarityNames[rarities.indexOf(item.rarity)]} · {slotNames[item.slot]}
      </Text>
      <Text style={ui.body}>
        체력 +{item.hpBonus} · 공격력 +{item.attackBonus} · 전투력 +{item.combatPower}
      </Text>
    </View>
  );
}
export function DrawsScreen() {
  return (
    <FeaturePage title="장비 뽑기">
      <Draws />
    </FeaturePage>
  );
}
function Draws() {
  const query = useFeatureQuery('/economy', economySchema);
  return (
    <>
      <Text style={ui.body}>
        퀘스트로 모은 게임 코인과 일반 뽑기권을 사용해요. 코인을 현금으로 구매하거나 교환할 수
        없어요.
      </Text>
      <QueryState pending={query.isPending} error={query.error} retry={query.refetch} />
      {query.data && <DrawPanel economy={query.data} />}
    </>
  );
}
export function DrawPanel({ economy }: { economy: Economy }) {
  const [choice, setChoice] = useState<{
    odds: Economy['draws'][number];
    payment: 'COINS' | 'TICKET';
  } | null>(null);
  const action = useFeatureAction(drawSchema);
  const [result, setResult] = useState<Draw | null>(null);
  const locked = action.isPending || !!result;
  async function confirm() {
    if (!choice || locked) return;
    const value = await action.run({
      path: '/draws',
      body: {
        type: choice.odds.type,
        probabilityVersionId: choice.odds.versionId,
        payment: choice.payment,
      },
    });
    if (value) setResult(value);
  }
  return (
    <>
      <View style={ui.note}>
        <Text style={ui.body}>
          사용 가능 {economy.availableCoins}C · 잠긴 코인 {economy.lockedCoins}C
        </Text>
        <Text style={ui.caption}>일반 뽑기권 {economy.normalTickets}장</Text>
      </View>
      {result ? (
        <View style={ui.card} accessibilityLiveRegion="polite">
          <Text style={ui.eyebrow}>뽑기 결과</Text>
          <ItemDescription item={result.item} />
          <Text style={ui.body}>
            {result.duplicate
              ? `이미 가진 장비예요. 조각 ${result.fragmentsGranted}개를 받았어요.`
              : '새 장비를 보관함에 넣었어요.'}
          </Text>
          <Text style={ui.caption}>
            {result.payment === 'TICKET' ? '일반 뽑기권 1장 사용' : `${result.cost}C 사용`}
            {result.pityApplied ? ' · 확정 획득 적용' : ''}
          </Text>
          <Button
            onPress={() => {
              action.acknowledge();
              setResult(null);
              setChoice(null);
            }}
          >
            결과 확인
          </Button>
          <Link href="/inventory" style={ui.link}>
            장비 보관함으로
          </Link>
        </View>
      ) : (
        <>
          {economy.draws.map((odds) => (
            <View key={odds.versionId} style={ui.card}>
              <Text accessibilityRole="header" style={ui.sectionTitle}>
                {drawNames[odds.type]} · {odds.cost}C
              </Text>
              <Text style={ui.body}>
                {odds.weights
                  .map(
                    (weight, index) =>
                      `${rarityNames[index]} ${(weight / 100).toLocaleString('ko-KR')}%`,
                  )
                  .join(' · ')}
              </Text>
              <Text style={ui.caption}>
                각 등급 안에서는 장비별 획득 확률이 같아요. 중복 장비는 해당 장비의 조각으로 쌓여요.
              </Text>
              <Text style={ui.caption}>
                {odds.pityThreshold
                  ? `${rarityNames[rarities.indexOf(odds.pityRarity!)]} 확정까지 ${Math.max(1, odds.pityThreshold - odds.misses)}회 · 이 종류의 뽑기에만 누적돼요.`
                  : '일반 뽑기는 확정 획득 횟수가 없어요.'}
              </Text>
              {economy.catalog
                .filter((item) => odds.itemIds.includes(item.id))
                .map((item) => {
                  const peers = economy.catalog.filter(
                    (value) => value.rarity === item.rarity && odds.itemIds.includes(value.id),
                  ).length;
                  return (
                    <Text key={item.id} style={ui.caption}>
                      {item.name} ·{' '}
                      {(odds.weights[rarities.indexOf(item.rarity)] / 100 / peers).toFixed(4)}% ·
                      중복 시 조각 {item.duplicateFragments}개
                    </Text>
                  );
                })}
              <Text style={ui.caption}>
                장비별 기본 확률은 소수점 다섯째 자리에서 반올림했어요. 확정 획득 회차에는 해당 등급
                안에서 같은 확률로 지급돼요.
              </Text>
              <Button
                secondary
                disabled={locked || !!choice || economy.availableCoins < odds.cost}
                onPress={() => setChoice({ odds, payment: 'COINS' })}
              >
                {drawNames[odds.type]} 선택
              </Button>
              {odds.type === 'NORMAL' && (
                <Button
                  secondary
                  disabled={locked || !!choice || economy.normalTickets < 1}
                  onPress={() => setChoice({ odds, payment: 'TICKET' })}
                >
                  일반 뽑기권 선택
                </Button>
              )}
            </View>
          ))}
          {choice && (
            <View style={ui.note}>
              <Text style={ui.sectionTitle}>{drawNames[choice.odds.type]}를 진행할까요?</Text>
              <Text style={ui.body}>
                {choice.payment === 'TICKET' ? '일반 뽑기권 1장' : `${choice.odds.cost}C`}이
                사용돼요. 확정한 뽑기는 되돌릴 수 없어요.
              </Text>
              <ActionError error={action.error} />
              <Button busy={action.isPending} onPress={() => void confirm()}>
                {action.isError ? '같은 뽑기 결과 다시 확인' : '사용하고 1회 뽑기'}
              </Button>
              <Button
                secondary
                disabled={action.isPending || action.isError}
                onPress={() => setChoice(null)}
              >
                선택 취소
              </Button>
              {action.isError && (
                <Text style={ui.caption}>
                  결과 확인 중 연결이 끊겼을 수 있어요. 같은 요청을 다시 확인해도 중복 차감되지
                  않아요.
                </Text>
              )}
            </View>
          )}
        </>
      )}
      <View style={ui.card}>
        <Text style={ui.sectionTitle}>최근 뽑기</Text>
        {economy.recentDraws.length === 0 && (
          <Text style={ui.caption}>아직 뽑기 기록이 없어요.</Text>
        )}
        {economy.recentDraws.map((draw) => (
          <Text key={draw.id} style={ui.caption}>
            {dateLabel(draw.createdAt)} · {draw.item.name} ·{' '}
            {draw.payment === 'TICKET' ? '뽑기권' : `${draw.cost}C`}
            {draw.duplicate ? ` · 조각 +${draw.fragmentsGranted}` : ''}
          </Text>
        ))}
      </View>
    </>
  );
}

export function InventoryScreen() {
  return (
    <FeaturePage title="장비 보관함">
      <Inventory />
    </FeaturePage>
  );
}
function Inventory() {
  const query = useFeatureQuery('/economy', economySchema);
  const action = useFeatureAction(economySchema);
  return (
    <>
      <Text style={ui.body}>
        무기·방어구·장신구를 하나씩 착용해요. 장비는 사용해도 사라지지 않아요.
      </Text>
      <QueryState pending={query.isPending} error={query.error} retry={query.refetch} />
      <ActionError error={action.error} />
      {query.data && (
        <>
          {query.data.equipment.map((slot) => (
            <View key={slot.slot} style={ui.note}>
              <Text style={ui.sectionTitle}>{slotNames[slot.slot]}</Text>
              <Text style={ui.body}>
                {query.data?.inventory.find((owned) => owned.item.id === slot.itemId)?.item.name ??
                  '착용하지 않음'}
              </Text>
              {slot.itemId && (
                <Button
                  secondary
                  busy={action.isPending}
                  onPress={() =>
                    void action.run({
                      path: '/equipment',
                      method: 'PUT',
                      body: { slot: slot.slot, itemId: null, rowVersion: slot.rowVersion },
                    })
                  }
                >
                  장착 해제
                </Button>
              )}
            </View>
          ))}
          {query.data.inventory.length === 0 && (
            <Text style={ui.body}>아직 보유한 장비가 없어요.</Text>
          )}
          {query.data.inventory.map(({ item, fragments }) => {
            const slot = query.data?.equipment.find((value) => value.slot === item.slot);
            const equipped = slot?.itemId === item.id;
            return (
              <View key={item.id} style={ui.card}>
                <ItemDescription item={item} />
                <Text style={ui.caption}>보유 조각 {fragments}개</Text>
                <Button
                  secondary
                  disabled={equipped || !slot}
                  busy={action.isPending}
                  onPress={() =>
                    void action.run({
                      path: '/equipment',
                      method: 'PUT',
                      body: { slot: item.slot, itemId: item.id, rowVersion: slot!.rowVersion },
                    })
                  }
                >
                  {equipped ? '장착 중' : '장착하기'}
                </Button>
              </View>
            );
          })}
        </>
      )}
      <Link href="/draws" style={ui.link}>
        장비 뽑기 →
      </Link>
    </>
  );
}

export function RescueScreen() {
  return (
    <FeaturePage title="다시 시작하는 작은 약속">
      <Rescue />
    </FeaturePage>
  );
}
function Rescue() {
  const query = useFeatureQuery('/rescue', rescueSchema);
  const action = useFeatureAction(rescueSchema);
  const [done, setDone] = useState(false);
  const value = query.data;
  const quest = value?.quest;
  const reasons: Record<string, string> = {
    BALANCE_SUFFICIENT: '사용 가능한 코인이 100C 이상이에요.',
    LOCKED_COINS: '진행 중인 약속에 잠긴 코인이 있어요.',
    COOLDOWN: '최근 이용 횟수를 모두 사용했어요. 다음 가능 시간을 확인해 주세요.',
  };
  return (
    <>
      <Text style={ui.body}>
        사용 가능한 코인이 100C 미만이고 잠긴 코인이 없을 때, 작은 약속을 마치고 100C를 받을 수
        있어요.
      </Text>
      <Text style={ui.caption}>
        최근 7일에 1회, 30일에 2회까지예요. 경험치·성장·연속 성공·케미에는 반영되지 않아요.
      </Text>
      <QueryState pending={query.isPending} error={query.error} retry={query.refetch} />
      <ActionError error={action.error} />
      {quest?.status === 'ACTIVE' ? (
        <View style={ui.card}>
          <Text style={ui.sectionTitle}>{quest.title}</Text>
          <Text style={ui.body}>완료 확인 가능: {dateLabel(quest.availableAt)}</Text>
          <Text style={ui.caption}>만료: {dateLabel(quest.expiresAt)}</Text>
          <Check checked={done} onPress={() => setDone(!done)} disabled={action.isPending}>
            1분 동안 화면에서 눈을 떼고 쉬었어요.
          </Check>
          <Button
            disabled={!done || Date.parse(value!.serverTime) < Date.parse(quest.availableAt)}
            busy={action.isPending}
            onPress={() =>
              void action.run({
                path: '/rescue/complete',
                body: { questId: quest.id, completed: true },
              })
            }
          >
            완료하고 100C 받기
          </Button>
          <Button secondary onPress={() => void query.refetch()}>
            가능 시간 다시 확인
          </Button>
        </View>
      ) : value?.eligible ? (
        <Button
          busy={action.isPending}
          onPress={() => {
            setDone(false);
            void action.run({ path: '/rescue/start' });
          }}
        >
          작은 약속 시작하기
        </Button>
      ) : (
        value && (
          <View style={ui.note}>
            <Text style={ui.body}>
              {quest?.status === 'COMPLETED'
                ? '작은 약속을 완료했어요. 100C를 받았어요.'
                : (reasons[value.reason ?? ''] ?? '지금은 시작 조건을 충족하지 않아요.')}
            </Text>
            {value.nextEligibleAt && (
              <Text style={ui.caption}>
                횟수 제한이 풀리는 시각: {dateLabel(value.nextEligibleAt)}
              </Text>
            )}
          </View>
        )
      )}
    </>
  );
}

export function CoopScreen() {
  return (
    <FeaturePage title="이번 주, 함께 도전" related>
      <Coop />
    </FeaturePage>
  );
}
function Coop() {
  const query = useFeatureQuery('/coop/week', weekSchema, true);
  const action = useFeatureAction(weekSchema, true);
  const [confirmed, setConfirmed] = useState(false);
  const boss = query.data?.boss;
  const labels = {
    LOCKED: '케미를 모으고 있어요',
    AVAILABLE: '함께 도전할 수 있어요',
    WAITING_PARTNER: '다른 한 사람을 기다려요',
    CLEARED: '보스를 물리쳤어요!',
    FAILED: '이번에는 힘이 부족했어요',
    EXPIRED: '이번 주 도전이 끝났어요',
    CANCELED_RELATIONSHIP_ENDED: '연결 종료로 취소되었어요',
  };
  return (
    <>
      <Text style={ui.body}>
        일반 퀘스트의 성공·실패 정산 10회를 함께 쌓으면 토요일부터 일요일까지 도전할 수 있어요.
      </Text>
      <QueryState pending={query.isPending} error={query.error} retry={query.refetch} />
      <ActionError error={action.error} />
      {query.data && !boss && (
        <Text style={ui.body}>
          서로 연결하고 두 사람 모두 배츄를 고르면 이번 주 보스가 준비돼요.
        </Text>
      )}
      {boss && (
        <View style={ui.card}>
          <Text style={ui.sectionTitle}>{labels[boss.status]}</Text>
          <Text style={ui.body}>
            케미 {Math.min(10, boss.chemistry)}/10 · 목표 전투력 {boss.requiredPower}
          </Text>
          <Text style={ui.caption}>
            도전 기간: {dateLabel(boss.opensAt)} ~ {dateLabel(boss.closesAt)}
          </Text>
          {[
            { label: '나', member: boss.self },
            { label: '파트너', member: boss.partner },
          ].map(({ label, member }) => (
            <View key={label} style={ui.note}>
              <Text style={ui.body}>
                {label} · {member.joined ? '참여 완료' : '참여 전'}
              </Text>
              <Text style={ui.caption}>
                현재 전투력 {member.currentPower} · 주간 변화 {member.weeklyGrowth >= 0 ? '+' : ''}
                {member.weeklyGrowth}
                {member.finalPower !== null ? ` · 판정 전투력 ${member.finalPower}` : ''}
              </Text>
            </View>
          ))}
          <Text style={ui.caption}>
            두 사람이 모두 참여한 순간의 장비 포함 전투력을 합쳐 판정해요. 성공하면 각자 일반 뽑기권
            1장을 받아요. 실패해도 코인은 차감되지 않아요.
          </Text>
          {!boss.self.joined && ['AVAILABLE', 'WAITING_PARTNER'].includes(boss.status) && (
            <>
              <Check
                checked={confirmed}
                onPress={() => setConfirmed(!confirmed)}
                disabled={action.isPending}
              >
                지금 장비로 이번 주 도전에 참여할게요.
              </Check>
              <Button
                busy={action.isPending}
                disabled={!confirmed}
                onPress={() =>
                  void action.run({
                    path: '/coop/join',
                    body: { bossId: boss.id, rowVersion: boss.rowVersion },
                  })
                }
              >
                참여 확정
              </Button>
            </>
          )}
          {boss.status === 'CLEARED' && (
            <Link href="/draws" style={ui.link}>
              받은 뽑기권 사용하기 →
            </Link>
          )}
        </View>
      )}
    </>
  );
}

export function WalletScreen() {
  return (
    <FeaturePage title="내 코인 기록">
      <Wallet />
    </FeaturePage>
  );
}
function Wallet() {
  const { user, session } = useAuth();
  const query = useInfiniteQuery({
    queryKey: ['account-features', user!.id, 'wallet-history'],
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) =>
      session.request(
        `/wallet/history${pageParam ? `?cursor=${encodeURIComponent(pageParam)}` : ''}`,
        walletSchema,
      ),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    retry: false,
  });
  const labels: Record<string, string> = {
    SIGNUP_GRANT: '가입 선물',
    DRAW: '장비 뽑기',
    RESCUE_REWARD: '구제 퀘스트',
    QUEST_APPROVAL: '약속 시작',
    QUEST_SETTLEMENT: '약속 정산',
    TUTORIAL_APPROVAL: '첫 약속 시작',
    TUTORIAL_SETTLEMENT: '첫 약속 정산',
  };
  return (
    <>
      <Text style={ui.caption}>사용 가능 코인과 약속에 잠긴 코인의 변화를 확인해요.</Text>
      <QueryState pending={query.isPending} error={query.error} retry={query.refetch} />
      {query.data?.pages
        .flatMap((page) => page.entries)
        .map((entry) => (
          <View key={entry.id} style={ui.card}>
            <Text style={ui.body}>{labels[entry.type] ?? '코인 변동'}</Text>
            <Text style={ui.body}>
              사용 가능 {entry.availableDelta >= 0 ? '+' : ''}
              {entry.availableDelta}C · 잠긴 코인 {entry.lockedDelta >= 0 ? '+' : ''}
              {entry.lockedDelta}C
            </Text>
            <Text style={ui.caption}>{dateLabel(entry.createdAt)}</Text>
          </View>
        ))}
      {query.data?.pages[0].entries.length === 0 && (
        <Text style={ui.body}>아직 코인 기록이 없어요.</Text>
      )}
      {query.hasNextPage && (
        <Button
          secondary
          busy={query.isFetchingNextPage}
          onPress={() => void query.fetchNextPage()}
        >
          이전 기록 더 보기
        </Button>
      )}
    </>
  );
}
