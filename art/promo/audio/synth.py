"""The instruments: DSP kernels and one function per sound, each returning a short array of
samples that the mixer places on the timeline. Nothing here knows about the film's timing.

- pads: detuned PolyBLEP saws with slow pitch drift (filtered on the synth group in mixer.py)
- arp and bass plucks: detuned saws with a filter envelope
- bells and sparkles: two-operator FM, kept below ~16.5 kHz so nothing aliases
- drums: pitched-sine kick, filtered-noise hats, claps and snares, soft noise cymbals
- whooshes, risers and impacts: swept band-pass noise, sine sweeps and sine drops
- space: synthetic, frequency-dependent decaying impulse responses for convolution reverbs

numba, when installed, compiles the per-sample filters (a render takes about 7 s instead of about
a minute); the pure Python fallback gives the same file, byte for byte.
"""

from __future__ import annotations

import math
import zlib

import numpy as np
from scipy import signal

try:
  from numba import njit

  HAVE_NUMBA = True
except ImportError:  # pragma: no cover - slow but equivalent fallback
  HAVE_NUMBA = False

  def njit(*args, **kwargs):
    if args and callable(args[0]):
      return args[0]
    return lambda f: f


SR = 48000
"""Sample rate of everything (Hz)."""

Rng = np.random.Generator


# ------------------------------------------------------------------------------------------
# Small helpers
# ------------------------------------------------------------------------------------------


def rng_for(name: str, *ids: int) -> Rng:
  """A deterministic generator per element, independent of render order.

  Events seed with their name and their time in ms (`int(t * 1000)`), so moving an event in
  time changes its noise, but rendering the same timing twice gives the same file.
  """
  return np.random.default_rng([zlib.crc32(name.encode()), *[int(i) for i in ids]])


def mtof(m: float) -> float:
  """MIDI note number to Hz (A4 = 69 = 440 Hz)."""
  return 440.0 * 2.0 ** ((m - 69) / 12.0)


NOTE_NAMES = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B']


def note_name(m: int) -> str:
  """MIDI note number to a name such as F#5 (C4 = 60)."""
  return f'{NOTE_NAMES[m % 12]}{m // 12 - 1}'


def pan_gains(pan):
  """Equal-power pan, 0 dB in the centre; pan is -1 (left) to 1 (right), scalar or array."""
  th = (np.clip(pan, -1.0, 1.0) + 1.0) * math.pi / 4.0
  return np.cos(th) * math.sqrt(2.0), np.sin(th) * math.sqrt(2.0)


def env_ar(n: int, attack: float, hold: float, release: float) -> np.ndarray:
  """Raised-cosine attack to 1, hold until `hold` s (note-off), raised-cosine release to 0."""
  t = np.arange(n) / SR
  e = np.ones(n)
  if attack > 0:
    m = t < attack
    e[m] = 0.5 - 0.5 * np.cos(np.pi * t[m] / attack)
  m = t >= hold
  u = np.clip((t[m] - hold) / max(release, 1e-4), 0.0, 1.0)
  e[m] *= 0.5 + 0.5 * np.cos(np.pi * u)
  return e


def tail_fade(n: int, secs: float = 0.01) -> np.ndarray:
  """Raised-cosine fade over the last `secs`, so a cut-off sound never clicks."""
  f = np.ones(n)
  k = min(n, int(secs * SR))
  if k > 0:
    f[-k:] = 0.5 + 0.5 * np.cos(np.linspace(0, np.pi, k))
  return f


def db(x: float) -> float:
  return 20.0 * np.log10(max(float(x), 1e-12))


def rms(x: np.ndarray) -> float:
  return float(np.sqrt(np.mean(np.square(x, dtype=np.float64))))


# ------------------------------------------------------------------------------------------
# DSP kernels
# ------------------------------------------------------------------------------------------


