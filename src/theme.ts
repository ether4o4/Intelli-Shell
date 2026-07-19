/**
 * IntelliShell palette — red · black · white · gold.
 *
 * Black grounds, white text, red for the wordmark's sibling actions (STOP, live
 * pulse), and metallic gold as the accent. Gold is never a flat fill in the UI —
 * see `ui/Gold.tsx` for the real metallic gradients (Skia text + LinearGradient
 * surfaces). The color tokens below hold the mid-gold used for hairlines, dots,
 * and text tints; anything prominent uses the gradient.
 *
 * The `gold*` keys are the accent. The legacy `purple*` keys are kept as aliases
 * that now resolve to gold, so components already themed on the accent stay gold
 * without churn.
 */
const GOLD = '#d9b45a';
const GOLD_DEEP = '#b98428';
const GOLD_SOFT = 'rgba(217,180,90,0.14)';

export const theme = {
  bg: '#0a0808',
  surface: '#100d0c',
  surface2: '#181413',
  border: '#2a2320',

  text: '#f3efe9', // white body text
  textDim: '#a89f97',
  textFaint: '#6d645d',

  red: '#e2313c', // wordmark siblings: STOP, live pulse
  redSoft: 'rgba(226,49,60,0.16)',

  // metallic gold accent (flat tokens; gradients live in ui/Gold.tsx)
  gold: GOLD,
  goldDeep: GOLD_DEEP,
  goldSoft: GOLD_SOFT,

  // legacy accent aliases → gold
  purple: GOLD,
  purpleDim: GOLD_DEEP,
  purpleSoft: GOLD_SOFT,

  // terminal — always black
  termBg: '#08070a',
  termText: '#ece7df',
  termDim: '#7d746c',
  termPrompt: GOLD,
  termRed: '#e2313c',

  mono: 'monospace',
};

/** Metallic gold gradient stops (dark → light band → dark), for Skia + LinearGradient. */
export const GOLD_GRADIENT = ['#8a6318', '#c79e3e', '#f4e6ab', '#e9ce79', '#7c5714'];
export const GOLD_GRADIENT_LOCATIONS = [0, 0.3, 0.5, 0.68, 1];

/** Vertical metallic gold for text (top-lit sheen). */
export const GOLD_TEXT_GRADIENT = ['#fbe7a6', '#e4c05a', '#b98428', '#f5e08c', '#9a6e22', '#e9ce79'];

export type Theme = typeof theme;
