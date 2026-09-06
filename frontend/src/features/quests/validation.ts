import { hasUnpairedSurrogate } from '@/features/starter/name-validation';

import { Draft, DraftInput } from './contracts';

export type DraftForm = {
  title: string;
  category: DraftInput['category'];
  successCriteria: string;
  difficulty: DraftInput['difficulty'];
  stake: DraftInput['stake'];
  dueAt: string;
  resultAt: string;
  minimumDurationMinutes: string;
};
export const emptyDraft: DraftForm = {
  title: '',
  category: 'CUSTOM',
  successCriteria: '',
  difficulty: 1,
  stake: 0,
  dueAt: '',
  resultAt: '',
  minimumDurationMinutes: '0',
};
const trim = (value: string) =>
  value.normalize('NFC').replace(/^\p{White_Space}+|\p{White_Space}+$/gu, '');

export function parseSeoulDate(value: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})$/.exec(value.trim());
  if (!match) throw new Error('날짜는 서울 시간으로 YYYY-MM-DD HH:mm 형식으로 입력해 주세요.');
  const [year, month, day, hour, minute] = match.slice(1).map(Number);
  if (year < 2000 || year > 2100 || month < 1 || month > 12 || day < 1 || hour > 23 || minute > 59)
    throw new Error('2000~2100년 범위의 올바른 날짜와 시간을 입력해 주세요.');
  const local = new Date(Date.UTC(year, month - 1, day, hour, minute));
  if (
    local.getUTCFullYear() !== year ||
    local.getUTCMonth() !== month - 1 ||
    local.getUTCDate() !== day
  )
    throw new Error('달력에 있는 날짜를 입력해 주세요.');
  const result = `${match[1]}-${match[2]}-${match[3]}T${match[4]}:${match[5]}:00+09:00`;
  if (
    Date.parse(result) < Date.parse('2000-01-01T00:00:00Z') ||
    Date.parse(result) >= Date.parse('2101-01-01T00:00:00Z')
  )
    throw new Error('2000년 1월 1일 09:00 이후의 날짜를 입력해 주세요.');
  return result;
}
export function toSeoulInput(value: string) {
  const date = new Date(new Date(value).getTime() + 9 * 60 * 60 * 1000);
  return date.toISOString().slice(0, 16).replace('T', ' ');
}
export function formFromDraft(draft: Draft): DraftForm {
  return {
    ...draft,
    dueAt: toSeoulInput(draft.dueAt),
    resultAt: toSeoulInput(draft.resultAt),
    minimumDurationMinutes: String(draft.minimumDurationMinutes),
  };
}
export function validateDraft(form: DraftForm, saved?: Draft): DraftInput {
  const title = trim(form.title),
    successCriteria = trim(form.successCriteria);
  if (
    Array.from(title).length < 1 ||
    Array.from(title).length > 80 ||
    hasUnpairedSurrogate(title) ||
    /[\p{Cc}\p{Cf}\u2028\u2029]/u.test(title)
  )
    throw new Error('제목은 줄바꿈·제어 문자 없이 1~80자로 적어 주세요.');
  if (
    Array.from(successCriteria).length < 1 ||
    Array.from(successCriteria).length > 1000 ||
    hasUnpairedSurrogate(successCriteria) ||
    /[\p{Cc}\p{Cf}\u2028\u2029]/u.test(successCriteria.replace(/\n/g, ''))
  )
    throw new Error('성공 조건은 1~1,000자로 적어 주세요. 줄바꿈 외 제어 문자는 사용할 수 없어요.');
  if (!/^\d+$/.test(form.minimumDurationMinutes) || Number(form.minimumDurationMinutes) > 10080)
    throw new Error('최소 수행 시간은 0~10,080분의 정수로 입력해 주세요.');
  const dueAt =
      saved && form.dueAt === toSeoulInput(saved.dueAt) ? saved.dueAt : parseSeoulDate(form.dueAt),
    resultAt =
      saved && form.resultAt === toSeoulInput(saved.resultAt)
        ? saved.resultAt
        : parseSeoulDate(form.resultAt);
  if (Date.parse(resultAt) <= Date.parse(dueAt))
    throw new Error('결과 확인 시작은 수행 마감보다 뒤여야 해요.');
  return {
    title,
    category: form.category,
    successCriteria,
    difficulty: form.difficulty,
    stake: form.stake,
    dueAt,
    resultAt,
    minimumDurationMinutes: Number(form.minimumDurationMinutes),
    evidenceMethod: 'NONE',
  };
}
