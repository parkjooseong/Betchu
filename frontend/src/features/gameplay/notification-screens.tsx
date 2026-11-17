import { useQuery, useQueryClient } from '@tanstack/react-query';
import Constants from 'expo-constants';
import * as Device from 'expo-device';
import { Link } from 'expo-router';
import { useState } from 'react';
import { Linking, Platform, Text, View } from 'react-native';

import { useAuth } from '@/features/onboarding/auth-provider';
import { Button, ui } from '@/features/onboarding/ui';

import { useFeatureAction, useFeatureQuery } from './api';
import { inboxSchema, preferencesSchema } from './contracts';
import { ActionError, dateLabel, FeaturePage, QueryState } from './page';

export function noticeDestination(route: string) {
  if (['/', '/tutorial', '/coop'].includes(route)) return route as '/' | '/tutorial' | '/coop';
  const quest =
    /^\/quest\?questId=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i.exec(
      route,
    );
  return quest ? { pathname: '/quest' as const, params: { questId: quest[1] } } : '/';
}

export function InboxScreen() {
  return (
    <FeaturePage title="내 알림" related>
      <Inbox />
    </FeaturePage>
  );
}
function Inbox() {
  const query = useFeatureQuery('/notifications', inboxSchema, true);
  const action = useFeatureAction(inboxSchema, true);
  return (
    <>
      <Text style={ui.body}>현재 확인할 수 있는 약속과 연결 알림이에요.</Text>
      <Link href="/settings" style={ui.link}>
        기기 알림 설정
      </Link>
      <QueryState pending={query.isPending} error={query.error} retry={query.refetch} />
      <ActionError error={action.error} />
      {query.data && (
        <>
          <Text style={ui.caption}>읽지 않은 알림 {query.data.unreadCount}개</Text>
          {query.data.notifications.length === 0 && (
            <Text style={ui.body}>새로운 알림이 없어요.</Text>
          )}
          {query.data.notifications.some((notice) => !notice.read) && (
            <Button
              secondary
              busy={action.isPending}
              onPress={() =>
                void action.run({
                  path: '/notifications/read',
                  body: {
                    ids: query
                      .data!.notifications.filter((notice) => !notice.read)
                      .map((notice) => notice.id),
                  },
                })
              }
            >
              표시된 알림 모두 읽음
            </Button>
          )}
          {query.data.notifications.map((notice) => (
            <View key={notice.id} style={ui.card}>
              <Text style={ui.eyebrow}>{notice.read ? '읽음' : '새 알림'}</Text>
              <Text style={ui.sectionTitle}>{notice.title}</Text>
              <Text style={ui.caption}>{dateLabel(notice.createdAt)}</Text>
              <Link href={noticeDestination(notice.route)} style={ui.link}>
                내용 확인
              </Link>
              {!notice.read && (
                <Button
                  secondary
                  busy={action.isPending}
                  onPress={() =>
                    void action.run({ path: '/notifications/read', body: { ids: [notice.id] } })
                  }
                >
                  읽음 표시
                </Button>
              )}
            </View>
          ))}
        </>
      )}
    </>
  );
}

export function PushSettings() {
  const { session, user } = useAuth();
  const client = useQueryClient();
  const statusKey = ['account-features', user!.id, 'push-sync'];
  const syncStatus = useQuery<string | null>({
    queryKey: statusKey,
    queryFn: () => null,
    enabled: false,
  });
  const query = useFeatureQuery('/notifications/device', preferencesSchema);
  const action = useFeatureAction(preferencesSchema);
  const [registering, setRegistering] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  async function enable() {
    if (registering) return;
    setRegistering(true);
    setError(null);
    const assertCurrent = session.captureSessionGuard();
    try {
      if (Platform.OS === 'web' || !Device.isDevice)
        throw new Error(
          '기기 알림은 Android·iOS 실제 기기의 설치된 앱에서 켤 수 있어요. 앱 안의 알림 목록은 계속 사용할 수 있어요.',
        );
      const projectId: unknown =
        Constants.easConfig?.projectId ?? Constants.expoConfig?.extra?.eas?.projectId;
      if (typeof projectId !== 'string' || !projectId)
        throw new Error(
          '이 설치본에서는 기기 알림을 준비 중이에요. 앱 안의 알림 목록을 이용해 주세요.',
        );
      const notifications = await import('expo-notifications');
      if (Platform.OS === 'android')
        await notifications.setNotificationChannelAsync('default', {
          name: '약속 알림',
          importance: notifications.AndroidImportance.DEFAULT,
        });
      const permission = await notifications.requestPermissionsAsync({
        ios: { allowAlert: true, allowBadge: true, allowSound: true },
      });
      if (!permission.granted)
        throw new Error('기기 알림이 허용되지 않았어요. 원하면 기기 설정에서 알림을 켜 주세요.');
      const token = await notifications.getExpoPushTokenAsync({ projectId });
      assertCurrent();
      await session.request('/notifications/device', preferencesSchema, {
        method: 'PUT',
        assertCurrent,
        body: { token: token.data, platform: Platform.OS === 'ios' ? 'IOS' : 'ANDROID' },
      });
      client.setQueryData(statusKey, null);
      await query.refetch();
    } catch (reason) {
      // Provider errors can include device tokens; never expose their text.
      const message =
        reason instanceof Error && /^(기기 알림|이 설치본)/.test(reason.message)
          ? reason.message
          : '기기 알림을 등록하지 못했어요. 연결을 확인하고 다시 시도해 주세요.';
      setError(new Error(message));
    } finally {
      setRegistering(false);
    }
  }
  return (
    <View style={ui.card}>
      <Text style={ui.sectionTitle}>기기 알림</Text>
      <Text style={ui.caption}>
        잠금 화면에는 이름·약속 내용·결과·코인 금액을 보내지 않아요. 알림을 꺼도 앱 안에서 소식을
        확인할 수 있어요.
      </Text>
      <QueryState pending={query.isPending} error={query.error} retry={query.refetch} />
      <ActionError error={error ?? action.error} />
      {syncStatus.data && (
        <Text accessibilityRole="alert" style={ui.caption}>
          {syncStatus.data}
        </Text>
      )}
      {query.data && (
        <Text style={ui.body}>
          이 로그인: {query.data.pushEnabled ? '알림 켜짐' : '알림 꺼짐'} · 등록 기기{' '}
          {query.data.registeredDevices}대
        </Text>
      )}
      <Button
        secondary
        busy={registering}
        disabled={action.isPending}
        onPress={() => void enable()}
      >
        이 기기 알림 켜기
      </Button>
      {query.data?.pushEnabled && (
        <Button
          secondary
          busy={action.isPending}
          disabled={registering}
          onPress={() => void action.run({ path: '/notifications/device', method: 'DELETE' })}
        >
          이 기기 알림 끄기
        </Button>
      )}
      {Platform.OS !== 'web' && (
        <Button
          secondary
          onPress={() =>
            void Linking.openSettings().catch(() =>
              setError(new Error('기기 설정을 열지 못했어요. 설정 앱에서 직접 확인해 주세요.')),
            )
          }
        >
          기기 알림 권한 확인
        </Button>
      )}
    </View>
  );
}
