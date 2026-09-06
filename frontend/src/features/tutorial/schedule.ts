import { parseSeoulDate } from '@/features/quests/validation';

export function validateTutorialSchedule(due: string, result: string, serverNow: number) {
  const dueAt = parseSeoulDate(due),
    resultAt = parseSeoulDate(result);
  const dueTime = Date.parse(dueAt),
    resultTime = Date.parse(resultAt);
  if (dueTime <= serverNow) throw new Error('수행 마감은 현재보다 뒤로 정해 주세요.');
  if (resultTime <= dueTime) throw new Error('결과 확인 시작은 수행 마감보다 뒤여야 해요.');
  if (resultTime > serverNow + 7 * 24 * 60 * 60 * 1000)
    throw new Error('수행 마감과 결과 확인 시작을 지금부터 7일 안으로 정해 주세요.');
  return { dueAt, resultAt };
}
