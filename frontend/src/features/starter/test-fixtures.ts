import type { StarterCatalog, StarterPreview } from './api';

export const catalogFixture: StarterCatalog = {
  starters: [
    { species: 'STARLIGHT', displayName: '별빛', description: '밤하늘과 별빛을 닮은 배츄' },
    { species: 'WAVE', displayName: '파도', description: '말랑한 파도와 바다를 닮은 배츄' },
    { species: 'SUNSET', displayName: '노을', description: '따뜻한 노을과 땅을 닮은 배츄' },
    { species: 'FOREST', displayName: '숲', description: '포근한 나무와 숲을 닮은 배츄' },
  ],
  initialStats: { level: 1, hp: 100, atk: 10, power: 700, exp: 0, nextLevelRequiredExp: 80 },
  nameRules: { minLength: 1, maxLength: 10 },
  growthMilestones: [
    {
      type: 'HATCH',
      requiredSuccessCount: 1,
      description: '튜토리얼 성공 또는 활성 알의 첫 인정 성공으로 부화해요.',
    },
    { type: 'INTERMEDIATE', requiredSuccessCount: 20, description: '중간 성장' },
    { type: 'FINAL', requiredSuccessCount: 40, description: '최종 진화' },
    { type: 'MASTERY', requiredSuccessCount: 60, description: '오라·칭호·전투력 0 숙련 장신구' },
    {
      type: 'SUCCESS_80',
      requiredSuccessCount: 80,
      description: 'MVP 달성 기록. 새 알 선택은 후속 P2에서 제공해요.',
    },
  ],
};

export const previewFixture: StarterPreview = {
  previewOnly: true,
  starter: catalogFixture.starters[1],
  name: '말랑이',
  stage: 'EGG',
  recognizedSuccessCount: 0,
  stats: catalogFixture.initialStats,
  growthMilestones: catalogFixture.growthMilestones,
};
