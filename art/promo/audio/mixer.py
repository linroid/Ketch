"""Renders a Score into buses, mixes and masters them, and measures the result.

- buses: each instrument family plays its score events into one stereo bus
- calibration: each bus is set to a level measured over a reference window (CALIBRATION)
- mix: sidechain pump from the kick, the synth group's filters and width, delays, two
  convolution reverbs
- master: 25 Hz high-pass, soft-knee saturation, 4x oversampled look-ahead true-peak limiter,
  loudness set to a target (ITU-R BS.1770 K-weighting with gating), fade to digital silence,
  TPDF dither to 24 bit
"""

from __future__ import annotations

import math
import os
import time
import wave
from dataclasses import dataclass

import numpy as np
from scipy import signal
from scipy.ndimage import minimum_filter1d, uniform_filter1d

from score import (
  ARP_HALFTIME_PATTERN,
  ARP_PATTERN,
  CHORDS,
  REVERSE_CYMBAL_SECS,
  Curve,
  Score,
  grid,
)
from synth import (
  SR,
  bass_pluck,
  bell,
  clap,
  cymbal,
  db,
  hat,
  impact,
  kick,
  lead_note,
  make_ir,
  mtof,
  pad_note,
  pan_gains,
  pingpong,
  pluck,
  release_follower,
  riser,
  rms,
  rng_for,
  snare,
  svf,
  thump,
  whoosh,
)

BUS_NAMES = [
  'kick', 'clap', 'hats', 'cymbal', 'sub', 'pad', 'arp', 'pluck', 'lead', 'bells', 'whoosh',
  'riser', 'impact',
]  # fmt: skip

# Per-bus calibration before the master gain: (reference window in Score.windows, metric,
# target dBFS). 'rms' buses are set by their loudness in the fullest section, one-shot effects
# by their peak; every level in the score is relative to this.
CALIBRATION = {
  'kick': ('full', 'rms', -18.5),
  'sub': ('full', 'rms', -23.5),
  'pad': ('full', 'rms', -21.0),
  'arp': ('full', 'rms', -25.0),
  'pluck': ('full', 'rms', -26.5),
  'lead': ('full', 'rms', -25.0),
  'bells': ('bells', 'rms', -28.0),
  'hats': ('full', 'rms', -37.0),
  'clap': ('full', 'rms', -28.5),
  'cymbal': ('all', 'peak', -21.0),
  'whoosh': ('whoosh', 'peak', -17.0),
  'riser': ('all', 'peak', -17.0),
  'impact': ('all', 'peak', -10.0),
}
HALL_LEVEL = -25.5  # dBFS RMS of each reverb's return over the 'full' window
ROOM_LEVEL = -32.0
LIMITER_CEILING = -1.4  # dBTP


# ------------------------------------------------------------------------------------------
# Timeline helpers
# ------------------------------------------------------------------------------------------


class Bus:
  def __init__(self, name: str, n: int):
    self.name = name
    self.x = np.zeros((2, n), dtype=np.float64)

  def add(self, sig: np.ndarray, t0: float, pan: float = 0.0, gain: float = 1.0) -> None:
    """Mixes a mono (n,) or stereo (2, n) signal in, starting at t0 seconds (may be < 0)."""
    sig = np.asarray(sig, dtype=np.float64)
    if sig.ndim == 1:
      gl, gr = pan_gains(pan)
      sig = np.vstack([sig * gl, sig * gr])
    elif pan != 0.0:
      gl, gr = pan_gains(pan)
      sig = np.vstack([sig[0] * gl, sig[1] * gr])
    n_total = self.x.shape[1]
    i0 = int(round(t0 * SR))
    s0 = 0
    if i0 < 0:
      s0 = -i0
      i0 = 0
    n = min(sig.shape[1] - s0, n_total - i0)
    if n <= 0:
      return
    self.x[:, i0 : i0 + n] += gain * sig[:, s0 : s0 + n]


