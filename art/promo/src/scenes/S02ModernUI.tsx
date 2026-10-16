// S02 Modern UI (global 120–360 at the time of writing, local 0–239). Text top-left, the desktop
// window rises from the bottom right and pushes in slowly; a diagonal wipe turns the whole frame
// to the other theme (light in the dark film, dark in the light one), the window recolours
// through the four accents, and the same wipe returns to the film's own window S03 starts on.
//
// Local timing (the dark film; the light film's is the same with the themes swapped):
//   0–9      Promo's lane wipe uncovers the scene
//   2–55     eyebrow, headline and sub enter (TextBlock, delay 2; the wipe reveals them rising)
//   4–36     window rises from below right with a perspective tilt (rotateX 14° -> 0,
//            scale .9 -> 1); it pushes in slowly for the whole scene (s02Transform)
//   130–150  diagonal wipe to light: canvas, text and window (desktop-light, clip frame f − 30;
//            the light film shows desktop-dark at frame f)
//   148–212  accent pill; window accents (stills): Signal cuts in at 150, then Harbor, Fathom
//            and Beacon on the next three beats (6-frame crossfades centred on 165, 180, 195)
//   212–222  text and pill exit
//   221–239  the same diagonal wipe back to dark (desktop-dark, clip frame = local frame), so no
//            frame sits at a mid-grey; fully dark on the last frame, as S03 begins
//
// Copy (both locales, the accent names included) lives in ./copy/S02.ts. The accent pill follows
// the sub in the text column, so it sits under the copy whatever its length.
//
// The other theme's window comes from each film's own run of the harness (PromoSnapshots.kt,
// desktopOtherTheme; its SCENE constants follow this file): the dark film's light clip runs
// LIGHT_OFFSET_FRAMES behind the dark one, which the scene reads from the manifest, while the
// light film shows the dark film's own clip, which its light clip runs with (`syncedTo` offset
// 0); each accent still (in the other theme) shows the dark clip's frame from AccentFrames.
// Moving the wipe or ACCENTS here means changing those there and re-rendering (`npm run assets
// -- all --theme all desktopOtherTheme`).
import React from 'react';
import {AbsoluteFill} from 'remotion';
import {useAssets} from '../assets';
import {Background} from '../components/Background';
import {ClipPlayer} from '../components/ClipPlayer';
import {StillImage} from '../components/StillImage';
import {TextBlock} from '../components/Text';
import {easeDecelerate, easeInOut, easeStandard, progress} from '../easing';
import {useCopy} from '../locale';
import {TEXT_ANCHOR, otherMode, type Mode} from '../theme';
import {useTheme} from '../theme-context';
import {useDesignFrame} from '../time';
import {HEIGHT, WIDTH, scene} from '../timing';
import {S02_COPY} from './copy/S02';
import {AccentPill} from './parts/a-accents';
import {DesktopWindow, S02_WINDOW, WindowLayer, desktopClip, s02Transform} from './parts/a-desktop';

const LEN = scene('S02').len;
const TEXT_AT = 2;
const EXIT_AT = 212;
/** Window entrance. */
const RISE_AT = 4;
const RISE_LEN = 32;
/** Diagonal wipe to light. */
const WIPE_AT = 130;
const WIPE_LEN = 20;
/**
 * The other theme's clip time = local frame − its offset. In the dark film the light clip
 * reaches its last frame (119) at 149: the manifest records the offset as the light clip's
 * `syncedTo.offset` (frame k of it shows frame k + offset of the dark one, in the clips' own
 * frames, converted to design frames here). The light film's clip records the dark one it runs
 * with as its own `syncedTo` (offset 0). These, by the film's theme, until the manifest does.
 */
const OTHER_OFFSET_FALLBACK: Record<Mode, number> = {dark: 30, light: 0};
/**
 * Accent stills (in the other theme: `desktop-accent-signal-light` in the dark film) and the
 * frame each one's 6-frame crossfade starts: Signal cuts in as the wipe ends, the others are
 * centred on beats (local 165, 180, 195; global 285, 300, 315 at the time of writing).
 */
