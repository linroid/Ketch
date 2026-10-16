// Helpers shared by scene builder B's scenes (S04, S05, S07): crops of real-UI stills aimed at a
// focus rect, a cropped still view, per-locale layout overrides, a few line icons and an icon
// chip. Every helper that reads an asset reads it in the current locale (useAssets), so a
// localized still is cropped by its own size.
import React from 'react';
import {useAssets, type NormRect} from '../../assets';
import {StillImage} from '../../components/StillImage';
import {TextRuns} from '../../components/Text';
import type {Crop} from '../../components/crop';
import {easeDecelerate, easeStandard, progress} from '../../easing';
import type {Locale} from '../../locale';
import {FONT, TYPE, alpha} from '../../theme';
import {usePalette, useThemed} from '../../theme-context';

// ---------------------------------------------------------------------------------------------
// Rects

/**
 * A rect of a still in px: reference px (px at 2 px per dp, as the scenes' hand-measured rects
 * are; assetBoundsRef, assetRefSize) unless a helper says source px.
 */
export interface SourceRect {
  x: number;
  y: number;
  w: number;
  h: number;
}

/** `rect` shrunk by `px` on every side (e.g. to keep a crop inside a surface's rim). */
export const insetRect = (rect: SourceRect, px: number): SourceRect => ({
  x: rect.x + px,
  y: rect.y + px,
  w: rect.w - 2 * px,
  h: rect.h - 2 * px,
});

/**
 * `rect` in fractions of an asset of `size`, both in the same px: reference px (`size` from
 * assetRefSize) or source px (assetSize).
 */
export const toFractions = (rect: SourceRect, size: {width: number; height: number}): NormRect => ({
  x: rect.x / size.width,
  y: rect.y / size.height,
  w: rect.w / size.width,
  h: rect.h / size.height,
});

// ---------------------------------------------------------------------------------------------
// Crops

/**
 * What a crop must show: `focus`, a rect of the still in fractions of its size. The crop shown
 * is the smallest rect with the box's aspect that contains the focus rect; `ax` / `ay` say where
 * the extra room goes (0 keeps the focus rect's left/top edge, 0.5 centres it, 1 keeps its
 * right/bottom edge). It is then kept inside the image.
 */
export interface CropSpec {
  focus: NormRect;
  ax?: number;
  ay?: number;
}

/** Crop (fractions) with the aspect of `box` that covers the spec's focus rect on a `source`. */
export const coverCrop = (
  source: {width: number; height: number},
  spec: CropSpec,
  box: {width: number; height: number},
): Crop => {
  const s = source;
  const f = spec.focus;
  const fx = f.x * s.width;
  const fy = f.y * s.height;
  const fw = f.w * s.width;
  const fh = f.h * s.height;
  const aspect = box.width / box.height;
  let w = fw;
  let h = fh;
  if (w / h > aspect) h = w / aspect;
  else w = h * aspect;
  if (w > s.width) {
    w = s.width;
    h = w / aspect;
  }
  if (h > s.height) {
    h = s.height;
    w = h * aspect;
  }
  const x = Math.min(Math.max(0, fx - (w - fw) * (spec.ax ?? 0.5)), s.width - w);
  const y = Math.min(Math.max(0, fy - (h - fh) * (spec.ay ?? 0.5)), s.height - h);
  return {x: x / s.width, y: y / s.height, w: w / s.width, h: h / s.height};
};

/** Soft edges: how far (fraction of the box) each side fades from transparent to opaque. */
export interface EdgeFade {
  l?: number;
  r?: number;
  t?: number;
  b?: number;
}

/** Eased ramp stops for one axis of an edge-fade mask. */
const ramp = (dir: string, a = 0, b = 0): string => {
  const stops: string[] = [];
  const n = 6;
  const push = (t: number, pos: number) =>
    stops.push(`rgba(0,0,0,${(t * t * (3 - 2 * t)).toFixed(3)}) ${(pos * 100).toFixed(2)}%`);
  if (a > 0) for (let i = 0; i <= n; i++) push(i / n, (a * i) / n);
  else stops.push('rgba(0,0,0,1) 0%');
  if (b > 0) for (let i = n; i >= 0; i--) push(i / n, 1 - (b * i) / n);
  else stops.push('rgba(0,0,0,1) 100%');
  return `linear-gradient(${dir}, ${stops.join(', ')})`;
};

