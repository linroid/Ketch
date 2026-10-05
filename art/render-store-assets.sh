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

# The App Store refuses images with an alpha channel; only the Play icon may have one.
for file in "${files[@]}"; do
  read -r width height alpha < <(
    sips -g pixelWidth -g pixelHeight -g hasAlpha "$file" | awk 'NR > 1 { print $2 }' |
      paste -sd ' ' -
  )
  echo "$width x $height alpha=$alpha  $file"
  if [[ "$alpha" == "yes" && "$file" != "$play/icon.png" ]]; then
    echo "error: $file has an alpha channel" >&2
    exit 1
  fi
done
