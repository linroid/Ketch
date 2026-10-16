// Typography with the storyboard's motion: words rise 28 px and fade in (2-frame stagger,
// ~18 frames each); exits fade and rise 16 px over 10 frames.
//
// CJK: text with Han, kana or Hangul is split into words with Intl.Segmenter instead of at
// spaces (punctuation stays with its word, so no line starts with "，"), CJK runs get no
// tracking, and headlines get CJK_TYPE.headlineLineHeight. This keys off the text itself, so
// Latin copy renders exactly as before and textBlockTimes() counts the same words the reveal
// animates, in any locale.
import React from 'react';
import {easeAccelerate, easeDecelerate, easeStandard, progress} from '../easing';
import {CJK_TYPE, EMBER, FONT, TEXT_ANCHOR, TYPE, alpha, type Mode} from '../theme';
import {usePalette} from '../theme-context';
import {useDesignFrame} from '../time';

// ---------------------------------------------------------------------------------------------
// Shared motion

/** Entrance length of one word (frames). */
export const WORD_IN = 18;
/**
 * Exit length (frames). An exit from `exitAt` still shows ~32% on `exitAt + 9` and is gone on
 * `exitAt + 10`; at a soft cut it starts at len − 10 (`softCutExitAt`), so it ends on the cut and
 * the scene's last frame is never a bare canvas.
 */
export const TEXT_OUT = 10;

/** Style for one animated unit (word or line) entering at `start` and leaving at `exitAt`. */
export const riseStyle = (
  frame: number,
  start: number,
  exitAt?: number,
  rise = 28,
): React.CSSProperties => {
  const move = progress(frame, start, WORD_IN, easeDecelerate);
  const fade = progress(frame, start, WORD_IN * 0.75, easeStandard);
  const out = exitAt === undefined ? 0 : progress(frame, exitAt, TEXT_OUT, easeAccelerate);
  const y = (1 - move) * rise - out * 16;
  return {
    opacity: fade * (1 - out),
    transform: y === 0 ? undefined : `translate3d(0, ${y.toFixed(2)}px, 0)`,
  };
};

// ---------------------------------------------------------------------------------------------
// CJK text

/** Han, kana and Hangul (no spaces between words), CJK punctuation and full-width forms. */
const CJK_CLASS = [
  '\\p{Script=Han}',
  '\\p{Script=Hiragana}',
  '\\p{Script=Katakana}',
  '\\p{Script=Hangul}',
  '\\u3000-\\u303F',
  '\\uFF00-\\uFFEF',
].join('');
const CJK_CHAR = new RegExp(`[${CJK_CLASS}]`, 'u');
const CJK_RUNS = new RegExp(`([${CJK_CLASS}]+)`, 'u');

/** True when `s` contains Han, kana or Hangul (or CJK punctuation). */
export const hasCJK = (s: string): boolean => CJK_CHAR.test(s);

/**
 * Renders `text` with its CJK runs at CJK_TYPE.letterSpacing, so Latin words around them keep
 * the element's tracking. Latin-only text renders as the plain string. For one-line labels
 * (eyebrows, chips, pills); scenes can use it in their own text elements too.
 */
export const TextRuns: React.FC<{text: string}> = ({text}) => {
  if (!hasCJK(text)) return <>{text}</>;
  return (
    <>
      {text.split(CJK_RUNS).map((run, i) =>
        i % 2 === 1 ? (
          <span key={i} style={{letterSpacing: CJK_TYPE.letterSpacing}}>
            {run}
          </span>
        ) : (
          run
        ),
      )}
    </>
  );
};

interface Segment {
  segment: string;
  isWordLike?: boolean;
}

const segmenters = new Map<string, Intl.Segmenter>();