def auto(points: Curve, n: int, log: bool = False) -> np.ndarray:
  """Piecewise-linear (or log-linear) automation curve over the whole film."""
  tt = np.arange(n) / SR
  ts = np.array([p[0] for p in points], dtype=float)
  vs = np.array([p[1] for p in points], dtype=float)
  if log:
    return np.exp(np.interp(tt, ts, np.log(vs)))
  return np.interp(tt, ts, vs)


def section_value(t: float, table: list[tuple[float, float]]) -> float:
  """Piecewise-constant lookup: table is [(start, value), ...] sorted by start."""
  v = table[0][1]
  for s, val in table:
    if t >= s - 1e-9:
      v = val
  return v


def sidechain(kicks: list[tuple[float, float]], n: int, depth: float, release: float):
  """Gain curve that dips at each kick and recovers over `release` s (the 'pump')."""
  g = np.ones(n)
  ia = int(0.004 * SR)
  ir = int(release * SR)
  shape = np.concatenate(
    [np.linspace(0.0, 1.0, ia, endpoint=False), (1.0 - np.linspace(0.0, 1.0, ir)) ** 2]
  )
  for t, vel in kicks:
    i0 = int(round(t * SR)) - ia
    seg = 1.0 - depth * vel * shape
    a = max(i0, 0)
    b = min(i0 + seg.size, n)
    if b > a:
      g[a:b] = np.minimum(g[a:b], seg[a - i0 : b - i0])
  return g


def convolve(x: np.ndarray, ir: np.ndarray) -> np.ndarray:
  n = x.shape[1]
  return np.vstack([signal.oaconvolve(x[c], ir[c])[:n] for c in range(2)])


def window(x: np.ndarray, w: tuple[float, float]) -> np.ndarray:
  return x[..., int(w[0] * SR) : int(w[1] * SR)]


# ------------------------------------------------------------------------------------------
# Buses
# ------------------------------------------------------------------------------------------


