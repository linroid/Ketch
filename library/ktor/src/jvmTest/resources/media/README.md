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
- **DASH with separate tracks** (`dash-tracks/`): two video representations (32 × 32 and
  16 × 16) and an audio adaptation set (a 440 Hz sine, AAC, 8 kHz mono). The download merges the
  best video representation and the audio into one MP4.
- **HLS with separate tracks** (`hls-tracks/`): a master playlist whose video variant plays with a
  separate audio rendition, both fragmented MP4. Their media segments are AES-128 encrypted with
  `key.bin` (the bytes `00 01 … 0f`), each with its media sequence number as IV; the
  initialization segments are not encrypted. The master also lists the audio playlist as a
  variant of its own, as FFmpeg writes it.

The first two contain four decoded video frames, and the tests verify the exact concatenated
bytes. For the separate tracks the tests check that the merged file holds both tracks and every
fragment's media data, decrypted, in the order the fragments start.

## Regenerate them

Run from this directory:

```sh
ffmpeg -f lavfi -i color=c=blue:s=16x16:r=2 -t 2 -c:v libx264 -g 2 -f hls -hls_time 1 -hls_list_size 0 hls/index.m3u8
```

```sh
ffmpeg -f lavfi -i color=c=blue:s=16x16:r=2 -t 2 -c:v libx264 -g 2 -f dash -seg_duration 1 -use_timeline 1 -use_template 1 dash/index.mpd
```

```sh
ffmpeg -f lavfi -i color=c=blue:s=32x32:r=2 -f lavfi -i sine=frequency=440:sample_rate=8000 -t 2 \
  -map 0:v -map 0:v -map 1:a -c:v libx264 -g 2 -s:v:0 32x32 -b:v:0 40k -s:v:1 16x16 -b:v:1 20k \
  -c:a aac -ac 1 -b:a 16k -f dash -seg_duration 1 -use_timeline 1 -use_template 1 \
  -adaptation_sets "id=0,streams=v id=1,streams=a" dash-tracks/index.mpd
```

FFmpeg cannot encrypt fragmented MP4 HLS, so `hls-tracks/` is written in the clear, then each
media segment is encrypted with OpenSSL and the media playlists get an `EXT-X-KEY` line after
their `EXT-X-MAP`:

```sh
cd hls-tracks
ffmpeg -f lavfi -i color=c=red:s=16x16:r=2 -f lavfi -i sine=frequency=440:sample_rate=8000 -t 2 \
  -map 0:v -map 1:a -c:v libx264 -g 2 -c:a aac -ac 1 -b:a 16k -f hls -hls_time 1 \
  -hls_list_size 0 -hls_playlist_type vod -hls_segment_type fmp4 -master_pl_name index.m3u8 \
  -var_stream_map "v:0,agroup:aud,name:video a:0,agroup:aud,name:audio" \
  -hls_fmp4_init_filename init_%v.mp4 -hls_segment_filename 'seg_%v_%d.m4s' 'stream_%v.m3u8'
printf '\x00\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f' > key.bin
for f in seg_*.m4s; do
  n=${f%.m4s}; n=${n##*_}
  openssl enc -aes-128-cbc -K 000102030405060708090a0b0c0d0e0f -iv "$(printf '%032x' "$n")" \
    -in "$f" -out "$f.enc" && mv "$f.enc" "$f"
done
# BSD sed (macOS); with GNU sed, use `sed -i` without the empty argument.
sed -i '' 's|^\(#EXT-X-MAP:.*\)$|\1\
#EXT-X-KEY:METHOD=AES-128,URI="key.bin"|' stream_audio.m3u8 stream_video.m3u8
```

See [media support and limits](../../../../../../docs/media.md) for what the sources accept.
