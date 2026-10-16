// Camera over content of a known pixel size: keyframes name a rect of the content (source px)
// to frame in the viewport; scale interpolates in log space, the centre linearly.
import React from 'react';
import {easeStandard} from '../easing';
import {useDesignFrame} from '../time';

export interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface CameraKey {
  /** Frame (on the camera's timeline) at which the camera frames `rect`. */
  at: number;
  /** Rect of the content, in source px, to fit into the viewport. */
  rect: Rect;
  /** Easing of the move that ends at this key. Default easeStandard. */
  easing?: (t: number) => number;
}

export interface CameraTransform {
  scale: number;
  /** Content translation in viewport px (applied before scale, origin top-left). */
  x: number;
  y: number;
}

export interface CameraProps {
  /**
   * Content size in source px (e.g. an asset's assetSize, 2560×1600 at 2 px per dp). Children are
   * laid out at this size; rects measured at 2 px per dp go through assetRefScale.
   */
  source: {width: number; height: number};
  /** Viewport size in px. Default 1920×1080. */
  viewport?: {width: number; height: number};
  /** Keyframes, sorted by `at`. Before the first key / after the last the camera holds. */
  keys: CameraKey[];
  /** 'contain' shows the whole rect; 'cover' fills the viewport with it. Default 'contain'. */
  fit?: 'contain' | 'cover';
  /** Clip to the viewport. Default true. */
  clip?: boolean;
  frame?: number;
  style?: React.CSSProperties;
  children?: React.ReactNode;
}

/** The whole content as a rect. */
export const fullRect = (source: {width: number; height: number}): Rect => ({
  x: 0,
  y: 0,
  w: source.width,
  h: source.height,
});

/** `rect` shrunk (factor > 1 zooms in) or grown (factor < 1) around its centre. */
export const zoomRect = (rect: Rect, factor: number): Rect => {
  const w = rect.w / factor;
  const h = rect.h / factor;
  return {x: rect.x + (rect.w - w) / 2, y: rect.y + (rect.h - h) / 2, w, h};
};

const frameRect = (r: Rect, vw: number, vh: number, fit: 'contain' | 'cover') => {
  const scale = fit === 'contain' ? Math.min(vw / r.w, vh / r.h) : Math.max(vw / r.w, vh / r.h);
  return {scale, cx: r.x + r.w / 2, cy: r.y + r.h / 2};
};

/** Camera transform at `frame` (exported so overlays can follow the camera). */
export const cameraAt = (
  keys: CameraKey[],
  frame: number,
  viewport: {width: number; height: number} = {width: 1920, height: 1080},
  fit: 'contain' | 'cover' = 'contain',
): CameraTransform => {
  if (keys.length === 0) return {scale: 1, x: 0, y: 0};
  const {width: vw, height: vh} = viewport;
  let a = keys[0];
  let b = keys[0];
  let u = 0;
  if (frame <= keys[0].at) {
    a = b = keys[0];
  } else if (frame >= keys[keys.length - 1].at) {
    a = b = keys[keys.length - 1];
  } else {
    for (let i = 0; i < keys.length - 1; i++) {
      if (frame >= keys[i].at && frame < keys[i + 1].at) {
        a = keys[i];
        b = keys[i + 1];
        const raw = (frame - a.at) / (b.at - a.at);
        u = (b.easing ?? easeStandard)(raw);
        break;
      }
    }
  }
  const A = frameRect(a.rect, vw, vh, fit);
  const B = frameRect(b.rect, vw, vh, fit);
  const scale = Math.exp(Math.log(A.scale) + (Math.log(B.scale) - Math.log(A.scale)) * u);
  const cx = A.cx + (B.cx - A.cx) * u;
  const cy = A.cy + (B.cy - A.cy) * u;
  return {scale, x: vw / 2 - cx * scale, y: vh / 2 - cy * scale};
};

/** Maps a point in source px to viewport px under a camera transform. */
export const toViewport = (
  t: CameraTransform,
  p: {x: number; y: number},
): {x: number; y: number} => ({
  x: t.x + p.x * t.scale,
  y: t.y + p.y * t.scale,
});

export const Camera: React.FC<CameraProps> = ({
  source,
  viewport = {width: 1920, height: 1080},
  keys,
  fit = 'contain',
  clip = true,
  frame: f,
  style,
  children,
}) => {
  const current = useDesignFrame();
  const frame = f ?? current;
  const t = cameraAt(keys, frame, viewport, fit);
  return (
    <div
      style={{
        position: 'relative',
        width: viewport.width,
        height: viewport.height,
        overflow: clip ? 'hidden' : 'visible',
        ...style,
      }}
    >
      <div
        style={{
          position: 'absolute',
          left: 0,
          top: 0,
          width: source.width,
          height: source.height,
          transformOrigin: '0 0',
          transform: `translate(${t.x}px, ${t.y}px) scale(${t.scale})`,
        }}
      >
        {children}
      </div>
    </div>
  );
};
