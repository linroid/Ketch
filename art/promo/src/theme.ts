// Design tokens from STORYBOARD.md "Look". The film is made in two themes: dark (the default)
// and light (`Promo-light`, …). Scenes read the current film's palette through
// src/theme-context.tsx (usePalette, useTheme, useThemed); S02's beat blends toward the other
// theme with `lightMix`.
import type {CSSProperties} from 'react';

export type Mode = 'dark' | 'light';

export interface Palette {
  canvas: string;
  /** 160° wash over the canvas. */
  wash: string;
  primary: string;
  secondary: string;
  tertiary: string;
  accentText: string;
  accentFill: string;
  hairline: string;
  surface: string;
  raised: string;
  /** 1 px rim around app windows. */
  windowRim: string;
  chipFill: string;
  chipRim: string;
  /** Ink used for window shadows. */
  shadowInk: string;
  /**
   * What the canvas's contrast is drawn in, at an alpha: white on dark, ink on light (faint
   * lines and fills, the sail's white lanes, overlays).
   */
  ink: string;
  /** Colour of the soft drop shadows under cards and pills (their alpha is the caller's). */
  shade: string;
}

export const DARK: Palette = {
  canvas: '#0C0E13',
  wash: 'linear-gradient(160deg, #151A33 0%, #0E1016 50%, #1C1310 100%)',
  primary: '#EEF0F4',
  secondary: '#AEB4C0',
  tertiary: '#858D9C',
  accentText: '#8E9BFF',
  accentFill: '#5563F0',
  hairline: '#2A2E37',
  surface: '#16181D',
  raised: '#1E2128',
  windowRim: 'rgba(255,255,255,0.11)',
  chipFill: 'rgba(255,255,255,0.06)',
  chipRim: 'rgba(255,255,255,0.10)',
  shadowInk: '#020306',
  ink: '#FFFFFF',
  shade: '#000000',
};

export const LIGHT: Palette = {
  canvas: '#EEF1F8',
  wash: 'linear-gradient(160deg, #E9EEFC 0%, #F1F0FA 50%, #FBF0EA 100%)',
  primary: '#141A26',
  secondary: '#4B5466',
  tertiary: '#6E7687',
  accentText: '#3C47B7',
  accentFill: '#4F5DE4',
  hairline: '#E2E6EE',
  surface: '#FFFFFF',
  raised: '#F6F7FA',
  windowRim: '#E2E6EE',
  chipFill: 'rgba(20,26,38,0.05)',
  chipRim: 'rgba(20,26,38,0.10)',
  shadowInk: '#0F172A',
  ink: '#141A26',
  shade: '#0F172A',
};

/** The four app accents. */
export const ACCENTS = {
  signal: '#4F5DE4',
  signalBright: '#5563F0',
  harbor: '#00818D',
  fathom: '#007F35',
  beacon: '#C9431C',
} as const;
export type AccentName = 'signal' | 'harbor' | 'fathom' | 'beacon';
export const ACCENT_LIST: {id: AccentName; name: string; color: string}[] = [
  {id: 'signal', name: 'Signal', color: ACCENTS.signal},
  {id: 'harbor', name: 'Harbor', color: ACCENTS.harbor},
  {id: 'fathom', name: 'Fathom', color: ACCENTS.fathom},
  {id: 'beacon', name: 'Beacon', color: ACCENTS.beacon},
];

/** Brand ember. Logo, lane-wipe edge and at most one highlight per scene. */
export const EMBER = {
  from: '#FFB25B',
  to: '#E0482B',
  gradient: 'linear-gradient(135deg, #FFB25B, #E0482B)',
} as const;

export const STATUS = {success: '#45C27A', paused: '#E8A93F', failed: '#FF6B6B'} as const;

/** Terminal window styling (S06, S09). */
export const TERMINAL = {
  body: '#16181D',
  bar: '#1E2128',
  title: '#858D9C',
  text: '#EEF0F4',
  prompt: '#8E9BFF',
  cursor: '#AEB4C0',
  dim: '#858D9C',
  lights: ['#FF5F57', '#FEBC2E', '#28C840'] as const,
} as const;

/**
 * System faces for Han, kana and Hangul, right after Inter in every stack: Latin letters, digits
 * and punctuation stay Inter, and CJK characters fall through to these (PingFang SC for
 * Simplified Chinese). They are macOS fonts, so CJK locales render as designed on a Mac; on
 * another system install a CJK font (e.g. Noto Sans CJK) and add it here. PingFang's heaviest
 * weight is Semibold, which is why CJK locales render with `font-synthesis: none` (locale.tsx).
 */
const CJK_FALLBACK = '"PingFang SC", "Hiragino Sans", "Apple SD Gothic Neo"';

/** Font stacks. Always set one on a root element: headless Chrome falls back to Times. */
export const FONT = {
  sans: `"Inter", ${CJK_FALLBACK}, system-ui, sans-serif`,
  display: `"Inter Display", "Inter", ${CJK_FALLBACK}, sans-serif`,
  mono: '"JetBrains Mono", "SF Mono", Menlo, monospace',
  /** For native language names in S11. */
  cjk: `"Inter", ${CJK_FALLBACK}, sans-serif`,
} as const;

/**
 * Typography for CJK text, applied by the Text components to text that contains Han, kana or
 * Hangul (Latin text is untouched): Inter's tight tracking crowds CJK glyphs, and they fill the
 * em box, so headlines need more leading.
 */
export const CJK_TYPE = {
  /** Tracking of CJK runs (Latin runs in the same line keep their style's tracking). */
  letterSpacing: 0,
  /** Minimum line-height of headlines. */
  headlineLineHeight: 1.15,
} as const;

