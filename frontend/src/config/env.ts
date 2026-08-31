import { z } from 'zod';

const publicEnvironmentSchema = z.object({
  appEnv: z.enum(['local', 'test', 'staging', 'production']),
  apiBaseUrl: z.url(),
});

const result = publicEnvironmentSchema.safeParse({
  appEnv: process.env.EXPO_PUBLIC_APP_ENV ?? 'local',
  apiBaseUrl: process.env.EXPO_PUBLIC_API_BASE_URL ?? 'http://localhost:8080/api/v1',
});

if (!result.success) {
  throw new Error(`Invalid public environment: ${result.error.message}`);
}

export const env = result.data;