const ACCENTS = [
  {accent: 'signal', at: 150},
  {accent: 'harbor', at: 162},
  {accent: 'fathom', at: 177},
  {accent: 'beacon', at: 192},
] as const;
const ACCENT_XFADE = 6;
const PILL_AT = 148;
/** Space between the sub's last line and the accent pill. */
const PILL_GAP = 36;
/** Diagonal wipe back to dark, after the copy has left; complete on the last frame (len − 1). */
const BACK_LEN = 18;
const BACK_AT = LEN - 1 - BACK_LEN;
/** Slant of the wipe edge: the top leads the bottom by this many px. */
const WIPE_SLANT = 440;

/** Opacity of accent still i at frame f (Signal cuts in; the others crossfade). */
const accentOpacity = (f: number, i: number): number =>
  i === 0 ? (f >= ACCENTS[0].at ? 1 : 0) : progress(f, ACCENTS[i].at, ACCENT_XFADE, easeInOut);

/**
 * How far (design frames) the other theme's clip `other` runs behind the film's own `own`: the
 * offset either records against the other in its `syncedTo`.
 */
const otherOffset = (
  {clipInfo, clipToDesignFrames}: ReturnType<typeof useAssets>,
  own: string,
  other: string,
  theme: Mode,
): number => {
  const ahead = clipInfo(other)?.syncedTo;
  if (ahead?.clip === own && ahead.offset !== undefined)
    return clipToDesignFrames(other, ahead.offset);
  const behind = clipInfo(own)?.syncedTo;
  if (behind?.clip === other && behind.offset !== undefined)
    return -clipToDesignFrames(own, behind.offset);
  return OTHER_OFFSET_FALLBACK[theme];
};

/** The other theme's window content: its clip, then the accent stills over it. */
const OtherContent: React.FC<{f: number}> = ({f}) => {
  const assets = useAssets();
  const theme = useTheme();
  const other = assets.themed('desktop', otherMode(theme));
  const offset = otherOffset(assets, desktopClip(assets), other, theme);
  const stills = ACCENTS.map(({accent}) =>
    assets.themed(`desktop-accent-${accent}`, otherMode(theme)),
  );
  const ops = ACCENTS.map((_, i) => accentOpacity(f, i));
  // Skip layers hidden under a fully opaque one.
  let top = -1;
  ops.forEach((o, i) => {
    if (o >= 1) top = i;
  });
  return (
    <>
      {top < 0 && (
        <WindowLayer>
          <ClipPlayer name={other} frame={f - offset} />
        </WindowLayer>
      )}
      {stills.map((still, i) =>
        i < top ? null : (
          <WindowLayer key={still} opacity={ops[i]}>
            <StillImage name={still} />
          </WindowLayer>
        ),
      )}
    </>
  );
};

/**
 * Everything in the scene at local frame f. `canvas` blends the canvas, text and pill toward
 * the other theme; `win` crossfades the window from the film's clip to the other theme's
 * content.
 */