/** Type scale (px at 1920×1080). */
export const TYPE = {
  headline: {
    fontFamily: FONT.display,
    fontWeight: 700,
    fontSize: 84,
    lineHeight: 1.02,
    letterSpacing: '-0.035em',
  },
  hero: {
    fontFamily: FONT.display,
    fontWeight: 700,
    fontSize: 140,
    lineHeight: 1,
    letterSpacing: '-0.04em',
  },
  eyebrow: {
    fontFamily: FONT.sans,
    fontWeight: 600,
    fontSize: 20,
    lineHeight: 1.2,
    letterSpacing: '0.16em',
    textTransform: 'uppercase',
  },
  sub: {
    fontFamily: FONT.sans,
    fontWeight: 500,
    fontSize: 32,
    lineHeight: 1.35,
    letterSpacing: '-0.006em',
  },
  chip: {
    fontFamily: FONT.sans,
    fontWeight: 500,
    fontSize: 22,
    lineHeight: 1.2,
    letterSpacing: '-0.003em',
  },
  number: {
    fontFamily: FONT.display,
    fontWeight: 600,
    fontFeatureSettings: '"tnum"',
    fontVariantNumeric: 'tabular-nums',
    letterSpacing: '-0.02em',
  },
  mono: {
    fontFamily: FONT.mono,
    fontWeight: 400,
    letterSpacing: '0',
    fontVariantLigatures: 'none',
  },
} satisfies Record<string, CSSProperties>;

/** Safe area. */
export const SAFE = {x: 120, y: 90} as const;
/** Default text-block anchor: eyebrow top at y≈110, left at the safe edge. */
export const TEXT_ANCHOR = {x: 120, y: 110} as const;

// ---------------------------------------------------------------------------------------------
// Colour helpers

type RGBA = [number, number, number, number];

const parse = (c: string): RGBA => {
  const s = c.trim();
  if (s.startsWith('#')) {
    const h = s.slice(1);
    const full = h.length === 3 || h.length === 4 ? [...h].map((x) => x + x).join('') : h;
    const n = (i: number) => parseInt(full.slice(i, i + 2), 16);
    return [n(0), n(2), n(4), full.length === 8 ? n(6) / 255 : 1];
  }
  const m = s.match(/rgba?\(([^)]+)\)/);
  if (m) {
    const p = m[1].split(',').map((x) => parseFloat(x));
    return [p[0], p[1], p[2], p.length > 3 ? p[3] : 1];
  }
  throw new Error(`Unsupported colour ${c}`);
};

/** Mixes two colours (#hex or rgb[a]) in sRGB. t = 0 gives `a`. */
export const mix = (a: string, b: string, t: number): string => {
  if (t <= 0) return a;
  if (t >= 1) return b;
  const x = parse(a);
  const y = parse(b);
  const v = x.map((xi, i) => xi + (y[i] - xi) * t);
  return `rgba(${Math.round(v[0])},${Math.round(v[1])},${Math.round(v[2])},${+v[3].toFixed(4)})`;
};

/** Same colour with a new alpha. */
export const alpha = (c: string, a: number): string => {
  const [r, g, b, a0] = parse(c);
  return `rgba(${r},${g},${b},${+(a0 * a).toFixed(4)})`;
};

/** The other theme: light for dark, dark for light. */
export const otherMode = (mode: Mode): Mode => (mode === 'dark' ? 'light' : 'dark');

/** A mode as a `lightMix`: 0 for dark, 1 for light. */
export const lightMixOf = (mode: Mode): number => (mode === 'light' ? 1 : 0);

/**
 * Palette for a mode, or a blend between the two: `lightMix` (0 = dark, 1 = light, whatever the
 * mode) overrides the mode when given, as S02's beat blends the film toward its other theme.
 */
export const palette = (mode: Mode = 'dark', lightMix?: number): Palette => {
  const base = mode === 'light' ? LIGHT : DARK;
  if (lightMix === undefined) return base;
  if (lightMix <= 0) return DARK;
  if (lightMix >= 1) return LIGHT;
  const out = {} as Palette;
  (Object.keys(DARK) as (keyof Palette)[]).forEach((k) => {
    out[k] =
      k === 'wash' ? (lightMix < 0.5 ? DARK.wash : LIGHT.wash) : mix(DARK[k], LIGHT[k], lightMix);
  });
  return out;
};

/**
 * Layered window shadow from the storyboard (y/blur/alpha with #0F172A ink), scaled by `s`
 * (1 = a 1280-dp window shown at 1:1). Lighter on the light canvas.
 */
export const windowShadow = (mode: Mode = 'dark', s = 1): string => {
  // #0F172A is lighter than the dark canvas, so on dark it would read as a bluish halo; use a
  // near-black ink there (same layer alphas) and the storyboard ink, softened, on light.
  const k = mode === 'light' ? 0.42 : 1;
  const base = mode === 'light' ? LIGHT.shadowInk : DARK.shadowInk;
  const ink = (a: number) => alpha(base, a * k);
  const r = (n: number) => +(n * s).toFixed(2);
  return [
    `0 ${r(3)}px ${r(8)}px ${ink(0.19)}`,
    `0 ${r(8)}px ${r(20)}px ${ink(0.34)}`,
    `0 ${r(16)}px ${r(32)}px ${ink(0.29)}`,
    `0 ${r(40)}px ${r(80)}px ${r(-14)}px ${ink(0.53)}`,
  ].join(', ');
};
