// Design time. The film is written in design frames: frames at timing.json's `fps` (30), the unit
// of every scene length, beat, cut and scene constant. A composition may render at a multiple of
// that rate (`Promo-60`, 60 fps): scenes then read the time with useDesignFrame(), which turns
// the composition's frame into design frames (fractional between two of them, 0.5 steps at 60
// fps), so every animation keeps its timing and only gets more samples. Never call
// useCurrentFrame() in a scene or a component; place nested timelines with DesignSequence.
//
// At the design rate itself nothing changes: useDesignFrame() is useCurrentFrame() and
// DesignSequence a plain Sequence, so the 30 fps films are the same frames byte for byte.
import React from 'react';
import {Sequence, useCurrentFrame, useVideoConfig, type SequenceProps} from 'remotion';
import {FPS} from './timing';

/** Frame rate of the design frames: timing.json's `fps`. */
export const DESIGN_FPS = FPS;

/**
 * Throws unless `fps` is a whole multiple of the design rate (30, 60, …), so every cut, beat and
 * wipe of timing.json lands on a whole output frame.
 */
export const checkOutputFps = (fps: number): number => {
  if (!Number.isInteger(fps) || fps <= 0 || fps % DESIGN_FPS !== 0)
    throw new Error(`fps ${fps} is not a whole multiple of the design rate (${DESIGN_FPS})`);
  return fps;
};

/**
 * The rates Root registers the film at: the design rate (`Promo`, the 1080p30 film) and twice it
 * (`Promo-60`, rendered at 4K with --scale=2 by `scripts/render-film.sh --4k60`).
 */
export const FILM_RATES: readonly number[] = [DESIGN_FPS, 2 * DESIGN_FPS];

/** A composition's id at `fps`: the bare id at the design rate, else `<id>-<fps>` (`Promo-60`). */
export const rateId = (id: string, fps: number): string =>
  fps === DESIGN_FPS ? id : `${id}-${fps}`;

/**
 * calculateMetadata for a composition `designLength` design frames long: its fps comes from the
 * `fps` prop (default the design rate) and its length follows, so `--props='{"fps":60}'` renders
 * any composition at 60 fps with the same timing.
 */
export const rateMetadata =
  (designLength: number) =>
  ({props}: {props: {fps?: number}}): {fps: number; durationInFrames: number} => {
    const fps = checkOutputFps(props.fps ?? DESIGN_FPS);
    return {fps, durationInFrames: toOutputFrames(designLength, fps)};
  };

/** Design frames as output frames at `fps` (whole for every multiple of the design rate). */
export const toOutputFrames = (designFrames: number, fps: number): number =>
  Math.round((designFrames * fps) / DESIGN_FPS);

/** An output frame at `fps` as design frames (fractional above the design rate). */
export const toDesignFrames = (outputFrame: number, fps: number): number =>
  fps === DESIGN_FPS ? outputFrame : (outputFrame * DESIGN_FPS) / fps;

/**
 * The current frame in design frames, relative to the enclosing (Design)Sequence: what scenes
 * and components time everything by. Fractional above 30 fps.
 */
export const useDesignFrame = (): number => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  return toDesignFrames(frame, fps);
};

export type DesignSequenceProps = Omit<SequenceProps, 'from' | 'durationInFrames'> & {
  /** Start, in design frames. Default 0. */
  from?: number;
  /** Length, in design frames. Default: to the end of the composition. */
  durationInFrames?: number;
};

/** A <Sequence> placed and sized in design frames, at any output rate. */
export const DesignSequence: React.FC<DesignSequenceProps> = ({
  from = 0,
  durationInFrames,
  ...rest
}) => {
  const {fps} = useVideoConfig();
  return (
    <Sequence
      {...rest}
      from={toOutputFrames(from, fps)}
      durationInFrames={
        durationInFrames === undefined ? Infinity : toOutputFrames(durationInFrames, fps)
      }
    />
  );
};
