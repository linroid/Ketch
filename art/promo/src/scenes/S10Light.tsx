// S10 Light (global 1845–1965 at the time of writing, 120 frames). Four download-size cards from
// the latest release in a 2×2 grid under the headline, smallest first: each number counts up from
// half its value as its card enters (tabular figures, so nothing jitters; a range keeps its width,
// so its ends never meet) with what the download is under it; the Android app carries the scene's
// one ember highlight. Then a row of two chips, with a tertiary footnote naming the release on the
// same line at the right (as S06 places its footnote). The copy is already rising on the cut (on
// the soft cymbal) and the first card is up at once; everything leaves softly, still fading
// on the scene's last frame and gone on the cut (soft cut into S11: softCutExitAt, so no bare frame
// between the two scenes).
//
// Copy (both locales) lives in ./copy/S10.ts; the sizes come from src/facts.json.
import React from 'react';
import {AbsoluteFill} from 'remotion';
import {Background} from '../components/Background';
import {ChipRow} from '../components/Chip';
import {TEXT_OUT, TextBlock, riseStyle} from '../components/Text';
import {easeAccelerate, easeStandard, progress} from '../easing';
import {FACTS, formatSize, parseSize, type SizeLabel, type Sizes} from '../facts';
import {useCopy} from '../locale';
import {EMBER, FONT, SAFE, TYPE, alpha} from '../theme';
import {usePalette, useThemed} from '../theme-context';
import {useDesignFrame} from '../time';
import {WIDTH, scene, softCutExitAt} from '../timing';
import {S10_COPY, type SizeCardStrings} from './copy/S10';

// ---------------------------------------------------------------------------------------------
// Cards

/** Bytes per unit, as GitHub lists release assets (binary units). */
const UNIT_BYTES: Record<string, number> = {B: 1, KB: 1024, MB: 1024 ** 2, GB: 1024 ** 3};
const bytes = (s: SizeLabel): number => s.to * (UNIT_BYTES[s.unit.toUpperCase()] ?? 1);

/**
 * The downloads, smallest first (the storyboard's order), sorted by size so a new release keeps
 * the order right on its own. In the 2×2 grid that reads left to right, then the second row.
 */
const CARDS: {key: keyof Sizes; size: SizeLabel}[] = (Object.keys(FACTS.sizes) as (keyof Sizes)[])
  .map((key) => ({key, size: parseSize(FACTS.sizes[key])}))
  .sort((a, b) => bytes(a.size) - bytes(b.size));

/** The card whose number carries the ember (the scene's one highlight). */
const EMBER_CARD: keyof Sizes = 'android';

/**
 * Where a number's count-up starts, as a fraction of its final value: it counts from half, so a
 * card never shows a zero ("0 KB", "0–0 MB") or an impossible range.
 */
const COUNT_FROM = 0.5;

/**
 * `s` partway (`t`, 0..1) through its count-up, for formatSize. Both ends of a range move by the
 * same amount, from COUNT_FROM × the lower end below their final values to the values, so the
 * range keeps its width the whole time and its ends never meet ("17–23 MB" … "33–39 MB").
 */
const countUp = (s: SizeLabel, t: number): SizeLabel => {
  const below = s.from * (1 - COUNT_FROM) * (1 - t);
  return {...s, from: s.from - below, to: s.to - below};
};

// ---------------------------------------------------------------------------------------------
// Timing (scene-local frames)

/** The copy starts before the cut, so the headline is already rising on the scene's frame 0. */
const TEXT_DELAY = -6;
/** First card's entrance (under the cut, so it shows, counting, on frame 0); the others follow. */
const CARDS_AT = -6;
const CARD_STAGGER = 4;
/**
 * A card's number counts up over COUNT_LEN from the frame the card starts entering, and fades in
 * with the card, so no card is ever an empty box.
 */
const COUNT_LEN = 36;
/** A card's label rises this long after the card starts entering. */
const LABEL_DELAY = 10;
/** The chips follow the last card's label; the footnote lands after them. */
const CHIPS_AT = 28;
const CHIP_STAGGER = 5;
const FOOTNOTE_AT = 36;
/** Everything leaves (fade + rise) from here, ending on the cut (about 32% on the last frame). */
const EXIT_AT = softCutExitAt(scene('S10').len);

// ---------------------------------------------------------------------------------------------
// Layout (px in the 1920×1080 frame). The cards are sized for the longest copy of any locale:
// the English label "Command line · one native binary" and the widest number, "50–62 MB" (the
// Chinese labels are shorter). Check a still of local 60 in every locale after changing a
// label or a release's sizes.

/**
 * LAYOUT: the 2×2 grid inside the safe area, its top 76 px under a one-line headline (eyebrow
 * at 110, headline 84 px); a headline that wraps needs a lower grid.
 */
const GRID = {left: SAFE.x, right: WIDTH - SAFE.x, top: 316, cardH: 226, gap: 24};
const CARD_W = (GRID.right - GRID.left - GRID.gap) / 2;
const GRID_BOTTOM = GRID.top + 2 * GRID.cardH + GRID.gap;
/**
 * LAYOUT: card padding (top, sides; the bottom takes what is left) and type, fitted to the
 * widest number and the longest label above.
 */
const PAD_TOP = 34;
const PAD_X = 44;
const VALUE_SIZE = 104;
const LABEL_SIZE = 30;
/** Chip row under the grid; the footnote is centred on it, right-aligned to the safe area. */
const CHIP_SIZE = 26;
const CHIPS_TOP = GRID_BOTTOM + 40;
/** LAYOUT: height of a chip, mirroring Chip (line-height 1.2 plus 10/22 of the size above and below). */
const CHIP_H = CHIP_SIZE * 1.2 + 2 * ((CHIP_SIZE * 10) / 22);
const FOOTNOTE_LINE = 20;

