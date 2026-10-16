// The Ketch sail glyph (path data from public/brand/icon.svg, 1024 coordinates), animated like a
// download. Segment k's fill starts at `start` + trackLead + k × stagger in both animated modes
// (frames 6, 9 … 33 from `start`, main rows top→bottom, then mizzen), where the music's sparkle
// bells land (audio/score.py, picture ties: SAIL_TIMING.trackLead and .stagger, SAIL_SEGMENTS).
//
//   'build'   each segment shows a faint track, then fills left→right; row 0 fills with ember,
//             the rest white at lane alphas. The hull rises in last, then the lanes settle to
//             the real mark (main 1.0, mizzen 0.65). The Kit demo.
//   'refill'  starts as the finished mark (so frame `start` is the logo). Each segment drains
//             right→left over drainLen to its floor (REFILL_FLOOR of its own colour, so every row
//             stays readable and nothing blinks), is empty on its fill frame, and refills
//             left→right over refillLen; then a completion glint (a pale, slanted band of light,
//             after the app's lane strips) crosses it over sheenLen. Row 0 refills with ember.
//             S01, the film's opening.
//   'static'  the finished mark.
import React from 'react';
import {easeDecelerate, easeInOut, easeStandard, progress} from '../easing';
import {EMBER} from '../theme';
import {useTheme} from '../theme-context';
import {useDesignFrame} from '../time';

interface Segment {
  d: string;
  x0: number;
  x1: number;
  y0: number;
  y1: number;
  mizzen: boolean;
}

const seg = (d: string, x0: number, x1: number, y0: number, mizzen = false): Segment => ({
  d,
  x0,
  x1,
  y0,
  y1: y0 + 60,
  mizzen,
});

/**
 * The mizzen's top row, kept out of line so its seg() call fits on one line like the others. (The
 * music counts the segments by their `seg(` calls and the mizzen rows by their `true` argument,
 * on one line or wrapped: audio/score.py PICTURE_TIES, SAIL_MIZZEN_ROWS.)
 */
const MIZZEN_TOP = 'M611.1,380.2Q632.6,405.5 652.1,432L604,432L604,382.8Q604,372 611.1,380.2Z';

/** Fill order: main rows top→bottom, then mizzen rows. */
export const SAIL_SEGMENTS: Segment[] = [
  seg('M325.2,228.5Q346.4,253.9 366.4,280L318,280L318,231Q318,220 325.2,228.5Z', 318, 366.4, 220),
  seg('M318,296L378.5,296Q400.6,325.5 421,356L318,356L318,296Z', 318, 421, 296),
  seg('M318,372L431.6,372Q450.9,401.6 468.8,432L318,432L318,372Z', 318, 468.8, 372),
  seg('M318,448L478.1,448Q495,477.6 510.5,508L318,508L318,448Z', 318, 510.5, 448),
  seg('M318,524L518.6,524Q533.3,553.6 546.8,584L318,584L318,524Z', 318, 546.8, 524),
  seg('M318,600L553.7,600Q566.5,629.6 578,660L318,660L318,600Z', 318, 578, 600),
  seg(MIZZEN_TOP, 604, 652.1, 372, true),
  seg('M604,448L663.7,448Q684.3,477.3 702.7,508L604,508L604,448Z', 604, 702.7, 448, true),
  seg('M604,524L712,524Q728.8,553.4 743.5,584L604,584L604,524Z', 604, 743.5, 524, true),
  seg('M604,600L751,600Q764.4,629.4 776,660L604,660L604,600Z', 604, 776, 600, true),
];

export const SAIL_HULL =
  'M241.2,686L789.9,686Q816,686 798.8,705.1C742.2,760.1 651,781 530.7,781C409.9,781 313.9,766.4 232.7,706.2Q208,686 241.2,686Z';

/** Lane alphas while building (mizzen × 0.65). */
export const LANE_ALPHAS = [1, 0.72, 0.88, 0.6, 0.94, 0.66, 0.8, 0.54];

/** The mark's colour in each tone (SailLogoProps.tone). */
const TONES = {white: '#FFFFFF', brand: '#EE5F2F', ink: '#141A26'} as const;

/** Glyph box in icon coordinates (a little padding around the mark). */
const VB = {x: 214, y: 214, w: 600, h: 572};
/** width / height of the rendered glyph box. */
export const SAIL_ASPECT = VB.w / VB.h;

