import { draftFixture } from '@/features/game/test-fixtures';

import { formFromDraft, parseSeoulDate, validateDraft } from './validation';

it('normalizes Unicode names and criteria while preserving internal LF', () => {
  const form = {
    ...formFromDraft(draftFixture),
    title: '\u0085가\u00a0',
    successCriteria: '\u00a0첫 줄\n둘째 줄\u0085',
  };
  const result = validateDraft(form);
  expect(result.title).toBe('가');
  expect(result.successCriteria).toBe('첫 줄\n둘째 줄');
});
it('counts codepoints and rejects hidden controls and non-LF newlines', () => {
  const form = formFromDraft(draftFixture);
  expect(() => validateDraft({ ...form, title: '😀'.repeat(80) })).not.toThrow();
  expect(() => validateDraft({ ...form, title: '😀'.repeat(81) })).toThrow();
  for (const value of ['a\nb', 'a\u200Db', 'a\u2028b', 'a\uD800b'])
    expect(() => validateDraft({ ...form, title: value })).toThrow();
  for (const value of ['a\rb', 'a\u200Db', 'a\u2029b', 'a\uDC00b'])
    expect(() => validateDraft({ ...form, successCriteria: value })).toThrow();
});
it('validates real Seoul calendar instants and UTC lower boundary', () => {
  expect(() => parseSeoulDate('2026-02-30 22:00')).toThrow();
  expect(() => parseSeoulDate('2000-01-01 08:59')).toThrow();
  expect(parseSeoulDate('2000-01-01 09:00')).toBe('2000-01-01T09:00:00+09:00');
  expect(parseSeoulDate('2100-12-31 23:59')).toBe('2100-12-31T23:59:00+09:00');
});
it('allows past drafts, requires result after due date, and preserves untouched sub-minute precision', () => {
  const saved = {
    ...draftFixture,
    dueAt: '2020-01-01T00:00:12.345Z',
    resultAt: '2020-01-01T00:00:13.456Z',
  };
  const form = formFromDraft(saved);
  expect(validateDraft({ ...form, title: '다른 제목' }, saved).dueAt).toBe(saved.dueAt);
  expect(validateDraft({ ...form, title: '다른 제목' }, saved).resultAt).toBe(saved.resultAt);
  expect(() => validateDraft({ ...form, resultAt: '2020-01-01 09:00' })).toThrow();
  expect(() => validateDraft({ ...form, minimumDurationMinutes: '10081' }, saved)).toThrow();
});