@njit
def _svf(x, fc, q, mode, sr):
  # Topology-preserving state-variable filter (Simper); safe under fast cutoff modulation.
  n = x.shape[0]
  y = np.empty(n)
  ic1 = 0.0
  ic2 = 0.0
  k = 1.0 / q
  hi = 0.45 * sr
  for i in range(n):
    f = fc[i]
    if f > hi:
      f = hi
    elif f < 5.0:
      f = 5.0
    g = math.tan(math.pi * f / sr)
    a1 = 1.0 / (1.0 + g * (g + k))
    a2 = g * a1
    a3 = g * a2
    v0 = x[i]
    v3 = v0 - ic2
    v1 = a1 * ic1 + a2 * v3
    v2 = ic2 + a2 * ic1 + a3 * v3
    ic1 = 2.0 * v1 - ic1
    ic2 = 2.0 * v2 - ic2
    if mode == 0:
      y[i] = v2
    elif mode == 1:
      y[i] = k * v1
    else:
      y[i] = v0 - k * v1 - v2
  return y


def svf(x: np.ndarray, fc, q: float = 0.707, mode: str = 'lp') -> np.ndarray:
  """State-variable filter: mode 'lp', 'bp' or 'hp'; fc in Hz, scalar or one value per sample."""
  x = np.ascontiguousarray(x, dtype=np.float64)
  fc = np.ascontiguousarray(np.broadcast_to(np.asarray(fc, dtype=np.float64), x.shape))
  return _svf(x, fc, float(q), {'lp': 0, 'bp': 1, 'hp': 2}[mode], float(SR))


@njit
def _pingpong(xl, xr, d, fb, damp):
  n = xl.shape[0]
  yl = np.zeros(n)
  yr = np.zeros(n)
  bl = np.zeros(d)
  br = np.zeros(d)
  fl = 0.0
  fr = 0.0
  j = 0
  for i in range(n):
    ol = bl[j]
    orr = br[j]
    yl[i] = ol
    yr[i] = orr
    fl += damp * (orr - fl)
    fr += damp * (ol - fr)
    bl[j] = 0.5 * (xl[i] + xr[i]) + fb * fl
    br[j] = fb * fr
    j += 1
    if j >= d:
      j = 0
  return yl, yr


def pingpong(x: np.ndarray, secs: float, fb: float, damp_hz: float) -> np.ndarray:
  """Stereo ping-pong delay (wet only): `secs` per bounce, feedback `fb`, damped above damp_hz."""
  d = int(round(secs * SR))
  damp = 1.0 - math.exp(-2.0 * math.pi * damp_hz / SR)
  yl, yr = _pingpong(
    np.ascontiguousarray(x[0], dtype=np.float64),
    np.ascontiguousarray(x[1], dtype=np.float64),
    d,
    fb,
    damp,
  )
  return np.vstack([yl, yr])


@njit
def release_follower(g, coef):
  # Instant attack, one-pole release (gain may only fall at once, rises slowly).
  y = np.empty_like(g)
  s = 1.0
  for i in range(g.shape[0]):
    v = g[i]
    if v < s:
      s = v
    else:
      s += (v - s) * coef
    y[i] = s
  return y


def saw(freq, n: int, phase0: float) -> np.ndarray:
  """Band-limited (PolyBLEP) sawtooth; freq is a scalar or an array of length n."""
  dt = np.empty(n)
  dt[:] = np.asarray(freq, dtype=np.float64) / SR
  ph = np.cumsum(dt)
  ph -= dt
  ph += phase0
  ph %= 1.0
  y = 2.0 * ph - 1.0
  m = ph < dt
  t = ph[m] / dt[m]
  y[m] -= t + t - t * t - 1.0
  m = ph > 1.0 - dt
  t = (ph[m] - 1.0) / dt[m]
  y[m] -= t * t + t + t + 1.0
  return y


# ------------------------------------------------------------------------------------------
# Instruments. Each returns mono (n,) or stereo (2, n) samples starting at its note-on.
# ------------------------------------------------------------------------------------------