/** Word segments of `s`; the language comes from the script (Hangul, kana, else Chinese). */
const segmentWords = (s: string): Iterable<Segment> => {
  const kana = /[\p{Script=Hiragana}\p{Script=Katakana}]/u.test(s);
  const lang = /\p{Script=Hangul}/u.test(s) ? 'ko' : kana ? 'ja' : 'zh';
  if (typeof Intl.Segmenter !== 'function') {
    // Not reached in Remotion's Chrome: one ideograph or kana per word, other runs whole.
    const ideo = '\\p{sc=Han}\\p{sc=Hira}\\p{sc=Kana}';
    const parts = s.match(new RegExp(`\\s+|[${ideo}]|[^\\s${ideo}]+`, 'gu'));
    return (parts ?? []).map((segment) => ({segment, isWordLike: /[\p{L}\p{N}]/u.test(segment)}));
  }
  let seg = segmenters.get(lang);
  if (!seg) segmenters.set(lang, (seg = new Intl.Segmenter(lang, {granularity: 'word'})));
  return seg.segment(s);
};

/**
 * Words of a CJK string, each with whether whitespace precedes it. Punctuation joins the word
 * it touches: closing marks the word before ("飞快。"), opening marks the word after
 * ("（预览"), so the reveal and line breaks never strand a mark.
 */
const cjkWords = (s: string): {text: string; space: boolean}[] => {
  const out: {text: string; space: boolean}[] = [];
  let space = false;
  let prefix = '';
  let prefixSpace = false;
  const flushPrefix = () => {
    if (prefix) out.push({text: prefix, space: prefixSpace});
    prefix = '';
  };
  for (const {segment, isWordLike} of segmentWords(s)) {
    if (/^\s+$/u.test(segment)) {
      flushPrefix();
      space = true;
      continue;
    }
    const mark = !isWordLike && /^[\p{P}\p{S}]+$/u.test(segment);
    const opening = /^[\p{Ps}\p{Pi}]+$/u.test(segment);
    const last = out[out.length - 1];
    if (mark && !opening && last && !space && !prefix) {
      last.text += segment;
    } else if (mark) {
      if (!prefix) prefixSpace = space;
      prefix += segment;
    } else {
      out.push({text: prefix + segment, space: prefix ? prefixSpace : space});
      prefix = '';
    }
    space = false;
  }
  flushPrefix();
  return out;
};

// ---------------------------------------------------------------------------------------------
// Words

interface Token {
  text: string;
  code?: boolean;
  br?: boolean;
  /** No space before this token (punctuation right after code, the next word in CJK). */
  glue?: boolean;
}

/**
 * Splits copy into words; `\n` breaks lines and `backticks` mark inline code. Latin text splits
 * at spaces, CJK text with Intl.Segmenter (spaces between CJK and Latin words are kept).
 */
export const tokenize = (s: string): Token[] => {
  const out: Token[] = [];
  s.split('\n').forEach((line, li) => {
    if (li > 0) out.push({text: '', br: true});
    let lineStart = true;
    line.split('`').forEach((part, i) => {
      if (i % 2 === 1) {
        if (part) {
          const prev = out[out.length - 1];
          const glue = !lineStart && prev && !prev.br && !/\s$/.test(line.split('`')[i - 1] ?? ' ');
          out.push({text: part, code: true, glue});
          lineStart = false;
        }
        return;
      }
      if (hasCJK(part)) {
        cjkWords(part).forEach((w) => {
          out.push({text: w.text, glue: !lineStart && !w.space});
          lineStart = false;
        });
        return;
      }
      const words = part.split(/\s+/).filter(Boolean);
      words.forEach((w, wi) => {
        out.push({text: w, glue: wi === 0 && !lineStart && !/^\s/.test(part)});
        lineStart = false;
      });
    });
  });
  return out;
};

/** Number of animated words in a copy string. */
export const wordCount = (s: string): number => tokenize(s).filter((t) => !t.br).length;

interface WordsProps {
  text: string;
  start: number;
  stagger: number;
  exitAt?: number;
  rise: number;
  frame: number;
  codeStyle?: React.CSSProperties;
  ember?: string;
}

const strip = (w: string) => w.replace(/[.,!?;:…，。！？；：、]+$/u, '');