const Layer: React.FC<{f: number; canvas: number; win: number}> = ({f, canvas, win}) => {
  const copy = useCopy(S02_COPY);
  const assets = useAssets();
  const theme = useTheme();
  // The components blend dark → light; the light film's other theme is dark.
  const toLight = (other: number) => (theme === 'dark' ? other : 1 - other);
  const rise = progress(f, RISE_AT, RISE_LEN, easeDecelerate);
  const r = 1 - rise;
  const entrance =
    rise >= 1
      ? undefined
      : {
          transform: [
            `translate3d(${(r * 160).toFixed(2)}px, ${(r * 260).toFixed(2)}px, 0)`,
            `rotateX(${(r * 14).toFixed(3)}deg)`,
            `scale(${(0.9 + 0.1 * rise).toFixed(4)})`,
          ].join(' '),
          origin: `${S02_WINDOW.x + S02_WINDOW.w / 2}px ${S02_WINDOW.y + 300}px`,
          opacity: progress(f, RISE_AT, 12, easeStandard),
        };
  const active = ACCENTS.map((_, i) => {
    const on = i === 0 ? 1 : progress(f, ACCENTS[i].at, ACCENT_XFADE, easeInOut);
    const next = ACCENTS[i + 1];
    const off = next === undefined ? 0 : progress(f, next.at, ACCENT_XFADE, easeInOut);
    return on - off;
  });
  return (
    <AbsoluteFill>
      <Background seed="s02" lightMix={toLight(canvas)} frame={f} />
      <DesktopWindow t={s02Transform(assets, f)} lightMix={toLight(win)} entrance={entrance}>
        {win < 1 && <ClipPlayer name={desktopClip(assets)} frame={f} />}
        {win > 0 && (
          <WindowLayer opacity={win}>
            <OtherContent f={f} />
          </WindowLayer>
        )}
      </DesktopWindow>
      {/* The copy and, under it, the accent pill, in one column at the text anchor. */}
      <div
        style={{
          position: 'absolute',
          left: TEXT_ANCHOR.x,
          top: TEXT_ANCHOR.y,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'flex-start',
        }}
      >
        <TextBlock
          eyebrow={copy.eyebrow}
          headline={copy.headline}
          sub={copy.sub}
          delay={TEXT_AT}
          exitAt={EXIT_AT}
          lightMix={toLight(canvas)}
          frame={f}
          inline
        />
        {f >= PILL_AT && f < EXIT_AT + 12 && (
          <AccentPill
            frame={f}
            lightMix={toLight(canvas)}
            names={copy.accents}
            active={active}
            delay={PILL_AT}
            exitAt={EXIT_AT}
            style={{marginTop: PILL_GAP}}
          />
        )}
      </div>
    </AbsoluteFill>
  );
};

/**
 * Diagonal wipe: `over` is revealed from the left over `under` as t goes 0 -> 1, along an edge
 * that leans so the top leads, with a soft sheen on the edge.
 */
const DiagonalWipe: React.FC<{t: number; under: React.ReactNode; over: React.ReactNode}> = ({
  t,
  under,
  over,
}) => {
  // Wipe edge: x = c at mid-height; it runs 10 px past the frame's top and bottom.
  const c = -WIPE_SLANT / 2 - 40 + (WIDTH + WIPE_SLANT + 80) * t;
  const xTop = c + WIPE_SLANT / 2 + 4;
  const xBottom = c - WIPE_SLANT / 2 - 4;
  const bottom = HEIGHT + 10;
  const clip = `polygon(-10px -10px, ${xTop}px -10px, ${xBottom}px ${bottom}px, -10px ${bottom}px)`;
  const edge = {x1: xTop, y1: -10, x2: xBottom, y2: bottom, stroke: '#FFFFFF'};
  return (
    <AbsoluteFill>
      {under}
      <AbsoluteFill style={{clipPath: clip}}>{over}</AbsoluteFill>
      <svg
        width={WIDTH}
        height={HEIGHT}
        style={{position: 'absolute', inset: 0, overflow: 'visible'}}
      >
        <defs>
          <filter id="s02-sheen" x="-50%" y="-50%" width="200%" height="200%">
            <feGaussianBlur stdDeviation={14} />
          </filter>
        </defs>
        <line {...edge} strokeOpacity={0.22} strokeWidth={26} filter="url(#s02-sheen)" />
        <line {...edge} strokeOpacity={0.55} strokeWidth={1.5} />
      </svg>
    </AbsoluteFill>
  );
};

export const S02ModernUI: React.FC = () => {
  const f = useDesignFrame();
  // The film's own theme (dark in the dark film) and the other one (`canvas`, `win` 1).
  const own = <Layer f={f} canvas={0} win={0} />;
  const other = <Layer f={f} canvas={1} win={1} />;
  // Back to the film's theme: the copy has left, and the same wipe as the way in reveals its
  // canvas and its clip (which S03 continues) over the other theme. Back by the last frame.
  const back = progress(f, BACK_AT, BACK_LEN, easeInOut);
  if (back >= 1) return own;
  if (back > 0) return <DiagonalWipe t={back} under={other} over={own} />;
  // Diagonal wipe to the other theme: its copy of the scene is revealed over the film's own.
  const wipe = progress(f, WIPE_AT, WIPE_LEN, easeInOut);
  if (wipe <= 0) return own;
  if (wipe >= 1) return other;
  return <DiagonalWipe t={wipe} under={own} over={other} />;
};
