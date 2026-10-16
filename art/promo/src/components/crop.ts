// Shared crop maths for ClipPlayer / StillImage.
import type React from 'react';

/** A rect of the source image: px by default, or fractions 0..1 with units 'fraction'. */
export interface Crop {
  x: number;
  y: number;
  w: number;
  h: number;
}

/**
 * Style for an <Img> inside an overflow-hidden box so that `crop` fills the box exactly. Give
 * the box the crop's aspect ratio, or the image stretches.
 */
export const cropImgStyle = (
  source: {width: number; height: number},
  crop: Crop,
  units: 'px' | 'fraction' = 'px',
): React.CSSProperties => {
  const c =
    units === 'fraction'
      ? {
          x: crop.x * source.width,
          y: crop.y * source.height,
          w: crop.w * source.width,
          h: crop.h * source.height,
        }
      : crop;
  return {
    position: 'absolute',
    left: `${(-c.x / c.w) * 100}%`,
    top: `${(-c.y / c.h) * 100}%`,
    width: `${(source.width / c.w) * 100}%`,
    height: `${(source.height / c.h) * 100}%`,
    maxWidth: 'none',
    objectFit: 'fill',
  };
};
