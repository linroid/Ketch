"""The score: what plays when, derived from the film's timing (src/timing.json).

Edit this file to change the music's arrangement. Every time here is built from a scene's start
or end (`s06.start`, `s09.end`), a named offset below, or a number of beats, never from absolute
seconds, so a retimed film keeps its cues on the cuts: run `audio/make-music.sh` after changing
timing.json. The sounds themselves are in synth.py and the levels in mixer.py.

A few cues follow animation inside a scene rather than a cut. Those numbers are copied from the
scene code, under "Picture ties" below, and listed in PICTURE_TIES; make_music.py reads each one
back from the scene code and warns when they differ or it cannot find it (`--check` does only
that, and fails). Change both sides together, then regenerate the music.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from timing import Timing, snap

# ------------------------------------------------------------------------------------------
# Picture ties: numbers copied from the scene code (frames local to their scene unless noted).
# When one changes there, change it here and run `npm run music`; PICTURE_TIES at the end of
# this block says where each one lives, and make_music.py checks them.
# ------------------------------------------------------------------------------------------

# S01: one bell per sail segment, on the frame its refill starts. The film opens on the finished
# lock-up; SailLogo (mode 'refill') drains segment k to a dim floor over the frames before and
# refills it from S01Intro's SAIL_AT + SAIL_TIMING.trackLead + k * SAIL_TIMING.stagger (frames
# 6, 9 ... 33), main-sail rows top to bottom, then the mizzen rows (SAIL_SEGMENTS, in order).
SAIL_AT = 0
SAIL_TRACK_LEAD = 6
SAIL_STAGGER = 3
SAIL_MAIN_ROWS = 6
SAIL_MIZZEN_ROWS = 4
# S01: the refill wave and its glints have crossed the sail at WORD_AT (S01Intro; the name is
# from the earlier opening, where the wordmark slid out there); the low thump lands
# THUMP_AFTER_WORD later, on the bar line, as the lock-up holds.
S01_WORD_AT = 54
THUMP_AFTER_WORD = 6
# S11: the build's snare roll runs under the whole scene into the S12 wipe: eighths from S11's
# start, sixteenths from ROLL_16THS_BEATS and thirty-seconds from ROLL_32NDS_BEATS beats before
# S12 (local 45 and 75 at the current length), until the BUILD_GAP. The language ticker
# (S11Languages) lands on its last name where the thirty-seconds start; the sixteenths are the
# music's own.
ROLL_16THS_BEATS = 4
ROLL_32NDS_BEATS = 2
# S12: the end card's icon lands on the final chord, at local frame CHORD (S12OpenSource); the
# music's S12 splits there into S12a (open source) and S12b (end card).
S12_END_CARD_AT = 105
# The music fades to digital silence over the film's last FADE_FRAMES; the picture fades to
# black over its last 20 (S12OpenSource FADE_AT 220 to 239), inside the music's fade.
FADE_FRAMES = 72


@dataclass(frozen=True)
class PictureTie:
  """A number the music copies from the scene code, and where make_music.py finds it there.

  `symbol` is `NAME` for a plain number (`const NAME = 54;`) or `NAME.key` for a property of an
  object literal (`key: 6` in `const NAME = {...}`). With `count`, the value is instead how many
  times that regex matches in the initializer of `const NAME` (e.g. the rows of an array).
  Comments and spacing do not matter; the value must be a plain number, not an expression.
  """

  name: str  # the constant in this file
  value: int
  file: str  # the scene code, relative to art/promo
  symbol: str
  cue: str  # what the music does with it (for warnings and the cue sheet)
  count: str | None = None


SAIL_LOGO = 'src/components/SailLogo.tsx'
PICTURE_TIES = [
  PictureTie(
    'SAIL_AT', SAIL_AT, 'src/scenes/S01Intro.tsx', 'SAIL_AT',
    'S01: the sail starts refilling; its segment refills carry the sparkle bells',
  ),
  PictureTie(
    'SAIL_TRACK_LEAD', SAIL_TRACK_LEAD, SAIL_LOGO, 'SAIL_TIMING.trackLead',
    'S01: frames from the refill start to the first segment refill and its sparkle bell',
  ),
  PictureTie(
    'SAIL_STAGGER', SAIL_STAGGER, SAIL_LOGO, 'SAIL_TIMING.stagger',
    'S01: frames between segment refills, one sparkle bell each',
  ),
  PictureTie(
    'SAIL_MAIN_ROWS + SAIL_MIZZEN_ROWS', SAIL_MAIN_ROWS + SAIL_MIZZEN_ROWS, SAIL_LOGO,
    'SAIL_SEGMENTS', 'S01: one sparkle note per sail segment (SPARKLE_NOTES)',
    count=r'\bseg\(',
  ),
  PictureTie(
    'SAIL_MIZZEN_ROWS', SAIL_MIZZEN_ROWS, SAIL_LOGO, 'SAIL_SEGMENTS',
    "S01: the last segments, the mizzen's, get softer sparkle bells",
    count=r',\s*true\s*,?\s*\)',  # also a seg( call Prettier wraps (`true,` then `)`)
  ),
  PictureTie(
    'S01_WORD_AT', S01_WORD_AT, 'src/scenes/S01Intro.tsx', 'WORD_AT',
    f'S01: the refill wave has crossed the sail; the low thump lands {THUMP_AFTER_WORD} frames '
    'later, on the held lock-up',
  ),
  PictureTie(
    'ROLL_32NDS_BEATS', ROLL_32NDS_BEATS, 'src/scenes/S11Languages.tsx', 'ROLL_32NDS_BEATS',
    'S11: the snare roll goes to 32nds this many beats before S12, where the language ticker '
    'lands its last name',
  ),
  PictureTie(
    'S12_END_CARD_AT', S12_END_CARD_AT, 'src/scenes/S12OpenSource.tsx', 'CHORD',
    'S12: the end card lands on the final chord (the music splits into S12a and S12b there)',
  ),
]  # fmt: skip

# ------------------------------------------------------------------------------------------
# Named musical offsets, in beats (one beat = timing.json `beat` frames, 0.5 s at 120 BPM).
# ------------------------------------------------------------------------------------------

DROP_GAP = 2  # kick and sub stop this long before the S06 and S09 wipes; a snare roll fills it
BUILD_GAP = 1  # drums and sub stop this long before the S12 wipe, under the rising whoosh
RISER_BEATS = 4  # risers into S06 and S09 (the S11 riser spans the whole scene)
DISCOVER_SPARSE_BARS = 2  # S08 opens with the kick on beats 1 and 3 only, for this many bars
ARP_S08_OUT = 1  # the S08 arp stops this long before the S09 wipe
STEP = 0.05  # seconds: a section's filter and level automation steps over this long before a cut

# Lane-wipe whooshes, by the scene the wipe enters: (seconds from start to peak, level). The
# peak is on the cut. Levels are relative to the first wipe's whoosh (S02), which mixer.py
# calibrates. The S12 one rises inside the one-beat gap (BUILD_GAP) and is louder because the
# S11 riser masks it: at 1.5 it swells about 5 dB into the cut in the 1.5-12 kHz band, as the
# S06 and S09 ones do (at 0.8 it was lost under the riser).
WHOOSH_STYLE = {
  'S02': (0.9, 1.0),
  'S06': (0.75, 0.75),
  'S09': (0.75, 0.75),
  'S12': (0.5, 1.5),
}
WHOOSH_DEFAULT = (0.75, 0.75)

# S08 colour: the synth group's low-pass closes to this at the cut and opens over the scene.
DISCOVER_LP_CLOSED = 420.0
DISCOVER_LP_OPEN = 9000.0

# S01's pad: the film's first frame is already the finished logo, so the pad is there from it,
# softly but at once: a short fade-in (OPENING_PAD_ATTACK s, raised cosine from zero, so no click;
# the master adds a 5 ms fade-in too) and a low-pass that starts at OPENING_PAD_LP (Hz) and opens
# over the intro, rather than a long swell from silence.
OPENING_PAD_ATTACK = 0.4
OPENING_PAD_LP = 500

# ------------------------------------------------------------------------------------------
# Harmony
# ------------------------------------------------------------------------------------------

# pad voicing (MIDI), sub root (MIDI, sounding pitch), arp tones (5, ascending), extra pad
# notes (MIDI, gain) that only the final chord has.
CHORDS: dict[str, dict] = {
  'Dadd9': dict(pad=[50, 57, 62, 64, 66, 69], root=38, arp=[69, 74, 76, 78, 81]),
  'Bm9': dict(pad=[59, 62, 66, 69, 73], root=35, arp=[71, 74, 78, 81, 85]),
  'Gmaj9': dict(pad=[55, 59, 62, 66, 69], root=31, arp=[67, 71, 74, 78, 81]),
  'D': dict(pad=[57, 62, 66, 69, 74], root=38, arp=[69, 74, 78, 81, 86]),
  'Asus4': dict(pad=[57, 62, 64, 69, 71], root=33, arp=[69, 71, 74, 76, 81]),
  'A': dict(pad=[57, 61, 64, 69, 71], root=33, arp=[69, 71, 73, 76, 81]),
  'A/C#': dict(pad=[57, 61, 64, 69, 73], root=37, arp=[69, 73, 76, 81, 85]),
  'Gmaj7#11': dict(pad=[55, 59, 61, 66, 69], root=31, arp=[67, 71, 73, 78, 81]),
  'Em9': dict(pad=[52, 55, 59, 62, 66], root=40, arp=[64, 67, 71, 74, 78]),
  'Dfinal': dict(
    pad=[50, 57, 62, 64, 66, 69, 74, 78],
    root=38,
    arp=[74, 78, 81, 86, 90],
    extra=[(38, 0.6), (86, 0.35)],
  ),
}

Chords = tuple[tuple[str, float], ...]


@dataclass(frozen=True)
class Prog:
  """A section's chords as (name, beats). `head` plays from the section's start and `tail` ends
  on its last beat (a cadence into the next scene); `body` repeats to fill what is left, and
  the last chord that does not fit is cut at the start of the tail. A chord that carries on
  into the next section (the same name on both sides of a cut) is held, not struck again."""

  body: Chords
  head: Chords = ()
  tail: Chords = ()


@dataclass(frozen=True)
class PadEnv:
  """Pad envelope for chords struck in a section. `hold` fixes the note length (s); without it
  the notes last until the next chord."""

  attack: float = 0.16
  release: float = 0.55
  hold: float | None = None


@dataclass(frozen=True)
class SectionSpec:
  label: str
  plays: str
  prog: Prog
  pad: PadEnv = PadEnv()


# The music's sections are the scenes, with S12 split at the end card (S12a, S12b). The chord
# lists match the current scene lengths exactly; a longer scene repeats its body.
SECTIONS: dict[str, SectionSpec] = {
  'S01': SectionSpec(
    'S01 intro',
    'pad, sparkle, thump, whoosh',
    Prog(body=(('Dadd9', 8),)),
    PadEnv(attack=OPENING_PAD_ATTACK, release=0.6),
  ),
  'S02': SectionSpec(
    'S02 modern UI',
    'kick, off-beat hats, sub, pads',
    Prog(body=(('Bm9', 4), ('Gmaj9', 4), ('D', 4)), tail=(('Asus4', 2), ('A', 2))),
  ),
  'S03': SectionSpec(
    'S03 lanes',
    '+ arp, ghost hats',
    Prog(body=(('Bm9', 4), ('Gmaj9', 4), ('D', 4))),
  ),
  'S04': SectionSpec(
    'S04 any link',
    '+ claps',
    Prog(head=(('Asus4', 2), ('A', 2)), body=(('Bm9', 4), ('Gmaj9', 4))),
  ),
  'S05': SectionSpec(
    'S05 features',
    '+ open hats, bass pluck; riser and roll before the S06 wipe',
    Prog(
      head=(('Gmaj9', 2),),
      body=(('D', 4), ('A', 4), ('Bm9', 4)),
      tail=(('Gmaj9', 2), ('A', 2)),
    ),
  ),
  'S06': SectionSpec(
    'S06 cross-platform',
    'everything, wider and brighter, + counter-melody',
    Prog(body=(('D', 4), ('A/C#', 4), ('Bm9', 4), ('Gmaj9', 4)), tail=(('Asus4', 2), ('A', 2))),
  ),
  'S07': SectionSpec(
    'S07 devices',
    'groove, lighter',
    Prog(body=(('Bm9', 4), ('Gmaj9', 4)), tail=(('Asus4', 4),)),
  ),
  'S08': SectionSpec(
    'S08 AI Discover',
    'filtered synths opening, bell motif, ticks; riser before the S09 wipe',
    Prog(
      body=(('Bm9', 4), ('Gmaj7#11', 4), ('Em9', 4), ('Gmaj7#11', 4)),
      tail=(('Asus4', 2), ('A', 2)),
    ),
    PadEnv(attack=0.3, release=0.7),
  ),
  'S09': SectionSpec(
    'S09 agents',
    'driving groove',
    Prog(body=(('D', 4), ('A/C#', 4))),
  ),
  'S10': SectionSpec(
    'S10 light',
    'driving groove + open hats',
    Prog(head=(('A/C#', 1),), body=(('Bm9', 4), ('Gmaj9', 4))),
  ),
  'S11': SectionSpec(
    'S11 build',
    'snare roll, riser, rising filters',
    Prog(head=(('Gmaj9', 1),), body=(('Em9', 4),), tail=(('Asus4', 2),)),
  ),
  'S12a': SectionSpec(
    'S12a open source',
    'half-time drums, closing phrase',
    Prog(body=(('Gmaj9', 4),), tail=(('Asus4', 2), ('A', 1))),
  ),
  'S12b': SectionSpec(
    'S12b end card',
    'final chord and tail, fading out',
    Prog(body=(('Dfinal', 4),)),
    PadEnv(attack=0.04, release=1.6, hold=2.6),
  ),
}

# ------------------------------------------------------------------------------------------
# Melodies and patterns (times in beats from the anchor named in the comment)
# ------------------------------------------------------------------------------------------

# Arp: 16 sixteenths per bar, indices into the chord's five arp tones; restarts at each range.
ARP_PATTERN = [0, 2, 3, 1, 4, 2, 3, 1, 0, 2, 3, 1, 4, 3, 2, 1]
ARP_HALFTIME_PATTERN = [0, 2, 4, 3, 1, 2, 4]  # S12a: eighths

# S01 sparkle, one note per sail segment: D major pentatonic, D5 to B6.
SPARKLE_NOTES = [74, 76, 78, 81, 83, 86, 88, 90, 93, 95]

# S08 bell motif over the body chords (beats from S08's start; repeats every 16 beats when the
# scene is longer) and its cadence (beats from S08's end).
BELL_MOTIF_BODY = [
  (0.5, 78), (1.0, 81), (1.5, 85), (2.5, 83),  # Bm9
  (4.5, 85), (5.0, 83), (5.5, 78), (6.5, 81),  # Gmaj7#11
  (8.5, 79), (9.0, 83), (9.5, 86), (10.5, 90),  # Em9
  (12.5, 85), (13.0, 86), (13.5, 90), (14.5, 88),  # Gmaj7#11
]  # fmt: skip
BELL_MOTIF_PERIOD = 16
BELL_MOTIF_TAIL = [(-3.5, 86), (-3.0, 88), (-2.0, 85)]  # Asus4 -> A

# Counter-melody over S06's body (beats from S06's start; repeats every 16 beats) and its last
# bar (beats from S06's end): (beat, length in beats, MIDI).
LEAD_S06_BODY = [
  (0, 1.5, 78), (1.5, 0.5, 76), (2, 1, 74), (3, 1, 69),  # D
  (4, 1.5, 76), (5.5, 0.5, 73), (6, 1, 76), (7, 1, 81),  # A/C#
  (8, 1.5, 78), (9.5, 0.5, 74), (10, 1, 73), (11, 1, 74),  # Bm9
  (12, 1, 71), (13, 1, 74), (14, 1, 78), (15, 1, 81),  # Gmaj9
]  # fmt: skip
LEAD_S06_PERIOD = 16
LEAD_S06_TAIL = [(-4, 1, 81), (-3, 0.5, 83), (-2.5, 0.5, 81), (-2, 2, 76)]  # Asus4 -> A
# Closing phrase over S12a (beats from S12's start), then the note the end card resolves to.
LEAD_CLOSING = [(0, 2, 78), (2, 1, 76), (3, 1, 74), (4, 2, 76), (6, 1, 73)]
LEAD_RESOLVE = (6, 74)  # (beats, MIDI) from the end card
LEAD_RESOLVE_RELEASE = 1.4

FINAL_SPARKLE = [74, 78, 81, 86, 90]  # end card, 0.1 s apart
REVERSE_CYMBAL_SECS = 1.2  # a reverse cymbal swells for this long into its cut


# ------------------------------------------------------------------------------------------
# The score
# ------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class Section:
  """A stretch of the music with its own chords: a scene, or half of S12 (S12a, S12b)."""

  id: str
  label: str
  plays: str
  start: float
  end: float


@dataclass(frozen=True)
class ChordSpan:
  start: float
  end: float
  name: str
  pad: PadEnv


@dataclass(frozen=True)
class Whoosh:
  peak: float  # on the cut
  pre: float  # seconds of rise before the peak
  level: float
  post: float = 0.55  # seconds of decay after it


@dataclass(frozen=True)
class Riser:
  start: float
  end: float
  level: float
  hp_top: float  # the synth group's high-pass rises to this (Hz) under the riser


@dataclass(frozen=True)
class ArpRange:
  start: float
  end: float
  gain: float = 1.0
  fade_in: float = 0.0  # seconds


@dataclass(frozen=True)
class PluckRange:
  start: float
  end: float
  pickups: bool = False  # also a short note on the last sixteenth of every bar


@dataclass(frozen=True)
class Sparkle:
  t: float
  midi: int
  gain: float
  pan: float


@dataclass(frozen=True)
class LeadNote:
  start: float
  dur: float
  midi: int
  release: float = 0.18


Curve = list[tuple[float, float]]
"""Automation breakpoints (seconds, value), interpolated linearly between them."""


@dataclass
class Score:
  """Everything the mixer plays, as events and automation. Times are seconds from the film's
  first frame (global frame / fps); velocities and levels are linear gains."""

  timing: Timing
  dur: float
  sections: list[Section]
  chords: list[ChordSpan]
  kicks: list[tuple[float, float]]
  claps: list[tuple[float, float]]
  halftime_snares: list[tuple[float, float]]
  rolls: list[tuple[float, float, float]]  # (t, velocity, pitch Hz)
  hats: list[tuple[float, float, str]]  # (t, velocity, kind: c closed, o open, g ghost, t tick)
  cymbals: list[tuple[float, float]]
  reverse_cymbals: list[tuple[float, float]]  # swells that end on t
  whooshes: list[Whoosh]
  risers: list[Riser]
  impacts: list[tuple[float, float]]
  thump: float
  sparkles: list[Sparkle]
  bell_motif: list[tuple[float, int]]
  bell_accents: list[tuple[float, int, float]]
  final_sparkle: list[tuple[float, int]]
  lead: list[LeadNote]
  arp_ranges: list[ArpRange]
  arp_halftime: tuple[float, float]
  arp_level: list[tuple[float, float]]  # piecewise constant from t
  arp_bright: list[tuple[float, float]]
  pluck_ranges: list[PluckRange]
  sub_amp: Curve
  end_card: float
  pad_lp: Curve  # log
  pad_width: Curve
  pad_level: Curve
  group_lp: Curve  # log
  group_hp: Curve  # log
  fade_start: float
  windows: dict[str, tuple[float, float]]  # reference windows for mixer.py's calibration
  marks: dict[str, float] = field(default_factory=dict)  # named landmarks, for the cue sheet

  def chord_at(self, t: float) -> str:
    for c in self.chords:
      if c.start - 1e-9 <= t < c.end - 1e-9:
        return c.name
    return self.chords[-1].name

  def section_at(self, t: float) -> Section:
    for s in self.sections:
      if s.start - 1e-9 <= t < s.end - 1e-9:
        return s
    return self.sections[-1]

  def curves(self) -> dict[str, Curve]:
    return {
      'sub_amp': self.sub_amp,
      'pad_lp': self.pad_lp,
      'pad_width': self.pad_width,
      'pad_level': self.pad_level,
      'group_lp': self.group_lp,
      'group_hp': self.group_hp,
    }


def grid(a: float, b: float, step: float, off: float = 0.0) -> list[float]:
  """Times a+off, a+off+step, ... strictly before b (rounded to the microsecond)."""
  out = []
  t = a + off
  while t < b - 1e-9:
    out.append(round(t, 6))
    t += step
  return out


def lay_chords(prog: Prog, start: float, end: float, beat: float) -> list[tuple[float, float, str]]:
  """Places a section's progression between start and end (seconds) as (start, end, chord);
  see `Prog`."""
  length = round((end - start) / beat, 6)
  tail_at = max(0.0, length - sum(b for _, b in prog.tail))
  spans = []
  pos = 0.0
  for name, beats in prog.head:
    if pos >= tail_at:
      break
    spans.append((pos, min(pos + beats, tail_at), name))
    pos += beats
  i = 0
  while pos < tail_at - 1e-9:
    name, beats = prog.body[i % len(prog.body)]
    spans.append((pos, min(pos + beats, tail_at), name))
    pos += beats
    i += 1
  pos = length - sum(b for _, b in prog.tail)
  for name, beats in prog.tail:
    if pos + beats > max(pos, 0.0):
      spans.append((max(pos, 0.0), pos + beats, name))
    pos += beats
  return [(snap(start + a * beat), snap(start + b * beat), name) for a, b, name in spans]


def roll(segments, v0: float, v1: float, p0: float, p1: float) -> list[tuple[float, float, float]]:
  """Snare roll: segments [(start, end, step)], velocity and pitch ramp v0->v1, p0->p1."""
  times = []
  for a, b, step in segments:
    times += grid(a, b, step)
  if not times:
    return []
  a, b = times[0], times[-1]
  out = []
  for t in times:
    u = (t - a) / max(b - a, 1e-9)
    out.append((t, v0 + (v1 - v0) * u**1.5, p0 + (p1 - p0) * u))
  return out


def looped(start: float, stop: float, period: float, notes: list, beat: float) -> list:
  """Notes given as (beats, ...) from `start`, repeated every `period` beats, kept before stop."""
  out = []
  k = 0
  while True:
    base = k * period
    if snap(start + base * beat) >= stop:
      return out
    for n in notes:
      t = snap(start + (base + n[0]) * beat)
      if t < stop - 1e-9:
        out.append((t, *n[1:]))
    k += 1


def make_score(timing: Timing) -> Score:
  """Builds the whole arrangement from the film's timing."""
  B = timing.beat
  BAR = timing.bar
  s01, s02, s03, s04, s05, s06, s07, s08, s09, s10, s11, s12 = (
    timing.scene(f'S{i:02d}') for i in range(1, 13)
  )
  end = timing.dur
  end_card = s12.at(S12_END_CARD_AT)
  if not s12.start < end_card < s12.end:
    raise ValueError(f'the end card (S12 + {S12_END_CARD_AT} frames) is outside S12')

  # Landmarks shared by several parts.
  drop1 = snap(s06.start - DROP_GAP * B)  # kick and sub out before the S06 wipe
  drop2 = snap(s09.start - DROP_GAP * B)  # ... and before the S09 wipe
  gap3 = snap(s12.start - BUILD_GAP * B)  # drums and sub out before the S12 wipe
  riser1 = snap(s06.start - RISER_BEATS * B)
  riser2 = snap(s09.start - RISER_BEATS * B)
  discover_full = snap(s08.start + DISCOVER_SPARSE_BARS * BAR)  # S08 kick on every beat
  thump = s01.at(S01_WORD_AT + THUMP_AFTER_WORD)

  # ---- sections and harmony
  bounds = [(s.id, s.start, s.end) for s in timing.scenes if s.id != 'S12']
  bounds += [('S12a', s12.start, end_card), ('S12b', end_card, s12.end)]
  missing = [i for i, _, _ in bounds if i not in SECTIONS]
  if missing:
    raise ValueError(f'score.py has no SECTIONS entry (chords, label) for {", ".join(missing)}')
  sections = [Section(i, SECTIONS[i].label, SECTIONS[i].plays, a, b) for i, a, b in bounds]
  chords: list[ChordSpan] = []
  for sec in sections:
    spec = SECTIONS[sec.id]
    for a, b, name in lay_chords(spec.prog, sec.start, sec.end, B):
      if chords and chords[-1].name == name and chords[-1].end == a:
        chords[-1] = ChordSpan(chords[-1].start, b, name, chords[-1].pad)
      else:
        chords.append(ChordSpan(a, b, name, spec.pad))

  # ---- drums
  kicks = (
    [(t, 1.0) for t in grid(s02.start, drop1, B)]
    + [(t, 1.0) for t in grid(s06.start, s08.start, B)]
    + [(t, 0.75) for t in grid(s08.start, discover_full, 2 * B)]  # beats 1 and 3
    + [(t, 0.85) for t in grid(discover_full, drop2, B)]
    + [(t, 1.0) for t in grid(s09.start, gap3, B)]
    + [(s12.start, 1.0), (snap(s12.start + 4 * B), 0.9), (snap(s12.start + 5.5 * B), 0.7)]
    + [(end_card, 1.0)]  # final hit under the impact
  )
  claps = (  # beats 2 and 4
    [(t, 0.8) for t in grid(s04.start, drop1, 2 * B, B)]
    + [(t, 0.85) for t in grid(s06.start, s08.start, 2 * B, B)]
    + [(t, 0.5) for t in grid(discover_full, riser2, 2 * B, B)]
    + [(t, 0.85) for t in grid(s09.start, s11.start, 2 * B, B)]
  )
  halftime_snares = [(snap(s12.start + 2 * B), 1.0), (snap(s12.start + 6 * B), 0.95)]
  build_16ths = snap(s12.start - ROLL_16THS_BEATS * B)
  build_32nds = snap(s12.start - ROLL_32NDS_BEATS * B)
  rolls = (
    roll([(drop1, s06.start - B, B / 4), (s06.start - B, s06.start, B / 8)], 0.22, 0.8, 180, 260)
    + roll([(drop2, s09.start, B / 4)], 0.18, 0.5, 190, 250)
    + roll(
      [
        (s11.start, build_16ths, B / 2),
        (build_16ths, build_32nds, B / 4),
        (build_32nds, gap3, B / 8),
      ],
      0.2,
      0.9,
      170,
      290,
    )
  )

  ev: dict[float, tuple[float, str]] = {}

  def put(t: float, vel: float, kind: str) -> None:
    ev[round(t, 6)] = (vel, kind)  # a later part replaces an earlier one at the same time

  for t in grid(s02.start, s03.start, B, B / 2):
    put(t, 0.32, 'c')
  for t in grid(s03.start, drop1, B / 2, B / 4):
    put(t, 0.13 if t < s04.start else 0.16, 'g')
  for t in grid(s03.start, drop1, B, B / 2):
    put(t, 0.4 if t < s04.start else 0.48, 'c')
  for t in grid(s05.start, drop1, 2 * B, 1.5 * B):
    put(t, 0.42, 'o')
  for t in grid(s06.start, s07.start, B / 2, B / 4):  # S06: open hats on every off-beat
    put(t, 0.24, 'g')
  for t in grid(s06.start, s07.start, B, B / 2):
    put(t, 0.4, 'o')
  for t in grid(s07.start, s08.start, B / 2, B / 4):  # S07: lighter
    put(t, 0.16, 'g')
  for t in grid(s07.start, s08.start, B, B / 2):
    put(t, 0.5, 'c')
  for t in grid(s08.start, riser2, B / 2):  # S08: soft 16th ticks while the agent works
    put(t, 0.12 + 0.2 * (t - s08.start) / (riser2 - s08.start), 't')
  for t in grid(discover_full, drop2, B, B / 2):  # ... off-beat hats once the filter opens
    put(t, 0.4, 'c')
  for t in grid(s09.start, gap3, B / 2, B / 4):  # S09-S11
    put(t, 0.26, 'g')
  for t in grid(s09.start, gap3, B, B / 2):
    put(t, 0.55, 'c')
  for t in grid(s10.start, s11.start, 2 * B, B / 2):
    put(t, 0.4, 'o')
  for t in grid(s12.start, end_card, B, B / 2):  # S12a half-time: straight eighths
    put(t, 0.38, 'c')
  for t in grid(s12.start, end_card, 2 * B):
    put(t, 0.2, 'g')
  hats = sorted((t, v, k) for t, (v, k) in ev.items())

  cymbals = [
    (s02.start, 0.22), (s04.start, 0.45), (s05.start, 0.3), (s06.start, 0.6), (s07.start, 0.25),
    (s09.start, 0.55), (s10.start, 0.2), (s12.start, 0.55), (end_card, 0.6),
  ]  # fmt: skip
  reverse_cymbals = [(s04.start, 0.35), (s05.start, 0.3)]

  # ---- fx: one whoosh per lane wipe, peaking on the cut
  whooshes = []
  for frame in timing.wipes:
    into = timing.scene_starting_at(frame)
    pre, level = WHOOSH_STYLE.get(into.id if into else '', WHOOSH_DEFAULT)
    whooshes.append(Whoosh(timing.sec(frame), pre, level))
  risers = [
    Riser(riser1, s06.start, 0.8, 300.0),
    Riser(riser2, s09.start, 0.65, 220.0),
    Riser(s11.start, s12.start, 0.9, 360.0),
  ]
  impacts = [(s06.start, 0.55), (s12.start, 0.7), (end_card, 0.8)]

  # ---- bells
  n_sail = SAIL_MAIN_ROWS + SAIL_MIZZEN_ROWS
  if len(SPARKLE_NOTES) != n_sail:
    raise ValueError(f'SPARKLE_NOTES needs one note per sail segment ({n_sail})')
  sparkles = []
  for k in range(n_sail):
    frame = s01.start_frame + SAIL_AT + SAIL_TRACK_LEAD + SAIL_STAGGER * k
    gain = (0.55 + 0.45 * k / (n_sail - 1)) * (0.85 if k >= SAIL_MAIN_ROWS else 1.0)
    sparkles.append(Sparkle(frame / timing.fps, SPARKLE_NOTES[k], gain, -0.5 + k / (n_sail - 1)))
  s08_tail = snap(s08.end - sum(b for _, b in SECTIONS['S08'].prog.tail) * B)
  bell_motif = looped(s08.start, s08_tail, BELL_MOTIF_PERIOD, BELL_MOTIF_BODY, B)
  bell_motif += [(snap(s08.end + b * B), m) for b, m in BELL_MOTIF_TAIL]
  bell_accents = [
    (s06.start, 86, 0.7), (s06.start, 93, 0.45), (s09.start, 86, 0.6), (s09.start, 90, 0.4),
  ]  # fmt: skip
  final_sparkle = [(end_card + 0.1 * k, m) for k, m in enumerate(FINAL_SPARKLE)]

  # ---- lead
  s06_tail = snap(s06.end - sum(b for _, b in SECTIONS['S06'].prog.tail) * B)
  lead = [
    LeadNote(t, snap(d * B), m)
    for t, d, m in looped(s06.start, s06_tail, LEAD_S06_PERIOD, LEAD_S06_BODY, B)
  ]
  lead += [LeadNote(snap(s06.end + b * B), snap(d * B), m) for b, d, m in LEAD_S06_TAIL]
  lead += [LeadNote(snap(s12.start + b * B), snap(d * B), m) for b, d, m in LEAD_CLOSING]
  resolve_beats, resolve_note = LEAD_RESOLVE
  lead.append(LeadNote(end_card, snap(resolve_beats * B), resolve_note, LEAD_RESOLVE_RELEASE))

  # ---- arp and bass
  arp_ranges = [
    ArpRange(s03.start, s06.start, fade_in=B),
    ArpRange(s06.start, s08.start),
    ArpRange(s08.start, snap(s09.start - ARP_S08_OUT * B), gain=0.85),
    ArpRange(s09.start, gap3),
  ]
  arp_level = [
    (s03.start, 0.72), (s04.start, 0.8), (s05.start, 0.85), (s06.start, 1.0), (s07.start, 0.72),
    (s08.start, 0.95), (s09.start, 0.9), (s11.start, 0.95),
  ]  # fmt: skip
  arp_bright = [
    (s03.start, 0.55), (s04.start, 0.65), (s05.start, 0.75), (s06.start, 1.0), (s07.start, 0.55),
    (s08.start, 0.8), (s09.start, 0.9), (s11.start, 1.0),
  ]  # fmt: skip
  pluck_ranges = [
    PluckRange(s05.start, drop1),
    PluckRange(s06.start, s07.start, pickups=True),
    PluckRange(s09.start, gap3),
  ]

  # ---- automation (seconds, value); the mixer interpolates linearly (pad_lp, group_lp and
  # group_hp in log frequency)
  sub_amp = [
    (s01.start, 0.0), (thump, 0.0), (snap(thump + B), 0.25), (snap(s02.start - STEP), 0.35),
    (s02.start, 1.0),
    (snap(drop1 - 0.02), 1.0), (drop1, 0.0), (snap(s06.start - 0.01), 0.0), (s06.start, 1.0),
    (snap(s08.start - 0.02), 1.0), (s08.start, 0.8), (riser2, 0.8), (drop2, 0.0),
    (snap(s09.start - 0.01), 0.0), (s09.start, 1.0),
    (snap(gap3 - 0.02), 1.0), (gap3, 0.0), (snap(s12.start - 0.01), 0.0), (s12.start, 1.0),
    (end_card, 1.0), (snap(end_card + 1.0), 0.55), (snap(end_card + 3.0), 0.12), (end, 0.0),
  ]  # fmt: skip

  def before(t: float) -> float:
    return snap(t - STEP)

  pad_lp = [
    (s01.start, OPENING_PAD_LP),  # the intro pad opens over the intro
    (snap(s01.start + 0.4 * s01.dur), 700), (snap(s01.start + 0.9 * s01.dur), 1700),
    (s02.start, 2100), (before(s03.start), 2300),
    (s03.start, 2400), (before(s04.start), 2600),
    (s04.start, 2900), (before(s05.start), 3000),
    (s05.start, 3300), (riser1, 3400), (before(s06.start), 4000),
    (s06.start, 4800), (before(s07.start), 4800),
    (s07.start, 2800), (before(s08.start), 2800),
    (s08.start, 2600), (before(s09.start), 3000),
    (s09.start, 3600), (before(s11.start), 3600),
    (s11.start, 3000), (before(s12.start), 6000),
    (s12.start, 5200), (before(end_card), 5200),
    (end_card, 5600), (end, 1300),
  ]  # fmt: skip
  pad_width = [
    (s01.start, 1.0), (before(s06.start), 1.0), (s06.start, 1.6), (before(s07.start), 1.6),
    (s07.start, 1.1), (before(s12.start), 1.1), (s12.start, 1.35), (end_card, 1.5), (end, 1.5),
  ]  # fmt: skip
  pad_level = [
    (s01.start, 0.85), (before(s02.start), 0.85), (s02.start, 0.78), (before(s04.start), 0.78),
    (s04.start, 0.82), (before(s05.start), 0.82), (s05.start, 0.86), (before(s06.start), 0.9),
    (s06.start, 1.0), (before(s07.start), 1.0), (s07.start, 0.8), (before(s08.start), 0.8),
    (s08.start, 0.95), (before(s09.start), 0.95), (s09.start, 0.88), (s11.start, 0.88),
    (before(s12.start), 0.98), (s12.start, 1.0), (end, 1.0),
  ]  # fmt: skip
  discover_open = snap(s09.start - 0.3)
  group_lp = [
    (s01.start, 20000), (snap(s08.start - 0.1), 20000),
    (snap(s08.start + 0.05), DISCOVER_LP_CLOSED), (discover_open, DISCOVER_LP_OPEN),
    (s09.start, 20000), (end, 20000),
  ]  # fmt: skip
  group_hp: Curve = [(s01.start, 20)]
  for r in risers:
    group_hp += [(r.start, 20), (snap(r.end - 0.03), r.hp_top), (r.end, 20)]
  group_hp.append((end, 20))

  ref_whoosh = whooshes[0]
  score = Score(
    timing=timing,
    dur=end,
    sections=sections,
    chords=chords,
    kicks=kicks,
    claps=claps,
    halftime_snares=halftime_snares,
    rolls=rolls,
    hats=hats,
    cymbals=cymbals,
    reverse_cymbals=reverse_cymbals,
    whooshes=whooshes,
    risers=risers,
    impacts=impacts,
    thump=thump,
    sparkles=sparkles,
    bell_motif=bell_motif,
    bell_accents=bell_accents,
    final_sparkle=final_sparkle,
    lead=lead,
    arp_ranges=arp_ranges,
    arp_halftime=(s12.start, end_card),
    arp_level=arp_level,
    arp_bright=arp_bright,
    pluck_ranges=pluck_ranges,
    sub_amp=sub_amp,
    end_card=end_card,
    pad_lp=pad_lp,
    pad_width=pad_width,
    pad_level=pad_level,
    group_lp=group_lp,
    group_hp=group_hp,
    fade_start=timing.sec(timing.total_frames - FADE_FRAMES),
    windows={
      'all': (0.0, end),
      'full': (s06.start, s06.end),  # the fullest section: the reference for most buses
      'bells': (s08.start, s08.end),
      # the first wipe's whoosh (the intro's): every whoosh level is relative to it
      'whoosh': (snap(ref_whoosh.peak - ref_whoosh.pre), snap(ref_whoosh.peak + ref_whoosh.post)),
    },
    marks={
      'drop1': drop1,
      'drop2': drop2,
      'gap3': gap3,
      'riser1': riser1,
      'riser2': riser2,
      'discover_full': discover_full,
      'discover_open': discover_open,
      'build_16ths': build_16ths,
      'build_32nds': build_32nds,
    },
  )
  for name, curve in score.curves().items():
    times = [t for t, _ in curve]
    if any(b < a for a, b in zip(times, times[1:])):
      raise ValueError(f'{name} automation runs backwards; a scene is too short for it: {curve}')
  return score