const emberText: React.CSSProperties = {
  backgroundImage: EMBER.gradient,
  WebkitBackgroundClip: 'text',
  backgroundClip: 'text',
  color: 'transparent',
  // keep the descenders inside the clipped box
  paddingBottom: '0.06em',
};

interface SizeCardProps {
  size: SizeLabel;
  strings: SizeCardStrings;
  /** Frame at which the card starts entering. */
  at: number;
  /** 0..1 progress of the scene's exit. */
  out: number;
  ember: boolean;
  left: number;
  top: number;
}

/**
 * A card's rims (its own; the ember card's), its top highlight and its drop shadow, on each
 * canvas: on the light one a white card with a hairline and a soft shadow.
 */
const CARD_LOOK = {
  dark: {
    rim: 'rgba(255,255,255,0.08)',
    emberRim: alpha(EMBER.from, 0.16),
    highlight: 'rgba(255,255,255,0.04)',
    drop: 'rgba(0,0,0,0.6)',
  },
  light: {
    rim: 'rgba(20,26,38,0.09)',
    emberRim: alpha(EMBER.to, 0.28),
    highlight: 'rgba(255,255,255,0)',
    drop: 'rgba(15,23,42,0.16)',
  },
};

/** One card: the size counting up (it enters with the card), and what the download is under it. */
const SizeCard: React.FC<SizeCardProps> = ({size, strings, at, out, ember, left, top}) => {
  const frame = useDesignFrame();
  const p = usePalette();
  const look = useThemed(CARD_LOOK);
  const enter = progress(frame, at, 16, easeStandard);
  const count = progress(frame, at, COUNT_LEN, easeStandard);
  return (
    <div
      style={{
        position: 'absolute',
        left,
        top,
        width: CARD_W,
        height: GRID.cardH,
        borderRadius: 20,
        background: ember
          ? `radial-gradient(120% 90% at 0% 0%, ${alpha(EMBER.from, 0.075)} 0%, ${alpha(EMBER.to, 0.03)} 45%, rgba(0,0,0,0) 75%), ${p.surface}`
          : p.surface,
        boxShadow: `inset 0 0 0 1px ${ember ? look.emberRim : look.rim}, inset 0 1px 0 ${look.highlight}, 0 24px 48px -24px ${look.drop}`,
        padding: `${PAD_TOP}px ${PAD_X}px 0`,
        boxSizing: 'border-box',
        display: 'flex',
        flexDirection: 'column',
        gap: 10,
        fontFamily: FONT.sans,
        // the chip entrance (rise 18 px, scale .96 → 1), and the text exit
        opacity: progress(frame, at, 10) * (1 - out),
        transform: `translate3d(0, ${(18 * (1 - enter) - 12 * out).toFixed(2)}px, 0) scale(${(0.96 + 0.04 * enter).toFixed(4)})`,
      }}
    >
      <div
        style={{
          ...TYPE.number,
          fontSize: VALUE_SIZE,
          lineHeight: 1.05,
          letterSpacing: '-0.03em',
          color: p.primary,
          whiteSpace: 'nowrap',
        }}
      >
        <span style={ember ? emberText : undefined}>{formatSize(countUp(size, count))}</span>
      </div>
      <div
        style={{
          ...TYPE.sub,
          fontSize: LABEL_SIZE,
          lineHeight: 1.3,
          color: p.secondary,
          whiteSpace: 'nowrap',
          ...riseStyle(frame, at + LABEL_DELAY, undefined, 14),
        }}
      >
        {strings.label}
        {strings.note && <span style={{color: p.tertiary}}>{` · ${strings.note}`}</span>}
      </div>
    </div>
  );
};

export const S10Light: React.FC = () => {
  const frame = useDesignFrame();
  const p = usePalette();
  const copy = useCopy(S10_COPY);
  const out = progress(frame, EXIT_AT, TEXT_OUT, easeAccelerate);
  return (
    <AbsoluteFill>
      <Background seed="s10" />
      <TextBlock
        eyebrow={copy.eyebrow}
        headline={copy.headline}
        delay={TEXT_DELAY}
        exitAt={EXIT_AT}
      />
      {CARDS.map(({key, size}, i) => (
        <SizeCard
          key={key}
          size={size}
          strings={copy.cards[key]}
          at={CARDS_AT + i * CARD_STAGGER}
          out={out}
          ember={key === EMBER_CARD}
          left={GRID.left + (i % 2) * (CARD_W + GRID.gap)}
          top={GRID.top + Math.floor(i / 2) * (GRID.cardH + GRID.gap)}
        />
      ))}
      <ChipRow
        labels={[...copy.chips]}
        delay={CHIPS_AT}
        stagger={CHIP_STAGGER}
        exitAt={EXIT_AT}
        size={CHIP_SIZE}
        style={{position: 'absolute', left: GRID.left, top: CHIPS_TOP}}
      />
      <div
        style={{
          position: 'absolute',
          right: SAFE.x,
          top: CHIPS_TOP + (CHIP_H - FOOTNOTE_LINE) / 2,
          fontFamily: FONT.sans,
          fontWeight: 500,
          fontSize: 16,
          lineHeight: `${FOOTNOTE_LINE}px`,
          color: p.tertiary,
          ...riseStyle(frame, FOOTNOTE_AT, EXIT_AT, 14),
        }}
      >
        {copy.footnote}
      </div>
    </AbsoluteFill>
  );
};
