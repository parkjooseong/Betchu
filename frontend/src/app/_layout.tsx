import { ErrorBoundaryProps, Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { Text, View } from 'react-native';

import { PushLifecycle } from '@/features/gameplay/push-lifecycle';
import { Button, ui } from '@/features/onboarding/ui';
import { AppProvider } from '@/providers/app-provider';

export default function RootLayout() {
  return (
    <AppProvider>
      <StatusBar style="dark" />
      <Stack screenOptions={{ headerShown: false }} />
      <PushLifecycle />
    </AppProvider>
  );
}

export function ErrorBoundary({ retry }: ErrorBoundaryProps) {
  return (
    <View style={[ui.root, { justifyContent: 'center', padding: 24, gap: 16 }]}>
      <Text accessibilityRole="header" style={ui.title}>
        화면을 다시 열어 주세요
      </Text>
      <Text style={ui.body}>
        화면을 표시하지 못했어요. 처리 중이던 약속이나 코인 기록은 다시 연결한 뒤 확인할 수 있어요.
      </Text>
      <Button onPress={() => void retry()}>다시 시도</Button>
    </View>
  );
}
