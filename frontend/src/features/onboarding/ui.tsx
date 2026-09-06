import { PropsWithChildren } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';

import { colors, radius, spacing, typography } from '@/theme/tokens';

export function Button({
  children,
  onPress,
  disabled = false,
  busy = false,
  secondary = false,
}: PropsWithChildren<{
  onPress: () => void;
  disabled?: boolean;
  busy?: boolean;
  secondary?: boolean;
}>) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled: disabled || busy, busy }}
      aria-disabled={disabled || busy}
      aria-busy={busy}
      disabled={disabled || busy}
      onPress={onPress}
      style={({ pressed }) => [
        ui.button,
        secondary && ui.secondary,
        (disabled || busy) && ui.disabled,
        pressed && ui.pressed,
      ]}
    >
      {busy && <ActivityIndicator color={secondary ? colors.brandStrong : colors.surface} />}
      <Text style={[ui.buttonText, secondary && ui.secondaryText]}>{children}</Text>
    </Pressable>
  );
}

export function Check({
  checked,
  onPress,
  children,
  disabled = false,
}: PropsWithChildren<{ checked: boolean; onPress: () => void; disabled?: boolean }>) {
  return (
    <Pressable
      accessibilityRole="checkbox"
      aria-checked={checked}
      aria-disabled={disabled}
      accessibilityState={{ checked, disabled }}
      onPress={onPress}
      disabled={disabled}
      style={ui.check}
    >
      <View style={[ui.checkMark, checked && ui.checkedMark]}>
        <Text style={ui.checkSymbol}>{checked ? '✓' : ''}</Text>
      </View>
      <Text style={ui.checkText}>{children}</Text>
    </Pressable>
  );
}

export const ui = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.background },
  scroll: { flexGrow: 1, padding: spacing.md },
  page: {
    width: '100%',
    maxWidth: 760,
    alignSelf: 'center',
    gap: spacing.lg,
    paddingBottom: spacing.xxl,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.md,
    paddingVertical: spacing.md,
    flexWrap: 'wrap',
  },
  brand: { color: colors.brandStrong, fontSize: 24, fontWeight: '900', letterSpacing: 1 },
  eyebrow: {
    color: colors.brandStrong,
    fontSize: 12,
    lineHeight: 20,
    fontWeight: '800',
    letterSpacing: 1,
  },
  hero: {
    backgroundColor: colors.hero,
    padding: spacing.lg,
    gap: spacing.md,
    borderRadius: radius.lg,
  },
  title: { color: colors.text, fontSize: 32, lineHeight: 42, fontWeight: '800' },
  sectionTitle: { color: colors.text, ...typography.title, fontWeight: '800' },
  body: { color: colors.text, ...typography.body },
  caption: { color: colors.textSecondary, ...typography.caption },
  card: {
    padding: spacing.lg,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.lg,
    gap: spacing.md,
  },
  note: {
    padding: spacing.md,
    backgroundColor: colors.brandSoft,
    borderRadius: radius.md,
    gap: spacing.sm,
  },
  button: {
    minHeight: 52,
    padding: spacing.md,
    gap: spacing.sm,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.brandStrong,
    borderRadius: radius.md,
    flexDirection: 'row',
    flexWrap: 'wrap',
  },
  secondary: { backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.brandStrong },
  buttonText: {
    color: colors.surface,
    ...typography.body,
    fontWeight: '700',
    textAlign: 'center',
    flexShrink: 1,
  },
  secondaryText: { color: colors.brandStrong },
  disabled: { opacity: 0.65 },
  pressed: { opacity: 0.8 },
  link: {
    color: colors.brandStrong,
    ...typography.caption,
    fontWeight: '700',
    paddingVertical: spacing.md,
    minHeight: 48,
    textDecorationLine: 'underline',
  },
  error: { color: colors.danger, ...typography.caption },
  check: {
    minHeight: 52,
    flexDirection: 'row',
    gap: spacing.md,
    alignItems: 'center',
    paddingVertical: spacing.sm,
  },
  checkMark: {
    width: 26,
    height: 26,
    borderWidth: 1,
    borderColor: colors.brandStrong,
    borderRadius: 6,
    alignItems: 'center',
    justifyContent: 'center',
  },
  checkedMark: { backgroundColor: colors.brandStrong },
  checkSymbol: { color: colors.surface, fontSize: 18, fontWeight: '700' },
  checkText: { flex: 1, color: colors.text, ...typography.body },
  input: {
    minHeight: 56,
    padding: spacing.md,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
    backgroundColor: colors.background,
    color: colors.text,
    ...typography.body,
  },
  row: { flexDirection: 'row', gap: spacing.md, flexWrap: 'wrap', alignItems: 'center' },
  divider: { height: 1, backgroundColor: colors.border },
  code: {
    color: colors.brandStrong,
    fontSize: 30,
    lineHeight: 42,
    fontWeight: '800',
    letterSpacing: 2,
  },
  avatar: {
    minWidth: 48,
    minHeight: 48,
    borderRadius: radius.pill,
    backgroundColor: colors.brandSoft,
    alignItems: 'center',
    justifyContent: 'center',
    padding: spacing.sm,
  },
  avatarText: { color: colors.brandStrong, fontSize: 22, fontWeight: '700' },
  profileCopy: { flex: 1, minWidth: 140, gap: spacing.xs },
});
