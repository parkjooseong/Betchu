import { useQueryClient } from '@tanstack/react-query';
import { Link } from 'expo-router';
import { useEffect } from 'react';
import { Text, View } from 'react-native';

import { useAuth } from '@/features/onboarding/auth-provider';
import { ui } from '@/features/onboarding/ui';

import { useQuestQuery } from './api';
import { pageSchema, summarySchema } from './contracts';
export function QuestHomeCard({ coupleId }: { coupleId: string }) {
  const { user } = useAuth();
  const client = useQueryClient();
  const pending = useQuestQuery(coupleId, '/actions/pending', pageSchema);
  const summary = useQuestQuery(coupleId, '/quests/summary', summarySchema);
  const stamp = pending.data?.quests.map((q) => `${q.id}:${q.rowVersion}`).join('|');
  useEffect(() => {
    if (stamp !== undefined) void client.invalidateQueries({ queryKey: ['home', user!.id] });
  }, [stamp, client, user]);
  return (
    <View style={ui.card}>
      <Text style={ui.sectionTitle}>우리의 퀘스트</Text>
      {pending.error ? (
        <Text accessibilityRole="alert" style={ui.error}>
          {pending.error.message}
        </Text>
      ) : (
        <Text style={ui.body}>
          {pending.data
            ? `지금 확인할 퀘스트 ${pending.data.quests.length}개`
            : '요청을 확인하고 있어요…'}
        </Text>
      )}
      {summary.data && (
        <Text style={ui.caption}>
          이번 주 함께 확정한 결과 {summary.data.current} / {summary.data.target} ·{' '}
          {summary.data.unlocked ? '주간 케미 조건 달성' : '서울 시간 월요일에 새로 시작해요'}
        </Text>
      )}
      <Link href="/quest" style={ui.link}>
        제출·진행·결과 모아보기 →
      </Link>
    </View>
  );
}
