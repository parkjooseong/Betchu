import { normalizeStarterName, validateStarterName } from './name-validation';

const rules = { minLength: 1, maxLength: 10 };

describe('starter name validation', () => {
  it('normalizes decomposed Korean and Unicode surrounding whitespace', () => {
    expect(normalizeStarterName('\u0085\u3000배츄\u00A0')).toBe('배츄');
  });

  it.each(['', ' \u3000\n ', '\u0085'])('rejects an empty name: %j', (name) => {
    expect(validateStarterName(name, rules)).toBe('배츄를 부를 이름을 입력해 주세요.');
  });

  it.each(['배\n츄', '배\t츄', '배\u0000츄', '배\u200B츄', '\uFEFF배츄', '배\u2028츄'])(
    'rejects internal newlines and control or format characters: %j',
    (name) => {
      expect(validateStarterName(name, rules)).toContain('제어 문자');
    },
  );

  it('counts Unicode code points after normalization, not UTF-16 code units', () => {
    expect(validateStarterName('🥚'.repeat(10), rules)).toBeUndefined();
    expect(validateStarterName('🥚'.repeat(11), rules)).toContain('1~10자');
    expect(validateStarterName('배'.repeat(10), rules)).toBeUndefined();
  });

  it('preserves spaces within valid names', () => {
    expect(normalizeStarterName('  우리 배츄  ')).toBe('우리 배츄');
    expect(validateStarterName('우리 배츄', rules)).toBeUndefined();
  });
});
