const assert = require('node:assert/strict');
const { test } = require('node:test');

const { releaseConfig } = require('./release-config.cjs');

const valid = {
  EAS_BUILD_PROFILE: 'production',
  EXPO_PUBLIC_APP_ENV: 'production',
  EXPO_PUBLIC_API_BASE_URL: 'https://api.betchu.app/api/v1',
  BETCHU_IOS_BUNDLE_IDENTIFIER: 'app.betchu.mobile',
  BETCHU_ANDROID_PACKAGE: 'app.betchu.mobile',
  BETCHU_EAS_PROJECT_ID: '36fa7155-28da-43b6-93d8-0d83500359e0',
};
test('production configuration requires deliberate identifiers, environment and HTTPS API', () => {
  assert.throws(() => releaseConfig({}, { EAS_BUILD_PROFILE: 'production' }), /APP_ENV/);
  for (const key of [
    'BETCHU_IOS_BUNDLE_IDENTIFIER',
    'BETCHU_ANDROID_PACKAGE',
    'BETCHU_EAS_PROJECT_ID',
    'EXPO_PUBLIC_API_BASE_URL',
  ]) {
    const input = { ...valid };
    delete input[key];
    assert.throws(() => releaseConfig({}, input), new RegExp(key));
  }
  for (const api of [
    'http://api.betchu.app/api/v1',
    'https://localhost/api/v1',
    'https://api.betchu.app/api/v1?token=secret',
    'https://user:secret@api.betchu.app/api/v1',
  ]) {
    assert.throws(
      () => releaseConfig({}, { ...valid, EXPO_PUBLIC_API_BASE_URL: api }),
      (error) =>
        error.message.includes('EXPO_PUBLIC_API_BASE_URL') && !error.message.includes('secret'),
    );
  }
});
test('production values are applied without publishing or embedding server secrets', () => {
  const config = releaseConfig(
    { name: 'BETCHU', plugins: ['expo-router'] },
    { ...valid, JWT_SIGNING_SECRET: 'not-public' },
  );
  assert.equal(config.ios.bundleIdentifier, valid.BETCHU_IOS_BUNDLE_IDENTIFIER);
  assert.equal(config.android.package, valid.BETCHU_ANDROID_PACKAGE);
  assert.equal(config.android.allowBackup, false);
  assert.equal(config.ios.infoPlist.ITSAppUsesNonExemptEncryption, false);
  assert.equal(JSON.stringify(config).includes('not-public'), false);
});
test('local development does not fabricate release identities', () => {
  const config = releaseConfig({ name: 'BETCHU' }, {});
  assert.equal(config.ios.bundleIdentifier, undefined);
  assert.equal(config.android.package, undefined);
  assert.equal(config.extra.eas, undefined);
});
