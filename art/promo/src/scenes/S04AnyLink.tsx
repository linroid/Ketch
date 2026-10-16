// S04 Any link (global 540–690 at the time of writing, 150 frames). Copy left, six link-type chips
// drop in one per beat (a queue), and the Add sheet with a torrent's file list (7 of 9 files
// chosen) slides in on the right as a framed panel: the sheet itself, cropped out of the real
// window, with the window frame's rim and shadow and the sheet's own corner radius. Cut in from S03
// (soft): the copy and the panel start before the scene does (negative delays), so on frame 0 the
// copy is mostly up and the sheet has all but landed (within ~9 px, settled by 13). Cut out to S05
// (hard): the copy and the chips leave first, gone on 148; the panel stays at full through the last
// frame, so the cut lands on the sheet.
//
// Copy (both locales) lives in ./copy/S04.ts.
//
// Local timing (LEN = 150, from timing.ts):
//   -11–33  eyebrow, headline, sub (word by word; the sub starts on 5)
//   -13–-1  panel fades up; -11–13 it slides in from the right (140 px)
//   0–150   panel's slow push 1 → 1.03 about its right edge, so it never crosses the safe line
//   30–106  chips drop in on the beats 30, 45, 60, 75, 90, 105
//   138–148 copy and chips leave (EXIT = LEN − 12); the panel stays
import React from 'react';
import {AbsoluteFill} from 'remotion';
import {useAssets, type NormRect} from '../assets';
import {Background} from '../components/Background';
import {objectEnterStyle} from '../components/Chip';
import {TextBlock} from '../components/Text';
import {WindowFrame} from '../components/WindowFrame';
import {useCopy, useLocale} from '../locale';
import {useDesignFrame} from '../time';
import {BEAT, scene} from '../timing';
import {S04_COPY, LINK_KINDS, type LinkKind} from './copy/S04';
import {
  CropView,
  IconPill,
  enterP,
  fadeP,
  insetRect,
  layoutFor,
  toFractions,
  type IconName,
  type LocaleOverrides,
} from './parts/b-kit';

// ---------------------------------------------------------------------------------------------
// Assets

/** The still: the Add sheet over the window, choosing a torrent's files (`intake-torrent-dark`). */
const STILL = 'intake-torrent';

/**
 * LAYOUT: where the Add sheet is on the still, in fractions, used until the manifest records
 * `bounds.sheet` (PromoSnapshots.kt, AssetBounds.IntakeTorrent). Measured by the pipeline on the
 * English still: x 640–1920, y 144–1263 of 2560 × 1600, its 2 px top highlight included.
 */
const SHEET_FALLBACK: NormRect = {x: 0.25, y: 0.09, w: 0.5, h: 0.6994};
/**
 * Reference px (px at 2 px per dp, see assetRefScale) the panel's crop stays inside the sheet on
 * every side, so none of the dimmed window behind it shows. The panel takes the cropped rect's
 * aspect.
 */
const SHEET_INSET = 3;
/**
 * LAYOUT: the sheet's corner radius in reference px (px at 2 px per dp), minus SHEET_INSET (so
 * the frame's rounded clip stays inside the sheet's own corners). Measured on the still at 2 px
 * per dp (the app's 20 dp `shapes.xl` renders ~35 px there); retune by zooming into a render's
 * top corners.
 */
const SHEET_RADIUS = 33;

// ---------------------------------------------------------------------------------------------
// Chips

/** Each chip's icon; the order (and the labels) come from the copy. */
const LINK_ICONS: Record<LinkKind, IconName> = {
  http: 'globe',
  ftp: 'server',
  bittorrent: 'swarm',
  magnet: 'magnet',
  torrentFile: 'file',
  stream: 'stream',
};

// ---------------------------------------------------------------------------------------------
// Layout

/** Text column and chip column, laid out on the English copy; see TEXT_OVERRIDES. */
interface TextLayout {
  /** TextBlock width: the column left of the sheet panel (its left edge lands near x 820–840). */
  width: number;
  /** LAYOUT: the sub's column: two lines in English ("…engine, / with a choice of files."). */
  subWidth: number;
  /**
   * LAYOUT: top of the chip column, about 60 px under the sub's last line (eyebrow, two
   * headline lines, two sub lines end near y 445 in English). Retune when the copy gains or
   * loses a line.
   */
  chipTop: number;
}

