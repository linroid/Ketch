// Directional gradient that keeps text readable over UI. Eased stops, so there is no visible
// edge where it ends.
import React from 'react';
import {AbsoluteFill} from 'remotion';
import {alpha} from '../theme';
import {usePalette} from '../theme-context';

export interface ScrimProps {
  /** Side the scrim is darkest on. Default 'left'. */
  from?: 'left' | 'right' | 'top' | 'bottom';
  /** How far it reaches, as a CSS length along that axis. Default '62%'. */
  extent?: string;
  /** Colour. Default the film's canvas. */
  color?: string;
  /** Opacity at the dark edge. Default 0.92. */
  strength?: number;
  /** Fraction of the extent that stays at full strength before fading. Default 0.35. */
  hold?: number;
  style?: React.CSSProperties;
}

const DIR = {left: 'to right', right: 'to left', top: 'to bottom', bottom: 'to top'} as const;

/** CSS gradient with smoothstep falloff from `strength` to 0. */
export const scrimGradient = (
  from: keyof typeof DIR,
  color: string,
  strength: number,
  hold: number,
): string => {
  const stops: string[] = [
    `${alpha(color, strength)} 0%`,
    `${alpha(color, strength)} ${(hold * 100).toFixed(1)}%`,
  ];
  const n = 8;
  for (let i = 1; i <= n; i++) {
    const t = i / n;
    const s = 1 - t * t * (3 - 2 * t);
    const pos = hold + (1 - hold) * t;
    stops.push(`${alpha(color, strength * s)} ${(pos * 100).toFixed(1)}%`);
  }
  return `linear-gradient(${DIR[from]}, ${stops.join(', ')})`;
};

export const Scrim: React.FC<ScrimProps> = ({
  from = 'left',
  extent = '62%',
  color,
  strength = 0.92,
  hold = 0.35,
  style,
}) => {
  const canvas = usePalette().canvas;
  const horizontal = from === 'left' || from === 'right';
  return (
    <AbsoluteFill style={{pointerEvents: 'none', ...style}}>
      <div
        style={{
          position: 'absolute',
          [from]: 0,
          ...(horizontal
            ? {top: 0, bottom: 0, width: extent}
            : {left: 0, right: 0, height: extent}),
          backgroundImage: scrimGradient(from, color ?? canvas, strength, hold),
        }}
      />
    </AbsoluteFill>
  );
};
