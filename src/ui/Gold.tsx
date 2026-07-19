/**
 * Gold branding accents.
 *
 *   • GoldText — the wordmark, rendered as plain Text in the theme's gold.
 *     (Previously Skia-drawn with a gradient fill; Skia 1.x bundles a React 18
 *     reconciler that crashes under React 19, and a whole native engine for
 *     one decorative label wasn't worth the size or the risk.)
 *   • GoldFill — a react-native-linear-gradient surface with a diagonal light
 *     band, for buttons and bars.
 */
import React from 'react';
import {StyleProp, Text, ViewStyle} from 'react-native';
import LinearGradient from 'react-native-linear-gradient';
import {theme, GOLD_GRADIENT, GOLD_GRADIENT_LOCATIONS} from '../theme';

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
  return (
    <Text
      style={{
        color: theme.gold,
        fontSize: size,
        fontWeight: weight,
        fontFamily: 'monospace',
        letterSpacing: spacing,
      }}
      numberOfLines={1}>
      {text}
    </Text>
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