def pad_note(m: int, dur: float, attack: float, release: float, rng: Rng, detune: float = 9.0):
  """Stereo pad voice: three detuned saws per side plus a sine, `dur` s held, then released."""
  n = int((dur + release) * SR)
  f = mtof(m)
  t = np.arange(n) / SR
  out = np.zeros((2, n))
  offsets = ([-1.0, -0.2, 0.7], [-0.65, 0.3, 1.0])
  for ch in range(2):
    for o in offsets[ch]:
      drift = 2.5 * np.sin(2 * np.pi * rng.uniform(0.07, 0.23) * t + rng.uniform(0, 2 * np.pi))
      out[ch] += saw(f * 2.0 ** ((o * detune + drift) / 1200.0), n, rng.random())
  out /= 3.0
  out += 0.45 * np.sin(2 * np.pi * f * t + rng.uniform(0, 2 * np.pi))
  return out * env_ar(n, attack, dur, release)


def pluck(m: int, rng: Rng, bright: float = 1.0, decay: float = 0.15, length: float = 0.42):
  """Arp pluck: two detuned saws through a low-pass with a fast filter envelope."""
  n = int(length * SR)
  t = np.arange(n) / SR
  f = mtof(m)
  x = 0.5 * (
    saw(f * 2 ** (-6 / 1200), n, rng.random()) + saw(f * 2 ** (6 / 1200), n, rng.random())
  )
  x += 0.3 * np.sin(2 * np.pi * f * t)
  fc = 300.0 + 600.0 * bright + 4000.0 * bright * np.exp(-t / 0.05)
  y = svf(x, fc, 0.9, 'lp')
  a = (1.0 - np.exp(-t / 0.0012)) * np.exp(-t / decay) * tail_fade(n, 0.012)
  return y * a


def bass_pluck(m: int, rng: Rng, length: float = 0.24):
  """Short bass note: saws and a sine through a closing low-pass."""
  n = int(length * SR)
  t = np.arange(n) / SR
  f = mtof(m)
  x = 0.5 * (
    saw(f * 2 ** (-5 / 1200), n, rng.random()) + saw(f * 2 ** (5 / 1200), n, rng.random())
  )
  x += 0.6 * np.sin(2 * np.pi * f * t)
  fc = 180.0 + 1500.0 * np.exp(-t / 0.04)
  y = svf(x, fc, 1.0, 'lp')
  a = (1.0 - np.exp(-t / 0.002)) * np.exp(-t / 0.11) * tail_fade(n, 0.015)
  return y * a


def bell(m: int, ratio: float = 3.5, index: float = 2.4, decay: float = 0.8, length: float = 3.2):
  """Two-operator FM bell; the modulation index is capped so sidebands stay below ~16.5 kHz."""
  n = int(length * SR)
  t = np.arange(n) / SR
  f = mtof(m)
  fm = f * ratio
  imax = max(0.15, min(index, (16500.0 - f) / fm - 1.0))
  ind = imax * (0.25 + 0.75 * np.exp(-t / 0.22))
  y = np.sin(2 * np.pi * f * t + ind * np.sin(2 * np.pi * fm * t))
  if 2 * f < 15000:
    y += 0.18 * np.sin(2 * np.pi * 2 * f * t) * np.exp(-t / 0.35)
  a = (1.0 - np.exp(-t / 0.0015)) * np.exp(-t / decay) * tail_fade(n, 0.05)
  return y * a


def lead_note(m: int, dur: float, rng: Rng, release: float = 0.18):
  """Soft saw lead with delayed vibrato."""
  n = int((dur + release) * SR)
  t = np.arange(n) / SR
  f0 = mtof(m)
  depth = np.clip((t - 0.28) / 0.35, 0.0, 1.0) * 14.0  # cents, delayed vibrato
  f = f0 * 2.0 ** (depth * np.sin(2 * np.pi * 5.2 * t) / 1200.0)
  x = 0.45 * (saw(f * 1.0025, n, rng.random()) + saw(f * 0.9975, n, rng.random()))
  x += 0.55 * np.sin(2 * np.pi * np.cumsum(f) / SR)
  y = svf(x, 2300.0, 0.8, 'lp')
  e = env_ar(n, 0.025, dur, release)
  e *= 0.82 + 0.18 * np.exp(-t / 0.25)
  return y * e