/** CSS mask that fades the given edges (both axes intersected). */
export const edgeFadeMask = (fade: EdgeFade): React.CSSProperties => {
  const img = `${ramp('to right', fade.l, fade.r)}, ${ramp('to bottom', fade.t, fade.b)}`;
  return {
    maskImage: img,
    WebkitMaskImage: img,
    maskComposite: 'intersect',
    WebkitMaskComposite: 'source-in',
  } as React.CSSProperties;
};

export interface CropViewProps {
  name: string;
  spec: CropSpec;
  width: number;
  height: number;
  /** Extra zoom around `origin` (1 = the crop exactly). */
  zoom?: number;
  /** transform-origin of the zoom, CSS. Default the centre. */
  origin?: string;
  /** Fade these edges into whatever is behind. */
  fade?: EdgeFade;
  style?: React.CSSProperties;
}

/** A still (in the current locale) cropped to a spec, filling a `width` × `height` box. */
export const CropView: React.FC<CropViewProps> = ({
  name,
  spec,
  width,
  height,
  zoom = 1,
  origin = '50% 50%',
  fade,
  style,
}) => {
  const {assetSize} = useAssets();
  const crop = coverCrop(assetSize(name), spec, {width, height});
  return (
    <div
      style={{
        position: 'relative',
        width,
        height,
        overflow: 'hidden',
        ...(fade ? edgeFadeMask(fade) : {}),
        ...style,
      }}
    >
      <div
        style={{
          position: 'absolute',
          inset: 0,
          transform: zoom === 1 ? undefined : `scale(${zoom.toFixed(5)})`,
          transformOrigin: origin,
        }}
      >
        <StillImage name={name} crop={crop} cropUnits="fraction" />
      </div>
    </div>
  );
};

/**
 * A still's `rect` in reference px (px at 2 px per dp, see assetRefScale) shown at `scale` screen
 * px per reference px: a box rect.w·scale wide, the same on screen whatever the still's density.
 */
export const SourceCrop: React.FC<{
  name: string;
  rect: SourceRect;
  scale: number;
  fade?: EdgeFade;
  style?: React.CSSProperties;
}> = ({name, rect, scale, fade, style}) => {
  const {assetRefSize} = useAssets();
  const spec: CropSpec = {focus: toFractions(rect, assetRefSize(name))};
  return (
    <CropView
      name={name}
      spec={spec}
      width={rect.w * scale}
      height={rect.h * scale}
      fade={fade}
      style={style}
    />
  );
};

// ---------------------------------------------------------------------------------------------
// Per-locale layout

/** A scene's layout values that some locales change (longer or denser copy, other UI widths). */
export type LocaleOverrides<T> = Partial<Record<Locale, Partial<T>>>;

/**
 * Layout for `locale`: `base`, laid out on the English copy and assets, with that locale's
 * overrides on top. A locale without overrides (e.g. a new one) gets the English layout.
 */
export const layoutFor = <T extends object>(
  locale: Locale,
  base: T,
  overrides: LocaleOverrides<T>,
): T => ({
  ...base,
  ...overrides[locale],
});

// ---------------------------------------------------------------------------------------------
// Motion

/** Entrance 0 → 1 with the entrance curve. */
export const enterP = (frame: number, at: number, len: number): number =>
  progress(frame, at, len, easeDecelerate);

/** Fade with the standard curve. */
export const fadeP = (frame: number, at: number, len: number): number =>
  progress(frame, at, len, easeStandard);

// ---------------------------------------------------------------------------------------------
// Icons (24-unit grid, 1.8 stroke, currentColor)

const Svg: React.FC<{size: number; children: React.ReactNode; style?: React.CSSProperties}> = ({
  size,
  children,
  style,
}) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth={1.8}
    strokeLinecap="round"
    strokeLinejoin="round"
    style={{display: 'block', flex: 'none', ...style}}
  >
    {children}
  </svg>
);

export type IconName =
  | 'globe'
  | 'server'
  | 'swarm'
  | 'magnet'
  | 'file'
  | 'stream'
  | 'laptop'
  | 'desktop'
  | 'rack'
  | 'phone';

