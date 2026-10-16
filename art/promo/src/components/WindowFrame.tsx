// App-window presentation: rounded clip, 1 px rim and the storyboard's layered shadow around
// any content (usually a ClipPlayer or StillImage filling it).
import React from 'react';
import {palette, windowShadow, type Mode} from '../theme';
import {useTheme} from '../theme-context';

export interface WindowFrameProps {
  /** Outer size in px. */
  width: number;
  height: number;
  /** Rim, shadow and surface for this canvas. Default the film's theme. */
  mode?: Mode;
  /** Logical width of the window in dp: radius and shadow scale with width / dpWidth (1280). */
  dpWidth?: number;
  /** Corner radius in px. Default 14 dp scaled (14 × width / dpWidth). */
  radius?: number;
  /** Shadow on/off, or a number to scale it further. Default true. */
  shadow?: boolean | number;
  /** Rim on/off. Default true. */
  rim?: boolean;
  /** Fill under the children (visible through transparent corners). Default the surface colour. */
  background?: string;
  style?: React.CSSProperties;
  children?: React.ReactNode;
}

export const windowScale = (width: number, dpWidth = 1280): number => width / dpWidth;

export const WindowFrame: React.FC<WindowFrameProps> = ({
  width,
  height,
  mode: modeProp,
  dpWidth = 1280,
  radius,
  shadow = true,
  rim = true,
  background,
  style,
  children,
}) => {
  const theme = useTheme();
  const mode = modeProp ?? theme;
  const p = palette(mode);
  const s = windowScale(width, dpWidth);
  const r = radius ?? 14 * s;
  const shadowScale = Math.min(1.6, Math.max(0.45, s)) * (typeof shadow === 'number' ? shadow : 1);
  return (
    <div
      style={{
        position: 'relative',
        width,
        height,
        borderRadius: r,
        boxShadow: shadow === false ? undefined : windowShadow(mode, shadowScale),
        flex: 'none',
        ...style,
      }}
    >
      <div
        style={{
          position: 'absolute',
          inset: 0,
          borderRadius: r,
          overflow: 'hidden',
          background: background ?? p.surface,
          // forces a clean anti-aliased clip of transformed children in Chrome
          isolation: 'isolate',
        }}
      >
        {children}
      </div>
      {rim && (
        <div
          style={{
            position: 'absolute',
            inset: 0,
            borderRadius: r,
            boxShadow: `inset 0 0 0 1px ${p.windowRim}`,
            pointerEvents: 'none',
          }}
        />
      )}
    </div>
  );
};
