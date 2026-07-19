/**
 * IntelliShell palette — calm slate with a soft blue accent.
 *
 * Neutral dark-slate grounds and off-white text (GitHub/VS Code dark family),
 * a muted blue as the single accent, and a restrained red kept only for the
 * STOP control. The `gold*`/`purple*` keys are legacy accent aliases that now
 * resolve to the blue accent, so existing components restyle without churn.
 */
const ACCENT = '#6aa9ff';
const ACCENT_DEEP = '#3d7de0';
const ACCENT_SOFT = 'rgba(106,169,255,0.14)';

export const theme = {
  bg: '#0d1117',
  surface: '#161b22',
  surface2: '#1c2129',
  border: '#2b323c',

  text: '#e6edf3',
  textDim: '#9aa4b0',
  textFaint: '#6a7480',

  red: '#e5534b', // STOP control only
  redSoft: 'rgba(229,83,75,0.16)',

  // blue accent (flat tokens; the surface gradient lives in ui/Gold.tsx)
  gold: ACCENT,
  goldDeep: ACCENT_DEEP,
  goldSoft: ACCENT_SOFT,

  // legacy accent aliases → blue accent
  purple: ACCENT,
  purpleDim: ACCENT_DEEP,
  purpleSoft: ACCENT_SOFT,

  // terminal
  termBg: '#0b0f15',
  termText: '#d7dee7',
  termDim: '#6a7480',
  termPrompt: ACCENT,
  termRed: '#e5534b',

  mono: 'monospace',
};

/** Accent gradient stops (deep → light band → deep), for LinearGradient surfaces. */
export const GOLD_GRADIENT = ['#2f66c0', '#4d8bec', '#8cc0ff', '#4d8bec', '#2f66c0'];
export const GOLD_GRADIENT_LOCATIONS = [0, 0.3, 0.5, 0.68, 1];

/** Legacy text-gradient stops (unused since the wordmark became plain text). */
export const GOLD_TEXT_GRADIENT = ['#8cc0ff', '#6aa9ff', '#3d7de0', '#8cc0ff', '#2f66c0', '#4d8bec'];

export type Theme = typeof theme;
