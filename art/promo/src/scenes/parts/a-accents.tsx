// S02's accent pill (scene builder A): one pill with the four accents, dot + name each; the
// active accent's dot grows and its name brightens. The names come from the scene's copy (the
// app's own names in each language), the colours and order from theme.ts.
import React from 'react';
import {objectEnterStyle} from '../../components/Chip';
import {TextRuns} from '../../components/Text';
import {ACCENT_LIST, FONT, TYPE, alpha, mix, palette, type AccentName} from '../../theme';

export interface AccentPillProps {
  frame: number;
  /** 0 = dark colours, 1 = light. */
  lightMix: number;
  /** Each accent's name in the current language (S02_COPY.accents). */
  names: Record<AccentName, string>;
  /** How active each accent is, 0..1, in ACCENT_LIST order. */
  active: number[];
  /** Entrance and exit frames. */
  delay: number;
  exitAt?: number;
  style?: React.CSSProperties;
}

const SIZE = 22;
const DOT = 12;

export const AccentPill: React.FC<AccentPillProps> = ({
  frame,
  lightMix,
  names,
  active,
  delay,
  exitAt,
  style,
}) => {
  const p = palette('dark', lightMix);
  return (
    <div
      style={{
        ...TYPE.chip,
        fontFamily: FONT.sans,
        fontSize: SIZE,
        display: 'inline-flex',
        alignItems: 'center',
        gap: 30,
        padding: '12px 26px 12px 22px',
        borderRadius: 999,
        background: p.chipFill,
        boxShadow: `inset 0 0 0 1px ${p.chipRim}`,
        whiteSpace: 'nowrap',
        transformOrigin: 'left center',
        ...objectEnterStyle(frame, delay, exitAt, 'rise'),
        ...style,
      }}
    >
      {ACCENT_LIST.map((a, i) => {
        const w = Math.max(0, Math.min(1, active[i] ?? 0));
        return (
          <span key={a.id} style={{display: 'inline-flex', alignItems: 'center', gap: 11}}>
            <span
              style={{
                width: DOT,
                height: DOT,
                borderRadius: '50%',
                background: a.color,
                flex: 'none',
                transform: `scale(${(1 + 0.42 * w).toFixed(4)})`,
                boxShadow: `0 0 0 ${(3 * w).toFixed(2)}px ${alpha(a.color, 0.22 * w)}`,
              }}
            />
            <span style={{color: mix(p.tertiary, p.primary, w)}}>
              <TextRuns text={names[a.id]} />
            </span>
          </span>
        );
      })}
    </div>
  );
};
