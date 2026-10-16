// Canvas for every scene: base colour, 160° wash and two slowly drifting blooms (dark: accent top
// left, ember bottom right; light: ember bottom left, a faint accent top right), in the film's
// theme. `lightMix` cross-blends between the two canvases for S02's beat in the other theme.
import React from 'react';
import {AbsoluteFill} from 'remotion';
import {noise2D} from '@remotion/noise';
import {DARK, FONT, LIGHT, mix, type Mode} from '../theme';
import {useTheme} from '../theme-context';
import {useDesignFrame} from '../time';

export interface BackgroundProps {
  /** Canvas mode. Default the film's theme. */
  mode?: Mode;
  /** 0..1 blend from dark to light (overrides `mode` when given). */
  lightMix?: number;
  /** Noise seed for the bloom drift; vary per scene so blooms do not repeat. Default 'ketch'. */
  seed?: string;
  /** Drift amplitude in px (each axis moves within ±drift/2). Default 40. */
  drift?: number;
  /** Bloom strength multiplier. Default 1. */
  intensity?: number;
  /** Static film grain that hides gradient banding. Default true. */
  grain?: boolean;
  /** Frame override (defaults to useDesignFrame()). */
  frame?: number;
  style?: React.CSSProperties;
  children?: React.ReactNode;
}

/** Radial bloom with a soft, Gaussian-like falloff (no visible ring). */
const bloom = (stops: [string, number][]): string =>
  `radial-gradient(circle closest-side, ${stops.map(([c, p]) => `${c} ${p}%`).join(', ')})`;

const ACCENT_BLOOM = bloom([
  ['rgba(85,99,240,0.26)', 0],
  ['rgba(85,99,240,0.21)', 18],
  ['rgba(85,99,240,0.135)', 38],
  ['rgba(85,99,240,0.065)', 58],
  ['rgba(85,99,240,0.02)', 80],
  ['rgba(85,99,240,0)', 100],
]);

const EMBER_BLOOM = bloom([
  ['rgba(255,178,91,0.15)', 0],
  ['rgba(246,140,72,0.12)', 22],
  ['rgba(224,72,43,0.08)', 45],
  ['rgba(224,72,43,0.035)', 70],
  ['rgba(224,72,43,0)', 100],
]);

const LIGHT_EMBER_BLOOM = bloom([
  ['rgba(255,217,194,0.35)', 0],
  ['rgba(255,217,194,0.24)', 30],
  ['rgba(255,217,194,0.1)', 62],
  ['rgba(255,217,194,0)', 100],
]);

const LIGHT_ACCENT_BLOOM = bloom([
  ['rgba(79,93,228,0.09)', 0],
  ['rgba(79,93,228,0.05)', 40],
  ['rgba(79,93,228,0)', 100],
]);

const GRAIN_URI = `url("data:image/svg+xml;utf8,${encodeURIComponent(
  `<svg xmlns='http://www.w3.org/2000/svg' width='220' height='220'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='2' stitchTiles='stitch'/><feColorMatrix values='0 0 0 0 0.5  0 0 0 0 0.5  0 0 0 0 0.5  0 0 0 1.6 -0.3'/></filter><rect width='100%' height='100%' filter='url(#n)'/></svg>`,
)}")`;

/** One bloom disc of radius `r` centred at (cx, cy). */
const Bloom: React.FC<{cx: number; cy: number; r: number; image: string; opacity: number}> = ({
  cx,
  cy,
  r,
  image,
  opacity,
}) =>
  opacity <= 0 ? null : (
    <div
      style={{
        position: 'absolute',
        left: cx - r,
        top: cy - r,
        width: r * 2,
        height: r * 2,
        backgroundImage: image,
        opacity,
      }}
    />
  );

export const Background: React.FC<BackgroundProps> = ({
  mode: modeProp,
  lightMix,
  seed = 'ketch',
  drift = 40,
  intensity = 1,
  grain = true,
  frame: frameOverride,
  style,
  children,
}) => {
  const current = useDesignFrame();
  const theme = useTheme();
  const mode = modeProp ?? theme;
  const frame = frameOverride ?? current;
  const m = lightMix ?? (mode === 'light' ? 1 : 0);
  const a = drift / 2;
  // Slow noise: about one noise period per 12 s, so a scene drifts a few dozen px at most.
  const t = frame / 360;
  const ax = noise2D(`${seed}-ax`, t, 0) * a;
  const ay = noise2D(`${seed}-ay`, 0, t) * a;
  const ex = noise2D(`${seed}-ex`, t, 3.7) * a;
  const ey = noise2D(`${seed}-ey`, 5.1, t) * a;

  return (
    <AbsoluteFill
      style={{
        backgroundColor: mix(DARK.canvas, LIGHT.canvas, m),
        overflow: 'hidden',
        fontFamily: FONT.sans,
        ...style,
      }}
    >
      {m < 1 && <AbsoluteFill style={{backgroundImage: DARK.wash, opacity: 1 - m}} />}
      {m > 0 && <AbsoluteFill style={{backgroundImage: LIGHT.wash, opacity: m}} />}
      <Bloom
        cx={260 + ax}
        cy={120 + ay}
        r={1000}
        image={ACCENT_BLOOM}
        opacity={(1 - m) * intensity}
      />
      <Bloom
        cx={1700 + ex}
        cy={960 + ey}
        r={660}
        image={EMBER_BLOOM}
        opacity={(1 - m) * intensity}
      />
      <Bloom
        cx={180 + ex}
        cy={1000 + ey}
        r={720}
        image={LIGHT_EMBER_BLOOM}
        opacity={m * intensity}
      />
      <Bloom
        cx={1500 + ax}
        cy={60 + ay}
        r={900}
        image={LIGHT_ACCENT_BLOOM}
        opacity={m * intensity}
      />
      {grain && (
        <AbsoluteFill
          style={{
            backgroundImage: GRAIN_URI,
            backgroundSize: '220px 220px',
            opacity: 0.5,
            mixBlendMode: 'overlay',
          }}
        />
      )}
      {children}
    </AbsoluteFill>
  );
};