def render(score: Score) -> dict[str, Bus]:
  """Plays every score event into its bus (dry, before calibration)."""
  t_start = time.time()
  n = int(round(score.dur * SR))
  B = score.timing.beat
  buses = {k: Bus(k, n) for k in BUS_NAMES}

  # ---- pads
  for c in score.chords:
    ch = CHORDS[c.name]
    dur = c.pad.hold if c.pad.hold is not None else c.end - c.start
    for m in ch['pad']:
      rng = rng_for('pad', int(c.start * 1000), m)
      buses['pad'].add(pad_note(m, dur, c.pad.attack, c.pad.release, rng), c.start, pan=0.0)
    for m, gain in ch.get('extra', ()):  # extra warmth for the last chord
      rng = rng_for('pad-final', m)
      note = pad_note(m, dur, c.pad.attack, c.pad.release, rng) * gain
      buses['pad'].add(note, c.start)

  # ---- arp (16ths, the pattern restarting with each range), half-time eighths at the end
  for r in score.arp_ranges:
    for t in grid(r.start, r.end, B / 4):
      step = int(round((t - r.start) / (B / 4))) % len(ARP_PATTERN)
      ch = CHORDS[score.chord_at(t)]
      m = ch['arp'][ARP_PATTERN[step]]
      acc = 1.0 if step % 4 == 0 else (0.78 if step % 2 == 0 else 0.64)
      lvl = section_value(t, score.arp_level)
      lvl *= r.gain
      if r.fade_in and t < r.start + r.fade_in:
        lvl *= 0.6 + 0.4 * (t - r.start) / r.fade_in
      bright = section_value(t, score.arp_bright)
      rng = rng_for('arp', int(t * 1000))
      buses['arp'].add(
        pluck(m, rng, bright=bright), t, pan=0.22 if step % 2 else -0.22, gain=acc * lvl
      )
  for k, t in enumerate(grid(*score.arp_halftime, B)):
    ch = CHORDS[score.chord_at(t)]
    m = ch['arp'][ARP_HALFTIME_PATTERN[k % len(ARP_HALFTIME_PATTERN)]]
    rng = rng_for('arp-ht', int(t * 1000))
    note = pluck(m, rng, bright=0.9, decay=0.24, length=0.6)
    buses['arp'].add(note, t, pan=0.25 if k % 2 else -0.25, gain=0.75)

  # ---- bass pluck (off-beat eighths), with pickups where the range asks for them
  for r in score.pluck_ranges:
    for t in grid(r.start, r.end, B, B / 2):
      ch = CHORDS[score.chord_at(t)]
      rng = rng_for('pluck', int(t * 1000))
      buses['pluck'].add(bass_pluck(ch['root'] + 12, rng), t, gain=1.0)
    if r.pickups:  # the last sixteenth of each bar
      for t in grid(r.start, r.end, 4 * B, 3.75 * B):
        ch = CHORDS[score.chord_at(t)]
        rng = rng_for('pluck-pu', int(t * 1000))
        buses['pluck'].add(bass_pluck(ch['root'] + 12, rng, length=0.12), t, gain=0.7)

  # ---- sub: one continuous sine following the roots (8 ms glide)
  tt = np.arange(n) / SR
  grid5 = np.arange(0, score.dur, 0.005)
  roots = np.array([mtof(CHORDS[score.chord_at(t)]['root']) for t in grid5])
  freq = np.interp(tt, grid5, roots)
  a = math.exp(-1 / (0.008 * SR))
  freq = signal.lfilter([1 - a], [1, -a], freq, zi=[freq[0] * a])[0]
  ph = 2 * np.pi * np.cumsum(freq) / SR
  sub = np.sin(ph) + 0.2 * np.sin(2 * ph)
  sub = np.tanh(1.4 * sub) / math.tanh(1.4)
  sub *= auto(score.sub_amp, n)
  final_low = np.zeros(n)  # a low D under the final chord
  i0 = int(score.end_card * SR)
  tl = tt[i0:] - score.end_card
  final_low[i0:] = (
    0.1 * np.sin(2 * np.pi * mtof(26) * tl) * np.exp(-tl / 1.6) * (1 - np.exp(-tl / 0.01))
  )
  buses['sub'].x[0] += sub + final_low
  buses['sub'].x[1] += sub + final_low

  # ---- drums
  for t, vel in score.kicks:
    buses['kick'].add(kick(vel, rng_for('kick', int(t * 1000))), t)
  for t, vel in score.claps:
    buses['clap'].add(clap(rng_for('clap', int(t * 1000))), t, gain=vel, pan=0.05)
  for t, vel in score.halftime_snares:
    rng = rng_for('ht-snare', int(t * 1000))
    c = clap(rng) * 0.9
    s = snare(rng, 185, 0.16) * 0.7
    c[: s.size] += s
    buses['clap'].add(c, t, gain=vel)
  for t, vel, pitch in score.rolls:
    rng = rng_for('roll', int(t * 10000))
    buses['clap'].add(snare(rng, pitch, 0.07), t, gain=vel * 0.8, pan=-0.1)
  hat_bank = {
    'c': [hat(rng_for('hat-c', i), 0.032, 0.25) for i in range(6)],
    'o': [hat(rng_for('hat-o', i), 0.14, 0.7) for i in range(3)],
    'g': [hat(rng_for('hat-g', i), 0.018, 0.15) for i in range(6)],
    't': [hat(rng_for('hat-t', i), 0.008, 0.08) for i in range(4)],
  }
  pans = {'c': 0.18, 'o': 0.22, 'g': -0.2, 't': 0.35}
  for i, (t, vel, kind) in enumerate(score.hats):
    bank = hat_bank[kind]
    hv = vel * (0.92 + 0.08 * ((i * 7919) % 13) / 12.0)  # small deterministic humanising
    pan = pans[kind] * (1 if kind != 't' else (1 if i % 2 else -1))
    buses['hats'].add(bank[i % len(bank)], t, pan=pan, gain=hv)
  for t, vel in score.cymbals:
    buses['cymbal'].add(cymbal(rng_for('cym', int(t * 1000))), t, gain=vel)
  for t, vel in score.reverse_cymbals:
    c = cymbal(rng_for('rcym', int(t * 1000)), decay=0.45, length=REVERSE_CYMBAL_SECS)[:, ::-1]
    buses['cymbal'].add(c, t - c.shape[1] / SR, gain=vel)

  # ---- lead
  for note in score.lead:
    rng = rng_for('lead', int(note.start * 1000))
    sig = lead_note(note.midi, note.dur, rng, release=note.release)
    buses['lead'].add(sig, note.start, pan=0.08)

  # ---- bells
  for s in score.sparkles:
    sig = bell(s.midi, ratio=2.0, index=1.6, decay=0.55, length=2.2)
    buses['bells'].add(sig, s.t, pan=s.pan, gain=s.gain)
  for t, m in score.bell_motif:
    sig = bell(m, ratio=3.5, index=1.4, decay=0.7)
    buses['bells'].add(sig, t, pan=0.3 if m % 2 else -0.3, gain=0.8)
  for t, m, vel in score.bell_accents:
    buses['bells'].add(bell(m, ratio=3.5, index=2.0, decay=1.1), t, gain=vel)
  for k, (t, m) in enumerate(score.final_sparkle):
    sig = bell(m, ratio=2.0, index=1.5, decay=1.0, length=3.0)
    buses['bells'].add(sig, t, pan=-0.4 + 0.2 * k, gain=0.55 + 0.08 * k)

  # ---- fx
  for w in score.whooshes:
    sig = whoosh(rng_for('whoosh', int(w.peak * 1000)), pre=w.pre, post=w.post)
    buses['whoosh'].add(sig, w.peak - w.pre, gain=w.level)
  for r in score.risers:
    sig = riser(rng_for('riser', int(r.start * 1000)), r.end - r.start)
    buses['riser'].add(sig, r.start, gain=r.level)
  for t, lvl in score.impacts:
    buses['impact'].add(impact(rng_for('impact', int(t * 1000))), t, gain=lvl)
  buses['impact'].add(thump(), score.thump, gain=0.75)

  print(f'  synthesis: {time.time() - t_start:.1f} s')
  return buses


