// Helpers for S06 (cross-platform) and S09 (agents), owned by scene group C: stage motion, the
// framed phones, the browser with the extension popup, the CLI's output format, the copy metrics
// both scenes lay out by, and S09's tool pills and JSON.
//
// Constants measured on the real UI or on the copy carry a LAYOUT tag in their comment (README.md,
// "Layout-dependent constants").
import React from 'react';
import {Img, staticFile} from 'remotion';
import {useAssets, type AssetResolver, type NormRect} from '../../assets';
import {BrowserFrame, browserScale, browserToolbarHeight} from '../../components/BrowserFrame';
import {StillImage} from '../../components/StillImage';
import {TextRuns, hasCJK} from '../../components/Text';
import {easeAccelerate, easeDecelerate, easeStandard, progress} from '../../easing';
import {CJK_TYPE, TYPE, alpha} from '../../theme';
import {usePalette, useThemed} from '../../theme-context';

// ---------------------------------------------------------------------------------------------
// Stage motion

/**
 * Entrance of a stage object (window, phone, card): slides in by (dx, dy) px over `len` frames
 * from `delay` and fades in over its first `fadeLen` frames (short, so overlapping windows are
 * not see-through for long). `drift` adds a slow parallax move (px over `driftLen` frames).
 */
export const stageStyle = (
  frame: number,
  delay: number,
  {
    dx = 0,
    dy = 56,
    len = 26,
    fadeLen = 10,
    scaleFrom = 0.97,
    drift = {x: 0, y: 0},
    driftLen = 150,
  }: {
    dx?: number;
    dy?: number;
    len?: number;
    fadeLen?: number;
    scaleFrom?: number;
    drift?: {x: number; y: number};
    driftLen?: number;
  } = {},
): React.CSSProperties => {
  const move = progress(frame, delay, len, easeDecelerate);
  const fade = progress(frame, delay, fadeLen, easeStandard);
  const d = progress(frame, delay, driftLen, (t) => t);
  const x = (1 - move) * dx + d * drift.x;
  const y = (1 - move) * dy + d * drift.y;
  const s = scaleFrom + (1 - scaleFrom) * move;
  return {
    opacity: fade,
    transform: `translate3d(${x.toFixed(2)}px, ${y.toFixed(2)}px, 0) scale(${s.toFixed(4)})`,
  };
};

/**
 * Exit of a whole group (one opacity for every layer, so overlapping windows never show through
 * each other): fades out over `len` frames from `at` while moving by (dx, dy). `easing` defaults
 * to the exit curve; a symmetric one suits a hand-over, where the next group follows at once.
 */
export const groupExitStyle = (
  frame: number,
  at: number,
  {
    dx = 0,
    dy = -14,
    len = 12,
    easing = easeAccelerate,
  }: {dx?: number; dy?: number; len?: number; easing?: (t: number) => number} = {},
): React.CSSProperties => {
  const out = progress(frame, at, len, easing);
  if (out <= 0) return {};
  return {
    opacity: 1 - out,
    transform: `translate3d(${(out * dx).toFixed(2)}px, ${(out * dy).toFixed(2)}px, 0)`,
  };
};

// ---------------------------------------------------------------------------------------------
// Copy metrics

/**
 * Sizes of the Text components' boxes, for scenes that place things under copy they lay out
 * themselves (S06's swapping headlines, S09's centred column). They mirror components/Text.tsx:
 * TYPE's sizes and line heights, and TextBlock's gaps.
 */
export const COPY_METRICS = {
  /** Height of the eyebrow line (20 px × 1.2). */
  eyebrow: TYPE.eyebrow.fontSize * TYPE.eyebrow.lineHeight,
  /** Gap between the eyebrow and the headline. */
  eyebrowGap: 26,
  /** Gap between the headline and the sub. */
  subGap: 30,
} as const;

/**
 * Lines of a copy string: one more than its `\n`s. Copy that is laid out by these metrics
 * breaks its lines itself, each fitting its column, so none wraps.
 */
const copyLines = (text: string): number => text.split('\n').length;

/**
 * Height in whole px of a Headline of `text` at `size` px: Inter Display's line-height (1.02),
 * or CJK_TYPE.headlineLineHeight for CJK text, as Headline sets them. 86 px for a one-line
 * Latin headline at 84 px, 97 px for a Chinese one.
 */
