// S03 Parallel lanes (global 360–540 at the time of writing, local 0–179). Continues S02's
// desktop window (in the film's theme) exactly (same framing, clip frame = local + 240, same canvas), then zooms into the
// inspector's Connections lanes while the copy enters in a left column on the canvas.
//
// Local timing:
//   0        identical to S02's last frame (camera s02Rect(240), clip frame 240)
//   2–38     camera zoom into the lanes (easeInOut), then a slow push to the end
//   8–28     the window dissolves into the canvas left of the inspector (a mask, so the copy
//            sits on the canvas, not on dimmed UI)
//   16–34    ... and above the tab row, once the zoom is landing
//   20–70    eyebrow, headline and sub enter (TextBlock, delay 20)
//   90, 105  the two chips, one per beat
//   166–176  text and chips exit (gone from 176); the lanes window stays at full through the
//            last frame (179), so the soft cut into S04 lands on the lanes, never on an empty
//            canvas
//
// Copy (both locales) lives in ./copy/S03.ts; the connection limit comes from src/facts.json.
// The zoom aims at the manifest's `connectionsBlock` bounds of the locale's desktop clip, so it
// follows the app's layout and language.
import React from 'react';
import {AbsoluteFill} from 'remotion';
import {useAssets} from '../assets';
import {Background} from '../components/Background';
import {cameraAt, zoomRect, type CameraKey} from '../components/Camera';
import {Chip} from '../components/Chip';
import {ClipPlayer} from '../components/ClipPlayer';
import {TextBlock} from '../components/Text';
import {easeInOut, easeStandard, progress} from '../easing';
import {useCopy} from '../locale';
import {TEXT_ANCHOR} from '../theme';
import {useDesignFrame} from '../time';
import {desktopClipFrame, scene, softCutExitAt} from '../timing';
import {S03_COPY} from './copy/S03';
import {
  DesktopWindow,
  S02_LEN,
  lanesFadeLeft,
  lanesRect,
  lanesTopGap,
  desktopClip,
  s02Rect,
} from './parts/a-desktop';

const START = scene('S03').start;
const LEN = scene('S03').len;
const ZOOM_AT = 2;
const ZOOM_END = 38;
/**
 * The masks that dissolve everything but the lanes: the left one (the text column) is nearly
 * complete before the copy enters; the top one waits for the zoom to land, so the camera still
 * dives into a whole window.
 */
const MASK_AT = 8;
const MASK_LEN = 20;
const MASK_TOP_AT = 16;
const MASK_TOP_LEN = 18;
const TEXT_AT = 20;
const CHIP_AT = [90, 105] as const;
/**
 * Text and chips leave on the shared 10-frame exit and are gone from 176, four frames before the
 * cut, where `softCutExitAt` would end the exit, so the cut lands on the lanes alone. The window
 * does not leave: it stays at full opacity through the last frame.
 */
const EXIT_AT = softCutExitAt(LEN) - 4;

/**
 * LAYOUT: the text column beside the lanes (viewport px), fitted to the copy's length: `top`
 * centres a two-line headline, a two-line sub (`\n` in the Chinese copy) and the two chips
 * under it on the lanes. Retune `top` when the copy gains or loses a line, and keep the column
 * left of the window's left mask (lanesFadeLeft; check a still of frame 120).
 */
const TEXT = {top: 300, width: 700, subWidth: 600} as const;
/** The chips stack this far under the sub's last line, 12 px apart. */
const CHIP_GAP = 62;

const linear = (t: number) => t;

/** Mask gradient: alpha 1 − amount before `from`, smoothstep up to 1 at `to`. */
const edgeMask = (
  dir: 'to right' | 'to bottom',
  from: number,
  to: number,
  amount: number,
): string => {
  const stops: string[] = [];
  const n = 8;
  for (let i = 0; i <= n; i++) {
    const u = i / n;
    const s = u * u * (3 - 2 * u);
    const a = 1 - amount * (1 - s);
    stops.push(`rgba(0,0,0,${a.toFixed(4)}) ${(from + (to - from) * u).toFixed(1)}px`);
  }
  return `linear-gradient(${dir}, ${stops.join(', ')})`;
};

const maskStyle = (image: string | undefined): React.CSSProperties | undefined =>
  image === undefined ? undefined : {maskImage: image, WebkitMaskImage: image};

export const S03Lanes: React.FC = () => {
  const f = useDesignFrame();
  const copy = useCopy(S03_COPY);
  const assets = useAssets();
  const lanes = lanesRect(assets);
  const keys: CameraKey[] = [
    {at: 0, rect: s02Rect(assets, S02_LEN)},
    {at: ZOOM_AT, rect: s02Rect(assets, S02_LEN + ZOOM_AT), easing: linear},
    // Symmetric ease: the camera accelerates out of S02's slow push and settles (no whip).
    {at: ZOOM_END, rect: lanes, easing: easeInOut},
    {at: LEN, rect: zoomRect(lanes, 1.045), easing: linear},
  ];
  const t = cameraAt(keys, f);
  const mask = progress(f, MASK_AT, MASK_LEN, easeStandard);
  const maskTop = progress(f, MASK_TOP_AT, MASK_TOP_LEN, easeStandard);
  // The window dissolves into the canvas left of the inspector (the text column, viewport px)
  // and above the tab row (source rows, so the edge stays between the Pause / Send to row and
  // the tabs while the camera moves).
  const left = lanesFadeLeft(assets);
  const gap = lanesTopGap(assets);
  const topFrom = t.y + gap.from * t.scale;
  const topTo = t.y + gap.to * t.scale;
  const leftMask = mask > 0 ? edgeMask('to right', left.from, left.to, mask) : undefined;
  const topMask = maskTop > 0 ? edgeMask('to bottom', topFrom, topTo, maskTop) : undefined;
  return (
    <AbsoluteFill>
      {/* S02's canvas, continued */}
      <Background seed="s02" frame={f + S02_LEN} />
      <AbsoluteFill style={maskStyle(leftMask)}>
        <AbsoluteFill style={maskStyle(topMask)}>
          <DesktopWindow t={t}>
            <ClipPlayer name={desktopClip(assets)} frame={desktopClipFrame(START + f)} />
          </DesktopWindow>
        </AbsoluteFill>
      </AbsoluteFill>
      {/* The copy and, under it, the chips, in one column left of the lanes. */}
      <div
        style={{
          position: 'absolute',
          left: TEXT_ANCHOR.x,
          top: TEXT.top,
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
          width={TEXT.width}
          subWidth={TEXT.subWidth}
          inline
        />
        <div
          style={{
            marginTop: CHIP_GAP,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'flex-start',
            gap: 12,
          }}
        >
          {copy.chips.map((label, i) => (
            <Chip key={label} label={label} delay={CHIP_AT[i]} exitAt={EXIT_AT} />
          ))}
        </div>
      </div>
    </AbsoluteFill>
  );
};
