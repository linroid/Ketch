"""Writes public/audio/CUES.md, the music's cue sheet, from the score and the measured file.

Every time and frame in the sheet comes from the score, so it follows timing.json. The sentences
describing each section are in `section_note` below (and `SectionSpec.plays` in score.py): when
you change what a section plays in score.py, change its sentence too.
"""

from __future__ import annotations

import os
from dataclasses import dataclass

from mixer import Measurements
from score import (
  BUILD_GAP,
  DISCOVER_LP_CLOSED,
  DISCOVER_LP_OPEN,
  PICTURE_TIES,
  REVERSE_CYMBAL_SECS,
  SAIL_MAIN_ROWS,
  Score,
  Section,
)
from synth import SR, note_name


@dataclass(frozen=True)
class Cue:
  """One row of the cue sheet."""

  start: float
  end: float | None
  where: str
  text: str
  frames: str | None = None  # overrides the frame column (e.g. a list of frames)


def _t(t: float) -> str:
  return f'{t:.3f}'


def _s(t: float) -> str:
  """Seconds without trailing zeros (4, 61.5)."""
  return f'{t:g}'


CHORD_LABELS = {'Dadd9': 'D add9', 'Dfinal': 'D add9'}


def _chord(name: str) -> str:
  return CHORD_LABELS.get(name, name)


def _where(sec: Section) -> str:
  return {'S12a': 'S12', 'S12b': 'S12 end card'}.get(sec.id, sec.id)


def section_note(sec: Section, score: Score) -> str:
  """What happens musically from a section's first beat (beyond the hits on its cut)."""
  fps = score.timing.fps
  mk = score.marks

  def f(t: float) -> str:
    return f'{_s(t)} ({round(t * fps)})'

  in_sec = [t for t, *_ in score.bell_motif if sec.start <= t < sec.end]
  claps = [t for t, _ in score.claps if sec.start <= t < sec.end]
  s12_kicks = [t for t, _ in score.kicks if sec.start <= t < sec.end]
  closing = [n for n in score.lead if sec.start <= n.start < sec.end]
  notes = {
    'S02': 'Beat in: soft kick on every beat, closed hats on off-beats, sub with sidechain pump',
    'S03': 'Arp layer enters (plucky 16ths with a dotted-eighth ping-pong delay), ghost 16th hats',
    'S04': 'Lift: claps on beats 2 and 4, brighter pad',
    'S05': 'Lift: open hats, off-beat bass pluck enters',
    'S06': (
      'Fullest section: wider and brighter pads, open hats on every off-beat, bass pickups, '
      f'counter-melody lead ({_s(sec.start)} - {_s(sec.end)})'
    ),
    'S07': 'Lighter: lead, open hats and bass pluck out, pads darker, groove continues',
    'S08': (
      f"AI Discover colour: the synths' low-pass snaps to {DISCOVER_LP_CLOSED:g} Hz and opens to "
      f'{DISCOVER_LP_OPEN / 1000:g} kHz by {_s(mk["discover_open"])}. FM bell motif '
      f'({_s(in_sec[0])} - {_s(in_sec[-1])}, {len(in_sec)} notes). Quiet 16th "ticks". Kick on '
      f'beats 1 and 3, then every beat from {f(mk["discover_full"])}; soft claps from '
      f'{f(claps[0])}'
      if in_sec and claps
      else 'AI Discover colour'
    ),
    'S09': 'Driving and tight: kick, claps, 16th hats, off-beat bass pluck, arp',
    'S10': 'Open hats on the "and" of 2 and 4',
    'S11': (
      f'Build: snare roll in 8ths, 16ths from {f(mk["build_16ths"])}, 32nds from '
      f'{f(mk["build_32nds"])}; riser; pads open; rising high-pass'
    ),
    'S12a': (
      f'Half-time drums: kick {", ".join(_s(t) for t in s12_kicks)}; snare '
      f'{", ".join(f(t) for t, _ in score.halftime_snares)}; arp in eighths; closing lead '
      f'phrase {" - ".join(note_name(n.midi)[:-1] for n in closing)}'
    ),
    'S12b': (
      f'Final D add9 chord over four octaves with a kick under it, '
      f'{len(score.final_sparkle)}-note bell sparkle ({_s(score.final_sparkle[0][0])} - '
      f'{_s(score.final_sparkle[-1][0])}), lead resolves to '
      f'{note_name(score.lead[-1].midi)[:-1]}. Drums out, long reverb tail'
    ),
  }
  return notes.get(sec.id, '')


