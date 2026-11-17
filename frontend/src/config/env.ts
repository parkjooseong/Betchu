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
  throw new Error('Invalid public environment. Check the app environment and API address.');
}

const api = new URL(result.data.apiBaseUrl);
if (
  api.username ||
  api.password ||
  api.search ||
  api.hash ||
  !['http:', 'https:'].includes(api.protocol) ||
  api.pathname !== '/api/v1'
)
  throw new Error(
    'Invalid API address. Use an HTTP(S) /api/v1 endpoint without credentials or query parameters.',
  );
if (
  ['staging', 'production'].includes(result.data.appEnv) &&
  (api.protocol !== 'https:' || ['localhost', '127.0.0.1', '10.0.2.2'].includes(api.hostname))
)
  throw new Error('Release builds require a public HTTPS API address.');

export const env = result.data;
