import {Config} from '@remotion/cli/config';

// Verified on M4 Pro / macOS 27, Remotion 4.0.532.
Config.setVideoImageFormat('jpeg'); // 'png' for the master render (~2x slower, ~+0.6 dB)
Config.setJpegQuality(95); // default 80 visibly hurts UI text and bloats the h264
// Metal-backed GPU compositing: 3-7x faster on blur, shadows and 3D.
Config.setChromiumOpenGlRenderer('angle');
Config.setColorSpace('bt709'); // proper yuv420p tv-range bt709 tags instead of yuvj420p/bt470bg
Config.setConcurrency(7);
Config.setCodec('h264');
Config.setCrf(18);
Config.setOverwriteOutput(true);

/**
 * H.264 above 1080p (the 4K60 film, `Promo-60` with --scale=2): High profile at level 5.2, the
 * level 3840 × 2160 at 60 fps needs, so players and upload sites read it as standard 4K60. The
 * 1080p30 film keeps x264's own choice (High, level 4.0), untouched.
 */
const UHD_H264 = ['-profile:v', 'high', '-level:v', '5.2'];
Config.overrideFfmpegCommand(({args}) => {
  const encoder = args.indexOf('libx264');
  const size = args.indexOf('-s');
  const height = size < 0 ? 0 : Number(String(args[size + 1]).split('x')[1]);
  if (encoder < 0 || !(height > 1080)) return args;
  return [...args.slice(0, encoder + 1), ...UHD_H264, ...args.slice(encoder + 1)];
});