/** Renders tokens as inline-block words, each with its own rise. */
const Words: React.FC<WordsProps> = ({
  text,
  start,
  stagger,
  exitAt,
  rise,
  frame,
  codeStyle,
  ember,
}) => {
  const tokens = tokenize(text);
  let i = 0;
  const nodes: React.ReactNode[] = [];
  tokens.forEach((t, k) => {
    if (t.br) {
      nodes.push(<br key={`br${k}`} />);
      return;
    }
    const idx = i++;
    const prev = tokens[k - 1];
    if (k > 0 && prev && !prev.br && !t.glue) nodes.push(' ');
    const emberWord = ember !== undefined && strip(t.text) === ember;
    const style: React.CSSProperties = {
      display: 'inline-block',
      whiteSpace: 'pre',
      ...(!t.code && hasCJK(t.text) ? {letterSpacing: CJK_TYPE.letterSpacing} : {}),
      ...riseStyle(frame, start + idx * stagger, exitAt, rise),
    };
    nodes.push(
      <span key={k} style={style}>
        {t.code ? (
          <code style={codeStyle}>{t.text}</code>
        ) : emberWord ? (
          <>
            <span
              style={{
                backgroundImage: EMBER.gradient,
                WebkitBackgroundClip: 'text',
                backgroundClip: 'text',
                color: 'transparent',
                // keep descenders inside the clipped box
                paddingBottom: '0.08em',
              }}
            >
              {ember}
            </span>
            {/* trailing punctuation keeps the text colour */}
            {t.text.slice(ember.length) || null}
          </>
        ) : (
          t.text
        )}
      </span>,
    );
  });
  return <>{nodes}</>;
};

interface CommonTextProps {
  /** Frame at which the entrance starts. Default 0. */
  delay?: number;
  /** Frame at which the exit starts (fade + rise 16 px over 10 frames). */
  exitAt?: number;
  mode?: Mode;
  /** 0..1 blend toward light colours (S02). */
  lightMix?: number;
  /** Colour override. */
  color?: string;
  align?: 'left' | 'center';
  /** Frame override (defaults to useDesignFrame()). */
  frame?: number;
  style?: React.CSSProperties;
}

// ---------------------------------------------------------------------------------------------
// Eyebrow

export interface EyebrowProps extends CommonTextProps {
  text: string;
}

/** Inter SemiBold 20 px, uppercase, 0.16em tracking, accent text colour. Enters as one unit. */
export const Eyebrow: React.FC<EyebrowProps> = ({
  text,
  delay = 0,
  exitAt,
  mode,
  lightMix,
  color,
  align = 'left',
  frame: f,
  style,
}) => {
  const current = useDesignFrame();
  const frame = f ?? current;
  const p = usePalette(mode, lightMix);
  return (
    <div
      style={{
        ...TYPE.eyebrow,
        color: color ?? p.accentText,
        textAlign: align,
        // tracking adds trailing space after the last letter; pull it back so centring is true
        marginRight: align === 'center' ? '-0.16em' : undefined,
        ...riseStyle(frame, delay, exitAt, 16),
        ...style,
      }}
    >
      <TextRuns text={text} />
    </div>
  );
};

// ---------------------------------------------------------------------------------------------
// Headline

export interface HeadlineProps extends CommonTextProps {
  /** Copy; `\n` breaks lines. */
  text: string;
  /** Font size in px. Default 84 (hero up to 140). */
  size?: number;
  /** Frames between words. Default 2. */
  stagger?: number;
  /** Max width in px. */
  width?: number;
  /**
   * One word (without trailing punctuation) filled with the ember gradient. Use sparingly. In
   * CJK copy it must be one whole word as Intl.Segmenter splits it (check the render).
   */
  ember?: string;
}

const CJK_HEADLINE_LINE_HEIGHT = Math.max(TYPE.headline.lineHeight, CJK_TYPE.headlineLineHeight);