def calibrate(buses: dict[str, Bus], score: Score) -> None:
  for name, (win, metric, target) in CALIBRATION.items():
    seg = window(buses[name].x, score.windows[win])
    level = rms(seg) if metric == 'rms' else float(np.max(np.abs(seg)))
    buses[name].x *= 10 ** ((target - db(level)) / 20.0)


# ------------------------------------------------------------------------------------------
# Mix
# ------------------------------------------------------------------------------------------


def mix(buses: dict[str, Bus], score: Score) -> np.ndarray:
  t_start = time.time()
  n = buses['pad'].x.shape[1]
  kicks = score.kicks
  sc_pad = sidechain(kicks, n, 0.42, 0.34)
  sc_sub = sidechain(kicks, n, 0.85, 0.26)
  sc_arp = sidechain(kicks, n, 0.25, 0.3)
  sc_pluck = sidechain(kicks, n, 0.35, 0.22)
  sc_lead = sidechain(kicks, n, 0.15, 0.3)
  sc_verb = sidechain(kicks, n, 0.3, 0.36)

  # synth group: pad + arp (with its delay) + bass pluck, through the group filters
  pad = buses['pad'].x
  pad_lp = auto(score.pad_lp, n, log=True)
  pad = np.vstack([svf2(pad[c], pad_lp) for c in range(2)])
  width = auto(score.pad_width, n)
  mid = 0.5 * (pad[0] + pad[1])
  side = 0.5 * (pad[0] - pad[1]) * width
  pad = np.vstack([mid + side, mid - side])
  pad *= auto(score.pad_level, n) * sc_pad

  arp = buses['arp'].x
  arp = arp + 0.3 * pingpong(arp, 0.375, 0.36, 3200.0)
  arp *= sc_arp
  pluck_x = buses['pluck'].x * sc_pluck

  group = pad + arp + pluck_x
  g_lp = auto(score.group_lp, n, log=True)
  g_hp = auto(score.group_hp, n, log=True)
  group = np.vstack([svf(svf(group[c], g_lp, 1.0, 'lp'), g_hp, 0.7, 'hp') for c in range(2)])

  sub = buses['sub'].x * sc_sub
  lead = buses['lead'].x
  lead = (lead + 0.22 * pingpong(lead, 0.375, 0.3, 2800.0)) * sc_lead
  bells = buses['bells'].x
  bells = bells + 0.32 * pingpong(bells, 0.375, 0.4, 4200.0)
  bells = np.vstack([svf(bells[c], 7500.0, 0.7, 'lp') for c in range(2)])

  drums = buses['kick'].x + buses['clap'].x + buses['hats'].x + buses['cymbal'].x
  fx = buses['whoosh'].x + buses['riser'].x + buses['impact'].x

  # reverbs
  hall_ir = make_ir(rng_for('hall'), 4.6, (3.1, 2.6, 1.1), 0.022)
  room_ir = make_ir(rng_for('room'), 1.6, (0.9, 0.75, 0.35), 0.008)
  hall_send = (
    0.30 * pad + 0.22 * arp + 0.36 * lead + 0.5 * bells + 0.2 * buses['cymbal'].x
    + 0.16 * buses['clap'].x + 0.3 * buses['whoosh'].x + 0.35 * buses['riser'].x
    + 0.4 * buses['impact'].x
  )  # fmt: skip
  room_send = 0.35 * buses['clap'].x + 0.14 * buses['hats'].x + 0.04 * buses['kick'].x
  hall = convolve(hall_send, hall_ir)
  room = convolve(room_send, room_ir)
  verb_hp = signal.butter(2, 190, 'high', fs=SR, output='sos')
  verb_lp = signal.butter(2, 8500, 'low', fs=SR, output='sos')
  hall = signal.sosfilt(verb_lp, signal.sosfilt(verb_hp, hall, axis=1), axis=1)
  room = signal.sosfilt(verb_lp, signal.sosfilt(verb_hp, room, axis=1), axis=1)
  ref = score.windows['full']
  hall *= 10 ** ((HALL_LEVEL - db(rms(window(hall, ref)))) / 20.0) * sc_verb
  room *= 10 ** ((ROOM_LEVEL - db(rms(window(room, ref)))) / 20.0)

  out = group + sub + lead + bells + drums + fx + hall + room
  print(f'  mix: {time.time() - t_start:.1f} s')
  return out


