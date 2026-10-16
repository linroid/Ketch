// S12 Open source + end card (global 2070–2310, 240 frames at the time of writing). Beat A (local
// 0–99): a centred statement with four chips, already rising as the lane wipe (local 0–8, placed by
// Promo) uncovers it; then six thin lanes under the chips (the sail's six rows, the app's
// Connections lanes) fill like one download and complete just before the end card. Beat B (local 95
// to the end): the end card, one centred stack: the app icon with a soft ember glow, rising as Beat
// A leaves and landing on the final chord (local 105; global 2175 at the time of writing) with the
// wordmark, then the tagline, the repository and the platform line. It holds with a barely visible
// push, then fades to black over the film's last FADE_LEN frames. The hold and the fade follow the
// scene's length (timing.json), so a retime only lengthens or shortens the hold.
//
// Copy (both locales) lives in ./copy/S12.ts. The end card has one address, the repository:
// never linroid.com/Ketch (the owner's request).
import React from 'react';
import {AbsoluteFill, Img, staticFile} from 'remotion';
import {Background} from '../components/Background';
import {ChipRow} from '../components/Chip';
import {Eyebrow, Headline, Sub, TextRuns, hasCJK, riseStyle} from '../components/Text';
import {easeDecelerate, easeInOut, easeStandard, lerpClamp, progress} from '../easing';
import {LOCALES, copyFor, useCopy, useLocale, type Locale} from '../locale';
import {CJK_TYPE, FONT, LIGHT, TYPE} from '../theme';
import {usePalette, useThemed} from '../theme-context';
import {useDesignFrame} from '../time';
import {scene} from '../timing';
import {S12_COPY, S12_SHARED} from './copy/S12';
import {LaneBar, laneBarHeight} from './parts/d-lanes';

// ---------------------------------------------------------------------------------------------
// Timing (scene-local frames)

/** Scene length (timing.json); the end card holds until the fade at its end. */
const LEN = scene('S12').len;

/**
 * The music's final chord, where the end card lands. A tie to the music: audio/score.py reads
 * this literal (PICTURE_TIES, S12_END_CARD_AT) and splits the score here, so change both and run
 * `npm run music`.
 */
const CHORD = 105;

// Beat A: the copy starts under the wipe so it is already rising as the bands leave (local 0–9).
const A_EYEBROW_AT = -6;
const A_HEADLINE_AT = -2;
/** The chips follow the headline closely, so the full row reads for about 2 s. */
const A_CHIPS_AT = 10;
const A_CHIP_STAGGER = 3;
/** The lanes appear after the chips and are all complete just before Beat A leaves. */
const A_LANES_AT = 30;
const A_LANES_DONE = 84;
/**
 * Beat A leaves from the top down, each part gone 10 frames after its exit starts: the eyebrow
 * and the headline, which sit where the end card's icon lands, first; the chips and the lanes,
 * below it, last, while the icon is already rising, so no frame between the beats is empty and
 * the icon never overlaps text.
 */
const A_EXIT_AT = 89;
const A_HEADLINE_EXIT_AT = A_EXIT_AT - 4;
const A_EYEBROW_EXIT_AT = A_EXIT_AT - 6;

// Beat B. The icon starts as Beat A's headline is gone and has settled by the chord; the
// wordmark follows at once, as the chips leave.
const ICON_AT = A_HEADLINE_EXIT_AT + 10;
const WORDMARK_AT = ICON_AT + 3;
const TAGLINE_AT = CHORD;
const URL_AT = CHORD + 11;
const PLATFORMS_AT = CHORD + 17;
/** The film's last frame (local), where the push ends and the picture is black. */
const END = LEN - 1;
/** Fade to black over the last FADE_LEN frames (220–239 at 240 frames), inside the music's fade. */
const FADE_LEN = 20;
const FADE_AT = LEN - FADE_LEN;
/**
 * What the film fades to: black in the dark film; the light film fades into its own canvas, so
 * its end stays light rather than dropping to black.
 */
const FADE_TO = {dark: '#000', light: LIGHT.canvas};

// ---------------------------------------------------------------------------------------------
// Layout (px in the 1920×1080 frame)

/**
 * Chinese closing marks are set in the left half of their em box, so a centred line ending in
 * one ("免费，而且开源。") has half an em of blank on its right and its ink sits left of centre
 * (28 px at 112 px). Pulling the box's right edge in by that half em centres the ink.
 */
const CJK_TRAILING_MARK = /[。，、]$/u;
const centreInk = (text: string): React.CSSProperties =>
  CJK_TRAILING_MARK.test(text) ? {marginRight: '-0.5em'} : {};

interface StackRow {
  /** Height of the row's box (its font size × line height). */
  h: number;
  /** Space below it. */
  gap: number;
}

