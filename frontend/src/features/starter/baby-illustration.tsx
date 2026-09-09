import { StyleSheet, View } from 'react-native';

import { colors, radius } from '@/theme/tokens';

import type { StarterSpecies } from './api';

const appearances = {
  STARLIGHT: { name: '별빛', color: colors.starlight, soft: colors.starlightSoft },
  WAVE: { name: '파도', color: colors.wave, soft: colors.waveSoft },
  SUNSET: { name: '노을', color: colors.sunset, soft: colors.sunsetSoft },
  FOREST: { name: '숲', color: colors.forest, soft: colors.forestSoft },
};

export function BabyIllustration({
  species,
  stage = 'BABY',
}: {
  species: StarterSpecies;
  stage?: 'BABY' | 'INTERMEDIATE' | 'FINAL';
}) {
  const appearance = appearances[species];
  return (
    <View
      accessible
      accessibilityRole="image"
      accessibilityLabel={
        stage === 'BABY'
          ? `부화한 ${appearance.name} 아기 배츄`
          : `${appearance.name} ${stage === 'INTERMEDIATE' ? '중간 성장' : '최종 성장'} 배츄`
      }
      style={styles.scene}
    >
      <View style={[styles.backdrop, { backgroundColor: appearance.soft }]} />
      {stage !== 'BABY' && (
        <View
          style={[
            styles.backdrop,
            {
              borderColor: appearance.color,
              borderWidth: stage === 'FINAL' ? 5 : 2,
              transform: [{ scale: 1.05 }],
            },
          ]}
        />
      )}
      <View style={styles.shadow} />
      <View style={[styles.arm, styles.armLeft, { backgroundColor: appearance.color }]} />
      <View style={[styles.arm, styles.armRight, { backgroundColor: appearance.color }]} />
      {species === 'SUNSET' && (
        <>
          <View style={[styles.ear, styles.earLeft, { backgroundColor: appearance.color }]} />
          <View style={[styles.ear, styles.earRight, { backgroundColor: appearance.color }]} />
        </>
      )}
      {species === 'STARLIGHT' && (
        <>
          <View style={[styles.star, styles.starLeft, { backgroundColor: appearance.color }]} />
          <View style={[styles.star, styles.starRight, { backgroundColor: appearance.color }]} />
        </>
      )}
      <View
        style={[
          styles.body,
          { backgroundColor: appearance.soft, borderColor: appearance.color },
          stage !== 'BABY' && { transform: [{ scale: stage === 'FINAL' ? 1.12 : 1.06 }] },
        ]}
      >
        <View style={[styles.cheek, styles.cheekLeft, { backgroundColor: appearance.color }]} />
        <View style={[styles.cheek, styles.cheekRight, { backgroundColor: appearance.color }]} />
        <View style={[styles.eye, styles.eyeLeft]} />
        <View style={[styles.eye, styles.eyeRight]} />
        <View style={styles.smile} />
        <View style={[styles.belly, { borderColor: appearance.color }]} />
      </View>
      {species === 'FOREST' && (
        <>
          <View style={[styles.leaf, styles.leafLeft]} />
          <View style={[styles.leaf, styles.leafRight]} />
        </>
      )}
      {species === 'WAVE' && <View style={styles.waveCrest} />}
      <View style={[styles.foot, styles.footLeft, { backgroundColor: appearance.color }]} />
      <View style={[styles.foot, styles.footRight, { backgroundColor: appearance.color }]} />
    </View>
  );
}

const styles = StyleSheet.create({
  scene: { width: 244, height: 252, alignSelf: 'center' },
  backdrop: {
    position: 'absolute',
    width: 222,
    height: 222,
    top: 10,
    left: 11,
    borderRadius: radius.pill,
  },
  shadow: {
    position: 'absolute',
    width: 140,
    height: 18,
    bottom: 12,
    left: 52,
    borderRadius: radius.pill,
    backgroundColor: colors.border,
  },
  body: {
    position: 'absolute',
    width: 152,
    height: 155,
    left: 46,
    top: 65,
    borderWidth: 3,
    borderTopLeftRadius: 75,
    borderTopRightRadius: 75,
    borderBottomLeftRadius: 58,
    borderBottomRightRadius: 58,
  },
  arm: { position: 'absolute', width: 29, height: 54, top: 143, borderRadius: radius.pill },
  armLeft: { left: 31, transform: [{ rotate: '25deg' }] },
  armRight: { right: 31, transform: [{ rotate: '-25deg' }] },
  ear: { position: 'absolute', width: 44, height: 48, top: 46, borderRadius: radius.pill },
  earLeft: { left: 46 },
  earRight: { right: 46 },
  star: {
    position: 'absolute',
    width: 38,
    height: 38,
    top: 50,
    borderRadius: radius.sm,
    transform: [{ rotate: '45deg' }],
  },
  starLeft: { left: 54 },
  starRight: { right: 54 },
  eye: {
    position: 'absolute',
    width: 8,
    height: 12,
    top: 57,
    borderRadius: radius.pill,
    backgroundColor: colors.text,
  },
  eyeLeft: { left: 43 },
  eyeRight: { right: 43 },
  smile: {
    position: 'absolute',
    width: 17,
    height: 10,
    top: 71,
    left: 65,
    borderColor: colors.text,
    borderWidth: 2,
    borderTopWidth: 0,
    borderBottomLeftRadius: 10,
    borderBottomRightRadius: 10,
  },
  cheek: {
    position: 'absolute',
    width: 21,
    height: 10,
    top: 76,
    borderRadius: radius.pill,
    opacity: 0.3,
  },
  cheekLeft: { left: 21 },
  cheekRight: { right: 21 },
  belly: {
    position: 'absolute',
    width: 40,
    height: 22,
    left: 53,
    bottom: 20,
    borderWidth: 2,
    borderRadius: radius.pill,
    opacity: 0.4,
  },
  foot: { position: 'absolute', width: 35, height: 18, top: 209, borderRadius: radius.pill },
  footLeft: { left: 68 },
  footRight: { right: 68 },
  leaf: {
    position: 'absolute',
    width: 25,
    height: 48,
    top: 32,
    backgroundColor: colors.forest,
    borderTopLeftRadius: 25,
    borderBottomRightRadius: 25,
  },
  leafLeft: { left: 99, transform: [{ rotate: '-30deg' }] },
  leafRight: { left: 121, transform: [{ rotate: '35deg' }] },
  waveCrest: {
    position: 'absolute',
    width: 55,
    height: 41,
    left: 94,
    top: 40,
    borderWidth: 10,
    borderColor: colors.wave,
    borderRightColor: 'transparent',
    borderRadius: radius.pill,
    transform: [{ rotate: '30deg' }],
  },
});
