// Scene builder D: one download in six parallel lanes, abstracted: six thin rounded tracks (the
// sail's six rows) that fill left→right, each at its own pace, all complete by `doneAt`. Lane 0
// fills with ember, the others white at the sail's lane alphas (ink on the light canvas); a soft
// glow rides each leading edge while it moves (the ember lane's only, on light, where a dark glow
// would smudge). The same picture as the app's Connections lanes and the intro's sail.
import React from 'react';
import {easeInOut, progress} from '../../easing';
import {EMBER, alpha} from '../../theme';
import {usePalette, useThemed} from '../../theme-context';
import {useDesignFrame} from '../../time';

/** Lane alphas of the sail rows (SailLogo's LANE_ALPHAS, first six). */
const LANE_ALPHAS = [1, 0.72, 0.88, 0.6, 0.94, 0.66];
/** Per lane: start offset after `start`, and how many frames before `doneAt` it completes. */
const LANE_START = [0, 3, 1, 5, 2, 6];
const LANE_EARLY = [8, 0, 14, 4, 11, 2];

export interface LaneBarProps {
  /** Frame at which the lanes start filling. */
  start: number;
  /** Frame by which every lane is full. */
  doneAt: number;
  /** Width of the block: px, or a CSS width such as '100%' to match the parent. */
  width: number | string;
  /** Lane height and the gap between lanes (px). Default 5 and 7. */
  lane?: number;
  gap?: number;
  /** Strength of the white fills at lane alpha 1. Default 0.5. */
  strength?: number;
  style?: React.CSSProperties;
}

/** Height of a LaneBar block. */
export const laneBarHeight = (lane = 5, gap = 7) => 6 * lane + 5 * gap;

export const LaneBar: React.FC<LaneBarProps> = ({
  start,
  doneAt,
  width,
  lane = 5,
  gap = 7,
  strength = 0.5,
  style,
}) => {
  const frame = useDesignFrame();
  const {ink} = usePalette();
  // How strong the white lanes' leading-edge glow is (none for the ink lanes on light).
  const glow = useThemed({dark: 0.35, light: 0});
  // The empty track: a faint grey on dark, a faint ink on light (the grey vanishes there).
  const track = useThemed({dark: 'rgba(133,141,156,0.10)', light: 'rgba(20,26,38,0.07)'});
  return (
    <div style={{position: 'relative', width, height: laneBarHeight(lane, gap), ...style}}>
      {LANE_ALPHAS.map((a, i) => {
        const from = start + LANE_START[i];
        const p = progress(frame, from, doneAt - LANE_EARLY[i] - from, easeInOut);
        const moving = p > 0 && p < 1;
        const ember = i === 0;
        const fill = ember ? EMBER.gradient : alpha(ink, +(strength * a).toFixed(3));
        const head = ember ? alpha(EMBER.from, 0.55) : alpha(ink, +(glow * a).toFixed(3));
        return (
          <div
            key={i}
            style={{
              position: 'absolute',
              left: 0,
              top: i * (lane + gap),
              width,
              height: lane,
              borderRadius: lane / 2,
              background: track,
            }}
          >
            <div
              style={{
                position: 'absolute',
                left: 0,
                top: 0,
                height: lane,
                width: `${(100 * p).toFixed(4)}%`,
                borderRadius: lane / 2,
                background: fill,
                opacity: ember ? 0.9 : 1,
                boxShadow: moving ? `${lane * 0.6}px 0 ${lane * 2.4}px ${head}` : undefined,
              }}
            />
          </div>
        );
      })}
    </div>
  );
};