export const headlineHeight = (text: string, size: number = TYPE.headline.fontSize): number => {
  const lineHeight = hasCJK(text)
    ? Math.max(TYPE.headline.lineHeight, CJK_TYPE.headlineLineHeight)
    : TYPE.headline.lineHeight;
  return Math.round(copyLines(text) * size * lineHeight);
};

/** Height in px of a Sub of `text` at `size` px (line-height 1.35). */
export const subHeight = (text: string, size: number = TYPE.sub.fontSize): number =>
  copyLines(text) * size * TYPE.sub.lineHeight;

// ---------------------------------------------------------------------------------------------
// Phones

/**
 * LAYOUT: transparent margin (reference px, i.e. px at 2 px per dp, every side; times the clip's
 * assetRefScale in its own px) around the phone body in the framed phone clips, used while the
 * manifest records no `bounds.body` for them. PromoSnapshots.framePhone draws the phone (with its
 * shadow) on a transparent canvas, PhoneMargin (112 dp) at PHONE_SCALE from every edge; change
 * this with PhoneMargin (or, better, have the pipeline record the body).
 */
const PHONE_CLIP_MARGIN = 224;

/**
 * Where the phone body is on the framed phone clip `name`, in source px: the manifest's
 * `bounds.body` when the asset pipeline records it, else inside PHONE_CLIP_MARGIN.
 */
const phoneBody = (assets: AssetResolver, name: string): NormRect => {
  const {width, height} = assets.assetSize(name);
  const m = PHONE_CLIP_MARGIN * assets.assetRefScale(name);
  return assets.assetBoundsPx(name, 'body', {
    x: m / width,
    y: m / height,
    w: (width - 2 * m) / width,
    h: (height - 2 * m) / height,
  });
};

/** Width of the phone body shown `bodyHeight` px tall, from the locale's clip (`useAssets()`). */
export const phoneBodyWidth = (assets: AssetResolver, name: string, bodyHeight: number): number => {
  const body = phoneBody(assets, name);
  return (body.w / body.h) * bodyHeight;
};

export interface PhoneDeviceProps {
  /** Clip name of a framed phone (with alpha) in the manifest. */
  name: string;
  /** Clip frame. */
  frame: number;
  /** Height of the phone body in px; the box is the body, the baked shadow spills outside. */
  bodyHeight: number;
  style?: React.CSSProperties;
}

/**
 * A framed phone clip (PromoSnapshots draws the phone, its screen and its shadow) laid out by its
 * body: the box is the body, and the clip's margin and shadow spill outside it.
 */
export const PhoneDevice: React.FC<PhoneDeviceProps> = ({name, frame, bodyHeight, style}) => {
  const assets = useAssets();
  const src = assets.assetSize(name);
  const body = phoneBody(assets, name);
  const k = bodyHeight / body.h;
  return (
    <div
      style={{
        position: 'relative',
        width: phoneBodyWidth(assets, name, bodyHeight),
        height: bodyHeight,
        flex: 'none',
        ...style,
      }}
    >
      <Img
        src={staticFile(assets.clipFramePath(name, frame))}
        style={{
          position: 'absolute',
          left: -body.x * k,
          top: -body.y * k,
          width: src.width * k,
          height: src.height * k,
          maxWidth: 'none',
        }}
      />
    </div>
  );
};

// ---------------------------------------------------------------------------------------------
// Browser with the extension popup open

/**
 * The extension popup still S06 shows, rendered by the asset pipeline from the real extension
 * (tools/extension-popup) in each locale and the film's theme (`popup-dark`, `popup-light`):
 * following the Studio server, with the downloads and states of the web app beside it; 360 CSS
 * px wide at 2×, as tall as its content.
 */
const popupStill = (assets: AssetResolver): string => assets.themed('popup');

/** Height of the popup shown `width` px wide, from the locale's still (`useAssets()`). */
export const popupHeight = (assets: AssetResolver, width: number): number => {
  const {width: w, height: h} = assets.assetSize(popupStill(assets));
  return (width * h) / w;
};

/**
 * The extension's browser on each canvas: its page under the placeholder, and the popup's
 * surface and shadow (it hangs over the page as the browser draws it).
 */
