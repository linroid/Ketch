# Media fixtures

Tiny HLS and DASH streams that `MediaDownloadIntegrationTest` serves over loopback to check the HLS
and DASH download sources end to end. This note says what they contain and how to regenerate them.

The fixtures were generated for these tests from a solid blue 16 × 16 image at two frames per
second, for two seconds. They contain no third-party media. FFmpeg is only needed to regenerate
them; the tests do not depend on it.

## What the tests expect

- **HLS** (`hls/`): the download concatenates `index0.ts` and `index1.ts`.
- **DASH** (`dash/`): the download concatenates `init-stream0.m4s`, `chunk-stream0-00001.m4s` and
  `chunk-stream0-00002.m4s`, in that order.

Both contain four decoded video frames. The tests verify the exact concatenated bytes.

## Regenerate them

Run from this directory:

```sh
ffmpeg -f lavfi -i color=c=blue:s=16x16:r=2 -t 2 -c:v libx264 -g 2 -f hls -hls_time 1 -hls_list_size 0 hls/index.m3u8
```

```sh
ffmpeg -f lavfi -i color=c=blue:s=16x16:r=2 -t 2 -c:v libx264 -g 2 -f dash -seg_duration 1 -use_timeline 1 -use_template 1 dash/index.mpd
```

See [media support and limits](../../../../../../docs/media.md) for what the sources accept.
