import { useQueryClient } from '@tanstack/react-query';
import Constants from 'expo-constants';
import * as Device from 'expo-device';
import { useRootNavigationState, useRouter } from 'expo-router';
import { useEffect } from 'react';
import { AppState, Platform } from 'react-native';

import { useAuth } from '@/features/onboarding/auth-provider';

import { preferencesSchema } from './contracts';

export function PushLifecycle() {
  const { user, session } = useAuth();
  const client = useQueryClient();
  const router = useRouter();
  const navigation = useRootNavigationState();
  const eligible = user?.status === 'ACTIVE' && !user.missingPolicyVersionIds.length;
  useEffect(() => {
    if (Platform.OS === 'web' || !Device.isDevice || !navigation?.key) return;
    let disposed = false;
    let working = false;
    const guard = session.captureSessionGuard();
    const statusKey = ['account-features', user?.id, 'push-sync'];
    const assertCurrent = () => {
      guard();
      if (disposed) throw new Error('Notification synchronization cancelled');
    };
    const reportFailure = () => {
      try {
        assertCurrent();
      } catch {
        return;
      }
      if (!disposed)
        client.setQueryData(
          statusKey,
          '기기 알림 상태를 갱신하지 못했어요. 기기 알림 설정에서 다시 시도해 주세요.',
        );
    };
    const subscriptions: { remove: () => void }[] = [];
    async function connect() {
      const notifications = await import('expo-notifications');
      if (disposed) return;
      notifications.setNotificationHandler({
        handleNotification: async () => ({
          shouldShowBanner: false,
          shouldShowList: false,
          shouldPlaySound: false,
          shouldSetBadge: false,
        }),
      });
      const openInbox = () => {
        if (disposed) return;
        notifications.clearLastNotificationResponse();
        router.push(eligible ? '/inbox' : '/');
      };
      subscriptions.push(notifications.addNotificationResponseReceivedListener(openInbox));
      subscriptions.push(
        notifications.addNotificationReceivedListener(() => {
          if (user) void client.invalidateQueries({ queryKey: ['relationship-features', user.id] });
        }),
      );
      if (notifications.getLastNotificationResponse()) openInbox();
      async function synchronize() {
        if (!eligible || disposed || working) return;
        working = true;
        try {
          assertCurrent();
          const preferences = await session.request('/notifications/device', preferencesSchema, {
            assertCurrent,
          });
          assertCurrent();
          if (!preferences.pushEnabled) {
            client.setQueryData(statusKey, null);
            return;
          }
          const permissions = await notifications.getPermissionsAsync();
          assertCurrent();
          if (!permissions.granted)
            await session.request('/notifications/device', preferencesSchema, {
              method: 'DELETE',
              assertCurrent,
            });
          else {
            const projectId: unknown =
              Constants.easConfig?.projectId ?? Constants.expoConfig?.extra?.eas?.projectId;
            if (typeof projectId !== 'string' || !projectId)
              throw new Error('Missing notification configuration');
            const token = await notifications.getExpoPushTokenAsync({ projectId });
            assertCurrent();
            await session.request('/notifications/device', preferencesSchema, {
              method: 'PUT',
              assertCurrent,
              body: { token: token.data, platform: Platform.OS === 'ios' ? 'IOS' : 'ANDROID' },
            });
          }
          client.setQueryData(statusKey, null);
        } catch {
          reportFailure();
        } finally {
          working = false;
        }
      }
      subscriptions.push(
        AppState.addEventListener('change', (state) => {
          if (state === 'active') void synchronize();
        }),
      );
      subscriptions.push(
        notifications.addPushTokenListener(() => {
          void synchronize();
        }),
      );
      await synchronize();
    }
    void connect().catch(reportFailure);
    return () => {
      disposed = true;
      subscriptions.forEach((subscription) => subscription.remove());
    };
  }, [client, eligible, navigation?.key, router, session, user]);
  return null;
}