const POPUP_LOOK = {
  dark: {
    page: (surface: string) => `linear-gradient(180deg, ${surface} 0%, #13151A 100%)`,
    popup: '#101214',
    shadow: '0 2px 6px rgba(0,0,0,0.35), 0 18px 44px -8px rgba(0,0,0,0.65)',
    rim: 'rgba(255,255,255,0.10)',
  },
  light: {
    page: (surface: string) => `linear-gradient(180deg, ${surface} 0%, #F3F5FA 100%)`,
    popup: '#FFFFFF',
    shadow: '0 2px 6px rgba(15,23,42,0.08), 0 18px 44px -8px rgba(15,23,42,0.22)',
    rim: 'rgba(20,26,38,0.10)',
  },
};

export interface PopupBrowserProps {
  /** Window size in px. */
  width: number;
  height: number;
  /** Popup width in px (the popup is 360 CSS px wide; 360 shows it at 1:1). */
  popupWidth: number;
  /** 0..1: the popup opening (fade + slight scale from its top-right corner). */
  open: number;
  url?: string;
  style?: React.CSSProperties;
}

/**
 * A faint, brand-neutral downloads page (heading, two lines of text, two file links), so the
 * browser never reads as an empty box before the popup opens over it. Its content sits within
 * the popup's columns (`left`/`right` px from the window's edges), so the open popup hides it
 * whole instead of leaving stubs beside it.
 */
const PlaceholderPage: React.FC<{left: number; right: number}> = ({left, right}) => {
  const p = usePalette();
  const line = (w: number | string, h = 9, a = 0.07): React.CSSProperties => ({
    width: w,
    height: h,
    borderRadius: h / 2,
    background: alpha(p.ink, a),
  });
  const fileRow: React.CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    gap: 12,
    padding: '12px 14px',
    borderRadius: 10,
    background: alpha(p.ink, 0.03),
    boxShadow: `inset 0 0 0 1px ${alpha(p.ink, 0.06)}`,
  };
  const icon: React.CSSProperties = {
    width: 26,
    height: 26,
    borderRadius: 7,
    flex: 'none',
    background: alpha(p.accentText, 0.16),
  };
  return (
    <div
      style={{
        position: 'absolute',
        inset: 0,
        padding: `30px ${right}px 30px ${left}px`,
        display: 'flex',
        flexDirection: 'column',
        gap: 12,
      }}
    >
      <div style={line('58%', 16, 0.1)} />
      <div style={{...line('86%'), marginTop: 6}} />
      <div style={line('64%')} />
      {[0.72, 0.6].map((w, i) => (
        <div key={i} style={{...fileRow, marginTop: i === 0 ? 14 : 0}}>
          <div style={icon} />
          <div style={{flex: 1, display: 'flex', flexDirection: 'column', gap: 7}}>
            <div style={line(`${w * 100}%`, 9, 0.12)} />
            <div style={line('34%', 7, 0.06)} />
          </div>
        </div>
      ))}
    </div>
  );
};

/**
 * A small browser window whose Ketch toolbar button is pressed, with the extension popup
 * (`popup-dark`) hanging from it the way the browser shows extension popups: right-aligned under
 * the button, above the page and the window's edge.
 */
export const PopupBrowser: React.FC<PopupBrowserProps> = ({
  width,
  height,
  popupWidth,
  open,
  url = '',
  style,
}) => {
  const s = browserScale(width);
  const bar = browserToolbarHeight(s);
  const assets = useAssets();
  const p = usePalette();
  const look = useThemed(POPUP_LOOK);
  const popupH = popupHeight(assets, popupWidth);
  // The popup's right edge, 6 px past the extension button's right edge, which BrowserFrame puts
  // 44·s from the window's right edge (keep in step with its toolbar).
  const right = 44 * s - 6;
  return (
    <div style={{position: 'relative', width, height, flex: 'none', ...style}}>
      <BrowserFrame
        width={width}
        height={height}
        url={url}
        extension
        extensionActive={open > 0.05}
        scale={s}
      >
        <div
          style={{
            position: 'absolute',
            inset: 0,
            background: look.page(p.surface),
          }}
        />
        <PlaceholderPage
          left={Math.ceil(width - right - popupWidth) + 8}
          right={Math.ceil(right) + 8}
        />
      </BrowserFrame>
      <div
        style={{
          position: 'absolute',
          right,
          top: bar + 3,
          width: popupWidth,
          height: popupH,
          borderRadius: 10,
          overflow: 'hidden',
          background: look.popup,
          boxShadow: `${look.shadow}, inset 0 0 0 1px ${look.rim}`,
          opacity: open,
          transform: `translate3d(0, ${((1 - open) * -6).toFixed(2)}px, 0) scale(${(0.965 + 0.035 * open).toFixed(4)})`,
          transformOrigin: 'top right',
        }}
      >
        <StillImage name={popupStill(assets)} />
        <div
          style={{
            position: 'absolute',
            inset: 0,
            borderRadius: 10,
            boxShadow: `inset 0 0 0 1px ${look.rim}`,
          }}
        />
      </div>
    </div>
  );
};

