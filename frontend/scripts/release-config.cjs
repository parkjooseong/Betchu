const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const identifier = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*){2,}$/;

function releaseConfig(config, environment) {
  const production =
    environment.EAS_BUILD_PROFILE === 'production' ||
    environment.EXPO_PUBLIC_APP_ENV === 'production';
  if (
    environment.EAS_BUILD_PROFILE === 'production' &&
    environment.EXPO_PUBLIC_APP_ENV !== 'production'
  )
    throw new Error('Production builds require EXPO_PUBLIC_APP_ENV=production.');
  const iosId = environment.BETCHU_IOS_BUNDLE_IDENTIFIER;
  const androidId = environment.BETCHU_ANDROID_PACKAGE;
  const projectId = environment.BETCHU_EAS_PROJECT_ID;
  if (production) {
    const errors = [];
    if (!identifier.test(iosId ?? '') || /^(com\.example|dev\.)/.test(iosId))
      errors.push('BETCHU_IOS_BUNDLE_IDENTIFIER');
    if (!identifier.test(androidId ?? '') || /^(com\.example|dev\.)/.test(androidId))
      errors.push('BETCHU_ANDROID_PACKAGE');
    if (!uuid.test(projectId ?? '')) errors.push('BETCHU_EAS_PROJECT_ID');
    try {
      const url = new URL(environment.EXPO_PUBLIC_API_BASE_URL);
      if (
        url.protocol !== 'https:' ||
        !url.hostname.includes('.') ||
        url.hostname === 'localhost' ||
        url.hostname.endsWith('.local') ||
        url.hostname.endsWith('.test') ||
        /^(\d+\.){3}\d+$/.test(url.hostname) ||
        url.username ||
        url.password ||
        url.search ||
        url.hash ||
        url.pathname !== '/api/v1'
      )
        throw new Error();
    } catch {
      errors.push('EXPO_PUBLIC_API_BASE_URL (public HTTPS /api/v1)');
    }
    if (errors.length)
      throw new Error(
        `Release configuration is incomplete: ${errors.join(', ')}. Values are never printed.`,
      );
  }
  return {
    ...config,
    ios: {
      ...config.ios,
      ...(iosId ? { bundleIdentifier: iosId } : {}),
      supportsTablet: false,
      infoPlist: { ...config.ios?.infoPlist, ITSAppUsesNonExemptEncryption: false },
    },
    android: {
      ...config.android,
      ...(androidId ? { package: androidId } : {}),
      allowBackup: false,
      blockedPermissions: [
        ...new Set([
          ...(config.android?.blockedPermissions ?? []),
          'android.permission.RECORD_AUDIO',
          'android.permission.CAMERA',
          'android.permission.READ_MEDIA_IMAGES',
          'android.permission.READ_MEDIA_VIDEO',
          'android.permission.READ_EXTERNAL_STORAGE',
          'android.permission.WRITE_EXTERNAL_STORAGE',
        ]),
      ],
    },
    extra: { ...config.extra, ...(projectId ? { eas: { projectId } } : {}) },
  };
}
module.exports = { releaseConfig };
