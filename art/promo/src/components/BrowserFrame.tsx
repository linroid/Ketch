// Minimal browser chrome (traffic lights, back/forward, URL pill, optional Ketch extension
// button) in a WindowFrame. Children fill the page area below the toolbar.
import React from 'react';
import {Img, staticFile} from 'remotion';
import {FONT, alpha, palette, type Mode} from '../theme';
import {useTheme} from '../theme-context';
import {WindowFrame} from './WindowFrame';

export interface BrowserFrameProps {
  /** Outer size in px, toolbar included. */
  width: number;
  height: number;
  /** Text in the URL pill. */
  url?: string;
  /** Light or dark chrome. Default the film's theme. */
  mode?: Mode;
  /** Chrome scale (1 = a 1280-px-wide browser at 1:1). Default width / 1280, clamped 0.8..1.6. */
  scale?: number;
  /** Show the Ketch extension button at the right of the toolbar. */
  extension?: boolean;
  /** Highlight the extension button (its popup is open). */
  extensionActive?: boolean;
  /** Page background under the children. */
  pageBackground?: string;
  style?: React.CSSProperties;
  children?: React.ReactNode;
}

/** Toolbar height in px for a given chrome scale. */
export const browserToolbarHeight = (scale = 1): number => Math.round(52 * scale);

/** Default chrome scale for a browser of this width. */
export const browserScale = (width: number): number => Math.min(1.6, Math.max(0.8, width / 1280));

/**
 * Position (relative to the frame's top-left) of the extension button's bottom-centre, where an
 * extension popup hangs from.
 */
export const browserExtensionAnchor = (
  width: number,
  scale = browserScale(width),
): {x: number; y: number} => ({
  x: width - 60 * scale,
  y: browserToolbarHeight(scale) - 6 * scale,
});

const Chevron: React.FC<{dir: 'left' | 'right'; size: number; color: string}> = ({
  dir,
  size,
  color,
}) => (
  <svg width={size} height={size} viewBox="0 0 16 16" style={{display: 'block'}}>
    <path
      d={dir === 'left' ? 'M10 3.5 L5.5 8 L10 12.5' : 'M6 3.5 L10.5 8 L6 12.5'}
      fill="none"
      stroke={color}
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
);

const Lock: React.FC<{size: number; color: string}> = ({size, color}) => (
  <svg width={size} height={size} viewBox="0 0 16 16" style={{display: 'block', flex: 'none'}}>
    <rect x="3.5" y="7" width="9" height="6.5" rx="1.6" fill={color} />
    <path d="M5.5 7 V5.2 a2.5 2.5 0 0 1 5 0 V7" fill="none" stroke={color} strokeWidth="1.5" />
  </svg>
);

export const BrowserFrame: React.FC<BrowserFrameProps> = ({
  width,
  height,
  url = '',
  mode: modeProp,
  scale,
  extension = false,
  extensionActive = false,
  pageBackground,
  style,
  children,
}) => {
  const theme = useTheme();
  const mode = modeProp ?? theme;
  const s = scale ?? browserScale(width);
  const p = palette(mode);
  const dark = mode === 'dark';
  const bar = browserToolbarHeight(s);
  const toolbarBg = dark ? '#1E2128' : '#F3F4F7';
  const pillBg = dark ? 'rgba(255,255,255,0.06)' : '#FFFFFF';
  const pillRim = dark ? 'rgba(255,255,255,0.07)' : '#E2E6EE';
  const icon = dark ? '#858D9C' : '#7A8293';
  const pillW = Math.min(620 * s, width * 0.5);
  return (
    <WindowFrame
      width={width}
      height={height}
      mode={mode}
      radius={12 * s}
      background={pageBackground ?? p.surface}
      style={style}
    >
      <div
        style={{
          position: 'absolute',
          left: 0,
          top: 0,
          right: 0,
          height: bar,
          background: toolbarBg,
          borderBottom: `1px solid ${dark ? 'rgba(255,255,255,0.06)' : '#E2E6EE'}`,
          display: 'flex',
          alignItems: 'center',
          fontFamily: FONT.sans,
          zIndex: 2,
        }}
      >
        <div style={{display: 'flex', gap: 8 * s, marginLeft: 18 * s}}>
          {['#FF5F57', '#FEBC2E', '#28C840'].map((c) => (
            <div
              key={c}
              style={{
                width: 12 * s,
                height: 12 * s,
                borderRadius: '50%',
                background: c,
                opacity: dark ? 0.75 : 0.9,
              }}
            />
          ))}
        </div>
        <div style={{display: 'flex', gap: 10 * s, marginLeft: 26 * s}}>
          <Chevron dir="left" size={16 * s} color={icon} />
          <Chevron dir="right" size={16 * s} color={alpha(icon, 0.5)} />
        </div>
        <div
          style={{
            position: 'absolute',
            left: (width - pillW) / 2,
            width: pillW,
            height: 32 * s,
            borderRadius: 16 * s,
            background: pillBg,
            boxShadow: `inset 0 0 0 1px ${pillRim}`,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 7 * s,
            color: dark ? '#AEB4C0' : '#4B5466',
            fontSize: 15 * s,
            fontWeight: 500,
            letterSpacing: '-0.005em',
            whiteSpace: 'nowrap',
            overflow: 'hidden',
          }}
        >
          <Lock size={12 * s} color={icon} />
          <span>{url}</span>
        </div>
        {extension && (
          <div
            style={{
              position: 'absolute',
              right: 44 * s,
              width: 32 * s,
              height: 32 * s,
              borderRadius: 8 * s,
              background: extensionActive
                ? dark
                  ? 'rgba(255,255,255,0.10)'
                  : 'rgba(20,26,38,0.07)'
                : 'transparent',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Img
              src={staticFile('brand/extension-icon-128.png')}
              style={{width: 20 * s, height: 20 * s}}
            />
          </div>
        )}
        <div
          style={{
            position: 'absolute',
            right: 18 * s,
            display: 'flex',
            flexDirection: 'column',
            gap: 3 * s,
          }}
        >
          {[0, 1, 2].map((i) => (
            <div
              key={i}
              style={{width: 3.2 * s, height: 3.2 * s, borderRadius: '50%', background: icon}}
            />
          ))}
        </div>
      </div>
      <div
        style={{position: 'absolute', left: 0, right: 0, top: bar, bottom: 0, overflow: 'hidden'}}
      >
        {children}
      </div>
    </WindowFrame>
  );
};
