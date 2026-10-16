// Plays an image-sequence clip (public/clips/<name>/0000.png…, or .jpg as the manifest's `ext`
// says, or the current locale's copy) frame-accurately, at any output rate. The caller maps scene time to clip time, both in design
// frames (30 fps, src/time.tsx); the clip's own rate (its manifest `fps`, default 30) picks the
// frame: at 60 fps a 30 fps clip holds each frame for two output frames and a 60 fps clip
// advances on every one (clipFrameIndex in assets.ts). Placeholder clips (frames 0) show their
// fallback still.
import React from 'react';
import {Img, staticFile} from 'remotion';
import {useAssets} from '../assets';
import {cropImgStyle, type Crop} from './crop';

export interface ClipPlayerProps {
  /** Clip name in the manifest, e.g. 'desktop-dark'. */
  name: string;
  /**
   * Clip time to show, in design frames since the clip's frame 0 (fractional at 60 fps); the
   * clip's frame at that time is shown, clamped to its first and last.
   */
  frame: number;
  /** object-fit when not cropping. Default 'cover' (size the box to assetSize(name)'s aspect). */
  fit?: React.CSSProperties['objectFit'];
  /** Show only this rect of the clip, filling the box (box should have the crop's aspect). */
  crop?: Crop;
  /**
   * Units of `crop`: source px of the clip (default; at its density, see assetRefScale) or
   * fractions 0..1 (survives asset size and density changes).
   */
  cropUnits?: 'px' | 'fraction';
  style?: React.CSSProperties;
}

/** Fills its parent by default. */
export const ClipPlayer: React.FC<ClipPlayerProps> = ({
  name,
  frame,
  fit = 'cover',
  crop,
  cropUnits = 'px',
  style,
}) => {
  const {assetSize, clipFramePath} = useAssets();
  const src = staticFile(clipFramePath(name, frame));
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
