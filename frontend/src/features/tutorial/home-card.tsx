import { Link } from 'expo-router';
import { Text, View } from 'react-native';

import { Button, ui } from '@/features/onboarding/ui';

import { useTutorialOverview } from './api';
import { statusLabels } from './contracts';

export function TutorialHomeCard({ coupleId }: { coupleId: string }) {
  const query = useTutorialOverview(coupleId);
  return (
    <View style={ui.card}>
      <Text style={ui.eyebrow}>함께 배우는 첫 약속</Text>
      {query.isPending ? (
        <Text style={ui.body}>첫 약속 상태를 불러오는 중…</Text>
      ) : query.error ? (
        <>
          <Text accessibilityRole="alert" style={ui.error}>
            {query.error.message}
          </Text>
          <Button secondary onPress={() => void query.refetch()}>
            첫 약속 상태 다시 확인
          </Button>
        </>
      ) : (
        <>
          <Text accessibilityRole="header" style={ui.sectionTitle}>
            {query.data.own
              ? statusLabels[query.data.own.status]
              : query.data.completedAt
                ? '나의 첫 약속 완료'
                : '물 한 잔으로 시작해요'}
          </Text>
          {query.data.partner && (
            <Text style={ui.body}>
              파트너의 첫 약속 · {statusLabels[query.data.partner.status]}
            </Text>
          )}
          {query.data.partner?.allowedActions.length ? (
            <Text style={ui.caption}>지금 내가 확인할 요청이 있어요.</Text>
          ) : null}
          <Link href="/tutorial" style={ui.link}>
            우리의 첫 약속 보기 →
          </Link>
        </>
      )}
    </View>
  );
}