// ---------------------------------------------------------------------------------------------
// CLI output (cli/src/main/kotlin/com/linroid/ketch/cli/Main.kt)

const KIB = 1024;
const MIB = 1024 * 1024;
const GIB = 1024 * MIB;

/** The CLI's formatBytes: binary units, `N B`, `%.1f KB`, `%.1f MB`, `%.2f GB`. */
const cliBytes = (bytes: number): string => {
  const b = Math.max(0, Math.floor(bytes));
  if (b < KIB) return `${b} B`;
  if (b < MIB) return `${(b / KIB).toFixed(1)} KB`;
  if (b < GIB) return `${(b / MIB).toFixed(1)} MB`;
  return `${(b / GIB).toFixed(2)} GB`;
};

/**
 * The CLI's progress line for a known size: `[Downloading] {pct}%  {done} / {total}  {speed}/s`
 * (pct is the truncated percentage; the speed part only when it is above zero).
 */
export const cliProgressLine = (
  fraction: number,
  totalBytes: number,
  bytesPerSecond: number,
): string => {
  const f = Math.min(1, Math.max(0, fraction));
  const pct = Math.floor(f * 100 + 1e-9);
  const speed = bytesPerSecond > 0 ? `  ${cliBytes(bytesPerSecond)}/s` : '';
  return `[Downloading] ${pct}%  ${cliBytes(f * totalBytes)} / ${cliBytes(totalBytes)}${speed}`;
};

/** One MiB in bytes, for the sizes copy/S06.ts gives in MiB. */
export const MEBIBYTE = MIB;

// ---------------------------------------------------------------------------------------------
// S09: tool pills flowing out of the config card

/** JetBrains Mono's advance in em (every glyph is 600 of 1000 units wide). */
const MONO_ADVANCE = 0.6;
/**
 * LAYOUT: Inter Medium's average advance in em, measured on the English `+N more` pill (set in
 * the sans face). Raise it if a pill's label looks crowded, lower it if it floats.
 */
const SANS_ADVANCE = 0.54;
/** Advance of a CJK character in em: full width, as PingFang SC sets Han and CJK punctuation. */
const CJK_ADVANCE = 1;

/** Horizontal and vertical padding of a pill at `size` px (Chip's 20 × 10 px at 22 px). */
const pillPadding = (size: number) => ({x: (size * 20) / 22, y: (size * 10) / 22});

/** Height of one pill at `size` px: one line of text (line-height 1.2) and its padding. */
const pillHeight = (size: number): number => size * 1.2 + 2 * pillPadding(size).y;

/** One pill to lay out: a tool name (mono), or the `+N more` pill (sans, secondary colour). */
export interface PillSpec {
  label: string;
  more?: boolean;
}

export interface FlowPill extends PillSpec {
  /** Final position (px) relative to the flow's origin, and size. */
  x: number;
  y: number;
  w: number;
  h: number;
}

/**
 * Estimated text width (px) of a pill label: fixed advances, since a layout has to be known
 * before the fonts render. Good to a few px for tool names and short sans labels; CJK
 * characters count full width.
 */
const labelWidth = (label: string, mono: boolean, size: number): number =>
  [...label].reduce(
    (w, ch) => w + (hasCJK(ch) ? CJK_ADVANCE : mono ? MONO_ADVANCE : SANS_ADVANCE),
    0,
  ) * size;

/** Lays pills out left-aligned, one given row per line. */
export const layoutPillRows = (
  rows: readonly (readonly PillSpec[])[],
  size: number,
  gapX = 12,
  gapY = 12,
): FlowPill[] => {
  const pad = pillPadding(size);
  const h = pillHeight(size);
  const out: FlowPill[] = [];
  rows.forEach((row, r) => {
    let x = 0;
    row.forEach((pill) => {
      const w = labelWidth(pill.label, !pill.more, size) + 2 * pad.x;
      out.push({...pill, x, y: r * (h + gapY), w, h});
      x += w + gapX;
    });
  });
  return out;
};

