// Animated number in Inter Display SemiBold with tabular figures.
import React from 'react';
import {easeStandard, progress} from '../easing';
import {TYPE} from '../theme';
import {useDesignFrame} from '../time';

export interface CountUpProps {
  /** Final value. */
  to: number;
  /** Start value. Default 0. */
  from?: number;
  /** Frame the count starts. Default 0. */
  start?: number;
  /** Frames to reach `to`. Default 30. */
  duration?: number;
  /** Easing of the count. Default easeStandard. */
  easing?: (t: number) => number;
  /** Decimal places for the default formatter. Default 0. */
  decimals?: number;
  /** Custom formatter (receives the current value). */
  format?: (value: number) => string;
  frame?: number;
  style?: React.CSSProperties;
}

/** Current value of a count-up (for callers that render the number themselves). */
export const countValue = (
  frame: number,
  {
    from = 0,
    to,
    start = 0,
    duration = 30,
    easing = easeStandard,
  }: Pick<CountUpProps, 'from' | 'to' | 'start' | 'duration' | 'easing'>,
): number => from + (to - from) * progress(frame, start, duration, easing);

export const CountUp: React.FC<CountUpProps> = ({
  to,
  from = 0,
  start = 0,
  duration = 30,
  easing = easeStandard,
  decimals = 0,
  format,
  frame: f,
  style,
}) => {
  const current = useDesignFrame();
  const frame = f ?? current;
  const v = countValue(frame, {from, to, start, duration, easing});
  const text = format ? format(v) : v.toFixed(decimals);
  return <span style={{...TYPE.number, whiteSpace: 'nowrap', ...style}}>{text}</span>;
};
