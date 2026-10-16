// Easing curves and small timing helpers shared by every scene.
import {Easing, interpolate} from 'remotion';

/** cubic-bezier(0.2, 0, 0, 1): UI moves, camera moves. */
export const easeStandard = Easing.bezier(0.2, 0, 0, 1);
/** cubic-bezier(0.05, 0.7, 0.1, 1): entrances. */
export const easeDecelerate = Easing.bezier(0.05, 0.7, 0.1, 1);
/** cubic-bezier(0.3, 0, 0.8, 0.15): exits. */
export const easeAccelerate = Easing.bezier(0.3, 0, 0.8, 0.15);
/** Symmetric ease for crossfades and loops. */
export const easeInOut = Easing.bezier(0.45, 0, 0.55, 1);

/** Spring config for text (no bounce). */
export const SPRING_TEXT = {damping: 200} as const;
/** The bounciest spring allowed for objects. */
export const SPRING_OBJECT = {damping: 18, stiffness: 120} as const;

/** 0..1 progress of `frame` through [start, start + duration], clamped and eased. */
export const progress = (
  frame: number,
  start: number,
  duration: number,
  easing: (t: number) => number = easeStandard,
): number =>
  interpolate(frame, [start, start + Math.max(duration, 0.0001)], [0, 1], {
    easing,
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

/** Clamped interpolate (the form used 95% of the time). */
export const lerpClamp = (
  frame: number,
  input: readonly number[],
  output: readonly number[],
  easing?: (t: number) => number,
): number =>
  interpolate(frame, input as number[], output as number[], {
    easing,
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

/** Linear mix of two numbers. */
export const mixNum = (a: number, b: number, t: number): number => a + (b - a) * t;

/** Enter/exit envelope: 0 before `enterAt`, 1 while shown, 0 after `exitAt + exitLen`. */
export const envelope = (
  frame: number,
  enterAt: number,
  enterLen: number,
  exitAt?: number,
  exitLen = 10,
): {enter: number; exit: number} => ({
  enter: progress(frame, enterAt, enterLen, easeDecelerate),
  exit: exitAt === undefined ? 0 : progress(frame, exitAt, exitLen, easeAccelerate),
});
