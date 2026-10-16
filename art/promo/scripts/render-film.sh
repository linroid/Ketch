#!/usr/bin/env bash
# Renders the finished film for one locale or all of them, from a single bundle:
#   out/ketch-promo-<locale>.mp4              the film (H.264 CRF 18, yuv420p bt709, AAC music,
#                                             as remotion.config.ts sets), with cover art
#   out/ketch-promo-<locale>-thumbnail.png    its first frame (the `Thumbnail` composition)
#   out/ketch-promo-<locale>-cover.jpg        the same as a JPEG, embedded in the mp4 as its
#                                             cover art (scripts/embed-cover.sh)
# then prints what ffprobe says about each file.
#
#   scripts/render-film.sh [--master] [--4k60] [--theme light] [<locale>|all]
#                                                    (npm run film / npm run film:all)
#
# <locale> defaults to en; the locales are the ones src/locale.tsx registers. --theme light
# renders the light film (`Promo-light`, `Promo-<locale>-light`, src/theme-context.tsx) to
# out/ketch-promo-<locale>-light.mp4 (-light-4k60.mp4 with --4k60), with its own thumbnail and
# cover (`Thumbnail-light`); dark, the default, is the dark film. --master renders
# the frames as PNG instead of JPEG 95 (Remotion --image-format=png) for the best quality, about
# twice as slow (README.md, "Render the final films"). --4k60 renders the 60 fps film
# (`Promo-60`, the same timing) at 3840 × 2160 (--scale=2) to out/ketch-promo-<locale>-4k60.mp4,
# with -4k60-thumbnail.png and -4k60-cover.jpg at 4K (H.264 High at level 5.2, see
# remotion.config.ts): about 5 minutes per locale on an M4 Pro, longer with --master. Run it
# from anywhere; running it again replaces all three files (a film never gets a second cover).
# Needs the real-UI assets (npm run assets) and the music (npm run music) in public/: with MUSIC
# on (src/config.ts) it refuses to render when music.wav is missing or no longer what
# src/timing.json and audio/ make (scripts/check-music.mjs). The 1080p30 film takes about 1½
# minutes per locale on an M4 Pro, about twice that with --master; the temporary bundle is deleted on exit. Then it scans each film for near-empty frames
# (scripts/check-film.mjs) and exits 1 when one has a run the storyboard does not allow.
set -euo pipefail

usage() {
  echo "usage: scripts/render-film.sh [--master] [--4k60] [--theme dark|light] [<locale>|all]" \
    "  (default: en, dark)" >&2
  exit 2
}
target=""
frames=()
# The film's composition, its output size and the files' suffix: Promo at 1080p30 by default.
film=Promo
scale=()
variant=""
# The film's theme: its compositions' and files' suffix (none for the dark film).
theme=""
while (( $# > 0 )); do
  case "$1" in
    --master) frames=(--image-format=png) ;;
    --4k60) film=Promo-60; scale=(--scale=2); variant=-4k60 ;;
    --theme)
      (( $# >= 2 )) || usage
      case "$2" in
        dark) theme="" ;;
        light) theme=-light ;;
        *) usage ;;
      esac
      shift
      ;;
    -*) usage ;;
    *) [[ -z "$target" ]] || usage; target="$1" ;;
  esac
  shift
done
target="${target:-en}"

cd "$(dirname "$0")/.."

if ! command -v ffprobe >/dev/null; then
  echo "ffprobe not found (brew install ffmpeg)" >&2
  exit 1
fi
node scripts/check-music.mjs

bundle="$(mktemp -d "${TMPDIR:-/tmp}/ketch-promo-bundle.XXXXXX")"
trap 'rm -rf "$bundle"' EXIT
node scripts/bundle.mjs "$bundle" >/dev/null

# Locales the bundle registers: `Promo` is en, `Promo-<locale>` the others (a locale starts with
# a letter; `Promo-60`, `Promo-60-<locale>` are the 60 fps films, and ids ending in -light the
# light film's).
ids="$(npx remotion compositions "$bundle" --quiet 2>/dev/null | tail -n 1)"
available=()
for id in $ids; do
  case "$id" in
    *-light) ;;
    Promo) available+=(en) ;;
    Promo-[A-Za-z]*) available+=("${id#Promo-}") ;;
  esac
done

if [[ "$target" == all ]]; then
  locales=("${available[@]}")
else
  if [[ " ${available[*]} " != *" $target "* ]]; then
    echo "unknown locale $target (the film has: ${available[*]})" >&2
    exit 2
  fi
  locales=("$target")
fi

mkdir -p out
for locale in "${locales[@]}"; do
  suffix=""
  [[ "$locale" == en ]] || suffix="-$locale"
  suffix+="$theme"
  file="out/ketch-promo-$locale$theme$variant.mp4"
  thumbnail="out/ketch-promo-$locale$theme$variant-thumbnail.png"
  echo "== $film$suffix -> $file${frames[*]:+ (master: PNG frames)}"
  npx remotion render "$bundle" "$film$suffix" "$file" \
    ${frames[@]+"${frames[@]}"} ${scale[@]+"${scale[@]}"}
  # The cover art: the film's first frame, rendered on its own (Thumbnail = S01 frozen at 0), at
  # the film's size.
  echo "== Thumbnail$suffix -> $thumbnail"
  npx remotion still "$bundle" "Thumbnail$suffix" "$thumbnail" --frame=0 --image-format=png \
    ${scale[@]+"${scale[@]}"}
  scripts/embed-cover.sh "$file" "$thumbnail"
done

probe() { ffprobe -v error "$@" -of compact=p=0 "$file"; }
echo
for locale in "${locales[@]}"; do
  file="out/ketch-promo-$locale$theme$variant.mp4"
  echo "== $file ($(du -h "$file" | cut -f1 | tr -d ' '))"
  echo "  file:  $(probe -show_entries format=duration,bit_rate)"
  video_fields=codec_name,profile,level,width,height,r_frame_rate,nb_frames,pix_fmt
  video_fields+=,color_range,color_space,color_primaries,color_transfer
  echo "  video: $(probe -select_streams V:0 -show_entries "stream=$video_fields")"
  audio="$(probe -select_streams a:0 -show_entries stream=codec_name,sample_rate,channels)"
  echo "  audio: ${audio:-NONE (is MUSIC off in src/config.ts?)}"
  cover_fields=stream=codec_name,width,height:stream_disposition=attached_pic
  cover="$(probe -select_streams v -show_entries "$cover_fields" | grep 'attached_pic=1' || true)"
  echo "  cover: ${cover:-NONE (scripts/embed-cover.sh)}"
done

# The storyboard's soft-cut rule: no near-empty run over 3 frames outside the wipes and fades.
echo
status=0
for locale in "${locales[@]}"; do
  node scripts/check-film.mjs "$locale$theme$variant" || status=1
done
exit "$status"
