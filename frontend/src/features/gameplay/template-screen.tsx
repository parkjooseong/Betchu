import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Text, TextInput, View } from 'react-native';
import { z } from 'zod';

import { Button, Check, ui } from '@/features/onboarding/ui';
import { categories, categorySchema, draftSchema } from '@/features/quests/contracts';
import { QuestPage } from '@/features/quests/screens';
import { parseSeoulDate } from '@/features/quests/validation';

import { useFeatureAction, useFeatureQuery } from './api';
import { ActionError, QueryState } from './page';

export const templatesSchema = z.array(
  z.object({
    code: z.string(),
    title: z.string(),
    category: categorySchema,
    successCriteria: z.string(),
    difficulty: z.number().int().min(1).max(4),
    minimumDurationMinutes: z.number().int().nonnegative(),
    stakes: z.array(z.union([z.literal(0), z.literal(100), z.literal(200), z.literal(300)])),
  }),
);
export function TemplatesScreen() {
  return (
    <QuestPage>
      <Templates />
    </QuestPage>
  );
}
function Templates() {
  const query = useFeatureQuery('/quest-templates', templatesSchema, true);
  const action = useFeatureAction(draftSchema, true);
  const router = useRouter();
  const [selected, setSelected] = useState<z.infer<typeof templatesSchema>[number] | null>(null);
  const [stake, setStake] = useState(0);
  const [due, setDue] = useState('');
  const [result, setResult] = useState('');
  const [error, setError] = useState<Error | null>(null);
  async function create() {
    if (!selected) return;
    setError(null);
    try {
      const dueAt = parseSeoulDate(due),
        resultAt = parseSeoulDate(result);
      if (Date.parse(resultAt) <= Date.parse(dueAt))
        throw new Error('결과 확인 시작은 수행 마감보다 뒤여야 해요.');
      const draft = await action.run({
        path: `/quest-templates/${encodeURIComponent(selected.code)}/draft`,
        body: { stake, dueAt, resultAt },
      });
      if (draft) router.replace({ pathname: '/quests/[questId]', params: { questId: draft.id } });
    } catch (reason) {
      setError(reason instanceof Error ? reason : new Error('날짜를 확인해 주세요.'));
    }
  }
  return (
    <View style={{ gap: 16 }}>
      <Text accessibilityRole="header" style={ui.title}>
        추천 약속 고르기
      </Text>
      <Text style={ui.body}>
        검토된 조건으로 시작해요. 내용·난이도·최소 수행 시간은 고정되고, 일정과 코인은 직접
        선택해요.
      </Text>
      <QueryState pending={query.isPending} error={query.error} retry={query.refetch} />
      {query.data?.map((template) => (
        <View key={template.code} style={ui.card}>
          <Text style={ui.eyebrow}>
            {categories[template.category]} · ★{template.difficulty}
          </Text>
          <Text style={ui.sectionTitle}>{template.title}</Text>
          <Text style={ui.body}>{template.successCriteria}</Text>
          <Text style={ui.caption}>
            최소 {template.minimumDurationMinutes}분 · 최대 {Math.max(...template.stakes)}C
          </Text>
          <Button
            secondary
            disabled={action.isPending}
            onPress={() => {
              setSelected(template);
              setStake(0);
            }}
          >
            {selected?.code === template.code ? '선택됨' : '이 약속 선택'}
          </Button>
        </View>
      ))}
      {selected && (
        <View style={ui.note}>
          <Text style={ui.sectionTitle}>{selected.title}</Text>
          <View style={ui.row}>
            {selected.stakes.map((amount) => (
              <Check
                key={amount}
                checked={stake === amount}
                disabled={action.isPending}
                onPress={() => setStake(amount)}
              >
                {amount}C
              </Check>
            ))}
          </View>
          <Text style={ui.caption}>
            성공 기본 보상:{' '}
            {stake * (selected.difficulty === 1 ? 0.5 : selected.difficulty === 2 ? 0.75 : 1)}C ·
            실제 보상은 일일 한도와 약속 승인 시점에 확정해요. 실패하면 걸어 둔 코인을 잃어요.
          </Text>
          <Text style={ui.body}>수행 마감 (서울 시간)</Text>
          <TextInput
            accessibilityLabel="수행 마감 (서울 시간)"
            style={ui.input}
            value={due}
            onChangeText={setDue}
            editable={!action.isPending}
            placeholder="YYYY-MM-DD HH:mm"
          />
          <Text style={ui.body}>결과 확인 시작 (서울 시간)</Text>
          <TextInput
            accessibilityLabel="결과 확인 시작 (서울 시간)"
            style={ui.input}
            value={result}
            onChangeText={setResult}
            editable={!action.isPending}
            placeholder="YYYY-MM-DD HH:mm"
          />
          <ActionError error={error ?? action.error} />
          <Button busy={action.isPending} onPress={() => void create()}>
            나만 보는 초안 만들기
          </Button>
          <Text style={ui.caption}>저장은 코인을 사용하거나 파트너에게 알리지 않아요.</Text>
        </View>
      )}
    </View>
  );
}