/** Build timeline (frames from `start`). */
export const SAIL_TIMING = {
  stagger: 3,
  trackLead: 6,
  fillLen: 18,
  hullAt: 38,
  hullLen: 16,
  settleAt: 50,
  settleLen: 12,
  /** Every segment and the hull are complete. */
  builtAt: 54,
  /**
   * 'refill': frames a segment takes to drain right→left to its floor (easeInOut), ending on its
   * fill frame, so the drain starts drainLen frames before the bell and the refill on it.
   */
  drainLen: 4,
  /** 'refill': frames a segment takes to refill from its floor (easeStandard). */
  refillLen: 10,
  /** 'refill': frames the completion glint takes to cross a segment once it has refilled. */
  sheenLen: 10,
} as const;

/** 'refill': frames from `start` until the last segment's glint has crossed it (53). */
export const SAIL_REFILL_END =
  SAIL_TIMING.trackLead +
  (SAIL_SEGMENTS.length - 1) * SAIL_TIMING.stagger +
  SAIL_TIMING.refillLen +
  SAIL_TIMING.sheenLen;

/**
 * 'refill': what is left of a segment while it is empty, as a fraction of its own alpha in its
 * own colour (ember on row 0): the lane dims instead of vanishing, so the mark always reads.
 */
const REFILL_FLOOR = 0.32;
/**
 * The completion glint: a slanted band of pale warm light. It brightens the grey mizzen and the
 * ember row and warms the white lanes, a gleam rather than a darker stripe. Width and slant in
 * icon units (about 7 and 5 px at S01's size), the same on every row.
 */
const SHEEN_COLOR = '#FFE7C4';
const SHEEN_ALPHA = 0.8;
const SHEEN_WIDTH = 26;
const SHEEN_SLANT = 18;

/** The glint's band on segment `s` at position `at` (0: left of the lane, 1: past its right). */
const glint = (s: Segment, at: number): string => {
  const x = s.x0 - SHEEN_WIDTH - SHEEN_SLANT + (s.x1 - s.x0 + SHEEN_WIDTH + SHEEN_SLANT) * at;
  const pts = [
    [x, s.y1],
    [x + SHEEN_SLANT, s.y0],
    [x + SHEEN_SLANT + SHEEN_WIDTH, s.y0],
    [x + SHEEN_WIDTH, s.y1],
  ];
  return pts.map(([px, py]) => `${px.toFixed(1)},${py}`).join(' ');
};

export interface SailLogoProps {
  /** Frame at which the build starts. Default 0. */
  start?: number;
  /**
   * 'build' builds the mark from `start`; 'refill' refills the finished mark's segments from
   * `start`; 'static' draws the finished mark. Default 'build'.
   */
  mode?: 'build' | 'refill' | 'static';
  /** Rendered height of the glyph box in px (width = height × SAIL_ASPECT). Default 420. */
  height?: number;
  /**
   * Mark colour for non-ember lanes and the hull: 'white' on dark (public/brand/icon-white.svg),
   * 'brand' on light (the brand's own mark for light surfaces, public/brand/icon.svg: ember
   * orange), or 'ink' (the light palette's text colour). Default the one for the film's canvas:
   * white in the dark film, brand in the light one. Row 0 is the ember gradient in every tone.
   */
  tone?: 'white' | 'brand' | 'ink';
  /** 'reveal' fills each segment's track like a progress bar; 'scale' grows it with scaleX. */
  fill?: 'reveal' | 'scale';
  /** Ease lane alphas to the real mark after the build. Default true. */
  settle?: boolean;
  /** 'refill': the completion glint after each segment refills. Default true. */
  sheen?: boolean;
  /** 'build': track colour. Default rgba(133,141,156,.12). ('refill' dims each lane instead.) */
  trackColor?: string;
  frame?: number;
  style?: React.CSSProperties;
}