/**
 * The line height Headline sets `text` in: TYPE.headline's, or CJK_TYPE.headlineLineHeight when
 * the text has CJK characters. For layouts that size a headline's rows themselves.
 */
export const headlineLineHeight = (text: string): number =>
  hasCJK(text) ? CJK_HEADLINE_LINE_HEIGHT : TYPE.headline.lineHeight;

/**
 * Inter Display Bold, line-height 1.02, −0.035em, revealed word by word. CJK headlines get
 * CJK_TYPE.headlineLineHeight (Han glyphs fill the em box) and untracked CJK words.
 */
export const Headline: React.FC<HeadlineProps> = ({
  text,
  delay = 0,
  exitAt,
  mode,
  lightMix,
  color,
  align = 'left',
  size = 84,
  stagger = 2,
  width,
  ember,
  frame: f,
  style,
}) => {
  const current = useDesignFrame();
  const frame = f ?? current;
  const p = usePalette(mode, lightMix);
  return (
    <div
      style={{
        ...TYPE.headline,
        fontSize: size,
        letterSpacing: size >= 120 ? '-0.04em' : TYPE.headline.letterSpacing,
        lineHeight: headlineLineHeight(text),
        color: color ?? p.primary,
        textAlign: align,
        maxWidth: width,
        ...style,
      }}
    >
      <Words
        text={text}
        start={delay}
        stagger={stagger}
        exitAt={exitAt}
        rise={28}
        frame={frame}
        ember={ember}
      />
    </div>
  );
};

// ---------------------------------------------------------------------------------------------
// Sub

export interface SubProps extends CommonTextProps {
  /** Copy; `\n` breaks lines and `backticks` render as inline code. */
  text: string;
  /** Font size in px. Default 32. */
  size?: number;
  /** Max width in px. Default 780. */
  width?: number;
  /** Frames between words. Default 1. */
  stagger?: number;
}

/** Inter Medium 32 px, line-height 1.35, secondary colour, max ~780 px. */
export const Sub: React.FC<SubProps> = ({
  text,
  delay = 0,
  exitAt,
  mode,
  lightMix,
  color,
  align = 'left',
  size = 32,
  width = 780,
  stagger = 1,
  frame: f,
  style,
}) => {
  const current = useDesignFrame();
  const frame = f ?? current;
  const p = usePalette(mode, lightMix);
  const codeStyle: React.CSSProperties = {
    ...TYPE.mono,
    fontSize: '0.84em',
    color: p.primary,
    background: alpha(p.primary, 0.08),
    border: `1px solid ${alpha(p.primary, 0.1)}`,
    borderRadius: '0.3em',
    padding: '0.04em 0.34em 0.08em',
  };
  return (
    <div
      style={{
        ...TYPE.sub,
        fontSize: size,
        color: color ?? p.secondary,
        textAlign: align,
        maxWidth: width,
        ...style,
      }}
    >
      <Words
        text={text}
        start={delay}
        stagger={stagger}
        exitAt={exitAt}
        rise={20}
        frame={frame}
        codeStyle={codeStyle}
      />
    </div>
  );
};

// ---------------------------------------------------------------------------------------------
// TextBlock

export interface TextBlockTimes {
  eyebrowAt: number;
  headlineAt: number;
  subAt: number;
  /** Frame at which every word has fully entered. */
  doneAt: number;
}

/** Start frames used by TextBlock (handy for timing visuals against the copy). */
export const textBlockTimes = ({
  eyebrow,
  headline,
  sub,
  delay = 0,
}: {
  eyebrow?: string;
  headline: string;
  sub?: string;
  delay?: number;
}): TextBlockTimes => {
  // The eyebrow leads by only 2 frames, so a scene's copy fills the canvas almost at once.
  const headlineAt = delay + (eyebrow ? 2 : 0);
  const hw = wordCount(headline);
  const subAt = headlineAt + Math.max(0, hw - 1) * 2 + 8;
  const sw = sub ? wordCount(sub) : 0;
  const doneAt =
    (sub ? subAt + Math.max(0, sw - 1) : headlineAt + Math.max(0, hw - 1) * 2) + WORD_IN;
  return {eyebrowAt: delay, headlineAt, subAt, doneAt};
};

