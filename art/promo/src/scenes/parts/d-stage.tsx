// Scene builder D: a framed app window treated as an object on the canvas, moved by a camera.
// Unlike components/Camera (which fits a source rect to the whole viewport), each key here says
// which point of the window (normalized 0..1) lands on which screen point, and how wide the
// window is on screen. That lets a scene keep its text column on the left while the camera
// follows the action inside the window on the right. The window is laid out at its on-screen
// size every frame (no CSS scale), so the rim stays a crisp 1 px.
import React from 'react';
import {easeStandard} from '../../easing';

export interface StageKey {
  /** Frame at which the camera reaches this key. */
  at: number;
  /** Point of the window, normalized (0..1 of its width / height). */
  fx: number;
  fy: number;
  /** Screen point (px) the focus point lands on. */
  sx: number;
  sy: number;
  /** On-screen window width (px). */
  w: number;
  /** Easing of the move that ends at this key. Default easeStandard. */
  easing?: (t: number) => number;
}

export interface StagePose {
  /** Window rect on screen (px). */
  left: number;
  top: number;
  width: number;
  height: number;
}

/**
 * Throws unless the keys' frames strictly increase. Keys timed from a clip's manifest events
 * can fall out of order when the clip is re-recorded with other timing; this names the scene
 * and the keys instead of letting the camera jump.
 */
export const checkStageKeys = (scene: string, keys: StageKey[]): void => {
  const bad = keys.flatMap((k, i) =>
    i > 0 && k.at <= keys[i - 1].at ? [`key ${i} at ${k.at} ≤ ${keys[i - 1].at}`] : [],
  );
  if (bad.length > 0) throw new Error(`${scene}: camera keys out of order (${bad.join('; ')})`);
};

/** Window rect at `frame`: width interpolates in log space, focus and screen points linearly. */
export const stagePose = (keys: StageKey[], frame: number, aspect: number): StagePose => {
  let a = keys[0];
  let b = keys[0];
  let u = 0;
  if (frame >= keys[keys.length - 1].at) {
    a = b = keys[keys.length - 1];
  } else if (frame > keys[0].at) {
    for (let i = 0; i < keys.length - 1; i++) {
      if (frame >= keys[i].at && frame < keys[i + 1].at) {
        a = keys[i];
        b = keys[i + 1];
        u = (b.easing ?? easeStandard)((frame - a.at) / (b.at - a.at));
        break;
      }
    }
  }
  const lerp = (x: number, y: number) => x + (y - x) * u;
  const width = Math.exp(lerp(Math.log(a.w), Math.log(b.w)));
  const height = width / aspect;
  return {
    left: lerp(a.sx, b.sx) - lerp(a.fx, b.fx) * width,
    top: lerp(a.sy, b.sy) - lerp(a.fy, b.fy) * height,
    width,
    height,
  };
};

/** Screen point of a normalized window point under a pose. */
export const stagePoint = (p: StagePose, fx: number, fy: number) => ({
  x: p.left + fx * p.width,
  y: p.top + fy * p.height,
});

/** Absolutely positions its child (usually a WindowFrame of the pose's size) at the pose. */
export const StageSlot: React.FC<{
  pose: StagePose;
  style?: React.CSSProperties;
  children: React.ReactNode;
}> = ({pose, style, children}) => (
  <div
    style={{
      position: 'absolute',
      left: pose.left,
      top: pose.top,
      width: pose.width,
      height: pose.height,
      ...style,
    }}
  >
    {children}
  </div>
);