export const SailLogo: React.FC<SailLogoProps> = ({
  start = 0,
  mode = 'build',
  height = 420,
  tone: toneProp,
  fill = 'reveal',
  settle = true,
  sheen = true,
  trackColor = 'rgba(133,141,156,0.12)',
  frame: f,
  style,
}) => {
  const current = useDesignFrame();
  const theme = useTheme();
  const tone = toneProp ?? (theme === 'light' ? 'brand' : 'white');
  const t = mode === 'static' ? 10_000 : (f ?? current) - start;
  const ink = TONES[tone];
  const uid = React.useId().replace(/[^a-zA-Z0-9_-]/g, '');
  const T = SAIL_TIMING;
  const refill = mode === 'refill';
  const settled = refill ? 1 : settle ? progress(t, T.settleAt, T.settleLen, easeInOut) : 0;

  /**
   * Fill (0..1 across the segment), track opacity and glint position (0..1) of segment k. In
   * 'refill' the track is the segment's own floor, drawn right of the fill.
   */
  const stateOf = (k: number): {p: number; track: number; sheenAt: number} => {
    const fillAt = k * T.stagger + T.trackLead;
    if (refill) {
      const done = fillAt + T.refillLen;
      return {
        p:
          t < fillAt
            ? 1 - progress(t, fillAt - T.drainLen, T.drainLen, easeInOut)
            : progress(t, fillAt, T.refillLen, easeStandard),
        track: REFILL_FLOOR,
        sheenAt: sheen ? progress(t, done, T.sheenLen, easeInOut) : 0,
      };
    }
    return {
      p: progress(t, fillAt, T.fillLen, easeStandard),
      track: progress(t, k * T.stagger, 8, easeStandard) * (1 - settled),
      sheenAt: 0,
    };
  };
  const states = SAIL_SEGMENTS.map((_, k) => stateOf(k));

  return (
    <svg
      width={height * SAIL_ASPECT}
      height={height}
      viewBox={`${VB.x} ${VB.y} ${VB.w} ${VB.h}`}
      style={{display: 'block', overflow: 'visible', ...style}}
    >
      <defs>
        <linearGradient id={`${uid}-ember`} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor={EMBER.from} />
          <stop offset="1" stopColor={EMBER.to} />
        </linearGradient>
        {SAIL_SEGMENTS.map((s, k) => {
          const {p} = states[k];
          const filled = (s.x1 - s.x0 + 2) * p;
          return (
            <React.Fragment key={k}>
              <clipPath id={`${uid}-c${k}`}>
                <rect x={s.x0 - 1} y={s.y0 - 2} width={filled} height={s.y1 - s.y0 + 4} />
              </clipPath>
              {/* 'refill': the floor, right of the fill. */}
              <clipPath id={`${uid}-f${k}`}>
                <rect
                  x={s.x0 - 1 + filled}
                  y={s.y0 - 2}
                  width={s.x1 - s.x0 + 2 - filled}
                  height={s.y1 - s.y0 + 4}
                />
              </clipPath>
              <clipPath id={`${uid}-s${k}`}>
                <path d={s.d} />
              </clipPath>
            </React.Fragment>
          );
        })}
      </defs>
      {SAIL_SEGMENTS.map((s, k) => {
        const {p, track, sheenAt} = states[k];
        const lane = LANE_ALPHAS[k % LANE_ALPHAS.length] * (s.mizzen ? 0.65 : 1);
        const final = s.mizzen ? 0.65 : 1;
        const a = k === 0 ? 1 : lane + (final - lane) * settled;
        const paint = k === 0 ? `url(#${uid}-ember)` : ink;
        const front = s.x0 + (s.x1 - s.x0) * p;
        const frontAlpha = p > 0 && p < 1 ? Math.min(1, (1 - p) * 4) * Math.min(1, p * 6) : 0;
        return (
          <g key={k}>
            {track > 0 &&
              (refill ? (
                p < 1 && (
                  <g clipPath={`url(#${uid}-f${k})`}>
                    <path d={s.d} fill={paint} fillOpacity={a * track} />
                  </g>
                )
              ) : (
                <path d={s.d} fill={trackColor} opacity={track} />
              ))}
            {p > 0 &&
              (fill === 'reveal' ? (
                <g clipPath={`url(#${uid}-c${k})`}>
                  <path d={s.d} fill={paint} fillOpacity={a} />
                </g>
              ) : (
                <path
                  d={s.d}
                  fill={paint}
                  fillOpacity={a}
                  transform={`translate(${s.x0} 0) scale(${p} 1) translate(${-s.x0} 0)`}
                />
              ))}
            {frontAlpha > 0 && fill === 'reveal' && (
              <g clipPath={`url(#${uid}-s${k})`}>
                <rect
                  x={front - 3}
                  y={s.y0}
                  width={3}
                  height={s.y1 - s.y0}
                  fill={k === 0 ? '#FFE2B8' : ink}
                  opacity={0.9 * frontAlpha}
                />
              </g>
            )}
            {sheenAt > 0 && sheenAt < 1 && (
              <g clipPath={`url(#${uid}-s${k})`}>
                <polygon points={glint(s, sheenAt)} fill={SHEEN_COLOR} opacity={SHEEN_ALPHA} />
              </g>
            )}
          </g>
        );
      })}
      {(() => {
        const h = refill ? 1 : progress(t, T.hullAt, T.hullLen, easeDecelerate);
        if (h <= 0) return null;
        return (
          <path d={SAIL_HULL} fill={ink} opacity={h} transform={`translate(0 ${(1 - h) * 26})`} />
        );
      })()}
    </svg>
  );
};
