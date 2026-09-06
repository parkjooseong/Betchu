import { z } from 'zod';

import { apiClient } from '@/api/client';
import type { components } from '@/api/generated/schema';

export type StarterCatalog = components['schemas']['StarterCatalog'];
export type StarterPreview = components['schemas']['StarterPreview'];
export type StarterSelection = components['schemas']['StarterPreviewRequest'];
export type StarterSpecies = components['schemas']['StarterSpecies'];

const starterSchema = z.object({
  species: z.enum(['STARLIGHT', 'WAVE', 'SUNSET', 'FOREST']),
  displayName: z.string().min(1),
  description: z.string().min(1),
});
const statsSchema = z.object({
  level: z.literal(1),
  hp: z.literal(100),
  atk: z.literal(10),
  power: z.literal(700),
  exp: z.literal(0),
  nextLevelRequiredExp: z.literal(80),
});
const milestonesSchema = z
  .array(
    z.object({
      type: z.enum(['HATCH', 'INTERMEDIATE', 'FINAL', 'MASTERY', 'SUCCESS_80']),
      requiredSuccessCount: z.literal([1, 20, 40, 60, 80]),
      description: z.string().min(1),
    }),
  )
  .length(5)
  .refine((items) => new Set(items.map((item) => item.type)).size === 5);
const catalogSchema = z.object({
  starters: z
    .array(starterSchema)
    .length(4)
    .refine((items) => new Set(items.map((item) => item.species)).size === 4),
  initialStats: statsSchema,
  nameRules: z.object({ minLength: z.literal(1), maxLength: z.literal(10) }),
  growthMilestones: milestonesSchema,
});
const previewSchema = z.object({
  previewOnly: z.literal(true),
  starter: starterSchema,
  name: z.string().min(1),
  stage: z.literal('EGG'),
  recognizedSuccessCount: z.literal(0),
  stats: statsSchema,
  growthMilestones: milestonesSchema,
});

const invalidResponseMessage = '배츄 정보를 확인하지 못했어요. 잠시 후 다시 시도해 주세요.';

async function withTimeout<T>(request: (signal: AbortSignal) => Promise<T>): Promise<T> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 10_000);
  try {
    return await request(controller.signal);
  } catch (error) {
    if (error instanceof SyntaxError) {
      throw new Error(invalidResponseMessage);
    }
    if (error instanceof Error && error.name === 'AbortError') {
      throw new Error('응답이 늦어지고 있어요. 연결을 확인한 뒤 다시 시도해 주세요.');
    }
    if (error instanceof TypeError) {
      throw new Error('서버에 연결할 수 없어요. 연결을 확인한 뒤 다시 시도해 주세요.');
    }
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

export async function getStarterCatalog(): Promise<StarterCatalog> {
  return withTimeout(async (signal) => {
    const { data, response } = await apiClient.GET('/monsters/starters', { signal });
    if (!response.ok) throw new Error('배츄 목록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.');
    const parsed = catalogSchema.safeParse(data);
    if (!parsed.success) throw new Error(invalidResponseMessage);
    return parsed.data;
  });
}

export async function getStarterPreview(selection: StarterSelection): Promise<StarterPreview> {
  return withTimeout(async (signal) => {
    const { data, error, response } = await apiClient.POST('/monsters/starter-preview', {
      body: selection,
      signal,
    });
    if (!response.ok) {
      if (response.status === 400 && error?.errorCode === 'INVALID_STARTER_SELECTION') {
        throw new Error(error.detail || '종류와 이름을 확인한 뒤 다시 시도해 주세요.');
      }
      throw new Error('미리보기를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.');
    }
    const parsed = previewSchema.safeParse(data);
    if (!parsed.success) throw new Error(invalidResponseMessage);
    return parsed.data;
  });
}