def make_cues(score: Score) -> list[Cue]:
  fps = score.timing.fps
  mk = score.marks
  cues: list[Cue] = []

  def add(start: float, end: float | None, text: str, where: str | None = None, **kw) -> None:
    cues.append(Cue(start, end, where or _where(score.section_at(start)), text, **kw))

  # Intro
  first = score.sections[0]
  add(first.start, None, (
    f'Pad ({_chord(score.chords[0].name)}) from the first frame, soft (a '
    f'{_s(score.chords[0].pad.attack)} s fade-in), its low-pass opening over '
    f'{_s(first.end - first.start)} s'
  ))
  sp = score.sparkles
  frames = [round(s.t * fps) for s in sp]
  add(sp[0].t, sp[-1].t, (
    f'{len(sp)} rising FM-bell sparkle notes, one per sail segment as it refills: '
    f'{" ".join(note_name(s.midi) for s in sp)} (main-sail rows 1-{SAIL_MAIN_ROWS}, then the '
    f'softer mizzen rows {SAIL_MAIN_ROWS + 1}-{len(sp)}), panned left to right'
  ), frames=f'{frames[0]}, {frames[1]}, {frames[2]}, ... {frames[-1]}')
  add(score.thump, None, (
    'Soft low thump on D as the refill wave settles on the held lock-up; the sub swells in '
    'under it'
  ))

  # Whoosh rises (their peaks are listed with the cut they land on)
  for w in score.whooshes:
    a, b = score.section_at(w.peak - 0.001), score.section_at(w.peak)
    add(w.peak - w.pre, w.peak, (
      'Whoosh rises, panning left to right with the lane wipe, **peaks on the cut**'
    ), where=f'{_where(a)} > {_where(b)}')

  # Everything that lands on a section's first beat
  whoosh_at = {w.peak for w in score.whooshes}
  for sec in score.sections[1:]:
    t = sec.start
    hits = []
    if t in whoosh_at:
      hits.append('**Whoosh peak** (lane wipe)')
    hits += ['impact' for i, _ in score.impacts if i == t]
    hits += [('soft cymbal' if v < 0.3 else 'cymbal') for c, v in score.cymbals if c == t]
    if any(b == t for b, *_ in score.bell_accents):
      hits.append('bell accent')
    if any(r == t for r, _ in score.reverse_cymbals):
      hits.append('after a reverse-cymbal swell')
    parts = [', '.join(hits)] if hits else []
    note = section_note(sec, score)
    if note:
      parts.append(note)
    chords = [_chord(c.name) for c in score.chords if sec.start <= c.start < sec.end]
    if len(chords) > 1:
      parts.append('Chords ' + ' - '.join(chords))
    add(t, None, '. '.join(p[0].upper() + p[1:] for p in parts), where=_where(sec))

  # Builds into the wipes, and the end
  for t, _ in score.reverse_cymbals:
    add(t - REVERSE_CYMBAL_SECS, t, 'Reverse cymbal swell')
  for r in score.risers:
    add(r.start, r.end, (
      'Riser (swept noise + rising D/A tones); the synths thin out with a rising high-pass'
    ))
  add(mk['drop1'], None, 'Kick and sub drop out; snare roll, 16ths then 32nds')
  add(mk['drop2'], None, 'Kick and sub out; light snare roll')
  beats = 'One-beat' if BUILD_GAP == 1 else f'{BUILD_GAP}-beat'
  add(mk['gap3'], None, f'{beats} gap: drums and sub out under the rising whoosh')
  add(score.fade_start, score.dur, (
    'Fade to digital silence (the picture fades to black over its last 20 frames)'
  ))

  cues.sort(key=lambda c: (c.start, c.end is not None))
  return cues


