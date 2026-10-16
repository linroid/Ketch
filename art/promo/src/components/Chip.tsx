// Pill label: Inter Medium 22 px, translucent fill, 1 px rim, 10×20 px padding. Enters with a
// short rise (or a drop from above) and leaves like text. String labels with CJK text get
// untracked CJK runs (TextRuns).
//
// Scenes that stack things under chips, or lay pills out themselves, size them with chipPadding
// and chipHeight rather than repeating the formula, so a change here moves them too.
import React from 'react';
import {easeAccelerate, easeDecelerate, easeStandard, progress} from '../easing';
import {FONT, TYPE, alpha, type Mode} from '../theme';
import {usePalette, useTheme} from '../theme-context';
import {useDesignFrame} from '../time';
import {TextRuns} from './Text';

export interface ChipProps {
  label: React.ReactNode;
  /** Frame at which the chip enters. Default 0. */
  delay?: number;
  /** Frame at which it leaves (fade + rise over 10 frames, gone on exitAt + 10). */
  exitAt?: number;
  /** Leading icon (any node, sized by the caller; ~0.9em works). */
  icon?: React.ReactNode;
  /** Small coloured dot before the label (e.g. an accent colour). */
  dot?: string;
  /** Accent-tinted fill and rim with primary text. */
  emphasis?: boolean;
  /** Monospace label (tool names, commands). */
  mono?: boolean;
  /** Entrance: 'rise' (default), 'drop' (falls in from above) or 'pop' (scale up). */
  enter?: 'rise' | 'drop' | 'pop';
  /** Font size in px. Default 22. */
  size?: number;
  mode?: Mode;
  lightMix?: number;
  frame?: number;
  style?: React.CSSProperties;
}

/**
 * The light film's chips, on its own canvas: white glass with a firmer rim than the palette's
 * (LIGHT.chipFill, a faint grey, stays for the dark film's light beat in S02), so a chip stands
 * off the pale canvas as the dark film's do off the dark one.
 */
const LIGHT_FILM_CHIP = {fill: 'rgba(255,255,255,0.7)', rim: 'rgba(20,26,38,0.13)'} as const;

/** Default chip text size in px (TYPE.chip). */
export const CHIP_SIZE = TYPE.chip.fontSize;

/** A chip's padding at `size` px: 10 px above and below, 20 px each side at 22 px, scaled. */
export const chipPadding = (size: number = CHIP_SIZE): {x: number; y: number} => ({
  x: (size * 20) / 22,
  y: (size * 10) / 22,
});

/**
 * A chip's height at `size` px: one line of TYPE.chip (line-height 1.2) and its padding, e.g.
 * 46.4 px at 22 px. For layouts that place things under a row of chips.
 */
export const chipHeight = (size: number = CHIP_SIZE): number =>
  size * TYPE.chip.lineHeight + 2 * chipPadding(size).y;

/** Entrance/exit transform shared by chips and other small objects. */
export const objectEnterStyle = (
  frame: number,
  delay: number,
  exitAt: number | undefined,
  enter: 'rise' | 'drop' | 'pop' = 'rise',
): React.CSSProperties => {
  const p = progress(frame, delay, 16, easeDecelerate);
  const fade = progress(frame, delay, 10, easeStandard);
  const out = exitAt === undefined ? 0 : progress(frame, exitAt, 10, easeAccelerate);
  const dy = enter === 'drop' ? -26 * (1 - p) : enter === 'rise' ? 18 * (1 - p) : 0;
  const s = enter === 'pop' ? 0.86 + 0.14 * p : 0.96 + 0.04 * p;
  return {
    opacity: fade * (1 - out),
    transform: `translate3d(0, ${(dy - out * 12).toFixed(2)}px, 0) scale(${s.toFixed(4)})`,
  };
};

export const Chip: React.FC<ChipProps> = ({
  label,
  delay = 0,
  exitAt,
  icon,
  dot,
  emphasis = false,
  mono = false,
  enter = 'rise',
  size = CHIP_SIZE,
  mode,
  lightMix,
  frame: f,
  style,
}) => {
  const current = useDesignFrame();
  const frame = f ?? current;
  const p = usePalette(mode, lightMix);
  const theme = useTheme();
  // On the light film's own light canvas (not mid-way through S02's blend), its chip look.
  const own = theme === 'light' && (mode ?? 'light') === 'light' && (lightMix ?? 1) >= 1;
  const fill = own ? LIGHT_FILM_CHIP.fill : p.chipFill;
  const rim = own ? LIGHT_FILM_CHIP.rim : p.chipRim;
  const pad = chipPadding(size);
  return (
    <div
      style={{
        ...TYPE.chip,
        ...(mono ? {fontFamily: FONT.mono, fontWeight: 400, letterSpacing: 0} : {}),
        fontSize: size,
        display: 'inline-flex',
        alignItems: 'center',
        gap: size * 0.45,
        padding: `${pad.y}px ${pad.x}px`,
        borderRadius: 999,
        color: p.primary,
        background: emphasis ? alpha(p.accentFill, 0.2) : fill,
        boxShadow: `inset 0 0 0 1px ${emphasis ? alpha(p.accentText, 0.42) : rim}`,
        whiteSpace: 'nowrap',
        transformOrigin: 'left center',
        ...objectEnterStyle(frame, delay, exitAt, enter),
        ...style,
      }}
    >
      {dot && (
        <span
          style={{
            width: size * 0.42,
            height: size * 0.42,
            borderRadius: '50%',
            background: dot,
            boxShadow: `0 0 0 ${size * 0.12}px ${alpha(dot, 0.22)}`,
            flex: 'none',
          }}
        />
      )}
      {icon}
      <span style={{opacity: emphasis ? 1 : 0.92}}>
        {typeof label === 'string' ? <TextRuns text={label} /> : label}
      </span>
    </div>
  );
};

export interface ChipRowProps {
  labels: React.ReactNode[];
  /** Frame of the first chip. Default 0. */
  delay?: number;
  /** Frames between chips. Default 4. */
  stagger?: number;
  exitAt?: number;
  gap?: number;
  enter?: 'rise' | 'drop' | 'pop';
  mono?: boolean;
  size?: number;
  mode?: Mode;
  lightMix?: number;
  frame?: number;
  /** Wraps onto several lines when true. Default true. */
  wrap?: boolean;
  style?: React.CSSProperties;
}

/** A row of chips entering one after another. */
export const ChipRow: React.FC<ChipRowProps> = ({
  labels,
  delay = 0,
  stagger = 4,
  exitAt,
  gap = 14,
  enter,
  mono,
  size,
  mode,
  lightMix,
  frame,
  wrap = true,
  style,
}) => (
  <div
    style={{
      display: 'flex',
      flexWrap: wrap ? 'wrap' : 'nowrap',
      gap,
      fontFamily: FONT.sans,
      ...style,
    }}
  >
    {labels.map((l, i) => (
      <Chip
        key={i}
        label={l}
        delay={delay + i * stagger}
        exitAt={exitAt}
        enter={enter}
        mono={mono}
        size={size}
        mode={mode}
        lightMix={lightMix}
        frame={frame}
      />
    ))}
  </div>
);