def kick(vel: float, rng: Rng):
  """Pitched-sine kick with a little click, gently saturated."""
  n = int(0.5 * SR)
  t = np.arange(n) / SR
  f = 50.0 + 105.0 * np.exp(-t / 0.032) + 40.0 * np.exp(-t / 0.005)
  body = np.sin(2 * np.pi * np.cumsum(f) / SR)
  body *= (1.0 - np.exp(-t / 0.0007)) * np.exp(-t / 0.26)
  click = svf(rng.standard_normal(n), 2600.0, 0.7, 'lp') * np.exp(-t / 0.003) * 0.18
  y = np.tanh(1.7 * (body + click)) / math.tanh(1.7)
  return y * tail_fade(n, 0.04) * vel


def hat(rng: Rng, decay: float, length: float):
  """Hi-hat: noise band-limited to about 6.5-11 kHz."""
  n = int(length * SR)
  t = np.arange(n) / SR
  y = rng.standard_normal(n)
  y = svf(y, 6800.0, 0.75, 'hp')
  y = svf(y, 6400.0, 0.75, 'hp')
  y = svf(y, 11000.0, 0.7, 'lp')
  y = svf(y, 12500.0, 0.6, 'lp')
  return y * (1.0 - np.exp(-t / 0.0005)) * np.exp(-t / decay) * tail_fade(n, 0.01)


def clap(rng: Rng):
  """Clap: three quick noise bursts and a tail, with a little body."""
  n = int(0.7 * SR)
  t = np.arange(n) / SR
  noise = rng.standard_normal(n)
  y = svf(noise, 1350.0, 1.3, 'bp') + 0.35 * svf(noise, 3200.0, 1.0, 'bp')
  e = np.zeros(n)
  for k, off in enumerate([0.0, 0.009, 0.018]):
    u = t - off
    e += np.where(u >= 0, np.exp(-np.maximum(u, 0) / 0.0055), 0.0) * (0.8 + 0.1 * k)
  u = t - 0.027
  e += np.where(u >= 0, np.exp(-np.maximum(u, 0) / 0.12), 0.0)
  body = 0.35 * np.sin(2 * np.pi * 205.0 * t) * np.exp(-t / 0.045)
  y = y * e + body
  return svf(y, 280.0, 0.7, 'hp') * tail_fade(n, 0.02)


def snare(rng: Rng, pitch: float = 190.0, tail: float = 0.11):
  """Snare: band-passed noise over a pitched body (also used for the rolls)."""
  n = int(0.6 * SR)
  t = np.arange(n) / SR
  noise = rng.standard_normal(n)
  y = svf(noise, 2100.0, 0.7, 'bp') + 0.4 * svf(noise, 5200.0, 0.9, 'bp')
  y *= (1.0 - np.exp(-t / 0.0006)) * np.exp(-t / tail)
  f = pitch * (1.0 + 0.35 * np.exp(-t / 0.012))
  body = 0.6 * np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.05)
  return svf(y + body, 150.0, 0.7, 'hp') * tail_fade(n, 0.02)


def cymbal(rng: Rng, decay: float = 1.0, length: float = 3.2):
  """Soft stereo cymbal: two decorrelated noise channels, 4.2-11.5 kHz."""
  n = int(length * SR)
  t = np.arange(n) / SR
  out = []
  for _ in range(2):
    y = rng.standard_normal(n)
    y = svf(y, 4200.0, 0.7, 'hp')
    y = svf(y, 11500.0, 0.7, 'lp')
    out.append(y)
  e = (1.0 - np.exp(-t / 0.004)) * np.exp(-t / decay) * tail_fade(n, 0.1)
  return np.vstack(out) * e


