import { Link } from 'expo-router';
import { PropsWithChildren } from 'react';
import { ScrollView, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useRelationshipBoundary } from '@/features/game/relationship-cache';
import { useAuth } from '@/features/onboarding/auth-provider';
import { Button, ui } from '@/features/onboarding/ui';

export function FeaturePage({
  title,
  children,
  safety = false,
  related = false,
}: PropsWithChildren<{ title: string; safety?: boolean; related?: boolean }>) {
  const { user, restoring } = useAuth();
  const boundary = useRelationshipBoundary(user?.id ?? 'signed-out');
  const eligible =
    user && (safety || (user.status === 'ACTIVE' && !user.missingPolicyVersionIds.length));
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
          <Text accessibilityRole="header" style={ui.title}>
            {title}
          </Text>
          {restoring ? (
            <Text style={ui.body}>로그인 상태를 확인하고 있어요…</Text>
          ) : !eligible ? (
            <Text style={ui.body}>홈에서 로그인과 가입 조건을 확인해 주세요.</Text>
          ) : related && boundary.ending ? (
            <Text style={ui.body}>연결 정리가 끝난 뒤 다시 확인해 주세요.</Text>
          ) : (
            <View
              style={{ gap: 16 }}
              key={`${user.id}:${related ? boundary.generation : 'account'}`}
            >
              {children}
            </View>
          )}
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

export function QueryState({
  pending,
  error,
  retry,
}: {
  pending: boolean;
  error: Error | null;
  retry: () => unknown;
}) {
  if (pending) return <Text style={ui.body}>불러오고 있어요…</Text>;
  if (error)
    return (
      <View style={ui.card}>
        <ActionError error={error} />
        <Button
          secondary
          onPress={() => {
            void retry();
          }}
        >
          다시 불러오기
        </Button>
      </View>
    );
  return null;
}
export function ActionError({ error }: { error: Error | null }) {
  return error ? (
    <Text accessibilityRole="alert" style={ui.error}>
      {error.message}
    </Text>
  ) : null;
}
export function dateLabel(value: string) {
  return new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }) + ' (서울)';
}
