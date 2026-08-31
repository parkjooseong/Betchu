import createClient from 'openapi-fetch';

import type { paths } from '@/api/generated/schema';
import { env } from '@/config/env';

export const apiClient = createClient<paths>({
  baseUrl: env.apiBaseUrl,
  headers: {
    Accept: 'application/json',
  },
});
