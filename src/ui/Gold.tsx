/**
 * Real metallic gold — not a flat color.
 *
 *   • GoldText — Skia-rendered text filled with a vertical metallic gradient
 *     (top-lit sheen). Used for the wordmark.
 *   • GoldFill — a react-native-linear-gradient surface with a diagonal light
 *     band, for buttons and bars.
 *
 * Both draw from the same gold stops in theme.ts so the metal reads consistently.
 */
import React from 'react';
import {StyleProp, ViewStyle} from 'react-native';
import LinearGradient from 'react-native-linear-gradient';
import {Canvas, Text as SkText, matchFont, LinearGradient as SkGradient, vec} from '@shopify/react-native-skia';
import {GOLD_GRADIENT, GOLD_GRADIENT_LOCATIONS, GOLD_TEXT_GRADIENT} from '../theme';

export function GoldText({
  text,
  size = 18,
  weight = 'bold',
  spacing = 2,
}: {
  text: string;
  size?: number;
  weight?: 'normal' | 'bold';
  spacing?: number;
}) {
  const font = matchFont({
    fontFamily: 'monospace',
    fontSize: size,
    fontWeight: weight,
  } as any);

  // Monospace advance ≈ 0.6em; size generously so glyphs never clip.
  const width = Math.ceil(text.length * size * 0.62 + spacing * text.length + 4);
  const height = Math.ceil(size * 1.35);
  const baseline = Math.ceil(size * 1.02);
  // Letter-spacing by drawing spaced text isn't supported per-glyph here; the
  // mono advance + the small padding reads as tracked at wordmark scale.

  return (
    <Canvas style={{width, height}}>
      <SkText x={0} y={baseline} text={text} font={font}>
        <SkGradient start={vec(0, 0)} end={vec(0, height)} colors={GOLD_TEXT_GRADIENT} />
      </SkText>
    </Canvas>
  );
}

export function GoldFill({
  children,
  style,
  radius = 4,
}: {
  children?: React.ReactNode;
  style?: StyleProp<ViewStyle>;
  radius?: number;
}) {
  return (
    <LinearGradient
      colors={GOLD_GRADIENT}
      locations={GOLD_GRADIENT_LOCATIONS}
      start={{x: 0, y: 0}}
      end={{x: 1, y: 1}}
      style={[{borderRadius: radius}, style]}>
      {children}
    </LinearGradient>
  );
}
