#!/usr/bin/env bash
# Renders every raster app icon from the SVG sources in this directory.
# Requires rsvg-convert (librsvg), iconutil (macOS) and python3.
set -euo pipefail

cd "$(dirname "$0")"
root=..
web=$root/app/web/src/wasmJsMain/resources
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

render() { rsvg-convert -w "$2" -h "$2" "$1" -o "$3"; }

# Desktop: Linux PNG, Windows ICO, macOS ICNS, and the window icon
render icon-app.svg 512 icon.png
render icon-app.svg 1024 icon-1024.png
cp icon-app.svg "$root/app/desktop/src/main/resources/icon.svg"

for size in 16 32 48 64 128 256; do
  render icon-app.svg "$size" "$tmp/ico-$size.png"
done
python3 - "$tmp" <<'EOF'
import struct, sys
sizes = [16, 32, 48, 64, 128, 256]
images = [open(f"{sys.argv[1]}/ico-{s}.png", "rb").read() for s in sizes]
header = struct.pack("<HHH", 0, 1, len(images))
offset = len(header) + 16 * len(images)
for size, image in zip(sizes, images):
  # A width/height of 0 means 256.
  header += struct.pack("<BBBBHHII", size % 256, size % 256, 0, 0, 1, 32, len(image), offset)
  offset += len(image)
open("icon.ico", "wb").write(header + b"".join(images))
EOF

mkdir "$tmp/icon.iconset"
for size in 16 32 128 256 512; do
  render icon-macos.svg "$size" "$tmp/icon.iconset/icon_${size}x${size}.png"
  render icon-macos.svg "$((size * 2))" "$tmp/icon.iconset/icon_${size}x${size}@2x.png"
done
iconutil -c icns "$tmp/icon.iconset" -o icon.icns

# iOS: an opaque square, the system applies the mask
render icon-square.svg 1024 "$root/app/ios/Assets.xcassets/AppIcon.appiconset/icon-1024.png"

# Web app
render icon-app.svg 192 "$web/icon-192.png"
render icon-app.svg 512 "$web/icon-512.png"
render icon-square.svg 512 "$web/icon-maskable-512.png"
render icon-square.svg 180 "$web/apple-touch-icon.png"