def svf2(x: np.ndarray, fc: np.ndarray) -> np.ndarray:
  """The pads' 4-pole low-pass: two SVFs, the second an octave and a bit above the first."""
  return svf(svf(x, fc, 0.8, 'lp'), fc * 2.6, 0.6, 'lp')


# ------------------------------------------------------------------------------------------
# Master and measurement
# ------------------------------------------------------------------------------------------


def k_weight(x: np.ndarray) -> np.ndarray:
  """ITU-R BS.1770 K-weighting at 48 kHz."""
  b1 = [1.53512485958697, -2.69169618940638, 1.19839281085285]
  a1 = [1.0, -1.69065929318241, 0.73248077421585]
  b2 = [1.0, -2.0, 1.0]
  a2 = [1.0, -1.99004745483398, 0.99007225036621]
  return signal.lfilter(b2, a2, signal.lfilter(b1, a1, x, axis=-1), axis=-1)


def block_powers(x: np.ndarray, block: float = 0.4, hop: float = 0.1) -> np.ndarray:
  y = k_weight(x)
  p = np.sum(y**2, axis=0)
  c = np.concatenate([[0.0], np.cumsum(p)])
  bl = int(block * SR)
  hp = int(hop * SR)
  starts = np.arange(0, p.size - bl + 1, hp)
  return (c[starts + bl] - c[starts]) / bl


