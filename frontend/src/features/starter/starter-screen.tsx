import { useMutation, useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  BackHandler,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  Text,
  TextInput,
  useWindowDimensions,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { colors } from '@/theme/tokens';

import { getStarterCatalog, getStarterPreview, StarterPreview, StarterSpecies } from './api';
import { EggIllustration } from './egg-illustration';
import { normalizeStarterName, validateStarterName } from './name-validation';
import { styles } from './starter-styles';

const speciesAppearance = {
  STARLIGHT: {
    symbol: '✦',
    color: colors.starlight,
    soft: colors.starlightSoft,
    mood: '반짝이는 밤하늘',
  },
  WAVE: { symbol: '≈', color: colors.wave, soft: colors.waveSoft, mood: '말랑한 바다의 물결' },
  SUNSET: {
    symbol: '◒',
    color: colors.sunset,
    soft: colors.sunsetSoft,
    mood: '따뜻한 노을 한 조각',
  },
  FOREST: {
    symbol: '♧',
    color: colors.forest,
    soft: colors.forestSoft,
    mood: '포근한 초록빛 쉼터',
  },
} satisfies Record<StarterSpecies, { symbol: string; color: string; soft: string; mood: string }>;

const milestoneTitles = {
  HATCH: '첫 만남, 부화',
  INTERMEDIATE: '조금 더 자란 배츄',
  FINAL: '나만의 최종 진화',
  MASTERY: '함께 쌓은 숙련',
  SUCCESS_80: '80번의 약속 기록',
};

export function StarterScreen() {
  const { width, fontScale } = useWindowDimensions();
  const wide = width >= 800 && fontScale < 1.5;
  const scrollRef = useRef<ScrollView>(null);
  const [species, setSpecies] = useState<StarterSpecies>();
  const [name, setName] = useState('');
  const [nameError, setNameError] = useState<string>();
  const [speciesError, setSpeciesError] = useState<string>();
  const [preview, setPreview] = useState<StarterPreview>();
  const catalog = useQuery({
    queryKey: ['starter-catalog'],
    queryFn: getStarterCatalog,
    retry: false,
  });
  const mutation = useMutation({ mutationFn: getStarterPreview });

  function returnToSelection() {
    setPreview(undefined);
    mutation.reset();
    scrollRef.current?.scrollTo({ y: 0, animated: false });
  }

  useEffect(() => {
    if (!preview) return;
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      setPreview(undefined);
      scrollRef.current?.scrollTo({ y: 0, animated: false });
      return true;
    });
    return () => subscription.remove();
  }, [preview]);

  function submit() {
    if (!catalog.data || mutation.isPending) return;
    const validationError = validateStarterName(name, catalog.data.nameRules);
    setNameError(validationError);
    setSpeciesError(species ? undefined : '마음에 드는 배츄를 한 종류 골라 주세요.');
    if (!species || validationError) return;
    const normalizedName = normalizeStarterName(name);
    setName(normalizedName);
    mutation.mutate(
      { species, name: normalizedName },
      {
        onSuccess: (data) => {
          setPreview(data);
          scrollRef.current?.scrollTo({ y: 0, animated: false });
        },
      },
    );
  }

  return (
    <SafeAreaView style={styles.safeArea}>
      <KeyboardAvoidingView
        style={styles.safeArea}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <ScrollView
          ref={scrollRef}
          contentContainerStyle={styles.scroll}
          keyboardShouldPersistTaps="handled"
        >
          <View style={styles.page}>
            <View style={styles.header}>
              <View style={styles.wordmarkRow}>
                <View style={styles.brandDot} />
                <Text style={styles.wordmark}>BETCHU</Text>
              </View>
              <View style={styles.previewBadge}>
                <Text style={styles.previewBadgeText}>미리보기</Text>
              </View>
            </View>
            {preview ? (
              <>
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel="선택으로 돌아가기"
                  onPress={returnToSelection}
                  style={styles.backButton}
                >
                  <Text style={styles.backText}>← 선택으로 돌아가기</Text>
                </Pressable>
                <View style={[styles.hero, wide && styles.heroWide]}>
                  <View style={styles.heroCopy}>
                    <Text style={styles.eyebrow}>우리의 첫 페이지</Text>
                    <Text accessibilityRole="header" style={styles.heroTitle}>
                      {preview.name}, 반가워!
                    </Text>
                    <Text style={styles.heroDescription}>
                      작은 알 속에서 새로운 이야기를 기다려요.{'\n'}선택한 모습과 성장의 시작을
                      확인해 보세요.
                    </Text>
                    <View style={styles.heroTags}>
                      <Text style={styles.eggBadge}>알 · EGG</Text>
                      <Text
                        style={[
                          styles.speciesBadge,
                          { color: speciesAppearance[preview.starter.species].color },
                        ]}
                      >
                        {speciesAppearance[preview.starter.species].symbol}{' '}
                        {preview.starter.displayName} 선택
                      </Text>
                    </View>
                  </View>
                  <EggIllustration />
                </View>
                <View style={styles.scopeNote}>
                  <Text style={styles.scopeTitle}>지금은 배츄 미리보기예요</Text>
                  <Text style={styles.caption}>
                    이름과 종류는 저장되지 않아요. 실제 배츄 생성과 코인 지급은 이루어지지 않아요.
                  </Text>
                </View>
                <View style={[styles.detailColumns, wide && styles.columnsWide]}>
                  <View style={[styles.card, styles.detailCard]}>
                    <Text style={styles.eyebrow}>STARTING POINT</Text>
                    <Text accessibilityRole="header" style={styles.sectionTitle}>
                      똑같이 공평한 시작
                    </Text>
                    <Text style={styles.caption}>네 종류 모두 같은 능력치로 시작해요.</Text>
                    <View style={styles.stats}>
                      <Stat label="레벨" value={`Lv. ${preview.stats.level}`} />
                      <Stat label="전투력" value={`${preview.stats.power}`} />
                      <Stat label="HP" value={`${preview.stats.hp}`} />
                      <Stat label="ATK" value={`${preview.stats.atk}`} />
                    </View>
                    <View style={styles.progressLabel}>
                      <Text style={styles.caption}>경험치</Text>
                      <Text style={styles.statHint}>
                        {preview.stats.exp} / {preview.stats.nextLevelRequiredExp} XP
                      </Text>
                    </View>
                    <View
                      accessible
                      accessibilityRole="progressbar"
                      accessibilityLabel="경험치"
                      aria-valuemin={0}
                      aria-valuemax={preview.stats.nextLevelRequiredExp}
                      aria-valuenow={preview.stats.exp}
                      accessibilityValue={{
                        min: 0,
                        max: preview.stats.nextLevelRequiredExp,
                        now: preview.stats.exp,
                      }}
                      style={styles.progressTrack}
                    >
                      <View
                        style={[
                          styles.progressFill,
                          {
                            width: `${Math.min(100, (preview.stats.exp / preview.stats.nextLevelRequiredExp) * 100)}%`,
                          },
                        ]}
                      />
                    </View>
                    <Text style={styles.caption}>
                      인정 성공 {preview.recognizedSuccessCount}회 · 아직 부화 전이에요
                    </Text>
                    <View style={styles.divider} />
                    <Text style={styles.body}>선택한 종의 색과 모습은 부화한 뒤에 나타나요.</Text>
                    <Pressable
                      accessibilityRole="button"
                      onPress={returnToSelection}
                      style={styles.secondaryButton}
                    >
                      <Text style={styles.secondaryButtonText}>종류와 이름 다시 고르기</Text>
                    </Pressable>
                  </View>
                  <View style={[styles.card, styles.detailCard]}>
                    <Text style={styles.eyebrow}>GROW TOGETHER</Text>
                    <Text accessibilityRole="header" style={styles.sectionTitle}>
                      약속이 쌓이면, 이렇게 자라요
                    </Text>
                    {preview.growthMilestones.map((milestone, index) => (
                      <View key={milestone.type} style={styles.milestone}>
                        <View style={styles.milestoneNumber}>
                          <Text style={styles.milestoneNumberText}>
                            {String(index + 1).padStart(2, '0')}
                          </Text>
                        </View>
                        <View style={styles.milestoneCopy}>
                          <Text style={styles.milestoneTitle}>
                            {milestoneTitles[milestone.type]}
                          </Text>
                          {milestone.type !== 'HATCH' && (
                            <Text style={styles.milestoneCount}>
                              인정 성공 {milestone.requiredSuccessCount}회
                            </Text>
                          )}
                          <Text style={styles.caption}>{milestone.description}</Text>
                        </View>
                      </View>
                    ))}
                    <Text style={styles.footnote}>
                      성장은 파트너의 최종 승인과 서버 정산 완료 후 반영돼요. 튜토리얼 성공은 부화만
                      처리하며 인정 성공 수는 0회로 유지돼요.
                    </Text>
                  </View>
                </View>
              </>
            ) : (
              <>
                <View style={[styles.hero, wide && styles.heroWide]}>
                  <View style={styles.heroCopy}>
                    <Text style={styles.eyebrow}>우리 둘의 베팅 몬스터</Text>
                    <Text accessibilityRole="header" style={styles.heroTitle}>
                      작은 약속이,{'\n'}배츄를 깨워요.
                    </Text>
                    <Text style={styles.heroDescription}>
                      나와 함께 자랄 배츄는 어떤 친구일까요?{'\n'}마음에 드는 종류와 이름을 먼저
                      골라 보세요.
                    </Text>
                    <Text style={styles.heroNote}>모든 배츄는 같은 작은 알에서 시작해요.</Text>
                  </View>
                  <EggIllustration />
                </View>
                {catalog.isPending ? (
                  <View
                    style={styles.loadingCard}
                    accessibilityRole="progressbar"
                    accessibilityLabel="배츄 목록 불러오는 중"
                    aria-busy
                  >
                    <ActivityIndicator color={colors.brandStrong} />
                    <Text style={styles.body}>배츄 친구들을 불러오고 있어요…</Text>
                  </View>
                ) : catalog.isError ? (
                  <View style={styles.card}>
                    <Text accessibilityRole="alert" style={styles.errorText}>
                      {catalog.error.message}
                    </Text>
                    <Pressable
                      accessibilityRole="button"
                      onPress={() => void catalog.refetch()}
                      style={styles.primaryButton}
                    >
                      <Text style={styles.primaryButtonText}>목록 다시 불러오기</Text>
                    </Pressable>
                  </View>
                ) : (
                  <View style={styles.card}>
                    <View style={[styles.selectionColumns, wide && styles.columnsWide]}>
                      <View style={styles.choicesColumn}>
                        <Text style={styles.eyebrow}>01 · 배츄 고르기</Text>
                        <Text accessibilityRole="header" style={styles.sectionTitle}>
                          어떤 친구에게 마음이 가나요?
                        </Text>
                        <Text style={styles.caption}>
                          능력치는 같아요. 취향에 따라 골라 주세요.
                        </Text>
                        <View
                          style={styles.speciesGrid}
                          accessibilityRole="radiogroup"
                          accessibilityLabel="스타팅 배츄 종류"
                        >
                          {catalog.data.starters.map((starter) => {
                            const appearance = speciesAppearance[starter.species];
                            const selected = species === starter.species;
                            return (
                              <Pressable
                                key={starter.species}
                                accessibilityRole="radio"
                                accessibilityLabel={`${starter.displayName} 배츄`}
                                accessibilityHint={starter.description}
                                aria-checked={selected}
                                aria-disabled={mutation.isPending}
                                accessibilityState={{
                                  checked: selected,
                                  selected,
                                  disabled: mutation.isPending,
                                }}
                                disabled={mutation.isPending}
                                onPress={() => {
                                  setSpecies(starter.species);
                                  setSpeciesError(undefined);
                                  mutation.reset();
                                }}
                                style={({ pressed }) => [
                                  styles.speciesCard,
                                  fontScale >= 1.5 && styles.fullWidthCard,
                                  selected && styles.speciesSelected,
                                  pressed && styles.pressed,
                                ]}
                              >
                                <View style={styles.speciesCardTop}>
                                  <View
                                    style={[
                                      styles.speciesIcon,
                                      { backgroundColor: appearance.soft },
                                    ]}
                                  >
                                    <Text
                                      style={[styles.speciesSymbol, { color: appearance.color }]}
                                    >
                                      {appearance.symbol}
                                    </Text>
                                  </View>
                                  <Text
                                    style={[
                                      styles.selectionIndicator,
                                      selected && styles.selectedIndicator,
                                    ]}
                                  >
                                    {selected ? '✓' : '○'}
                                  </Text>
                                </View>
                                <Text style={styles.speciesName}>{starter.displayName}</Text>
                                <Text style={styles.caption}>{appearance.mood}</Text>
                                {selected && <Text style={styles.selectedLabel}>선택했어요</Text>}
                              </Pressable>
                            );
                          })}
                        </View>
                        {speciesError && (
                          <Text accessibilityRole="alert" style={styles.errorText}>
                            {speciesError}
                          </Text>
                        )}
                      </View>
                      <View style={[styles.formColumn, wide && styles.formColumnWide]}>
                        <Text style={styles.eyebrow}>02 · 이름 지어주기</Text>
                        <Text accessibilityRole="header" style={styles.sectionTitle}>
                          어떻게 불러줄까요?
                        </Text>
                        <Text style={styles.caption}>배츄에게 어울리는 이름을 붙여 주세요.</Text>
                        <Text style={styles.inputLabel} nativeID="starter-name-label">
                          배츄 이름
                        </Text>
                        <TextInput
                          accessibilityLabel="배츄 이름"
                          accessibilityHint={`${catalog.data.nameRules.minLength}~${catalog.data.nameRules.maxLength}자. 줄바꿈과 제어 문자는 사용할 수 없어요.`}
                          accessibilityState={{ disabled: mutation.isPending }}
                          aria-disabled={mutation.isPending}
                          style={[styles.nameInput, nameError && styles.inputError]}
                          value={name}
                          editable={!mutation.isPending}
                          placeholder="예: 말랑이"
                          placeholderTextColor={colors.textSecondary}
                          onChangeText={(value) => {
                            setName(value);
                            setNameError(undefined);
                            mutation.reset();
                          }}
                          onBlur={() => {
                            if (name)
                              setNameError(validateStarterName(name, catalog.data.nameRules));
                          }}
                          onSubmitEditing={submit}
                          returnKeyType="done"
                          autoCapitalize="none"
                          autoCorrect={false}
                        />
                        <View style={styles.inputHintRow}>
                          <Text style={styles.inputHint}>
                            앞뒤 공백을 제외한 {catalog.data.nameRules.minLength}~
                            {catalog.data.nameRules.maxLength}자
                          </Text>
                          <Text style={styles.inputHint}>
                            {Array.from(normalizeStarterName(name)).length}/
                            {catalog.data.nameRules.maxLength}
                          </Text>
                        </View>
                        {nameError && (
                          <Text accessibilityRole="alert" style={styles.errorText}>
                            {nameError}
                          </Text>
                        )}
                        <View style={styles.formSummary}>
                          <Text style={styles.formSummaryTitle}>
                            {species
                              ? `${catalog.data.starters.find((starter) => starter.species === species)?.displayName} 배츄와의 첫 만남`
                              : '나만의 배츄를 만나기 전'}
                          </Text>
                          <Text style={styles.caption}>
                            {species
                              ? catalog.data.starters.find((starter) => starter.species === species)
                                  ?.description
                              : '종류를 고르면 어떤 친구인지 알려드릴게요.'}
                          </Text>
                        </View>
                        {mutation.isError && (
                          <Text accessibilityRole="alert" style={styles.errorText}>
                            {mutation.error.message}
                          </Text>
                        )}
                        <Pressable
                          accessibilityRole="button"
                          aria-disabled={mutation.isPending}
                          aria-busy={mutation.isPending}
                          accessibilityState={{
                            disabled: mutation.isPending,
                            busy: mutation.isPending,
                          }}
                          disabled={mutation.isPending}
                          onPress={submit}
                          style={({ pressed }) => [
                            styles.primaryButton,
                            mutation.isPending && styles.buttonPending,
                            pressed && styles.pressed,
                          ]}
                        >
                          {mutation.isPending && <ActivityIndicator color={colors.surface} />}
                          <Text style={styles.primaryButtonText}>
                            {mutation.isPending
                              ? '미리보기 불러오는 중…'
                              : mutation.isError
                                ? '미리보기 다시 시도'
                                : '내 배츄 미리보기 →'}
                          </Text>
                        </Pressable>
                        <Text style={styles.footnote}>
                          미리보기에서는 이름과 종류를 저장하거나 계정을 활성화하지 않아요. 코인도
                          지급되지 않아요.
                        </Text>
                      </View>
                    </View>
                  </View>
                )}
                <View style={styles.bottomNote}>
                  <Text style={styles.bottomNoteTitle}>약속하고, 걸고, 키운다.</Text>
                  <Text style={styles.caption}>혼자보다 즐겁게, 둘이라서 특별하게.</Text>
                </View>
              </>
            )}
            <View style={styles.footer}>
              <Text style={styles.footerText}>BETCHU · 우리 둘의 작은 성장</Text>
            </View>
          </View>
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.stat}>
      <Text style={styles.caption}>{label}</Text>
      <Text style={styles.statValue}>{value}</Text>
    </View>
  );
}