const TEXT_LAYOUT: TextLayout = {width: 660, subWidth: 600, chipTop: 506};

/**
 * LAYOUT: per-locale changes to TEXT_LAYOUT. Chinese headlines are taller (CJK_TYPE line-height
 * 1.15 against 1.02: +22 px over two lines), so the chips move down to keep the same gap.
 */
const TEXT_OVERRIDES: LocaleOverrides<TextLayout> = {
  'zh-Hans': {chipTop: 528},
};

const CHIP_GAP = 16;
const CHIP_SIZE = 24;

/** Panel height; its width follows the crop's aspect. Right edge on the safe line. */
const PANEL_H = 840;
const PANEL_TOP = 120;
const PANEL_RIGHT = 1800;

// ---------------------------------------------------------------------------------------------
// Timing

/** Scene length (frames). */
const LEN = scene('S04').len;
/** Copy and chips leave from here, gone on LEN − 2; the cut lands on the sheet alone. */
const EXIT = LEN - 12;
/** The copy starts before the cut, so it is mostly up on frame 0. */
const TEXT_AT = -11;
/** Panel slide: starts before the cut and has all but landed on frame 0. */
const PANEL_AT = -11;
const PANEL_SLIDE = 24;
/** First chip; one chip per beat after it. */
const CHIP_AT = 30;

export const S04AnyLink: React.FC = () => {
  const frame = useDesignFrame();
  const copy = useCopy(S04_COPY);
  const text = layoutFor(useLocale(), TEXT_LAYOUT, TEXT_OVERRIDES);
  const {assetRefSize, assetBoundsRef, themed} = useAssets();
  const still = themed(STILL);

  const slide = enterP(frame, PANEL_AT, PANEL_SLIDE);
  const fade = fadeP(frame, PANEL_AT - 2, 12);
  const push = 1 + 0.03 * fadeP(frame, 0, LEN);

  // The panel shows the sheet as this locale's still has it, inset so only the sheet shows. In
  // reference px, the unit of SHEET_INSET and SHEET_RADIUS, whatever the still's density.
  const src = assetRefSize(still);
  const crop = insetRect(assetBoundsRef(still, 'sheet', SHEET_FALLBACK), SHEET_INSET);
  const panelW = Math.round((PANEL_H * crop.w) / crop.h);
  const scale = PANEL_H / crop.h;

  return (
    <AbsoluteFill>
      <Background seed="s04" />
      <TextBlock
        eyebrow={copy.eyebrow}
        headline={copy.headline}
        sub={copy.sub}
        width={text.width}
        subWidth={text.subWidth}
        delay={TEXT_AT}
        exitAt={EXIT}
      />
      <div
        style={{
          position: 'absolute',
          left: 120,
          top: text.chipTop,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'flex-start',
          gap: CHIP_GAP,
        }}
      >
        {LINK_KINDS.map((kind, i) => {
          const e = objectEnterStyle(frame, CHIP_AT + i * BEAT, EXIT, 'drop');
          return (
            <IconPill
              key={kind}
              label={copy.links[kind]}
              icon={LINK_ICONS[kind]}
              size={CHIP_SIZE}
              style={{...e, transformOrigin: 'left center'}}
            />
          );
        })}
      </div>
      <div
        style={{
          position: 'absolute',
          left: PANEL_RIGHT - panelW,
          top: PANEL_TOP,
          opacity: fade,
          transform: `translate3d(${((1 - slide) * 140).toFixed(2)}px, 0, 0) scale(${push.toFixed(4)})`,
          transformOrigin: '100% 50%',
        }}
      >
        <WindowFrame width={panelW} height={PANEL_H} radius={SHEET_RADIUS * scale}>
          <CropView
            name={still}
            spec={{focus: toFractions(crop, src)}}
            width={panelW}
            height={PANEL_H}
          />
        </WindowFrame>
      </div>
    </AbsoluteFill>
  );
};
