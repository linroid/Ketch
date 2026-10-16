#!/usr/bin/env bash
# Regenerates the promo's music bed, public/audio/music.wav, and its cue sheet,
# public/audio/CUES.md, from src/timing.json (see audio/README.md). On first use, and whenever
# audio/requirements.txt changes, it sets up a Python environment in art/promo/.venv.
#
# Usage: audio/make-music.sh [make_music.py options]
#   e.g. audio/make-music.sh --stems /tmp/stems   (also writes each instrument bus)
#        audio/make-music.sh --check              (only checks the timing and the picture ties
#                                                  against the scene code; no venv needed)
# Set PYTHON to choose the interpreter (default python3).
set -euo pipefail

cd "$(dirname "$0")/.."

# --check needs only the standard library: skip the venv, so it is quick on a fresh clone too.
for arg in "$@"; do
  if [[ "$arg" == --check ]]; then exec "${PYTHON:-python3}" audio/make_music.py "$@"; fi
done

venv=.venv
stamp="$venv/.requirements.txt"

if [[ ! -x "$venv/bin/python" ]] || ! cmp -s audio/requirements.txt "$stamp"; then
  echo "Setting up $venv from audio/requirements.txt ..."
  "${PYTHON:-python3}" -m venv "$venv"
  "$venv/bin/python" -m pip install --quiet --disable-pip-version-check -r audio/requirements.txt
  cp audio/requirements.txt "$stamp"
fi

exec "$venv/bin/python" audio/make_music.py "$@"
