import { useQuery } from '@tanstack/react-query';
import { Link } from 'expo-router';
import { ActivityIndicator, ScrollView, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { EggIllustration } from '@/features/starter/egg-illustration';
import { colors } from '@/theme/tokens';

import { launchLogin, useAuth } from './auth-provider';
import { policiesSchema, providersSchema } from './contracts';
import { Couples } from './couples';
import { Eligibility } from './eligibility';
import { request } from './transport';
import { Button, ui } from './ui';

export function OnboardingScreen() {
  const { session, user, restoring, busy, error } = useAuth();
  const providers = useQuery({
    queryKey: ['auth-providers'],
    queryFn: () => request('/auth/providers', providersSchema),
    retry: false,
  });
  const policies = useQuery({
    queryKey: ['current-policies'],
    queryFn: () => request('/policy-versions/current', policiesSchema),
    retry: false,
  });
  const ready = providers.data?.registrationAvailable && policies.data?.ready;
  const google = providers.data?.providers.find(
    (provider) => provider.provider === 'GOOGLE' && provider.enabled,
  );
  const loading = restoring || (!user && (providers.isPending || policies.isPending));
  const publicError = providers.error ?? policies.error;

  function refreshPublic() {
    void providers.refetch();
    void policies.refetch();
  }

  return (
    <SafeAreaView style={ui.root}>
      <ScrollView contentContainerStyle={ui.scroll} keyboardShouldPersistTaps="handled">
        <View style={ui.page}>
          <View style={ui.header}>
            <Text style={ui.brand}>BETCHU</Text>
            <Link href="/preview" style={ui.link}>
              배츄 미리보기
            </Link>
          </View>
          <View style={ui.hero}>
            <Text style={ui.eyebrow}>우리 둘의 베팅 몬스터</Text>
            <Text accessibilityRole="header" style={ui.title}>
              {user?.status === 'ACTIVE'
                ? `${user.nickname}님, 반가워요.`
                : '작은 약속부터,\n함께 자라는 우리.'}
            </Text>
            <Text style={ui.body}>
              {user?.status === 'ACTIVE'
                ? '가입이 활성화됐어요. 함께 약속할 사람을 연결해 보세요.'
                : '안전하게 가입하고, 서로를 확인한 뒤 함께 시작해요.'}
            </Text>
            {!user && <EggIllustration />}
          </View>
          {error && (
            <View style={ui.note}>
              <Text accessibilityRole="alert" style={ui.error}>
                {error}
              </Text>
              {!user && (
                <Button
                  secondary
                  onPress={() =>
                    void session
                      .clearSession()
                      .then(() => session.clearError())
                      .catch((reason: unknown) => session.setError(reason))
                  }
                >
                  기기 로그인 정보 지우기
                </Button>
              )}
            </View>
          )}
          {publicError && (
            <View style={ui.card}>
              <Text accessibilityRole="alert" style={ui.error}>
                {publicError.message}
              </Text>
              <Button secondary onPress={refreshPublic}>
                가입 상태 다시 확인
              </Button>
            </View>
          )}
          {loading ? (
            <View style={ui.card} aria-busy>
              <ActivityIndicator color={colors.brandStrong} />
              <Text style={ui.body}>가입 가능 상태를 확인하고 있어요…</Text>
            </View>
          ) : !user ? (
            <View style={ui.card}>
              <Text accessibilityRole="header" style={ui.sectionTitle}>
                둘만의 이야기를 시작해요
              </Text>
              {google ? (
                <>
                  <Text style={ui.caption}>
                    Google로 로그인한 뒤 연령과 정책 동의를 직접 확인해 주세요.
                  </Text>
                  {!ready && (
                    <Text style={ui.caption}>
                      로그인은 가능해요. 신규 가입 완료와 초대는 필수 정책이 준비된 뒤 진행할 수
                      있어요.
                    </Text>
                  )}
                  <Button busy={busy} onPress={() => void launchLogin(session)}>
                    Google로 계속하기
                  </Button>
                  {busy && (
                    <Button
                      secondary
                      onPress={() =>
                        void session
                          .cancelLogin()
                          .catch((reason: unknown) => session.setError(reason))
                      }
                    >
                      로그인 취소
                    </Button>
                  )}
                </>
              ) : (
                <>
                  <Text style={ui.body}>
                    {publicError
                      ? '가입 상태를 확인하지 못했어요.'
                      : '지금은 가입을 준비 중이에요.'}
                  </Text>
                  <Text style={ui.caption}>
                    로그인 연결과 필수 정책이 준비되면 가입할 수 있어요. 배츄 미리보기는 바로 둘러볼
                    수 있어요.
                  </Text>
                  <Button secondary onPress={refreshPublic}>
                    가입 가능 여부 다시 확인
                  </Button>
                </>
              )}
              <Link href="/preview" style={ui.link}>
                스타팅 배츄 먼저 만나보기 →
              </Link>
            </View>
          ) : user.status === 'PENDING_ELIGIBILITY' ||
            (user.status === 'ACTIVE' &&
              (!policies.data?.ready || user.missingPolicyVersionIds.length > 0)) ? (
            <>
              <Eligibility
                ready={policies.data?.ready ?? false}
                policies={policies.data?.policies ?? []}
                refresh={refreshPublic}
              />
              {user.status === 'ACTIVE' && <Couples safetyOnly />}
            </>
          ) : user.status === 'ACTIVE' ? (
            <>
              <View style={ui.card}>
                <Text style={ui.eyebrow}>내 계정</Text>
                <Text style={ui.sectionTitle}>내 츄코인</Text>
                <Text style={ui.body}>
                  사용 가능 {user.availableCoins ?? '확인 필요'}C · 잠김{' '}
                  {user.lockedCoins ?? '확인 필요'}C
                </Text>
                <Text style={ui.caption}>
                  서버에서 확인한 내 잔액이에요. 연결이나 재연결로 가입 코인이 다시 지급되지는
                  않아요.
                </Text>
                <Button
                  secondary
                  onPress={() =>
                    void session.refreshUser().catch((reason: unknown) => session.setError(reason))
                  }
                >
                  계정 상태 새로고침
                </Button>
              </View>
              <Couples />
              <View style={ui.note}>
                <Text style={ui.body}>스타팅 배츄 저장과 퀘스트는 준비 중이에요.</Text>
                <Link href="/preview" style={ui.link}>
                  배츄 미리보기 둘러보기
                </Link>
              </View>
            </>
          ) : (
            <View style={ui.card}>
              <Text style={ui.body}>
                현재 사용할 수 없는 계정이에요. 로그아웃한 뒤 계정 상태를 확인해 주세요.
              </Text>
            </View>
          )}
          {user && (
            <Button
              secondary
              busy={busy}
              onPress={() =>
                void session.logout().catch((reason: unknown) => session.setError(reason))
              }
            >
              로그아웃
            </Button>
          )}
          <Text style={ui.caption}>약속하고, 걸고, 키운다. · BETCHU</Text>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}
