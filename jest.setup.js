/**
 * Jest setup — mocks the native modules the shell depends on so the App
 * smoke test can render outside an Android runtime.
 */
import 'react-native-gesture-handler/jestSetup';

jest.mock('react-native-linear-gradient', () => 'LinearGradient');

// Stub the Skia surface the glass components touch: drawing components render
// as host strings, Skia.RuntimeEffect compiles to null, frame hooks are inert.
// `virtual` because the package is no longer a dependency — without it the
// whole suite fails at setup with "Cannot find module".
jest.mock('@shopify/react-native-skia', () => ({
  Canvas: 'SkiaCanvas',
  Group: 'SkiaGroup',
  Rect: 'SkiaRect',
  RoundedRect: 'SkiaRoundedRect',
  Circle: 'SkiaCircle',
  Paint: 'SkiaPaint',
  Shader: 'SkiaShader',
  RuntimeShader: 'SkiaRuntimeShader',
  LinearGradient: 'SkiaLinearGradient',
  RadialGradient: 'SkiaRadialGradient',
  BlurMask: 'SkiaBlurMask',
  Fill: 'SkiaFill',
  vec: (x, y) => ({ x, y }),
  Skia: {
    RuntimeEffect: { Make: () => null },
  },
  useDerivedValue: fn => ({ value: typeof fn === 'function' ? fn() : undefined }),
  useFrame: () => {},
}), { virtual: true });
