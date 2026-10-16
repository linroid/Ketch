#!/usr/bin/env bash
# Renders the real-UI assets of the film and puts them in it (`npm run assets`): the app's
# screens through its headless Compose snapshot harness (PromoSnapshots.kt), one Gradle run per
# locale and theme, then scripts/assets/sync.mjs, which links them into public/ and writes the
# manifest; and the browser extension's popup (tools/extension-popup), with Remotion's headless
# Chrome, which it makes sure of before Gradle starts (`remotion browser ensure`; skipped with
# $CHROME).
#
#   scripts/assets/render-ui.sh [<locale>|all] [--theme dark|light|all] [<test>…]
#
#   locale  en, zh-Hans, … (scripts/assets/locales.json), or all (the default)
#   theme   the film the assets are for: dark (the default), light, or all for both; the light
#           film's go to their own folders and manifests (docs/ASSETS.md, "Themes")
#   test    a PromoSnapshots test (desktop, discover, stillPalette, …), or popup for the
#           extension's popup; without one, every test and the popup
#
# Examples:
#   scripts/assets/render-ui.sh                          # everything, every locale (~50 min)
#   scripts/assets/render-ui.sh all --theme light        # the light film's, every locale
#   scripts/assets/render-ui.sh zh-Hans discover         # one clip in one locale
#   scripts/assets/render-ui.sh en stillPalette popup    # a still and the popup
#
# Clips are recorded on virtual time, so a busy machine only makes this slower. docs/ASSETS.md
# explains every asset, the timings and what to do when something looks wrong.
set -euo pipefail

promo="$(cd "$(dirname "$0")/../.." && pwd)"
repo="$(cd "$promo/../.." && pwd)"
locales_json="$promo/scripts/assets/locales.json"
suite="com.linroid.ketch.app.snapshot.PromoSnapshots"

target=""
themes=dark
tests=()
while (( $# > 0 )); do
  case "$1" in
    --theme)
      [[ $# -ge 2 ]] || { echo "error: --theme needs dark, light or all" >&2; exit 1; }
      themes="$2"
      shift 2
      ;;
    *)
      if [[ -z "$target" ]]; then target="$1"; else tests+=("$1"); fi
      shift
      ;;
  esac
done
target="${target:-all}"
case "$themes" in
  dark | light) ;;
  all) themes="dark light" ;;
  *) echo "error: unknown theme \"$themes\"; known: dark, light, all" >&2; exit 1 ;;
esac

known="$(node -e 'console.log(Object.keys(require(process.argv[1]).locales).join(" "))' "$locales_json")"
if [[ "$target" == all ]]; then
  locales="$known"
elif [[ " $known " == *" $target "* ]]; then
  locales="$target"
else
  echo "error: unknown locale \"$target\"; known: $known, all" >&2
  exit 1
fi

# macOS Reduce Motion turns the app's motion off, and the clips would jump instead of glide.
if [[ "$(defaults read com.apple.universalaccess reduceMotion 2>/dev/null || echo 0)" == 1 ]]; then
  echo "error: macOS Reduce Motion is on; the app would render without motion (docs/ASSETS.md)" >&2
  exit 1
fi

# Every locale's clips take about 2 GB a film in the build folder (public/ links to the same
# files).
free_kb="$(df -Pk "$repo" | awk 'NR == 2 { print $4 }')"
if (( free_kb < 5 * 1024 * 1024 )); then
  echo "warning: less than 5 GB free; a locale's clips need about 2 GB" >&2
fi

# Which Gradle tests, and whether the popup, this run renders.
gradle_tests=()
popup=false
if (( ${#tests[@]} == 0 )); then
  gradle_tests=(--tests "$suite")
  popup=true
else
  for test in "${tests[@]}"; do
    if [[ "$test" == popup ]]; then
      popup=true
    else
      gradle_tests+=(--tests "$suite.$test")
    fi
  done
fi

# The popup needs Remotion's headless Chrome (render.mjs; $CHROME overrides it). Make sure of it
# now, so a missing browser stops the run before the Gradle runs (minutes), not after them. It
# downloads Chrome once and takes under a second when Chrome is there; running it again is safe.
if [[ "$popup" == true && -z "${CHROME:-}" ]]; then
  (cd "$promo" && ./node_modules/.bin/remotion browser ensure)
fi

for theme in $themes; do
  for locale in $locales; do
    echo "── $locale, $theme film"
    if (( ${#gradle_tests[@]} > 0 )); then
      snapshot_locale="$(node -e 'const row = require(process.argv[1]).locales[process.argv[2]]; console.log(row.snapshotLocale ?? "")' "$locales_json" "$locale")"
      locale_flag=()
      [[ -n "$snapshot_locale" ]] && locale_flag=("-PsnapshotLocale=$snapshot_locale")
      # One JVM runs the tests one after another; `-Psnapshots` turns the scenarios on.
      (cd "$repo" && ./gradlew :app:shared:jvmTest -Psnapshots -PsnapshotTheme="$theme" \
        ${locale_flag[@]+"${locale_flag[@]}"} "${gradle_tests[@]}" --console=plain)
      node "$promo/scripts/assets/sync.mjs" "$locale" --theme "$theme"
    fi
    if [[ "$popup" == true ]]; then
      node "$promo/tools/extension-popup/render.mjs" "$locale" --theme "$theme"
    fi
  done
done