/**
 * Tops of a vertical stack of rows whose middle sits on `centerY`. Rows are laid out in the
 * order the object lists them; change a size or a gap and the rows below move with it.
 */
const stackTops = <K extends string>(
  rows: Record<K, StackRow>,
  centerY: number,
): Record<K, number> => {
  const list = Object.entries(rows) as [K, StackRow][];
  const height = list.reduce((sum, [, r], i) => sum + r.h + (i < list.length - 1 ? r.gap : 0), 0);
  let y = centerY - height / 2;
  const tops = {} as Record<K, number>;
  for (const [k, r] of list) {
    tops[k] = Math.round(y);
    y += r.h + r.gap;
  }
  return tops;
};

const A_HEADLINE_SIZE = 112;
const A_CHIP_SIZE = 28;
const A_CHIP_GAP = 18;
/** The lanes sit this far under the chips and run exactly as wide as the chip row. */
const A_LANES_BELOW_CHIPS = 63;
const A_LANE = 6;
const A_LANE_GAP = 8;

/**
 * Line height of a headline: Headline gives CJK text CJK_TYPE.headlineLineHeight (Han glyphs
 * fill the em box), so a Chinese headline's box is taller and the rows below it move down.
 */
const headlineLineHeight = (text: string): number =>
  hasCJK(text)
    ? Math.max(TYPE.headline.lineHeight, CJK_TYPE.headlineLineHeight)
    : TYPE.headline.lineHeight;

/**
 * LAYOUT: Beat A's rows (eyebrow, headline, chips, lanes) for one locale's headline, centred a
 * little below the frame's middle (556), so the end card's icon, above the eyebrow's place,
 * clears the headline as it rises. A chip's height mirrors Chip (its line height plus 10/22 of
 * its size above and below); retune it with the component.
 */
const beatALayout = (headline: string) =>
  stackTops(
    {
      eyebrow: {h: Math.round(TYPE.eyebrow.fontSize * TYPE.eyebrow.lineHeight), gap: 26},
      headline: {h: Math.round(A_HEADLINE_SIZE * headlineLineHeight(headline)), gap: 56},
      chips: {
        h: Math.round(A_CHIP_SIZE * TYPE.chip.lineHeight + 2 * ((A_CHIP_SIZE * 10) / 22)),
        gap: A_LANES_BELOW_CHIPS,
      },
      lanes: {h: laneBarHeight(A_LANE, A_LANE_GAP), gap: 0},
    },
    556,
  );

/** Beat A's layout per locale (the headline's script sets its row height). */

const A_LAYOUTS = Object.fromEntries(
  LOCALES.map((l) => [l, beatALayout(copyFor(S12_COPY, l).headline)]),
) as Record<Locale, ReturnType<typeof beatALayout>>;

const ICON = 176;
const WORDMARK_SIZE = 120;
const URL_SIZE = 34;
const PLATFORMS_SIZE = 20;
/** LAYOUT: the tagline's column; the intro's tagline is one line in every locale (./copy/S01.ts). */
const TAGLINE_W = 1300;
/**
 * LAYOUT: the end card, one stack centred a touch above the middle (the glow adds weight above
 * the icon). The tagline sits close under the wordmark; the repository and the platform line
 * form the card's foot. The tagline row is one line of Sub: a tagline that wraps needs twice
 * the height here.
 */
const B_LAYOUT = stackTops(
  {
    icon: {h: ICON, gap: 36},
    wordmark: {h: Math.round(WORDMARK_SIZE * headlineLineHeight(S12_SHARED.wordmark)), gap: 18},
    tagline: {h: Math.round(TYPE.sub.fontSize * TYPE.sub.lineHeight), gap: 56},
    url: {h: Math.round(URL_SIZE * TYPE.sub.lineHeight), gap: 26},
    platforms: {h: Math.round(PLATFORMS_SIZE * TYPE.chip.lineHeight), gap: 0},
  },
  532,
);

