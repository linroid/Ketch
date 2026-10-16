// Shared desktop stage for S02 (Modern UI) and S03 (Parallel lanes), by scene builder A.
//
// The window is the film's own theme's desktop clip (`desktop-dark`, `desktop-light`:
// desktopClip); S02 also shows the other theme's for its beat.
// Both scenes draw the desktop window at its source pixel size under one camera transform
// (source px -> viewport px), so S03 can start exactly where S02 ends and then zoom into the
// inspector's Connections lanes. The clip mapping is the storyboard's (timing.ts,
// desktopClipFrame): clip frame = global frame - S02's start, i.e. S02 local frame, or S03 local
// frame + S02's length.
//
// Sizes and the lanes' place come from the current locale's assets (the helpers take the
// AssetResolver a scene gets from useAssets()), so the Chinese film frames the Chinese UI. The
// window is laid out in the clip's source px, whatever its density; the constants below that
// were measured on the clip are in reference px (px at 2 px per dp) and go through
// assetRefScale, so the window looks the same on screen at any density, only sharper.
import React from 'react';
import {AbsoluteFill} from 'remotion';
import {useAssets, type AssetResolver, type NormRect} from '../../assets';
import type {CameraTransform, Rect} from '../../components/Camera';
import {WindowFrame} from '../../components/WindowFrame';
import {lightMixOf, palette, windowShadow, type Mode} from '../../theme';
import {useTheme} from '../../theme-context';
import {HEIGHT, WIDTH, scene} from '../../timing';

/** The clip both scenes play: the desktop window in the film's theme (`desktop-dark`, …). */
export const desktopClip = (a: AssetResolver): string => a.themed('desktop');
/** S02's length; S03 continues S02's timeline (camera, clip and canvas) from here. */
export const S02_LEN = scene('S02').len;

/**
 * LAYOUT: S02 framing once the window has settled: its top-left corner and width in viewport px,
 * chosen on the app's layout. The whole width shows (sidebar, table, inspector and the
 * accent-coloured Add button, so the accent beat reads) and the bottom ~45% is cropped by the
 * frame edge. The right edge starts at the right safe line (1790) and stays within ~1815 through
 * the push. Retune `w` (and `x`, to keep the right edge) if the window's proportions change.
 */
export const S02_WINDOW = {x: 330, y: 575, w: 1460} as const;
/** S02's slow push: the scale grows by this much over S02's length, about the window's top. */
export const S02_PUSH = 0.035;

/**
 * LAYOUT: fallback for the manifest's `bounds.connectionsBlock` on the desktop clip (the
 * inspector's tab row down to the bottom of the Connections tab, PromoSnapshots.kt
 * AssetBounds.Desktop), used while the manifest has none: what the harness measured on the
 * English desktop clips at 2 px per dp (2624×1640), x 1936–2576, y 627–1351, from the tab row (a
 * few px inside the tab pill's outline, whose top is at y≈622) to the bottom of the "re-splits
 * the remaining bytes" note. Fractions, so they hold at any density. Copy the manifest's values
 * here if the app's layout changes.
 */
const CONNECTIONS_BLOCK_FALLBACK: NormRect = {x: 0.7378, y: 0.3823, w: 0.2439, h: 0.4415};

/** The inspector's tab row and Connections tab, in source px of the locale's desktop clip. */
export const connectionsBlock = (a: AssetResolver): Rect =>
  a.assetBoundsPx(desktopClip(a), 'connectionsBlock', CONNECTIONS_BLOCK_FALLBACK);

/**
 * LAYOUT: fallback for the manifest's `bounds.inspector` on the desktop clip (the inspector pane,
 * its divider included), as the harness measured it on the English clips: x 1904–2608.
 */
const INSPECTOR_FALLBACK: NormRect = {x: 0.7256, y: 0.122, w: 0.2683, h: 0.8293};

/** The inspector pane, from its divider, in source px of the locale's desktop clip. */
export const inspectorPane = (a: AssetResolver): Rect =>
  a.assetBoundsPx(desktopClip(a), 'inspector', INSPECTOR_FALLBACK);

/**
 * LAYOUT: where S03's zoom puts the connections block: its left edge in viewport px and its
 * scale in viewport px per reference px (px at 2 px per dp; lanesScale converts it for the
 * clip's density), vertically centred. 1.0 shows the window at 2 px per dp, a 2× clip pixel for
 * pixel; the English block then spans y≈178–902, inside the safe area, and the window's right
 * edge (the inspector is 704 reference px wide) sits at x≈1750. Lower `left` if the inspector
 * gets wider, so the window's edge stays inside the right safe line (1800).
 */
export const LANES_VIEW = {left: 1060, scale: 1} as const;

/** LANES_VIEW's scale for the locale's desktop clip: viewport px per source px. */
const lanesScale = (a: AssetResolver): number => LANES_VIEW.scale / a.assetRefScale(desktopClip(a));

/**
 * S03's left mask dissolves the window into the canvas left of the inspector, so the copy sits
 * on the canvas: from `lead` px left of the inspector's divider (so the divider goes too) to
 * `into` px inside the connections block (viewport px at LANES_VIEW's framing).
 */
const LEFT_FADE = {lead: 32, into: 8} as const;

/** Viewport x where S03's left mask starts (window gone) and ends (window whole). */
export const lanesFadeLeft = (a: AssetResolver): {from: number; to: number} => {
  const divider = LANES_VIEW.left - (connectionsBlock(a).x - inspectorPane(a).x) * lanesScale(a);
  return {from: divider - LEFT_FADE.lead, to: LANES_VIEW.left + LEFT_FADE.into};
};