def whoosh(rng: Rng, pre: float = 0.75, post: float = 0.55):
  """Filtered-noise sweep that peaks `pre` s after its start and pans left to right."""
  n = int((pre + post) * SR)
  t = np.arange(n) / SR
  noise = svf(rng.standard_normal(n), 6000.0, 0.7, 'lp')
  fc = np.where(
    t < pre,
    260.0 * (2600.0 / 260.0) ** (t / pre),
    2600.0 * (800.0 / 2600.0) ** ((t - pre) / post),
  )
  y = svf(noise, fc, 1.5, 'bp') + 0.3 * svf(noise, fc * 2.1, 2.2, 'bp')
  e = np.where(t < pre, (t / pre) ** 2.4, np.exp(-(t - pre) / 0.17)) * tail_fade(n, 0.05)
  y *= e
  pan = np.clip(-0.75 + 1.5 * t / (pre + post), -1.0, 1.0)
  gl, gr = pan_gains(pan)
  return np.vstack([y * gl, y * gr])


def riser(rng: Rng, length: float, top: float = 6500.0):
  """Stereo riser over `length` s: band-pass noise sweeping up to `top` Hz, plus D and A sines
  sweeping up an octave with a speeding tremolo."""
  n = int(length * SR)
  t = np.arange(n) / SR
  u = t / length
  out = []
  for _ in range(2):
    noise = rng.standard_normal(n)
    fc = 320.0 * (top / 320.0) ** u
    y = svf(noise, fc, 1.4, 'bp') + 0.25 * svf(noise, np.minimum(fc * 1.8, 15000.0), 1.0, 'bp')
    out.append(y)
  out = np.vstack(out)
  e = u**2.3
  trem = 0.75 + 0.25 * np.sin(2 * np.pi * np.cumsum(6.0 + 10.0 * u) / SR)
  tone = np.zeros(n)
  for m in (62, 69):
    f = mtof(m) * 2.0**u
    tone += np.sin(2 * np.pi * np.cumsum(f) / SR)
  tone *= 0.18 * u**2 * trem
  sig = out * e + tone
  return sig * tail_fade(n, 0.012)


def impact(rng: Rng, length: float = 2.6):
  """Soft impact: a sine drop with a short burst of low-passed noise."""
  n = int(length * SR)
  t = np.arange(n) / SR
  f = 45.0 + 65.0 * np.exp(-t / 0.11)
  boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * (1.0 - np.exp(-t / 0.002)) * np.exp(-t / 0.5)
  out = []
  for _ in range(2):
    nz = rng.standard_normal(n)
    nz = svf(nz, 300.0 + 2200.0 * np.exp(-t / 0.25), 0.7, 'lp') * np.exp(-t / 0.4) * 0.3
    out.append(boom + nz)
  return np.vstack(out) * tail_fade(n, 0.2)


def thump(length: float = 1.8):
  """Low thump landing on D2 (the intro's wordmark)."""
  n = int(length * SR)
  t = np.arange(n) / SR
  f = mtof(38) * (1.0 + 0.7 * np.exp(-t / 0.045))
  y = np.sin(2 * np.pi * np.cumsum(f) / SR) * (1.0 - np.exp(-t / 0.003)) * np.exp(-t / 0.38)
  y += 0.25 * np.sin(2 * np.pi * np.cumsum(f * 2) / SR) * np.exp(-t / 0.12)
  return y * tail_fade(n, 0.2)


def make_ir(rng: Rng, length: float, t60s: tuple[float, float, float], predelay: float):
  """Stereo impulse response: decorrelated noise, low/mid/high bands decaying at their T60s."""
  n = int(length * SR)
  t = np.arange(n) / SR
  lo_sos = signal.butter(2, 450, 'low', fs=SR, output='sos')
  mid_sos = signal.butter(2, [450, 3800], 'band', fs=SR, output='sos')
  hi_sos = signal.butter(2, 3800, 'high', fs=SR, output='sos')
  chans = []
  for _ in range(2):
    nz = rng.standard_normal(n)
    ir = np.zeros(n)
    for sos, t60 in zip((lo_sos, mid_sos, hi_sos), t60s):
      ir += signal.sosfilt(sos, nz) * 10.0 ** (-3.0 * t / t60)
    ir *= 1.0 - np.exp(-t / 0.015)
    ir = np.concatenate([np.zeros(int(predelay * SR)), ir])
    chans.append(ir)
  ir = np.vstack(chans)
  return ir / np.sqrt(np.sum(ir**2) / 2.0)
