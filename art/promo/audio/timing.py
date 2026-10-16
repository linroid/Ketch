"""Film timing for the music, read from `src/timing.json`, the file the Remotion code reads too.

timing.json counts in video frames (global, `fps` per second); the music works in seconds. A
scene's start and end in seconds are its frames divided by `fps`, so a cue built from them lands
on the cut the picture uses. One beat is `beat` frames (15 at 30 fps, so 120 BPM).
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_TIMING = os.path.normpath(os.path.join(HERE, '..', 'src', 'timing.json'))


def snap(t: float) -> float:
  """Rounds a time in seconds to the microsecond.

  Derived times (`start - 0.05`, `end - 2 * beat`) then equal the literal they stand for, so the
  same timing always gives the same floats, seeds and sample indices.
  """
  return round(t, 6)


@dataclass(frozen=True)
class Scene:
  """One scene of the film: global frames from timing.json, seconds as properties."""

  id: str
  start_frame: int
  len_frames: int
  fps: int

  @property
  def end_frame(self) -> int:
    return self.start_frame + self.len_frames

  @property
  def start(self) -> float:
    """Start in seconds (the cut into this scene)."""
    return snap(self.start_frame / self.fps)

  @property
  def end(self) -> float:
    """End in seconds (the cut out of this scene, which is the next scene's start)."""
    return snap(self.end_frame / self.fps)

  @property
  def dur(self) -> float:
    return snap(self.len_frames / self.fps)

  def at(self, local_frame: float) -> float:
    """Seconds of a frame local to this scene (0 is the scene's first frame)."""
    return snap((self.start_frame + local_frame) / self.fps)


@dataclass(frozen=True)
class Timing:
  fps: int
  width: int
  height: int
  beat_frames: int
  total_frames: int
  scenes: tuple[Scene, ...]
  wipes: tuple[int, ...]
  path: str

  @property
  def beat(self) -> float:
    """One beat in seconds (0.5 at 120 BPM)."""
    return self.beat_frames / self.fps

  @property
  def bar(self) -> float:
    """One 4/4 bar in seconds."""
    return 4 * self.beat

  @property
  def bpm(self) -> float:
    return 60.0 / self.beat

  @property
  def dur(self) -> float:
    """Length of the film in seconds."""
    return snap(self.total_frames / self.fps)

  def sec(self, frame: float) -> float:
    """Seconds of a global frame."""
    return snap(frame / self.fps)

  def scene(self, scene_id: str) -> Scene:
    for s in self.scenes:
      if s.id == scene_id:
        return s
    raise KeyError(f'{self.path} has no scene {scene_id}; the music score (score.py) needs it')

  def scene_starting_at(self, frame: int) -> Scene | None:
    return next((s for s in self.scenes if s.start_frame == frame), None)


def load_timing(path: str = DEFAULT_TIMING) -> Timing:
  """Reads timing.json. Raises ValueError when the scenes do not add up (see `timing_errors`)."""
  with open(path, encoding='utf-8') as f:
    raw = json.load(f)
  fps = int(raw['fps'])
  scenes = tuple(Scene(s['id'], int(s['start']), int(s['len']), fps) for s in raw['scenes'])
  timing = Timing(
    fps=fps,
    width=int(raw['width']),
    height=int(raw['height']),
    beat_frames=int(raw['beat']),
    total_frames=int(raw['total']),
    scenes=scenes,
    wipes=tuple(int(w) for w in raw['wipes']),
    path=path,
  )
  errors = timing_errors(timing)
  if errors:
    raise ValueError(f'{path}:\n  ' + '\n  '.join(errors))
  return timing


def timing_errors(t: Timing) -> list[str]:
  """What the music cannot follow: scenes must run back to back from frame 0 to `total`, and
  every wipe must sit on a cut."""
  errors = []
  at = 0
  for s in t.scenes:
    if s.start_frame != at:
      errors.append(f'{s.id} starts at {s.start_frame}, expected {at} (end of the scene before)')
    if s.len_frames <= 0:
      errors.append(f'{s.id} has length {s.len_frames}')
    at = s.start_frame + s.len_frames
  if at != t.total_frames:
    errors.append(f'the scenes end at frame {at} but total is {t.total_frames}')
  starts = {s.start_frame for s in t.scenes}
  for w in t.wipes:
    if w not in starts or w == 0:
      errors.append(f'wipe at frame {w} is not on a cut between two scenes')
  return errors


def timing_warnings(t: Timing) -> list[str]:
  """What the music can follow but will not sound right: a cut that is not on a beat puts every
  later bar line, drum hit and chord change off the picture's beat grid."""
  warnings = [
    f'{s.id} starts at frame {s.start_frame}, which is not on a beat ({t.beat_frames} frames)'
    for s in t.scenes
    if s.start_frame % t.beat_frames
  ]
  if t.total_frames % t.beat_frames:
    warnings.append(f'total {t.total_frames} is not a whole number of beats')
  return warnings
