import { env } from './env';

describe('public environment', () => {
  it('uses a valid API URL', () => {
    expect(new URL(env.apiBaseUrl).protocol).toMatch(/^https?:$/);
  });
});