def integrated_lufs(x: np.ndarray) -> float:
  z = block_powers(x)
  lk = -0.691 + 10 * np.log10(np.maximum(z, 1e-20))
  z1 = z[lk > -70.0]
  rel = -0.691 + 10 * np.log10(np.mean(z1)) - 10.0
  z2 = z[(lk > -70.0) & (lk > rel)]
  return float(-0.691 + 10 * np.log10(np.mean(z2)))


def short_term(x: np.ndarray) -> np.ndarray:
  """Short-term loudness (3 s windows, 10 per second), LUFS."""
  z = block_powers(x, 3.0, 0.1)
  return -0.691 + 10 * np.log10(np.maximum(z, 1e-20))


def loudness_range(x: np.ndarray) -> float:
  """EBU Tech 3342 loudness range (LU): 10th to 95th percentile of the gated short-term
  loudness (absolute gate -70 LUFS, relative gate 20 LU below their power mean)."""
  st = short_term(x)
  st = st[st > -70.0]
  rel = 10 * np.log10(np.mean(10 ** (st / 10))) - 20.0
  st = st[st > rel]
  return float(np.percentile(st, 95) - np.percentile(st, 10))


def true_peak(x: np.ndarray) -> float:
  up = signal.resample_poly(x, 4, 1, axis=1)
  return float(np.max(np.abs(up)))


def soft_clip(x: np.ndarray, th: float = 0.5) -> np.ndarray:
  a = np.abs(x)
  y = np.where(a <= th, a, th + (1 - th) * np.tanh((a - th) / (1 - th)))
  return np.sign(x) * y


def limiter(
  x: np.ndarray, ceiling_db: float = LIMITER_CEILING, look: float = 0.003, release: float = 0.09
):
  """Look-ahead limiter on the 4x oversampled peak; returns the signal and its gain curve."""
  n = x.shape[1]
  ceil = 10 ** (ceiling_db / 20.0)
  up = signal.resample_poly(x, 4, 1, axis=1)
  pk = np.max(np.abs(up), axis=0)[: n * 4].reshape(n, 4).max(axis=1)
  g = np.minimum(1.0, ceil / np.maximum(pk, 1e-12))
  la = int(look * SR) | 1
  g = minimum_filter1d(g, size=2 * la + 1, mode='nearest')
  g = release_follower(g, 1.0 - math.exp(-1.0 / (release * SR)))
  g = uniform_filter1d(g, size=la, mode='nearest')
  return x * g, g


def fade_curve(n: int, fade_start: float) -> np.ndarray:
  """5 ms fade-in, then a raised-cosine fade from fade_start to digital silence at the end."""
  f = np.ones(n)
  i = int(0.005 * SR)
  f[:i] = np.linspace(0.0, 1.0, i)
  a = int(fade_start * SR)
  u = np.linspace(0.0, 1.0, n - a)
  f[a:] = (0.5 + 0.5 * np.cos(np.pi * u)) ** 1.5
  return f


def master(x: np.ndarray, gain: float, fade_start: float):
  n = x.shape[1]
  x = signal.sosfilt(signal.butter(2, 25, 'high', fs=SR, output='sos'), x, axis=1)
  x = x * gain
  x = soft_clip(x, 0.55)
  x, g = limiter(x)
  fade = fade_curve(n, fade_start)
  x *= fade
  x -= np.mean(x, axis=1, keepdims=True) * fade  # residual DC (tiny) removed, ends stay 0
  return x, g


def master_to(pre: np.ndarray, target_lufs: float, fade_start: float):
  """Masters with the gain that puts the result at target_lufs (within 0.05 LU)."""
  gain = 1.0
  for _ in range(5):
    y, g = master(pre, gain, fade_start)
    lufs = integrated_lufs(y)
    if abs(lufs - target_lufs) < 0.05:
      break
    gain *= 10 ** ((target_lufs - lufs) / 20.0)
  print(f'  master gain {db(gain):+.2f} dB')
  return y, g


