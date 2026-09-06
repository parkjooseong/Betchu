import { useState } from 'react';
import { Linking, Text, View } from 'react-native';

import { useAuth } from './auth-provider';
import { emptySchema, Policy, userSchema } from './contracts';
import { Button, Check, ui } from './ui';

const policyNames = {
  TERMS: '서비스 이용약관',
  PRIVACY: '개인정보 처리 동의',
  EVIDENCE_OPTIONAL: '퀘스트 인증 자료 처리 동의',
};

export function Eligibility({
  ready,
  policies,
  refresh,
}: {
  ready: boolean;
  policies: Policy[];
  refresh: () => void;
}) {
  const { session, user } = useAuth();
  const [ageChecked, setAgeChecked] = useState(false);
  const [selected, setSelected] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  const [cancelReason, setCancelReason] = useState<'underage' | 'cancel' | null>(null);
  const [error, setError] = useState<string>();
  if (!user) return null;

  async function save() {
    if (!user || saving) return;
    if (!user.ageEligible && !ageChecked) {
      setError('만 14세 이상 여부를 직접 확인해 주세요.');
      return;
    }
    const missing = policies.filter(
      (policy) => policy.required && user.missingPolicyVersionIds.includes(policy.id),
    );
    if (missing.some((policy) => !selected.includes(policy.id))) {
      setError('현재 필수 정책을 읽고 동의 여부를 선택해 주세요.');
      return;
    }
    setSaving(true);
    setError(undefined);
    try {
      if (!user.ageEligible)
        session.setUser(
          await session.request('/onboarding/age-eligibility', userSchema, {
            method: 'POST',
            body: { eligible: true },
          }),
        );
      const toSave = policies
        .filter((policy) => selected.includes(policy.id))
        .sort((a, b) => Number(a.required) - Number(b.required));
      for (const policy of toSave) {
        session.setUser(
          await session.request(`/consents/${policy.policyType}`, userSchema, {
            method: 'PUT',
            body: { policyVersionId: policy.id, locale: policy.locale },
          }),
        );
      }
      await session.refreshUser();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '가입 조건을 저장하지 못했어요.');
    } finally {
      setSaving(false);
    }
  }

  async function cancel() {
    setSaving(true);
    setError(undefined);
    try {
      await session.request('/onboarding/age-eligibility', emptySchema, {
        method: 'POST',
        body: { eligible: cancelReason === 'underage' ? false : null },
      });
      await session.clearSession();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '가입 취소를 완료하지 못했어요.');
    } finally {
      setSaving(false);
    }
  }

  async function openPolicy(policy: Policy) {
    try {
      const url = new URL(policy.documentUrl);
      if (!['https:', 'http:'].includes(url.protocol))
        throw new Error('정책 문서 주소를 확인할 수 없어요.');
      await Linking.openURL(url.toString());
    } catch {
      setError('정책 문서를 열지 못했어요. 연결을 확인한 뒤 다시 시도해 주세요.');
    }
  }

  return (
    <View style={ui.card}>
      <Text style={ui.eyebrow}>01 · 가입 확인</Text>
      <Text accessibilityRole="header" style={ui.sectionTitle}>
        {user.status === 'ACTIVE'
          ? '현재 필수 정책을 다시 확인해 주세요'
          : '안전하게 함께 시작해요'}
      </Text>
      <Text style={ui.caption}>
        {user.status === 'ACTIVE'
          ? '기존 계정은 유지돼요. 정책을 다시 확인해도 가입 코인이 추가로 지급되지는 않아요.'
          : '생년월일은 입력하지 않아요. 필수 동의를 완료하고 서버에서 가입이 활성화되면 시작 코인이 한 번 지급돼요.'}
      </Text>
      {!ready ? (
        <>
          <Text style={ui.body}>
            현재 필수 정책을 준비 중이에요. 가입을 완료하려면 게시된 정책이 필요해요.
          </Text>
          <Button secondary onPress={refresh}>
            정책 다시 확인
          </Button>
        </>
      ) : (
        <>
          {user.ageEligible ? (
            <Text style={ui.body}>✓ 만 14세 이상 확인 완료</Text>
          ) : (
            <Check
              checked={ageChecked}
              disabled={saving}
              onPress={() => setAgeChecked(!ageChecked)}
            >
              저는 만 14세 이상입니다.
            </Check>
          )}
          {policies.map((policy) => {
            const accepted = policy.required
              ? !user.missingPolicyVersionIds.includes(policy.id)
              : user.evidenceConsent;
            return (
              <View key={policy.id} style={ui.note}>
                {accepted ? (
                  <Text style={ui.body}>✓ {policyNames[policy.policyType]} 동의 완료</Text>
                ) : (
                  <Check
                    checked={selected.includes(policy.id)}
                    disabled={saving}
                    onPress={() =>
                      setSelected((current) =>
                        current.includes(policy.id)
                          ? current.filter((id) => id !== policy.id)
                          : [...current, policy.id],
                      )
                    }
                  >
                    {policy.required ? '[필수]' : '[선택]'} {policyNames[policy.policyType]}
                  </Check>
                )}
                <Text style={ui.caption}>
                  버전 {policy.version} · {policy.locale}
                </Text>
                <Button secondary onPress={() => void openPolicy(policy)}>
                  정책 원문 읽기
                </Button>
                {!policy.required && (
                  <Text style={ui.caption}>
                    동의하지 않아도 가입할 수 있어요. 인증 자료를 사용하는 기능에만 적용돼요.
                  </Text>
                )}
              </View>
            );
          })}
          <Button busy={saving} onPress={() => void save()}>
            확인한 동의 저장
          </Button>
        </>
      )}
      {error && (
        <Text accessibilityRole="alert" style={ui.error}>
          {error}
        </Text>
      )}
      {user.status === 'PENDING_ELIGIBILITY' &&
        (cancelReason ? (
          <View style={ui.note}>
            <Text style={ui.body}>
              {cancelReason === 'underage'
                ? '만 14세 미만이면 서비스를 이용할 수 없어요. 임시 가입 정보와 로그인 세션을 삭제할까요?'
                : '가입을 취소하고 임시 가입 정보와 로그인 세션을 삭제할까요?'}
            </Text>
            <Button busy={saving} onPress={() => void cancel()}>
              임시 가입 삭제하기
            </Button>
            <Button secondary disabled={saving} onPress={() => setCancelReason(null)}>
              계속 확인하기
            </Button>
          </View>
        ) : (
          <>
            <Button secondary disabled={saving} onPress={() => setCancelReason('underage')}>
              만 14세 미만이에요
            </Button>
            <Button secondary disabled={saving} onPress={() => setCancelReason('cancel')}>
              가입 취소
            </Button>
          </>
        ))}
    </View>
  );
}
