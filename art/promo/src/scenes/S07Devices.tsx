// S07 All your devices (global 1260–1440 at the time of writing, 180 frames). Copy top left, the
// All devices window rises from the bottom right, and the three device pills it lists (This Mac,
// Studio, Home server) send thin lines that converge on the window's "All devices" row, with small
// dots flowing along them. Cut in from S06 (soft): copy and window are already entering on
// frames 0–2. Cut out to S08 (hard): only the copy leaves, gone on 176; the window, pills,
// lines and dots stay at full through the last frame, so the cut lands on them.
//
// Copy (both locales) and the device names live in ./copy/S07.ts.
//
// Local timing (LEN = 180, from timing.ts):
//   -3–50   eyebrow (from -3, so it shows on frame 0), headline, sub (word by word)
//   0–28    window rises 90 px and fades up (from -2); 20–180 slow push 1 → 1.025 about its
//           right edge, so its right edge stays on the safe line
//   30–56   device pills rise in, 5 frames apart
//   40–72   lines draw from each pill to the node (22 frames each, 5 apart); node lights at 58
//   62–180  dots flow along the lines toward the window
//   166–176 the copy leaves (EXIT = LEN − 14); everything else stays
import React from 'react';
import {AbsoluteFill} from 'remotion';
import {evolvePath, getLength, getPointAtLength} from '@remotion/paths';
import {useAssets} from '../assets';
import {Background} from '../components/Background';
import {objectEnterStyle} from '../components/Chip';
import {TextBlock, TextRuns} from '../components/Text';
import {WindowFrame} from '../components/WindowFrame';
import {easeStandard, progress} from '../easing';
import {useCopy} from '../locale';
import {FONT, TYPE, alpha} from '../theme';
import {usePalette, useThemed} from '../theme-context';
import {useDesignFrame} from '../time';
import {scene} from '../timing';
import {DEVICE_IDS, S07_COPY, S07_SHARED, type DeviceId} from './copy/S07';
import {CropView, Icon, enterP, fadeP, type IconName} from './parts/b-kit';

// ---------------------------------------------------------------------------------------------
// Assets

/** The All devices view, with its Device column (`all-devices-dark`, in the film's theme). */
const WINDOW_STILL = 'all-devices';

/** A device pill's fill and the depth of its drop shadow, on each canvas. */
const PILL_LOOK = {
  dark: {fill: alpha('#FFFFFF', 0.06), rim: alpha('#FFFFFF', 0.1), drop: 0.8},
  light: {fill: alpha('#FFFFFF', 0.72), rim: 'rgba(20,26,38,0.10)', drop: 0.16},
};

/**
 * LAYOUT: where the lines converge, as a fraction of the all-devices-dark still's height: the
 * centre of the sidebar's "All devices" row, at the window's left edge. Measured on the English
 * still (the manifest records no bounds for this screen; the Chinese row sits 4 px lower at 2 px
 * per dp, under 2 px on screen); retune by reading the row's pixel band if the sidebar changes.
 */
const NODE_Y = 0.304;

// ---------------------------------------------------------------------------------------------
// Layout

/** Right edge of the window: the safe line. */
const RIGHT = 1800;
/** The window's right edge stays on RIGHT while it pushes in (about that edge). */
const WIN = {y: 492, w: 1170};
/**
 * Device pills. LAYOUT: `w` fits the longest name ("Home server" at 22 px) with its icon and
 * the port dot; widen it if a locale's name for this Mac runs longer.
 */
const PILL = {x: 120, w: 236, h: 52, gap: 20, size: 22};
/** LAYOUT: copy column; the sub breaks into two lines. */
const TEXT = {width: 1100, subWidth: 760};
/** Each device's icon (its kind of machine, as the window's sidebar shows it). */
const DEVICE_ICONS: Record<DeviceId, IconName> = {
  thisMac: 'laptop',
  studio: 'desktop',
  homeServer: 'rack',
};

// ---------------------------------------------------------------------------------------------
// Timing

/** Scene length (frames). */
const LEN = scene('S07').len;
/** The copy leaves from here, gone on LEN − 4; nothing else leaves (hard cut to S08). */
const EXIT = LEN - 14;
const PILLS_AT = 30;
const PILL_STAGGER = 5;
const LINES_AT = 40;
const LINE_LEN = 22;
/** Frames a dot takes from a pill to the window. */
const FLOW_PERIOD = 46;

