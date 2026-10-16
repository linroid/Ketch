// S09 Ready for agents (105 frames at the time of writing; its start and length are in
// timing.json). Two columns centred on the frame: on the left the copy, on the right an editor
// card with the MCP client config, from which the tool names flow out, across, to settle under
// the sentence that counts them. The lane wipe into this scene is placed by Promo (local 0–9 is
// being revealed): the copy and the card start under it and the config is already typing as the
// bands leave. Everything holds at full through the last frame and the film hard-cuts to S10 on
// the cymbal (the storyboard's cut rule: gone on the cut, or at full through it).
//
// Copy (both locales), the config and the tool names live in ./copy/S09.ts.
import React, {useMemo} from 'react';
import {AbsoluteFill} from 'remotion';
import {Background} from '../components/Background';
import {TerminalWindow, type TermLine} from '../components/TerminalWindow';
import {TextBlock} from '../components/Text';
import {useCopy} from '../locale';
import {FONT, TEXT_ANCHOR} from '../theme';
import {useDesignFrame} from '../time';
import {HEIGHT} from '../timing';
import {S09_COPY, S09_SHARED, type S09Strings} from './copy/S09';
import {
  COPY_METRICS,
  JSON_COLORS,
  ToolFlow,
  type PillSpec,
  headlineHeight,
  jsonLine,
  layoutPillRows,
  pillRowsHeight,
  stageStyle,
  subHeight,
} from './parts/c-parts';

const T = {
  /**
   * Eyebrow at -3, headline at -1, then the sub (textBlockTimes): every word in by ~41 in
   * English, a few frames later in Chinese, whose copy splits into more words.
   */
  text: -3,
  card: -3,
  /** First JSON line, relative to the card; one line every 2 frames. */
  json: 0,
  /** The pills flow once the sub has started landing; all settled by ~66. */
  pills: 18,
  pillStagger: 2,
} as const;

/**
 * The named tools, one row per line, then (on the last row) the `+N more` pill counting the
 * rest, labelled by `more`.
 */
const toolRows = (more: (n: number) => string): PillSpec[][] => {
  const rows: PillSpec[][] = S09_SHARED.toolRows.map((row) => row.map((label) => ({label})));
  const rest = S09_SHARED.toolCount - rows.flat().length;
  if (rest > 0) rows[rows.length - 1].push({label: more(rest), more: true});
  return rows;
};

// Layout (px). The left column (eyebrow, headline, sub, a 44 px gap, the pills) is centred on
// the frame's middle, measured per locale from the copy's lines (see copy/S09.ts).
/** LAYOUT: the sub's column, wide enough for each locale's longest sub line (en "…to start,"). */
const SUB_WIDTH = 700;
/** LAYOUT: the headline's column, wider than each locale's one-line headline. */
const COPY_WIDTH = 860;
const PILL_SIZE = 22;
/** Gap between the sub and the pills. */
const PILLS_GAP = 44;
const PILLS_H = pillRowsHeight(S09_SHARED.toolRows.length, PILL_SIZE);

/** Top of the copy column and of the pills under it, for a locale's copy. */
const columnLayout = (copy: S09Strings): {top: number; pillsTop: number} => {
  const {eyebrow, eyebrowGap, subGap} = COPY_METRICS;
  const headline = headlineHeight(copy.headline);
  const sub = subHeight(copy.sub(S09_SHARED.toolCount));
  const height = eyebrow + eyebrowGap + headline + subGap + sub + PILLS_GAP + PILLS_H;
  const top = Math.round(HEIGHT / 2 - height / 2);
  return {top, pillsTop: top + height - PILLS_H};
};

/** The card is TerminalWindow at 2× its storyboard metrics, centred on the frame's middle. */
const CARD_SCALE = 2;
/** One config line: TerminalWindow's 14 px text × line-height 1.6, at CARD_SCALE. */
const LINE_H = 14 * CARD_SCALE * 1.6;
/**
 * Fits the config: TerminalWindow's title bar (38) and top padding (16), the lines, and its
 * bottom padding (18), all at CARD_SCALE (keep in step with TerminalWindow).
 */
const CARD_H = Math.round(
  38 * CARD_SCALE + 16 * CARD_SCALE + S09_SHARED.config.length * LINE_H + 18 * CARD_SCALE,
);
/** LAYOUT: wide enough for the config's longest line (`"command": "ketch",`) at CARD_SCALE. */
const CARD = {x: 1100, w: 700, y: Math.round(HEIGHT / 2 - CARD_H / 2)};
/**
 * Where the pills come out from: 140 px inside the card's left edge, relative to the pills'
 * origin (the copy column's left edge), so each starts hidden behind the card.
 */
const PILLS_FROM_X = CARD.x - TEXT_ANCHOR.x + 140;

const LINES: TermLine[] = S09_SHARED.config.map((line, i) => ({
  at: T.json + i * 2,
  text: (
    <>
      <span style={{color: JSON_COLORS.gutter}}>{`${i + 1}`.padStart(2, ' ')} </span>
      {jsonLine(line)}
    </>
  ),
}));

export const S09Agents: React.FC = () => {
  const frame = useDesignFrame();
  const copy = useCopy(S09_COPY);
  const pills = useMemo(() => layoutPillRows(toolRows(copy.more), PILL_SIZE), [copy]);
  const column = useMemo(() => columnLayout(copy), [copy]);
  return (
    <AbsoluteFill style={{fontFamily: FONT.sans}}>
      <Background seed="s09" />
      <TextBlock
        eyebrow={copy.eyebrow}
        headline={copy.headline}
        sub={copy.sub(S09_SHARED.toolCount)}
        delay={T.text}
        width={COPY_WIDTH}
        subWidth={SUB_WIDTH}
        y={column.top}
      />
      {/* tools come out from behind the card's left edge, each along its row, and settle under the copy */}
      <ToolFlow
        pills={pills}
        from={{x: PILLS_FROM_X}}
        delay={T.pills}
        stagger={T.pillStagger}
        size={PILL_SIZE}
        frame={frame}
        style={{left: TEXT_ANCHOR.x, top: column.pillsTop}}
      />
      <div
        style={{
          position: 'absolute',
          left: CARD.x,
          top: CARD.y,
          ...stageStyle(frame, T.card, {drift: {x: 0, y: -10}, driftLen: 100}),
        }}
      >
        <TerminalWindow
          title={S09_SHARED.configTitle}
          lines={LINES}
          width={CARD.w}
          height={CARD_H}
          scale={CARD_SCALE}
          frame={frame - T.card}
        />
      </div>
    </AbsoluteFill>
  );
};
