import { validateTutorialSchedule } from './schedule';

const now = Date.parse('2026-09-07T00:00:00Z');
it('uses explicit Seoul offsets and accepts the seven-day boundary', () => {
  expect(validateTutorialSchedule('2026-09-07 10:00', '2026-09-14 09:00', now)).toEqual({
    dueAt: '2026-09-07T10:00:00+09:00',
    resultAt: '2026-09-14T09:00:00+09:00',
  });
});
it('rejects an elapsed approval deadline, equal result time, and dates beyond seven days', () => {
  expect(() => validateTutorialSchedule('2026-09-07 09:00', '2026-09-07 10:00', now)).toThrow(
    '현재보다 뒤',
  );
  expect(() => validateTutorialSchedule('2026-09-07 10:00', '2026-09-07 10:00', now)).toThrow(
    '수행 마감보다 뒤',
  );
  expect(() => validateTutorialSchedule('2026-09-07 10:00', '2026-09-14 09:01', now)).toThrow(
    '7일',
  );
});
