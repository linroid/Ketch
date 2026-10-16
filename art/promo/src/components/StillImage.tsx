// Shows a still by manifest name, in the current locale (English when the locale has none): its
// path, else a clip's fallback, else stills/<name>.png.
import React from 'react';
import {Img, staticFile} from 'remotion';
import {useAssets} from '../assets';
import {cropImgStyle, type Crop} from './crop';

export interface StillImageProps {
  /** Still name in the manifest (a clip name also works and shows its fallback). */
  name: string;
  /** object-fit when not cropping. Default 'cover'. */
  fit?: React.CSSProperties['objectFit'];
  /** Show only this rect of the image, filling the box (box should have the crop's aspect). */
  crop?: Crop;
  /**
   * Units of `crop`: source px of the still (default; at its density, see assetRefScale) or
   * fractions 0..1 (survives asset size and density changes).
   */
  cropUnits?: 'px' | 'fraction';
  style?: React.CSSProperties;
}

/** Fills its parent by default. */
export const StillImage: React.FC<StillImageProps> = ({
  name,
  fit = 'cover',
  crop,
  cropUnits = 'px',
  style,
}) => {
  const {assetSize, stillPath} = useAssets();
  const src = staticFile(stillPath(name));
  if (!crop)
    return (
      <Img
        src={src}
        style={{display: 'block', width: '100%', height: '100%', objectFit: fit, ...style}}
      />
    );
  return (
    <div
      style={{position: 'relative', width: '100%', height: '100%', overflow: 'hidden', ...style}}
    >
      <Img src={src} style={cropImgStyle(assetSize(name), crop, cropUnits)} />
    </div>
  );
};
