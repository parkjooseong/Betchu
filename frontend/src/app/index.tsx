import { StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { env } from '@/config/env';
import { colors, radius, spacing, typography } from '@/theme/tokens';

const setupItems = [
  'Expo SDK 57 · React Native 0.86',
  'TanStack Query · Zustand · React Hook Form · Zod',
  'OpenAPI fetch client · SecureStore · Notifications',
];

export default function HomeScreen() {
  return (
    <SafeAreaView style={styles.safeArea}>
      <View style={styles.container}>
        <View style={styles.brandMark} accessibilityElementsHidden>
          <Text style={styles.brandEmoji}>🥚</Text>
        </View>

        <Text accessibilityRole="header" style={styles.title}>
          BETCHU
        </Text>
        <Text style={styles.subtitle}>우리 둘의 베팅 몬스터</Text>

        <View style={styles.card}>
          <Text style={styles.cardTitle}>개발 환경 준비 완료</Text>
          {setupItems.map((item) => (
            <View key={item} style={styles.setupRow}>
              <Text style={styles.check}>✓</Text>
              <Text style={styles.setupText}>{item}</Text>
            </View>
          ))}
        </View>

        <View style={styles.environmentCard}>
          <Text style={styles.environmentLabel}>APP ENV</Text>
          <Text style={styles.environmentValue}>{env.appEnv}</Text>
          <Text style={styles.environmentLabel}>API</Text>
          <Text selectable style={styles.apiValue}>
            {env.apiBaseUrl}
          </Text>
        </View>

        <Text style={styles.helpText}>
          다음 구현 순서는 로그인 → 커플 연결 → 스타팅 배츄입니다.
        </Text>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: colors.background,
  },
  container: {
    flex: 1,
    justifyContent: 'center',
    padding: spacing.xl,
    gap: spacing.md,
  },
  brandMark: {
    alignItems: 'center',
    justifyContent: 'center',
    alignSelf: 'center',
    width: 104,
    height: 104,
    borderRadius: 52,
    backgroundColor: colors.brandSoft,
  },
  brandEmoji: {
    fontSize: 56,
  },
  title: {
    marginTop: spacing.sm,
    color: colors.text,
    fontSize: typography.display.fontSize,
    lineHeight: typography.display.lineHeight,
    fontWeight: '800',
    textAlign: 'center',
    letterSpacing: 1.5,
  },
  subtitle: {
    color: colors.textSecondary,
    fontSize: typography.body.fontSize,
    lineHeight: typography.body.lineHeight,
    textAlign: 'center',
  },
  card: {
    marginTop: spacing.lg,
    padding: spacing.lg,
    gap: spacing.md,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.lg,
    backgroundColor: colors.surface,
  },
  cardTitle: {
    color: colors.text,
    fontSize: typography.title.fontSize,
    lineHeight: typography.title.lineHeight,
    fontWeight: '700',
  },
  setupRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: spacing.sm,
  },
  check: {
    color: colors.success,
    fontSize: typography.body.fontSize,
    fontWeight: '800',
  },
  setupText: {
    flex: 1,
    color: colors.text,
    fontSize: typography.caption.fontSize,
    lineHeight: typography.caption.lineHeight,
  },
  environmentCard: {
    padding: spacing.md,
    gap: spacing.xs,
    borderRadius: radius.md,
    backgroundColor: colors.brandSoft,
  },
  environmentLabel: {
    color: colors.brandStrong,
    fontSize: 11,
    fontWeight: '800',
    letterSpacing: 1,
  },
  environmentValue: {
    marginBottom: spacing.sm,
    color: colors.text,
    fontSize: typography.caption.fontSize,
    lineHeight: typography.caption.lineHeight,
  },
  apiValue: {
    color: colors.text,
    fontSize: 12,
    lineHeight: 18,
  },
  helpText: {
    color: colors.textSecondary,
    fontSize: typography.caption.fontSize,
    lineHeight: typography.caption.lineHeight,
    textAlign: 'center',
  },
});
