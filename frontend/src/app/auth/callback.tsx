import { Link, useLocalSearchParams, useNavigationContainerRef, useRouter } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Text, View } from 'react-native';

import { useAuth } from '@/features/onboarding/auth-provider';
import { Button } from '@/features/onboarding/ui';
import { styles } from '@/features/starter/starter-styles';
import { colors } from '@/theme/tokens';

export default function AuthCallbackScreen() {
  const params = useLocalSearchParams<{
    loginId?: string | string[];
    status?: string | string[];
    handoffCode?: string | string[];
  }>();
  const router = useRouter();
  const navigation = useNavigationContainerRef();
  const { session } = useAuth();
  const [error, setError] = useState<string>();
  const [navigationReady, setNavigationReady] = useState(false);
  const handled = useRef(false);
  useEffect(() => {
    function completeWhenReady() {
      if (handled.current || !navigation.isReady()) return;
      handled.current = true;
      setNavigationReady(true);
      const callback = {
        loginId: typeof params.loginId === 'string' ? params.loginId : '',
        status:
          typeof params.status === 'string' &&
          (params.status === 'SUCCESS' || params.handoffCode === undefined)
            ? params.status
            : '',
        handoffCode: typeof params.handoffCode === 'string' ? params.handoffCode : undefined,
      };
      router.setParams({ loginId: undefined, status: undefined, handoffCode: undefined });
      void session
        .completeLogin(callback.loginId, callback.status, callback.handoffCode)
        .then(() => router.replace('/'))
        .catch((reason: unknown) =>
          setError(reason instanceof Error ? reason.message : '로그인을 완료하지 못했어요.'),
        );
    }
    const removeReady = navigation.addListener('ready', completeWhenReady);
    const removeState = navigation.addListener('state', completeWhenReady);
    completeWhenReady();
    return () => {
      removeReady();
      removeState();
    };
  }, [navigation, params.loginId, params.status, params.handoffCode, router, session]);
  return (
    <View style={[styles.safeArea, styles.loadingCard]}>
      {error ? (
        <>
          <Text accessibilityRole="alert" style={styles.errorText}>
            {error}
          </Text>
          <Link href="/" style={styles.backButton}>
            처음으로 돌아가기
          </Link>
        </>
      ) : (
        <>
          <ActivityIndicator color={colors.brandStrong} />
          <Text style={styles.body}>로그인을 확인하고 있어요…</Text>
          <Button
            secondary
            disabled={!navigationReady}
            onPress={() =>
              void session
                .cancelLogin()
                .then(() => router.replace('/'))
                .catch((reason: unknown) =>
                  setError(
                    reason instanceof Error ? reason.message : '로그인 취소를 완료하지 못했어요.',
                  ),
                )
            }
          >
            로그인 취소
          </Button>
        </>
      )}
    </View>
  );
}