/** Height of `rows` rows of pills. */
export const pillRowsHeight = (rows: number, size: number, gapY = 12): number =>
  rows * pillHeight(size) + (rows - 1) * gapY;

export interface ToolFlowProps {
  pills: FlowPill[];
  /**
   * Point (relative to the flow's origin) the pills come out from, e.g. behind the card. Without
   * `y` every pill travels horizontally along its own row, so no two pills ever cross.
   */
  from: {x: number; y?: number};
  /** Travel time of one pill in frames. Default 30. */
  len?: number;
  /** Frame of the first pill. */
  delay: number;
  /** Frames between pills. */
  stagger: number;
  exitAt?: number;
  size: number;
  frame: number;
  style?: React.CSSProperties;
}

/**
 * Mono pills that slide out from behind the card to their places, one after another (ease in
 * while still hidden, ease out as they land).
 */
export const ToolFlow: React.FC<ToolFlowProps> = ({
  pills,
  from,
  delay,
  stagger,
  len = 30,
  exitAt,
  size,
  frame,
  style,
}) => {
  const palette = usePalette();
  return (
    <div style={{position: 'absolute', ...style}}>
      {pills.map((p, i) => {
        const start = delay + i * stagger;
        const t = progress(frame, start, len, easeStandard);
        const fade = progress(frame, start, len * 0.4, easeStandard);
        const dx = (from.x - (p.x + p.w / 2)) * (1 - t);
        const dy = from.y === undefined ? 0 : (from.y - (p.y + p.h / 2)) * (1 - t);
        const out = exitAt === undefined ? 0 : progress(frame, exitAt, 10, easeAccelerate);
        const s = 0.94 + 0.06 * t;
        const more = p.more === true;
        return (
          <div
            key={p.label + i}
            style={{
              position: 'absolute',
              left: p.x,
              top: p.y,
              width: p.w,
              height: p.h,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              borderRadius: 999,
              ...(more ? TYPE.chip : TYPE.mono),
              fontSize: size,
              lineHeight: 1.2,
              color: more ? palette.secondary : palette.primary,
              background: palette.chipFill,
              boxShadow: `inset 0 0 0 1px ${palette.chipRim}`,
              whiteSpace: 'nowrap',
              opacity: fade * (1 - out),
              transform: `translate3d(${dx.toFixed(2)}px, ${(dy - out * 12).toFixed(2)}px, 0) scale(${s.toFixed(4)})`,
            }}
          >
            {/* one flex item, so the spaces around a CJK run's Latin words stay */}
            <span>
              <TextRuns text={p.label} />
            </span>
          </div>
        );
      })}
    </div>
  );
};

// ---------------------------------------------------------------------------------------------
// S09: JSON with syntax colours

export const JSON_COLORS = {
  key: '#8E9BFF',
  string: '#EEF0F4',
  punct: '#858D9C',
  gutter: alpha('#858D9C', 0.55),
} as const;

/** Colours one line of pretty-printed JSON: keys, string values and punctuation. */
export const jsonLine = (line: string): React.ReactNode => {
  const parts: React.ReactNode[] = [];
  const re = /("(?:[^"\\]|\\.)*")(\s*:)?/g;
  let last = 0;
  let m: RegExpExecArray | null;
  let k = 0;
  while ((m = re.exec(line))) {
    if (m.index > last)
      parts.push(
        <span key={k++} style={{color: JSON_COLORS.punct}}>
          {line.slice(last, m.index)}
        </span>,
      );
    const isKey = Boolean(m[2]);
    parts.push(
      <span key={k++} style={{color: isKey ? JSON_COLORS.key : JSON_COLORS.string}}>
        {m[1]}
      </span>,
    );
    if (m[2])
      parts.push(
        <span key={k++} style={{color: JSON_COLORS.punct}}>
          {m[2]}
        </span>,
      );
    last = m.index + m[0].length;
  }
  if (last < line.length)
    parts.push(
      <span key={k++} style={{color: JSON_COLORS.punct}}>
        {line.slice(last)}
      </span>,
    );
  return <>{parts}</>;
};
