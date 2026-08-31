/**
 * 디자이너의 최종 토큰 전달 전 사용하는 개발용 임시 값입니다.
 * 기능 코드에서는 직접 색상·간격 값을 만들지 말고 이 파일을 참조합니다.
 */
export const colors = {
  background: '#FAFBF8',
  surface: '#FFFFFF',
  text: '#1C251E',
  textSecondary: '#56635A',
  border: '#D9E1DA',
  brandSoft: '#E8F1E8',
  brandStrong: '#3F6548',
  success: '#2C7040',
  warning: '#8A5B00',
  danger: '#A33A3A',
} as const;

export const spacing = {
  xs: 4,
  sm: 8,
  md: 16,
  lg: 24,
  xl: 32,
} as const;

export const radius = {
  sm: 8,
  md: 12,
  lg: 20,
  pill: 999,
} as const;

export const typography = {
  display: { fontSize: 36, lineHeight: 44 },
  title: { fontSize: 20, lineHeight: 28 },
  body: { fontSize: 16, lineHeight: 24 },
  caption: { fontSize: 14, lineHeight: 20 },
} as const;
