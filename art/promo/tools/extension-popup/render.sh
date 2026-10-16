#!/usr/bin/env bash
# Renders the browser extension's popup for the promo in one locale, or every locale, into
# public/stills[/<locale>]/popup-dark.png (with --theme light, .../light/popup-light.png for the
# light film) and registers it in the manifest. render.mjs does the work; docs/ASSETS.md
# explains it.
#
#   tools/extension-popup/render.sh <locale|all> [--theme light]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
promo="$(cd "$here/../.." && pwd)"
target="${1:-all}"
[[ $# -gt 0 ]] && shift
if [[ "$target" == all ]]; then
  locales="$(node -e 'console.log(Object.keys(require(process.argv[1]).locales).join(" "))' "$promo/scripts/assets/locales.json")"
else
  locales="$target"
fi
for locale in $locales; do
  node "$here/render.mjs" "$locale" "$@"
done