def render_markdown(score: Score, m: Measurements, wav_name: str) -> str:
  timing = score.timing
  fps = timing.fps
  wipes = ', '.join(str(w) for w in timing.wipes)
  lines = [
    '# Ketch promo music bed: cue sheet',
    '',
    '<!-- Generated by art/promo/audio/make_music.py from src/timing.json. Do not edit by hand:',
    '     change the score (audio/score.py) or the timing and run audio/make-music.sh. -->',
    '',
    f'`{wav_name}` is an original track synthesized from scratch by `art/promo/audio/` (no '
    'samples or third-party audio), so it is royalty-free and falls under the repository\'s '
    'license. It is mastered for the film: mount it unchanged at volume 1. How it is built and '
    'how to change it: `art/promo/audio/README.md`.',
    '',
    '| | |',
    '|---|---|',
    f'| File | `public/audio/{wav_name}`, {SR // 1000} kHz, 24-bit PCM, stereo |',
    f'| Length | exactly {m.duration:.3f} s = {m.samples:,} samples = '
    f'{timing.total_frames} frames at {fps} fps |',
    f'| Timing | `src/timing.json`: {len(timing.scenes)} scenes, lane wipes at frames {wipes} |',
    f'| Tempo | {timing.bpm:g} BPM, 4/4. One beat = {timing.beat:g} s = {timing.beat_frames} '
    f'frames, one bar = {timing.bar:g} s = {4 * timing.beat_frames} frames. Every cut is on a '
    'beat |',
    '| Key | D major / B minor |',
    f'| Loudness | {m.integrated:.1f} LUFS integrated, LRA {m.lra:.1f} LU, true peak '
    f'{m.true_peak:.1f} dBTP, sample peak {m.sample_peak:.2f} dBFS |',
    f'| Ends | starts at digital silence, fades to digital silence at {score.dur:.3f} s |',
    '',
    '## Mounting in Remotion',
    '',
    'Sample 0 is frame 0 of `Promo`. Put the audio at the root of `Promo` with no offset, trim '
    'or fade (both fades are in the file), for example',
    f"`<Audio src={{staticFile('audio/{wav_name}')}} />`, with `MUSIC = true` in "
    '`src/config.ts`. Do not mount it inside a scene `<Sequence>`: every cue below uses global '
    'frames.',
    '',
    '## Cues',
    '',
    f'Times are seconds from the start; frame = seconds x {fps}. Whooshes peak exactly on the cut '
    'and pan left to right like the lane wipe (cut - 9 to cut + 9 frames).',
    '',
    '| time (s) | frame | scene | event |',
    '|---|---|---|---|',
  ]
  for c in make_cues(score):
    if c.end is None:
      t, fr = _t(c.start), str(round(c.start * fps))
    else:
      t = f'{_t(c.start)} - {_t(c.end)}'
      fr = f'{round(c.start * fps)} - {round(c.end * fps)}'
    lines.append(f'| {t} | {c.frames or fr} | {c.where} | {c.text} |')
  lines += [
    '',
    '## Picture ties',
    '',
    'Most cues follow the cuts in `src/timing.json`. These follow animation inside a scene, so '
    'the score copies numbers from the scene code (`PICTURE_TIES` in `audio/score.py`). When '
    'you change one in the scene, change it in the score too and regenerate; '
    '`npm run music -- --check` compares them.',
    '',
    '| score.py | value | scene code | the music |',
    '|---|---|---|---|',
  ]
  for tie in PICTURE_TIES:
    where = f'`{tie.file}`: `{tie.symbol}`' + (f' (matches of `{tie.count}`)' if tie.count else '')
    lines.append(f'| `{tie.name}` | {tie.value} | {where} | {tie.cue} |')
  lines += [
    '',
    '## Arrangement and level by section',
    '',
    'Section loudness is K-weighted and ungated (LUFS), measured on the final file.',
    '',
    '| section | s | frames | loudness | what plays |',
    '|---|---|---|---|---|',
  ]
  for sec, (_, a, b, lufs, _, _) in zip(score.sections, m.sections):
    frames = f'{round(a * fps)} - {round(b * fps)}'
    lines.append(f'| {sec.label} | {_s(a)} - {_s(b)} | {frames} | {lufs:.1f} | {sec.plays} |')
  lines += [
    '',
    '## Sound',
    '',
    'Pads are detuned band-limited saws through a state-variable low-pass; arp and bass are saw '
    'plucks with filter envelopes; bells are two-operator FM kept below 16.5 kHz (no aliasing); '
    'the sub is one continuous sine with a little second harmonic, sidechained to the kick; '
    'drums, whooshes and risers are pitched sines and filtered noise. Space comes from two '
    'convolution reverbs with synthetic impulse responses. The master has a 25 Hz high-pass, '
    'soft-knee saturation and a 4x oversampled look-ahead true-peak limiter (at most '
    f'{-m.limiter_max_gr:.2f} dB of gain reduction, on {m.limiter_active * 100:.1f} % of '
    f'samples). Mono fold-down loses {m.mono_loss:.1f} LU.',
    '',
    '## Regenerating',
    '',
    '```sh',
    'npm run music   # in art/promo; runs audio/make-music.sh',
    '```',
    '',
    'It sets up `art/promo/.venv` from `audio/requirements.txt` on first use, writes '
    f'`public/audio/{wav_name}` and this file, and prints the measurements. The output is '
    'deterministic (fixed seeds; the same file byte for byte on every run).',
    '',
  ]
  return '\n'.join(lines)


def write(path: str, score: Score, m: Measurements, wav_name: str) -> None:
  os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
  with open(path, 'w', encoding='utf-8') as f:
    f.write(render_markdown(score, m, os.path.basename(wav_name)))
