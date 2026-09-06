export type NameRules = { minLength: number; maxLength: number };

export function normalizeStarterName(value: string): string {
  return value.normalize('NFC').replace(/^\p{White_Space}+|\p{White_Space}+$/gu, '');
}

export function validateStarterName(value: string, rules: NameRules): string | undefined {
  const name = normalizeStarterName(value);
  const length = Array.from(name).length;

  if (length < rules.minLength) {
    return '배츄를 부를 이름을 입력해 주세요.';
  }
  if (/[\p{Cc}\p{Cf}\u2028\u2029]/u.test(name)) {
    return '이름에는 줄바꿈이나 보이지 않는 제어 문자를 사용할 수 없어요.';
  }
  if (length > rules.maxLength) {
    return `이름은 ${rules.minLength}~${rules.maxLength}자로 입력해 주세요.`;
  }
  return undefined;
}
