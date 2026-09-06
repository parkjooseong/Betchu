import { StyleSheet, View } from 'react-native';

import { colors, radius } from '@/theme/tokens';

export function EggIllustration() {
  return (
    <View
      accessible
      accessibilityRole="image"
      accessibilityLabel="아직 부화하지 않은 공통 알. 모든 종류가 같은 알 모습으로 시작해요."
      style={styles.scene}
    >
      <View style={styles.orbit} />
      <View style={styles.sparkOne} />
      <View style={styles.sparkTwo} />
      <View style={styles.shadow} />
      <View style={styles.egg}>
        <View style={styles.spotOne} />
        <View style={styles.spotTwo} />
        <View style={styles.spotThree} />
        <View style={styles.eyeLeft} />
        <View style={styles.eyeRight} />
        <View style={styles.smile} />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  scene: { width: 244, height: 252, alignSelf: 'center' },
  orbit: {
    position: 'absolute',
    width: 224,
    height: 224,
    top: 10,
    left: 10,
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.border,
  },
  shadow: {
    position: 'absolute',
    width: 130,
    height: 18,
    bottom: 12,
    left: 57,
    borderRadius: radius.pill,
    backgroundColor: colors.border,
  },
  egg: {
    position: 'absolute',
    width: 144,
    height: 181,
    left: 50,
    top: 35,
    borderTopLeftRadius: 78,
    borderTopRightRadius: 78,
    borderBottomLeftRadius: 67,
    borderBottomRightRadius: 67,
    backgroundColor: colors.egg,
    borderWidth: 2,
    borderColor: colors.eggShade,
    transform: [{ rotate: '-7deg' }],
    overflow: 'hidden',
  },
  spotOne: {
    position: 'absolute',
    width: 24,
    height: 33,
    left: 25,
    top: 30,
    borderRadius: radius.pill,
    backgroundColor: colors.eggSpot,
    transform: [{ rotate: '20deg' }],
  },
  spotTwo: {
    position: 'absolute',
    width: 16,
    height: 20,
    right: 19,
    top: 55,
    borderRadius: radius.pill,
    backgroundColor: colors.eggShade,
  },
  spotThree: {
    position: 'absolute',
    width: 36,
    height: 25,
    left: 1,
    bottom: 16,
    borderRadius: radius.pill,
    backgroundColor: colors.eggShade,
  },
  eyeLeft: {
    position: 'absolute',
    width: 7,
    height: 10,
    left: 47,
    top: 100,
    borderRadius: radius.pill,
    backgroundColor: colors.text,
  },
  eyeRight: {
    position: 'absolute',
    width: 7,
    height: 10,
    right: 43,
    top: 100,
    borderRadius: radius.pill,
    backgroundColor: colors.text,
  },
  smile: {
    position: 'absolute',
    width: 12,
    height: 7,
    left: 64,
    top: 113,
    borderBottomLeftRadius: 8,
    borderBottomRightRadius: 8,
    borderWidth: 2,
    borderTopWidth: 0,
    borderColor: colors.text,
  },
  sparkOne: {
    position: 'absolute',
    width: 11,
    height: 11,
    right: 22,
    top: 24,
    backgroundColor: colors.brandStrong,
    transform: [{ rotate: '45deg' }],
  },
  sparkTwo: {
    position: 'absolute',
    width: 6,
    height: 6,
    left: 14,
    bottom: 65,
    borderRadius: radius.pill,
    backgroundColor: colors.brandStrong,
  },
});
