import { overviewSchema, tutorialSchema } from './contracts';
import { creatorFixture, overviewFixture, partnerFixture } from './test-fixtures';

it('rejects private result selections in creator responses, including null placeholders', () => {
  expect(tutorialSchema.safeParse(creatorFixture).success).toBe(true);
  expect(tutorialSchema.safeParse({ ...creatorFixture, partnerSelection: null }).success).toBe(
    false,
  );
  expect(
    tutorialSchema.safeParse({
      ...creatorFixture,
      partnerSelection: {
        selectedResult: 'SUCCESS',
        selectionRevision: 1,
        selectedAt: '2026-09-09T00:00:00Z',
      },
    }).success,
  ).toBe(false);
  expect(tutorialSchema.safeParse(partnerFixture).success).toBe(true);
  const { partnerSelection: _selection, ...beforeSelection } = partnerFixture;
  expect(tutorialSchema.safeParse(beforeSelection).success).toBe(true);
});
it('rejects swapped overview roles and undocumented wallet fields', () => {
  expect(overviewSchema.safeParse({ ...overviewFixture, own: partnerFixture }).success).toBe(false);
  expect(overviewSchema.safeParse({ ...overviewFixture, partner: creatorFixture }).success).toBe(
    false,
  );
  expect(tutorialSchema.safeParse({ ...partnerFixture, availableCoins: 900 }).success).toBe(false);
});
