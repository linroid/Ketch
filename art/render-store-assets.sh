#!/usr/bin/env bash
# Renders the App Store and Google Play screenshots, the Play feature graphic and the Play icon
# from the StoreScreenshots scenario in app:shared, and copies them into the fastlane folders of
# app/android and app/ios. Shrinks the PNGs losslessly with oxipng when it is installed.
set -euo pipefail

cd "$(dirname "$0")/.."
./gradlew :app:shared:jvmTest -Psnapshots --tests '*StoreScreenshots*' --console=plain

out=app/shared/build/snapshots/store
play=app/android/fastlane/metadata/android/en-US/images
apple=app/ios/fastlane/screenshots/en-US

# copy <from> <to> [prefix]: replaces the PNGs in <to> with those of <from>.
copy() {
  mkdir -p "$2"
  find "$2" -maxdepth 1 -name "${3:-}*.png" -delete
  for file in "$1"/*.png; do
    cp "$file" "$2/${3:-}$(basename "$file")"
  done
}

copy "$out/android-phone" "$play/phoneScreenshots"
copy "$out/android-tablet-10" "$play/tenInchScreenshots"
cp "$out/feature-graphic.png" "$play/featureGraphic.png"
cp "$out/play-icon.png" "$play/icon.png"

copy "$out/iphone-6.9" "$apple" iphone-
copy "$out/ipad-13" "$apple" ipad-

files=("$play"/*.png "$play"/*/*.png "$apple"/*.png)
if command -v oxipng >/dev/null; then
  # --nx keeps the icon's alpha channel, which Play asks for, though the icon is opaque.
  oxipng -o 4 --strip safe --nx "${files[@]}"
else
  echo "oxipng not found: the images were copied without optimizing them" >&2
fi

# png_info <file>: the width, height and color type in the PNG's IHDR chunk, which starts at
# byte 16; plain od, so it works wherever bash does.
png_info() {
  od -An -tu1 -j16 -N10 "$1" | awk '{
    printf "%d %d %d\n", (($1 * 256 + $2) * 256 + $3) * 256 + $4,
      (($5 * 256 + $6) * 256 + $7) * 256 + $8, $10
  }'
}

# expect <width> <height> <alpha> <file>...: fails unless every file is that size, and has an
# alpha channel when <alpha> is yes and none when it is no.
expect() {
  local width=$1 height=$2 alpha=$3 file w h type has
  shift 3
  for file in "$@"; do
    read -r w h type < <(png_info "$file")
    # Color types 4 (gray) and 6 (RGB) carry alpha.
    has=no
    if [[ $type == 4 || $type == 6 ]]; then has=yes; fi
    echo "$w x $h alpha=$has  $file"
    if [[ $w != "$width" || $h != "$height" || $has != "$alpha" ]]; then
      echo "error: $file must be $width x $height with alpha=$alpha" >&2
      exit 1
    fi
  done
}

# The sizes each store slot takes. The App Store refuses images with an alpha channel, and Play
# asks for a 32-bit icon.
expect 1440 2560 no "$play"/phoneScreenshots/*.png
expect 2560 1440 no "$play"/tenInchScreenshots/*.png
expect 1024 500 no "$play/featureGraphic.png"
expect 512 512 yes "$play/icon.png"
expect 1320 2868 no "$apple"/iphone-*.png
expect 2752 2064 no "$apple"/ipad-*.png
