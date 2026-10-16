#!/usr/bin/env bash
# Gives a rendered film its cover art: turns the thumbnail PNG (the `Thumbnail` composition, the
# film's own first frame) into a JPEG of the film's size (1920×1080, or 3840×2160 for a 4K60 film)
# and stores that in the mp4 as an attached picture, the image Finder, Quick Look, players and
# upload sites show for the file.
#
#   scripts/embed-cover.sh <film.mp4> <thumbnail.png> [<cover.jpg>]
#
# <cover.jpg> defaults to the film's name with -cover.jpg (out/ketch-promo-en-cover.jpg); it is
# kept, for sites that want a JPEG cover. The film's streams are copied, never re-encoded, and any
# cover art it already has is replaced, so running this twice gives the same file. The mp4 is
# rewritten through a temporary file next to it (hidden, so check-film.mjs never scans it) with
# its index first (+faststart), then moved over the original. render-film.sh runs this after each
# render; run it alone to put a cover on a film rendered without one.
set -euo pipefail

usage() {
  echo "usage: scripts/embed-cover.sh <film.mp4> <thumbnail.png> [<cover.jpg>]" >&2
  exit 2
}
[[ $# -ge 2 && $# -le 3 ]] || usage
film="$1"
thumbnail="$2"
cover="${3:-${film%.mp4}-cover.jpg}"
[[ "$film" == *.mp4 && -f "$film" ]] || { echo "embed-cover: no film at $film" >&2; exit 1; }
[[ -f "$thumbnail" ]] || { echo "embed-cover: no thumbnail at $thumbnail" >&2; exit 1; }

# The cover: the film's size, the best JPEG quality (q 1, about 300 KB at 1080p and 1.2 MB at
# 4K, 50 dB from the PNG), no metadata.
size="$(ffprobe -v error -select_streams V:0 -show_entries stream=width,height \
  -of csv=p=0:s=x "$film")"
ffmpeg -v error -y -i "$thumbnail" -frames:v 1 -vf "scale=${size/x/:}:flags=lanczos" \
  -qmin 1 -q:v 1 -map_metadata -1 "$cover"

# The film's video and audio (0:V is the video, never an attached picture, so a cover from an
# earlier run is dropped), then the new cover as an attached picture.
tmp="$(dirname "$film")/.$(basename "$film" .mp4).cover-tmp.mp4"
trap 'rm -f "$tmp"' EXIT
ffmpeg -v error -y -i "$film" -i "$cover" \
  -map 0:V -map '0:a?' -map 1 -c copy -disposition:v:0 default -disposition:v:1 attached_pic \
  -movflags +faststart "$tmp"
mv -f "$tmp" "$film"
echo "cover art: $cover -> $film"