export interface TextBlockProps {
  eyebrow?: string;
  /** Headline copy; `\n` breaks lines. */
  headline: string;
  sub?: string;
  /** Entrance start frame. Default 0. */
  delay?: number;
  /** Exit start frame (all parts leave together). */
  exitAt?: number;
  align?: 'left' | 'center';
  /** Block width in px. Default 1100 (sub stays ≤ subWidth). */
  width?: number;
  mode?: Mode;
  lightMix?: number;
  /** Left edge (align left). Default 120. */
  x?: number;
  /** Top of the eyebrow. Default 110. */
  y?: number;
  /** Headline size. Default 84. */
  headlineSize?: number;
  /** Sub max width. Default 780. */
  subWidth?: number;
  /** Word of the headline to fill with ember. */
  ember?: string;
  /** Absolutely positioned at x/y (default) or laid out inline. */
  inline?: boolean;
  frame?: number;
  style?: React.CSSProperties;
}

/**
 * TextBlock's spacing, for scenes that lay their copy out themselves and place things under it
 * (a centred column, swapping headlines, a stack of rows): the eyebrow's line box and the gaps
 * TextBlock leaves after the eyebrow and before the sub.
 */
export const TEXT_BLOCK_METRICS = {
  /** Height of the eyebrow's line box (TYPE.eyebrow: 20 px × 1.2). */
  eyebrowHeight: TYPE.eyebrow.fontSize * TYPE.eyebrow.lineHeight,
  /** Gap between the eyebrow and the headline. */
  eyebrowGap: 26,
  /** Gap between the headline and the sub. */
  subGap: 30,
} as const;

/** Eyebrow + headline + sub with standard spacing (TEXT_BLOCK_METRICS) and staggered timing. */
export const TextBlock: React.FC<TextBlockProps> = ({
  eyebrow,
  headline,
  sub,
  delay = 0,
  exitAt,
  align = 'left',
  width = 1100,
  mode,
  lightMix,
  x = TEXT_ANCHOR.x,
  y = TEXT_ANCHOR.y,
  headlineSize = 84,
  subWidth = 780,
  ember,
  inline = false,
  frame,
  style,
}) => {
  const t = textBlockTimes({eyebrow, headline, sub, delay});
  const centered = align === 'center';
  const position: React.CSSProperties = inline
    ? {}
    : centered
      ? {position: 'absolute', top: y, left: 0, right: 0}
      : {position: 'absolute', top: y, left: x};
  return (
    <div
      style={{
        ...position,
        display: 'flex',
        flexDirection: 'column',
        alignItems: centered ? 'center' : 'flex-start',
        fontFamily: FONT.sans,
        ...style,
      }}
    >
      <div
        style={{
          width,
          maxWidth: '100%',
          display: 'flex',
          flexDirection: 'column',
          alignItems: centered ? 'center' : 'flex-start',
        }}
      >
        {eyebrow && (
          <Eyebrow
            text={eyebrow}
            delay={t.eyebrowAt}
            exitAt={exitAt}
            mode={mode}
            lightMix={lightMix}
            align={align}
            frame={frame}
            style={{marginBottom: TEXT_BLOCK_METRICS.eyebrowGap}}
          />
        )}
        <Headline
          text={headline}
          delay={t.headlineAt}
          exitAt={exitAt}
          mode={mode}
          lightMix={lightMix}
          align={align}
          size={headlineSize}
          ember={ember}
          frame={frame}
        />
        {sub && (
          <Sub
            text={sub}
            delay={t.subAt}
            exitAt={exitAt}
            mode={mode}
            lightMix={lightMix}
            align={align}
            width={subWidth}
            frame={frame}
            style={{marginTop: TEXT_BLOCK_METRICS.subGap}}
          />
        )}
      </div>
    </div>
  );
};