export const Icon: React.FC<{name: IconName; size?: number; style?: React.CSSProperties}> = ({
  name,
  size = 22,
  style,
}) => {
  switch (name) {
    case 'globe':
      return (
        <Svg size={size} style={style}>
          <circle cx="12" cy="12" r="9" />
          <ellipse cx="12" cy="12" rx="3.8" ry="9" />
          <path d="M3.5 9h17M3.5 15h17" />
        </Svg>
      );
    case 'server':
      return (
        <Svg size={size} style={style}>
          <rect x="3.5" y="4" width="17" height="7" rx="2" />
          <rect x="3.5" y="13" width="17" height="7" rx="2" />
          <path d="M7.5 7.5h.01M7.5 16.5h.01" strokeWidth={2.6} />
        </Svg>
      );
    case 'swarm':
      return (
        <Svg size={size} style={style}>
          <circle cx="12" cy="5.5" r="2.5" />
          <circle cx="5.5" cy="17.5" r="2.5" />
          <circle cx="18.5" cy="17.5" r="2.5" />
          <path d="M10.8 7.7 6.7 15.3M13.2 7.7l4.1 7.6M8 17.5h8" />
        </Svg>
      );
    case 'magnet':
      return (
        <Svg size={size} style={style}>
          <path d="M5 4h4.5v7.5a2.5 2.5 0 0 0 5 0V4H19v7.5a7 7 0 0 1-14 0Z" />
          <path d="M5 8.2h4.5M14.5 8.2H19" />
        </Svg>
      );
    case 'file':
      return (
        <Svg size={size} style={style}>
          <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8Z" />
          <path d="M14 3v5h5" />
        </Svg>
      );
    case 'stream':
      return (
        <Svg size={size} style={style}>
          <rect x="3" y="5" width="18" height="14" rx="2.5" />
          <path d="M10 9.2v5.6l4.8-2.8Z" />
        </Svg>
      );
    case 'laptop':
      return (
        <Svg size={size} style={style}>
          <rect x="4.5" y="5" width="15" height="10.5" rx="1.6" />
          <path d="M2.5 19h19" />
        </Svg>
      );
    case 'desktop':
      return (
        <Svg size={size} style={style}>
          <rect x="3" y="4" width="18" height="12.5" rx="1.8" />
          <path d="M9 20.5h6M12 16.5v4" />
        </Svg>
      );
    case 'rack':
      return (
        <Svg size={size} style={style}>
          <rect x="4" y="3.5" width="16" height="5.5" rx="1.5" />
          <rect x="4" y="9.25" width="16" height="5.5" rx="1.5" />
          <rect x="4" y="15" width="16" height="5.5" rx="1.5" />
          <path d="M7.5 6.25h.01M7.5 12h.01M7.5 17.75h.01" strokeWidth={2.4} />
        </Svg>
      );
    case 'phone':
      return (
        <Svg size={size} style={style}>
          <rect x="6.5" y="2.5" width="11" height="19" rx="2.6" />
          <path d="M10.5 18.5h3" />
        </Svg>
      );
  }
};

// ---------------------------------------------------------------------------------------------
// Pills

export interface IconPillProps {
  label: string;
  icon: IconName;
  /** Font size, px. Default 24. */
  size?: number;
  /** Icon colour. Default the accent text colour. */
  iconColor?: string;
  style?: React.CSSProperties;
}

/**
 * Chip look (translucent fill, 1 px rim, pill radius) with a line icon in front. Motion is the
 * caller's (wrap it, or pass a transform/opacity in `style`).
 */
export const IconPill: React.FC<IconPillProps> = ({label, icon, size = 24, iconColor, style}) => {
  const p = usePalette();
  // A soft drop under the pill: deep on the dark canvas, a faint one on the light.
  const drop = useThemed({dark: 0.7, light: 0.16});
  return (
    <div
      style={{
        ...TYPE.chip,
        fontFamily: FONT.sans,
        fontSize: size,
        display: 'inline-flex',
        alignItems: 'center',
        gap: Math.round(size * 0.5),
        padding: `${Math.round((size * 11) / 22)}px ${Math.round((size * 22) / 22)}px ${Math.round((size * 11) / 22)}px ${Math.round((size * 17) / 22)}px`,
        borderRadius: 999,
        color: p.primary,
        background: p.chipFill,
        boxShadow: `inset 0 0 0 1px ${p.chipRim}, 0 10px 24px -14px ${alpha(p.shade, drop)}`,
        whiteSpace: 'nowrap',
        ...style,
      }}
    >
      <Icon name={icon} size={Math.round(size * 1.0)} style={{color: iconColor ?? p.accentText}} />
      <span style={{opacity: 0.94}}>
        <TextRuns text={label} />
      </span>
    </div>
  );
};
