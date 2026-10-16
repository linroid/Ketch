#!/usr/bin/env python3
"""Original music bed for the Ketch promo film, synthesized from scratch (no samples).

Reads the film's timing from src/timing.json, builds the score from it (score.py), renders and
masters it (synth.py, mixer.py), and writes public/audio/music.wav and its cue sheet
public/audio/CUES.md. 48 kHz stereo, 24-bit; 120 BPM at 30 fps (one beat = 15 frames), D major
/ B minor; -16 LUFS integrated, -1.4 dBTP ceiling; deterministic (every random source has a fixed
seed, so the same timing gives the same file byte for byte).

Before rendering it checks the timing and the picture ties (score.py PICTURE_TIES: numbers the
music copies from the scene code) and prints a WARNING for each problem. `--check` does only
that, without the venv's packages, and exits 1 when anything is off.

Usage (normally through audio/make-music.sh, which sets up the venv):
  python make_music.py [--timing PATH] [--out PATH] [--cues PATH | --no-cues]
                       [--target-lufs -16] [--stems DIR]
  python make_music.py --check [--timing PATH]
"""

from __future__ import annotations

import sys

sys.dont_write_bytecode = True  # keep audio/ free of __pycache__

import argparse  # noqa: E402
import os  # noqa: E402
import re  # noqa: E402
import time  # noqa: E402

# Standard library only up to here (score and timing too), so --check runs without the venv;
# render() imports numpy, scipy and numba.
from score import PICTURE_TIES, PictureTie, make_score  # noqa: E402
from timing import DEFAULT_TIMING, load_timing, timing_warnings  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
PROMO = os.path.normpath(os.path.join(HERE, '..'))
DEFAULT_OUT = os.path.join(PROMO, 'public', 'audio', 'music.wav')
DEFAULT_CUES = os.path.join(PROMO, 'public', 'audio', 'CUES.md')


def show(path: str) -> str:
  """A path relative to art/promo when it is inside it, else absolute."""
  rel = os.path.relpath(os.path.abspath(path), PROMO)
  return path if rel.startswith('..') else rel


# ------------------------------------------------------------------------------------------
# Picture ties: reading numbers back from the scene code
# ------------------------------------------------------------------------------------------


def strip_comments(code: str) -> str:
  """TypeScript without its // and /* */ comments (string literals are kept as they are).

  A plain scanner, enough for finding constants: it knows quotes but not regex literals or JSX
  text (copy lives in src/scenes/copy/, so scene code has little of either)."""
  out = []
  i, n = 0, len(code)
  quote = None
  while i < n:
    c = code[i]
    if quote:
      out.append(c)
      if c == '\\' and i + 1 < n:
        out.append(code[i + 1])
        i += 1
      elif c == quote:
        quote = None
    elif c in '\'"`':
      quote = c
      out.append(c)
    elif code.startswith('//', i):
      i = code.find('\n', i)
      if i < 0:
        break
      continue
    elif code.startswith('/*', i):
      close = code.find('*/', i + 2)
      i = n if close < 0 else close + 2
      out.append(' ')
      continue
    else:
      out.append(c)
    i += 1
  return ''.join(out)


def initializer(code: str, name: str) -> str | None:
  """The initializer of `const name = ...;` in comment-free code, up to the `;` that ends the
  statement (outside brackets and strings), or None when there is no such declaration."""
  decl = re.search(rf'\bconst\s+{re.escape(name)}\b[^=;]*=', code)
  if not decl:
    return None
  depth = 0
  quote = None
  i = decl.end()
  while i < len(code):
    c = code[i]
    if quote:
      if c == '\\':
        i += 1
      elif c == quote:
        quote = None
    elif c in '\'"`':
      quote = c
    elif c in '([{':
      depth += 1
    elif c in ')]}':
      depth -= 1
    elif c == ';' and depth <= 0:
      break
    i += 1
  return code[decl.end() : i].strip()


def read_tie(tie: PictureTie, code: str) -> int | None:
  """The tie's value in the scene's comment-free code, or None when it is not there as a plain
  number (see PictureTie)."""
  const, _, key = tie.symbol.partition('.')
  init = initializer(code, const)
  if init is None:
    return None
  if tie.count:
    return len(re.findall(tie.count, init))
  if not key:
    return int(init) if re.fullmatch(r'\d+', init) else None
  prop = re.search(rf'(?<![\w$.]){re.escape(key)}\s*:\s*(\d+)\s*(?=[,}}]|$)', init)
  return int(prop.group(1)) if prop else None


def tie_form(tie: PictureTie) -> str:
  """How the scene code is expected to spell the tie, for messages."""
  const, _, key = tie.symbol.partition('.')
  if tie.count:
    return f'`const {const} = ...` (counting /{tie.count}/)'
  if key:
    return f'`{key}: <number>` in `const {const} = {{...}}`'
  return f'`const {const} = <number>;`'