export const S07Devices: React.FC = () => {
  const frame = useDesignFrame();
  const copy = useCopy(S07_COPY);
  const {assetSize, themed} = useAssets();
  const p = usePalette();
  const pill = useThemed(PILL_LOOK);
  const windowStill = themed(WINDOW_STILL);
  const deviceName = (id: DeviceId): string =>
    id === 'thisMac' ? copy.thisMac : S07_SHARED.deviceNames[id];

  const src = assetSize(windowStill);
  const winH = (WIN.w * src.height) / src.width;
  const NY = WIN.y + NODE_Y * winH;

  const rise = enterP(frame, 0, 28);
  const winFade = fadeP(frame, -2, 12);
  const push = 1 + 0.025 * fadeP(frame, 20, LEN - 20);
  /** The window's left edge at the node row, where the lines converge (it moves with the push). */
  const NX = RIGHT - WIN.w * push;

  const pillsTop = NY - (DEVICE_IDS.length * PILL.h + (DEVICE_IDS.length - 1) * PILL.gap) / 2;
  const port = (i: number) => ({
    x: PILL.x + PILL.w - 22,
    y: pillsTop + i * (PILL.h + PILL.gap) + PILL.h / 2,
  });
  const paths = DEVICE_IDS.map((_, i) => {
    const p = port(i);
    const dx = NX - p.x;
    return `M ${p.x} ${p.y} C ${p.x + dx * 0.42} ${p.y}, ${NX - dx * 0.5} ${NY}, ${NX - 8} ${NY}`;
  });
  const nodeOn = fadeP(frame, LINES_AT + LINE_LEN - 4, 10);

  return (
    <AbsoluteFill>
      <Background seed="s07" />
      <TextBlock
        eyebrow={copy.eyebrow}
        headline={copy.headline}
        sub={copy.sub}
        width={TEXT.width}
        subWidth={TEXT.subWidth}
        delay={-3}
        exitAt={EXIT}
      />

      {/* the All devices window, bleeding off the bottom edge */}
      <div
        style={{
          position: 'absolute',
          left: RIGHT - WIN.w,
          top: WIN.y,
          opacity: winFade,
          transform: `translate3d(0, ${((1 - rise) * 90).toFixed(2)}px, 0) scale(${push.toFixed(5)})`,
          transformOrigin: `${WIN.w}px ${(NY - WIN.y).toFixed(1)}px`,
        }}
      >
        <WindowFrame width={WIN.w} height={winH}>
          <CropView
            name={windowStill}
            spec={{focus: {x: 0, y: 0, w: 1, h: 1}}}
            width={WIN.w}
            height={winH}
          />
        </WindowFrame>
      </div>

      {/* lines, flowing dots and the node */}
      <svg width={1920} height={1080} style={{position: 'absolute', inset: 0, overflow: 'visible'}}>
        <defs>
          <linearGradient
            id="s07-line"
            x1={port(0).x}
            y1="0"
            x2={NX}
            y2="0"
            gradientUnits="userSpaceOnUse"
          >
            <stop offset="0" stopColor={alpha(p.accentText, 0.25)} />
            <stop offset="1" stopColor={alpha(p.accentText, 0.75)} />
          </linearGradient>
        </defs>
        {paths.map((d, i) => {
          const draw = progress(frame, LINES_AT + i * PILL_STAGGER, LINE_LEN, easeStandard);
          if (draw <= 0) return null;
          const evo = evolvePath(draw, d);
          return (
            <path
              key={`l${i}`}
              d={d}
              fill="none"
              stroke="url(#s07-line)"
              strokeWidth={1.6}
              strokeLinecap="round"
              strokeDasharray={evo.strokeDasharray}
              strokeDashoffset={evo.strokeDashoffset}
            />
          );
        })}
        {paths.map((d, i) => {
          const start = LINES_AT + i * PILL_STAGGER + LINE_LEN;
          if (frame < start) return null;
          const len = getLength(d);
          return [0, 0.5].map((phase) => {
            const t = ((((frame - start) / FLOW_PERIOD + phase + i * 0.13) % 1) + 1) % 1;
            const pt = getPointAtLength(d, t * len) ?? {x: NX, y: NY};
            const a = Math.min(1, t / 0.15, (1 - t) / 0.15) * fadeP(frame, start, 10);
            return (
              <g key={`d${i}-${phase}`} opacity={a}>
                <circle cx={pt.x} cy={pt.y} r={6} fill={alpha(p.accentText, 0.18)} />
                <circle cx={pt.x} cy={pt.y} r={2.4} fill={p.accentText} />
              </g>
            );
          });
        })}
        {nodeOn > 0 && (
          <g opacity={nodeOn}>
            {/* on the window's rim, clear of the sidebar's All devices avatars */}
            <circle cx={NX - 6} cy={NY} r={14} fill={alpha(p.accentFill, 0.22)} />
            <circle cx={NX - 6} cy={NY} r={6} fill={p.accentText} />
          </g>
        )}
      </svg>

      {/* device pills */}
      {DEVICE_IDS.map((id, i) => {
        const e = objectEnterStyle(frame, PILLS_AT + i * PILL_STAGGER, undefined, 'rise');
        return (
          <div
            key={id}
            style={{
              position: 'absolute',
              left: PILL.x,
              top: pillsTop + i * (PILL.h + PILL.gap),
              width: PILL.w,
              height: PILL.h,
              borderRadius: 999,
              background: pill.fill,
              boxShadow: `inset 0 0 0 1px ${pill.rim}, 0 12px 28px -16px ${alpha(p.shade, pill.drop)}`,
              display: 'flex',
              alignItems: 'center',
              gap: 12,
              padding: '0 18px',
              boxSizing: 'border-box',
              ...TYPE.chip,
              fontFamily: FONT.sans,
              fontSize: PILL.size,
              color: p.primary,
              whiteSpace: 'nowrap',
              ...e,
              transformOrigin: 'left center',
            }}
          >
            <Icon name={DEVICE_ICONS[id]} size={24} style={{color: p.accentText}} />
            <span style={{opacity: 0.94, flex: 1}}>
              <TextRuns text={deviceName(id)} />
            </span>
            <span
              style={{
                width: 8,
                height: 8,
                borderRadius: 4,
                background: p.accentText,
                boxShadow: `0 0 0 4px ${alpha(p.accentText, 0.16)}`,
                opacity: fadeP(frame, LINES_AT + i * PILL_STAGGER - 2, 8),
              }}
            />
          </div>
        );
      })}
    </AbsoluteFill>
  );
};