/**
 * S03's top mask dissolves the rows above the tab pill (Pause, Send to) over `len` reference px
 * (px at 2 px per dp), ending `clearance` reference px above the block's top. LAYOUT: the block
 * starts at the tab row, ~5 px inside the tab pill's outline, so 7 keeps the pill whole; the
 * Pause / Send to row ends ~35 px above the block (English clip at 2 px per dp: y≈588 against
 * 627), inside the fade.
 */
const TOP_FADE = {len: 30, clearance: 7} as const;

/** Source rows (desktop clip px) between the Pause / Send to row and the tab pill. */
export const lanesTopGap = (a: AssetResolver): {from: number; to: number} => {
  const k = a.assetRefScale(desktopClip(a));
  const to = Math.round(connectionsBlock(a).y) - TOP_FADE.clearance * k;
  return {from: to - TOP_FADE.len * k, to};
};

/** Rect of the desktop source visible in the viewport under a camera transform. */
const viewRect = (scale: number, x: number, y: number): Rect => ({
  x: -x / scale,
  y: -y / scale,
  w: WIDTH / scale,
  h: HEIGHT / scale,
});

/** Desktop clip size in px (from the locale's manifest). */
export const desktopSize = (a: AssetResolver) => a.assetSize(desktopClip(a));

/** Camera transform of S02's framing at S02-local frame `f` (S03 continues it past S02_LEN). */
export const s02Transform = (a: AssetResolver, f: number): CameraTransform => {
  const src = desktopSize(a);
  const push = 1 + (S02_PUSH * f) / S02_LEN;
  const half = S02_WINDOW.w / 2;
  return {
    scale: (S02_WINDOW.w / src.width) * push,
    x: S02_WINDOW.x + half - half * push,
    y: S02_WINDOW.y,
  };
};

/** Visible source rect of S02's framing at S02-local frame `f` (a Camera key rect). */
export const s02Rect = (a: AssetResolver, f: number): Rect => {
  const t = s02Transform(a, f);
  return viewRect(t.scale, t.x, t.y);
};

/** Camera rect that puts the connections block where LANES_VIEW says. */
export const lanesRect = (a: AssetResolver): Rect => {
  const b = connectionsBlock(a);
  const k = lanesScale(a);
  return viewRect(k, LANES_VIEW.left - b.x * k, HEIGHT / 2 - (b.y + b.h / 2) * k);
};

/**
 * Shadow scale at the window's size in reference px (px at 2 px per dp; times assetRefScale at
 * source size): reads like a ~1500 px window's shadow at S02's scale.
 */
const SHADOW = 2;

export interface DesktopWindowProps {
  /** Source px -> viewport px. */
  t: CameraTransform;
  /** 0 = dark window chrome (rim, shadow, surface), 1 = light. Default the film's theme's. */
  lightMix?: number;
  /** Optional 3D entrance applied in viewport space. */
  entrance?: {transform?: string; origin?: string; opacity?: number};
  /** Window content, laid out at the clip's source size (usually stacked ClipPlayers). */
  children?: React.ReactNode;
}

/**
 * The desktop window at source size under a camera transform: WindowFrame's rounded clip, the
 * storyboard shadow (scaled with the camera, blended between its dark and light versions) and a
 * rim that stays 1 viewport px wide at any zoom.
 */
export const DesktopWindow: React.FC<DesktopWindowProps> = ({
  t,
  lightMix: lightMixProp,
  entrance,
  children,
}) => {
  const assets = useAssets();
  const theme = useTheme();
  const lightMix = lightMixProp ?? lightMixOf(theme);
  const src = desktopSize(assets);
  const dpWidth = src.width / assets.assetDensity(desktopClip(assets));
  const radius = (14 * src.width) / dpWidth;
  const shadow = SHADOW * assets.assetRefScale(desktopClip(assets));
  const p = palette('dark', lightMix);
  const tilted = entrance?.transform !== undefined;
  const shadowBox = (mode: Mode, opacity: number) =>
    opacity <= 0 ? null : (
      <div
        style={{
          position: 'absolute',
          inset: 0,
          borderRadius: radius,
          boxShadow: windowShadow(mode, shadow),
          opacity: opacity >= 1 ? undefined : opacity,
        }}
      />
    );
  return (
    <AbsoluteFill style={{perspective: tilted ? 2200 : undefined, opacity: entrance?.opacity}}>
      <AbsoluteFill style={{transform: entrance?.transform, transformOrigin: entrance?.origin}}>
        <div
          style={{
            position: 'absolute',
            left: 0,
            top: 0,
            width: src.width,
            height: src.height,
            transformOrigin: '0 0',
            transform: `translate(${t.x}px, ${t.y}px) scale(${t.scale})`,
          }}
        >
          {shadowBox('dark', 1 - lightMix)}
          {shadowBox('light', lightMix)}
          <WindowFrame
            width={src.width}
            height={src.height}
            dpWidth={dpWidth}
            rim={false}
            shadow={false}
            background={p.surface}
          >
            {children}
            <div
              style={{
                position: 'absolute',
                inset: 0,
                borderRadius: radius,
                boxShadow: `inset 0 0 0 ${(1 / t.scale).toFixed(3)}px ${p.windowRim}`,
                pointerEvents: 'none',
              }}
            />
          </WindowFrame>
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

/** A full-size layer inside the window, for stacking clips and stills. */
export const WindowLayer: React.FC<{opacity?: number; children: React.ReactNode}> = ({
  opacity = 1,
  children,
}) =>
  opacity <= 0 ? null : (
    <AbsoluteFill style={{opacity: opacity >= 1 ? undefined : opacity}}>{children}</AbsoluteFill>
  );