def check_picture_ties(ties: list[PictureTie] = PICTURE_TIES) -> list[str]:
  """Compares score.py's picture ties with the scene code they were copied from."""
  problems = []
  for tie in ties:
    try:
      with open(os.path.join(PROMO, tie.file), encoding='utf-8') as f:
        actual = read_tie(tie, strip_comments(f.read()))
    except OSError:
      actual = None
    if actual is None:
      problems.append(
        f'could not check {tie.name} ({tie.cue}): no {tie_form(tie)} in {tie.file}. Find where '
        'the scene keeps it now (as a plain number) and point its PictureTie in audio/score.py '
        'there'
      )
    elif actual != tie.value:
      there = f'has {actual} (/{tie.count}/)' if tie.count else f'is {actual}'
      problems.append(
        f'{tie.name} is {tie.value} in audio/score.py but {tie.symbol} {there} in {tie.file} '
        f'({tie.cue}): the music no longer matches the picture. Set {tie.name} to {actual} (or '
        'move the scene back) and run npm run music'
      )
  return problems


# ------------------------------------------------------------------------------------------
# Command line
# ------------------------------------------------------------------------------------------


def check(timing_path: str) -> int:
  """--check: the timing and the picture ties, without rendering. Returns the exit status."""
  try:
    timing = load_timing(timing_path)
  except (OSError, ValueError, KeyError) as e:
    print(f'ERROR: {e}', file=sys.stderr)
    return 1
  problems = timing_warnings(timing) + check_picture_ties()
  for p in problems:
    print(f'WARNING: {p}', file=sys.stderr)
  if problems:
    return 1
  print(f'{show(timing.path)} and {len(PICTURE_TIES)} picture ties OK')
  return 0


def render(args: argparse.Namespace) -> None:
  import numpy as np

  import cuesheet
  import mixer
  from synth import HAVE_NUMBA

  t0 = time.time()
  print(f'numba: {"yes" if HAVE_NUMBA else "no (pure Python, about a minute)"}')
  timing = load_timing(args.timing)
  problems = timing_warnings(timing) + check_picture_ties()
  for p in problems:
    print(f'WARNING: {p}', file=sys.stderr)
  print(
    f'timing: {show(timing.path)}, {timing.total_frames} frames at {timing.fps} fps '
    f'= {timing.dur:.3f} s, {len(timing.scenes)} scenes, wipes at {list(timing.wipes)}'
  )

  score = make_score(timing)
  buses = mixer.render(score)
  mixer.calibrate(buses, score)
  if args.stems:
    for name, bus in buses.items():
      mixer.write_wav24(os.path.join(args.stems, f'{name}.wav'), np.clip(bus.x, -1.0, 1.0))
    print(f'wrote stems to {args.stems}')
  pre = mixer.mix(buses, score)
  del buses
  y, g = mixer.master_to(pre, args.target_lufs, score.fade_start)
  m = mixer.measure(y, g, score)
  mixer.report(m, timing.fps)
  mixer.write_wav24(args.out, y)
  print(f'wrote {show(args.out)}')
  if not args.no_cues:
    cuesheet.write(args.cues, score, m, os.path.basename(args.out))
    print(f'wrote {show(args.cues)}')
  print(
    f'{os.path.basename(args.out)}: {m.duration:.3f} s, {m.integrated:.1f} LUFS, '
    f'true peak {m.true_peak:.2f} dBTP ({time.time() - t0:.0f} s)'
  )
  if problems:  # repeated here so they are not lost above the mixer's report
    print(
      f'WARNING: {len(problems)} timing or picture-tie problem(s), listed at the top: the music '
      'may not match the picture',
      file=sys.stderr,
    )


def main() -> None:
  ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
  ap.add_argument('--timing', default=DEFAULT_TIMING, help='film timing (default src/timing.json)')
  ap.add_argument('--out', default=DEFAULT_OUT, help='WAV to write')
  ap.add_argument('--cues', default=DEFAULT_CUES, help='cue sheet to write')
  ap.add_argument('--no-cues', action='store_true', help='do not write the cue sheet')
  ap.add_argument('--target-lufs', type=float, default=-16.0)
  ap.add_argument('--stems', metavar='DIR', help='also write each dry, calibrated bus (debugging)')
  ap.add_argument(
    '--check',
    action='store_true',
    help='only check the timing and the picture ties against the scene code; exit 1 on any '
    'problem (needs no venv)',
  )
  args = ap.parse_args()
  if args.check:
    sys.exit(check(args.timing))
  render(args)


if __name__ == '__main__':
  main()