def write_wav24(path: str, x: np.ndarray) -> None:
  """24-bit stereo WAV with TPDF dither; the first and last 5 ms are not dithered (silence)."""
  os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
  rng = rng_for('dither')
  d = rng.random(x.shape) - rng.random(x.shape)  # TPDF, +-1 LSB
  q = np.round(x * 8388607.0 + d)
  q[:, :240] = np.round(x[:, :240] * 8388607.0)
  q[:, -240:] = 0.0  # digital silence at the end
  q = np.clip(q, -8388608, 8388607).astype('<i4')
  inter = np.ascontiguousarray(q.T).reshape(-1)
  raw = inter.view(np.uint8).reshape(-1, 4)[:, :3].tobytes()
  with wave.open(path, 'wb') as w:
    w.setnchannels(2)
    w.setsampwidth(3)
    w.setframerate(SR)
    w.writeframes(raw)


@dataclass
class Measurements:
  duration: float
  samples: int
  sample_peak: float  # dBFS
  true_peak: float  # dBTP
  integrated: float  # LUFS
  lra: float  # LU
  short_term_max: float  # LUFS
  mono_loss: float  # LU lost when folded down to mono
  dc: tuple[float, float]
  limiter_max_gr: float  # dB
  limiter_active: float  # fraction of samples
  last_10ms: float
  sections: list[tuple[str, float, float, float, float, float]]  # label, a, b, LUFS, RMS, peak


def measure(x: np.ndarray, g: np.ndarray, score: Score) -> Measurements:
  kp = k_weight(x)
  sections = []
  for s in score.sections:
    i, j = int(s.start * SR), int(s.end * SR)
    seg = x[:, i:j]
    p = np.sum(np.mean(kp[:, i:j] ** 2, axis=1))
    sections.append(
      (s.label, s.start, s.end, -0.691 + 10 * np.log10(p), db(rms(seg)), db(np.max(np.abs(seg))))
    )
  mono = 0.5 * (x[0] + x[1])
  integrated = integrated_lufs(x)
  return Measurements(
    duration=x.shape[1] / SR,
    samples=x.shape[1],
    sample_peak=db(np.max(np.abs(x))),
    true_peak=db(true_peak(x)),
    integrated=integrated,
    lra=loudness_range(x),
    short_term_max=float(np.max(short_term(x))),
    mono_loss=integrated - integrated_lufs(np.vstack([mono, mono])),
    dc=(float(np.mean(x[0])), float(np.mean(x[1]))),
    limiter_max_gr=db(np.min(g)),
    limiter_active=float(np.mean(g < 0.999)),
    last_10ms=float(np.max(np.abs(x[:, -480:]))),
    sections=sections,
  )


def report(m: Measurements, fps: int) -> None:
  print(f'duration        {m.duration:.3f} s ({m.samples} samples, {m.duration * fps:.1f} frames)')
  print(f'integrated      {m.integrated:.2f} LUFS')
  print(f'true peak (4x)  {m.true_peak:.2f} dBTP')
  print(f'sample peak     {m.sample_peak:.2f} dBFS')
  print(f'loudness range  {m.lra:.1f} LU')
  print(f'short-term max  {m.short_term_max:.2f} LUFS (3 s)')
  print(f'mono fold-down  -{m.mono_loss:.2f} LU')
  print(f'DC offset       L {m.dc[0]:+.2e}  R {m.dc[1]:+.2e}')
  print(
    f'limiter         max gain reduction {m.limiter_max_gr:.2f} dB, '
    f'active {m.limiter_active * 100:.1f}% of samples'
  )
  print(f'last 10 ms      max |x| = {m.last_10ms:.2e}')
  print('section                    LUFS(ungated)  RMS dBFS  peak dBFS')
  for label, _, _, lufs, r, pk in m.sections:
    print(f'  {label:<24} {lufs:9.1f}  {r:9.1f}  {pk:8.1f}')
