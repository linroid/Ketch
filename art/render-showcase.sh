#!/usr/bin/env bash
# Renders the README showcase (art/showcase-light.png, art/showcase-dark.png) from the
# ShowcaseSnapshots scenario in app:shared, then shrinks them losslessly with oxipng when it is
# installed.
set -euo pipefail

cd "$(dirname "$0")/.."
./gradlew :app:shared:jvmTest -Psnapshots --tests '*ShowcaseSnapshots*' --console=plain

for theme in light dark; do
  cp "app/shared/build/snapshots/showcase-$theme.png" "art/showcase-$theme.png"
done

if command -v oxipng >/dev/null; then
  oxipng -o 6 --strip safe --alpha art/showcase-light.png art/showcase-dark.png
else
  echo "oxipng not found: the images were copied without optimizing them" >&2
fi
