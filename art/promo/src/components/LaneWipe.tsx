// Signature transition. Six horizontal bands (the sail's six rows: equal height, gap = 16/60 of
// a band) sweep left→right in a staircase, cover the whole frame at the cut, then carry on off
// to the right to reveal the next scene. Band 0 has a 6 px ember leading edge. 18 frames,
// centred on the cut: nothing is drawn before `at - 9` or from `at + 9`.
import React from 'react';
import {AbsoluteFill, Easing, useVideoConfig} from 'remotion';
import {EMBER, alpha} from '../theme';
import {usePalette} from '../theme-context';
import {useDesignFrame} from '../time';

export interface LaneWipeProps {
  /** Cut frame, on the timeline the wipe is mounted in. */
  at: number;
  /** Band colour: the next scene's canvas. Default the film's canvas (#0C0E13 on dark). */
  color?: string;
  /** Horizontal offset between consecutive bands, as a fraction of the width. Default 0.16. */
  stagger?: number;
  /** Frame override (defaults to useDesignFrame()). */
  frame?: number;
}

/** Frames before (and after) the cut. */
export const LANE_WIPE_HALF = 9;
const BANDS = 6;
const GAP_RATIO = 16 / 60;

// Entry accelerates into the cover; exit leaves fast and settles, so the sweep reads as one
// continuous pass.
const easeIn = Easing.bezier(0.4, 0.05, 0.75, 0.6);
const easeOut = Easing.bezier(0.25, 0.4, 0.6, 0.95);

interface Geometry {
  y: number[];
  h: number;
  g: number;
}

const geometry = (height: number): Geometry => {
  const h = height / (BANDS + (BANDS - 1) * GAP_RATIO);
  const g = h * GAP_RATIO;
  return {y: Array.from({length: BANDS}, (_, i) => i * (h + g)), h, g};
};

/** Leading (entry) and trailing (exit) edges of band i at time t (frames from the cut). */
const edges = (
  t: number,
  i: number,
  W: number,
  D: number,
  lag: number,
): {left: number; right: number} => {
  const span = W + (BANDS - 1) * D + lag;
  if (t < 0) {
    const p = Math.min(1, Math.max(0, (t + LANE_WIPE_HALF) / LANE_WIPE_HALF));
    return {left: -Infinity, right: span * easeIn(p) - i * D};
  }
  const q = Math.min(1, Math.max(0, t / LANE_WIPE_HALF));
  return {left: span * easeOut(q) - i * D - lag, right: Infinity};
};

/** A horizontal strip from x0 to x1 with feathered (motion-blurred) ends. */
const Strip: React.FC<{
  y: number;
  h: number;
  x0: number;
  x1: number;
  color: string;
  featherL: number;
  featherR: number;
  W: number;
}> = ({y, h, x0, x1, color, featherL, featherR, W}) => {
  const a = Math.max(x0 - featherL / 2, -featherL - 4);
  const b = Math.min(x1 + featherR / 2, W + featherR + 4);
  if (b <= a || b < 0 || a > W) return null;
  const w = b - a;
  const fl = Number.isFinite(x0) ? Math.min(featherL, w / 2) : 0;
  const fr = Number.isFinite(x1) ? Math.min(featherR, w / 2) : 0;
  const clear = alpha(color, 0);
  return (
    <div
      style={{
        position: 'absolute',
        top: y,
        height: h,
        left: a,
        width: w,
        backgroundImage:
          `linear-gradient(to right, ${fl > 0 ? clear : color} 0px, ${color} ${fl}px, ` +
          `${color} ${w - fr}px, ${fr > 0 ? clear : color} ${w}px)`,
      }}
    />
  );
};

export const LaneWipe: React.FC<LaneWipeProps> = ({
  at,
  color: colorProp,
  stagger = 0.16,
  frame: f,
}) => {
  const current = useDesignFrame();
  const canvas = usePalette().canvas;
  const color = colorProp ?? canvas;
  const {width: W, height: H} = useVideoConfig();
  const frame = f ?? current;
  const t = frame - at;
  if (t < -LANE_WIPE_HALF || t >= LANE_WIPE_HALF) return null;

  const {y, h, g} = geometry(H);
  const D = W * stagger;
  // Gaps close this far behind the bands' edges, so the lanes read as separate rows near the edge.
  const lag = D * 0.45;
  const e = (tt: number) => Array.from({length: BANDS}, (_, i) => edges(tt, i, W, D, lag));
  const now = e(t);
  const prev = e(t - 1);
  // Edges stay crisp; a hair of feather (≤ 3 px) only anti-aliases fast-moving edges.
  const blur = (a: number, b: number) =>
    Number.isFinite(a) && Number.isFinite(b) ? Math.min(3, Math.abs(a - b) * 0.01) : 0;

  const strips: React.ReactNode[] = [];
  for (let i = 0; i < BANDS; i++) {
    strips.push(
      <Strip
        key={`b${i}`}
        y={y[i]}
        h={h}
        x0={now[i].left}
        x1={now[i].right}
        color={color}
        featherL={blur(now[i].left, prev[i].left)}
        // band 0's leading edge stays crisp under its ember line
        featherR={i === 0 ? 0 : blur(now[i].right, prev[i].right)}
        W={W}
      />,
    );
    if (i < BANDS - 1) {
      // Gap j is covered once both neighbouring bands have passed it by `lag`.
      const x0 = Math.max(now[i].left, now[i + 1].left) + lag;
      const x1 = Math.min(now[i].right, now[i + 1].right) - lag;
      const p0 = Math.max(prev[i].left, prev[i + 1].left) + lag;
      const p1 = Math.min(prev[i].right, prev[i + 1].right) - lag;
      strips.push(
        <Strip
          key={`g${i}`}
          y={y[i] + h - 0.5}
          h={g + 1}
          x0={x0}
          x1={x1}
          color={color}
          featherL={blur(x0, p0)}
          featherR={blur(x1, p1)}
          W={W}
        />,
      );
    }
  }

  // Ember leading edge on band 0 while it enters.
  const r0 = now[0].right;
  const showEmber = t < 0 && Number.isFinite(r0) && r0 > 0 && r0 < W + 8;
  return (
    <AbsoluteFill style={{pointerEvents: 'none', overflow: 'hidden'}}>
      {strips}
      {showEmber && (
        <>
          <div
            style={{
              position: 'absolute',
              top: y[0],
              height: h,
              left: r0 - 40,
              width: 40,
              background:
                'linear-gradient(to right, rgba(224,72,43,0) 0%, rgba(240,110,60,0.28) 100%)',
            }}
          />
          <div
            style={{
              position: 'absolute',
              top: y[0],
              height: h,
              left: r0 - 6,
              width: 6,
              backgroundImage: `linear-gradient(180deg, ${EMBER.from}, ${EMBER.to})`,
              boxShadow: `0 0 18px 2px rgba(255,140,70,0.45)`,
            }}
          />
        </>
      )}
    </AbsoluteFill>
  );
};