export const S12OpenSource: React.FC = () => {
  const frame = useDesignFrame();
  const copy = useCopy(S12_COPY);
  const layoutA = A_LAYOUTS[useLocale()];
  const p = usePalette();
  const fadeTo = useThemed(FADE_TO);
  // The icon's ember glow: softer on the light canvas, where a full one reads as a pink smudge.
  const glowStrength = useThemed({dark: 1, light: 0.7});

  // Beat B: icon pop (scale .86 → 1 with a small rise), glow blooming behind it.
  const iconIn = progress(frame, ICON_AT, 22, easeDecelerate);
  const iconFade = progress(frame, ICON_AT, 10, easeStandard);
  const glow = progress(frame, ICON_AT + 4, 40, easeInOut);
  // Slow, calm push over the hold (≈1.5 %).
  const push = 1 + 0.015 * progress(frame, ICON_AT, END - ICON_AT, easeInOut);

  return (
    <AbsoluteFill style={{fontFamily: FONT.sans}}>
      <Background seed="s12" />

      {frame < A_EXIT_AT + 10 && (
        <AbsoluteFill style={{alignItems: 'center'}}>
          <Eyebrow
            text={copy.eyebrow}
            delay={A_EYEBROW_AT}
            exitAt={A_EYEBROW_EXIT_AT}
            align="center"
            style={{position: 'absolute', top: layoutA.eyebrow}}
          />
          <Headline
            text={copy.headline}
            size={A_HEADLINE_SIZE}
            delay={A_HEADLINE_AT}
            exitAt={A_HEADLINE_EXIT_AT}
            align="center"
            style={{position: 'absolute', top: layoutA.headline, ...centreInk(copy.headline)}}
          />
          {/* The chip row and the lanes under it, in one column as wide as the chip row, so
              the lanes match the chips in any locale. */}
          <div
            style={{
              position: 'absolute',
              top: layoutA.chips,
              display: 'flex',
              flexDirection: 'column',
            }}
          >
            <ChipRow
              labels={[...copy.chips]}
              delay={A_CHIPS_AT}
              stagger={A_CHIP_STAGGER}
              exitAt={A_EXIT_AT}
              size={A_CHIP_SIZE}
              gap={A_CHIP_GAP}
              wrap={false}
            />
            <LaneBar
              start={A_LANES_AT}
              doneAt={A_LANES_DONE}
              width="100%"
              lane={A_LANE}
              gap={A_LANE_GAP}
              style={{
                marginTop: A_LANES_BELOW_CHIPS,
                opacity:
                  progress(frame, A_LANES_AT - 4, 10, easeStandard) *
                  (1 - progress(frame, A_EXIT_AT, 10, easeStandard)),
              }}
            />
          </div>
        </AbsoluteFill>
      )}

      {frame >= ICON_AT && (
        <AbsoluteFill style={{alignItems: 'center', transform: `scale(${push.toFixed(5)})`}}>
          {/* Icon tile with its ember glow */}
          <div style={{position: 'absolute', top: B_LAYOUT.icon, width: ICON, height: ICON}}>
            <div
              style={{
                position: 'absolute',
                left: ICON / 2 - 360,
                top: ICON / 2 - 340,
                width: 720,
                height: 720,
                background:
                  'radial-gradient(circle closest-side, rgba(255,150,80,0.30) 0%, rgba(240,110,60,0.16) 32%, rgba(224,72,43,0.06) 60%, rgba(224,72,43,0) 100%)',
                opacity: glow * glowStrength,
              }}
            />
            <Img
              src={staticFile('brand/icon-app.svg')}
              style={{
                position: 'relative',
                width: ICON,
                height: ICON,
                opacity: iconFade,
                transform: `translate3d(0, ${((1 - iconIn) * 18).toFixed(2)}px, 0) scale(${(0.86 + 0.14 * iconIn).toFixed(4)})`,
              }}
            />
          </div>
          <Headline
            text={S12_SHARED.wordmark}
            size={WORDMARK_SIZE}
            align="center"
            delay={WORDMARK_AT}
            style={{position: 'absolute', top: B_LAYOUT.wordmark}}
          />
          <Sub
            text={copy.tagline}
            delay={TAGLINE_AT}
            align="center"
            width={TAGLINE_W}
            style={{position: 'absolute', top: B_LAYOUT.tagline, ...centreInk(copy.tagline)}}
          />
          <div
            style={{
              position: 'absolute',
              top: B_LAYOUT.url,
              ...TYPE.sub,
              fontWeight: 600,
              fontSize: URL_SIZE,
              letterSpacing: '-0.01em',
              color: p.primary,
              ...riseStyle(frame, URL_AT, undefined, 20),
            }}
          >
            {S12_SHARED.url}
          </div>
          <div
            style={{
              position: 'absolute',
              top: B_LAYOUT.platforms,
              ...TYPE.chip,
              fontSize: PLATFORMS_SIZE,
              letterSpacing: '0.06em',
              color: p.tertiary,
              ...riseStyle(frame, PLATFORMS_AT, undefined, 14),
            }}
          >
            <TextRuns text={copy.platforms} />
          </div>
        </AbsoluteFill>
      )}

      <AbsoluteFill
        style={{
          background: fadeTo,
          opacity: lerpClamp(frame, [FADE_AT - 1, END], [0, 1], easeInOut),
        }}
      />
    </AbsoluteFill>
  );
};
