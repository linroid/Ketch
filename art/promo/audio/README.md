# Promo music

The promo's music bed, `public/audio/music.wav`, is synthesized from scratch by the Python code
in this folder: no samples or third-party audio, so it is royalty-free and covered by the
repository's license. Its timing comes from `src/timing.json`, the same file the Remotion code
reads, so when the film is retimed the music is regenerated to match.

```sh
npm run music            # from art/promo; the same as audio/make-music.sh
```

The first run creates `art/promo/.venv` from `requirements.txt` (pinned numpy, scipy and numba;
about 300 MB, git-ignored) and takes half a minute; after that a render takes about 10 s (about a
minute without numba, with the same result). It
writes `public/audio/music.wav` (77 s, 48 kHz, 24-bit stereo, 22 MB) and its cue sheet
`public/audio/CUES.md`, and prints the duration, loudness (target -16 LUFS integrated), true
peak (limited to -1.4 dBTP) and each section's loudness. Both outputs are generated: never
edit them by hand. The same timing gives the same file byte for byte, so the WAV is not in git
(regenerate it after a fresh clone); the cue sheet is, as the record of what the music does.

Options, passed through to `make_music.py`:

| option | |
|---|---|
| `--timing PATH` | render another timing file, e.g. a trial cut (default `src/timing.json`) |
| `--out PATH`, `--cues PATH` | write somewhere else, e.g. to compare with the current file |
| `--no-cues` | skip the cue sheet |
| `--target-lufs N` | integrated loudness target (default -16) |
| `--stems DIR` | also write each instrument bus, dry and calibrated, for debugging |
| `--check` | only check the timing and the [picture ties](#how-the-music-follows-the-picture) against the scene code, and exit 1 on any problem; renders nothing and needs no venv (plain `python3`) |

Set `PYTHON` to pick the interpreter for the venv (default `python3`, 3.10 or later).

## Files

| file | what it holds |
|---|---|
| `timing.py` | reads and checks `src/timing.json`; frames to seconds |
| `score.py` | **the arrangement**: picture ties, named offsets, chords per section, melodies, drum patterns, effects and automation, all built from the timing |
| `synth.py` | the instruments and DSP (pads, plucks, FM bells, drums, whooshes, risers, reverb impulse responses); knows nothing about the film |
| `mixer.py` | plays the score into buses, calibrates and mixes them, masters, measures and writes the WAV |
| `cuesheet.py` | writes `CUES.md` from the score and the measurements |
| `make_music.py` | the command line; reads the picture ties back from the scene code and checks them |
| `make-music.sh` | sets up the venv and runs `make_music.py` (not needed for `--check`) |
| `requirements.txt` | pinned Python packages |

## How the music follows the picture

- **Tempo.** 120 BPM at 30 fps: one beat is `beat` = 15 frames, a bar 60. Every scene starts
  on a beat (`make_music.py` warns when one does not, because every later chord and drum hit
  would then fall off the picture's beat).
- **Sections.** The music's sections are the scenes, with S12 split where the end card lands
  (S12a, S12b). Each has a chord progression in beats (`SECTIONS` in `score.py`): `head` plays
  from the section's start, `tail` ends on its last beat (the cadence into the next scene) and
  `body` repeats to fill the rest. A chord that continues across a cut is held, not struck
  again. Lengthen a scene and its body plays on; shorten it and the body is cut first.
- **Events** are placed from scene starts and ends plus a number of beats, never at absolute
  times: the drop-outs before the S06 and S09 wipes (`DROP_GAP`), the one-beat gap before S12
  (`BUILD_GAP`), the risers (`RISER_BEATS`), S08's sparse first bars (`DISCOVER_SPARSE_BARS`),
  the S06 counter-melody and the S08 bell motif (a body from the scene's start that repeats
  every 16 beats, and a last bar placed from the scene's end).
- **Lane wipes.** Every cut in `wipes` gets a whoosh that peaks on the cut. `WHOOSH_STYLE` sets
  its rise time and level by the scene the wipe enters; a new wipe gets `WHOOSH_DEFAULT`.
- **Picture ties.** A few cues follow animation inside a scene rather than a cut, so the score
  copies those numbers from the scene code: the sail's segment refills in S01 (the film opens on
  the finished logo, then each segment drains to a dim floor and refills; one bell per segment,
  as its refill starts, at `SAIL_AT + trackLead + k * stagger`: frames 6, 9 ... 33, the last four the mizzen's, counted by
  their `true` argument), the thump on the held lock-up once the refill wave has crossed the sail
  (`WORD_AT + 6`), the S11 roll's switch to 32nds, where the language ticker lands its last name
  (`ROLL_32NDS_BEATS` beats before S12: local 75), and the end card (S12 `CHORD`, local 105). They
  sit together under "Picture ties" at the top of `score.py`, and `PICTURE_TIES` there records
  where each one lives in the scene code; the cue sheet lists them (`CUES.md`, "Picture ties").
  Every run reads them back from the scene code and prints a `WARNING` when one differs or cannot
  be found (renamed, moved, or no longer a plain number); `npm run music -- --check` does only
  that and exits 1, in a fraction of a second. Each scene-side constant carries a note naming
  `audio/score.py` and its `PICTURE_TIES` entry, so the tie is visible from both sides.
- **The opening.** The film's first frame is already the finished logo, so the intro pad starts
  on it, softly but at once: a short fade-in (`OPENING_PAD_ATTACK`) and a low-pass opening from
  `OPENING_PAD_LP` over the intro, never a long swell from silence.

## Changing things

**Retime the film.** Change `src/timing.json` (`npm run retime -- S05=285` moves every later
start, `total` and the wipes for you; see the promo README), then run `npm run music` and read
the `WARNING` lines and the new `CUES.md`. Everything moves with the cuts. Changes of a whole bar
(60 frames) keep the harmony's phrasing; other lengths repeat or cut a section's body, which is
worth a listen.

**Change an animation the music hits.** Change the number in the scene code and the matching
picture tie in `score.py` (the warning names both), then `npm run music`. If the scene stops
keeping the number as a plain constant (it becomes an expression, or moves to another file or
name), point its `PictureTie` in `PICTURE_TIES` at the new place, or the check cannot read it.

**Add a picture tie.** When a new cue has to land on an animation frame, give the frame a named
constant in the scene (with a note that the music follows it), copy it into the "Picture ties"
block of `score.py`, build the cue from it in `make_score()`, and add a `PictureTie` saying where
the scene keeps it. `npm run music -- --check` should then pass.

**Change the harmony or a melody.** `CHORDS` holds the voicings, `SECTIONS` the progressions,
`LEAD_*`, `BELL_MOTIF_*`, `SPARKLE_NOTES` and `FINAL_SPARKLE` the melodies (MIDI note numbers,
60 = C4), `ARP_PATTERN` the arpeggio. Times in those tables are in beats.

**Change what plays in a section.** The drum patterns, effects and automation are in
`make_score()` in `score.py`, each built from scene boundaries (`s06.start`, `drop1`, ...).
Change the sentence describing it in `cuesheet.py` (`section_note`) and `SECTIONS[...].plays`
too, so the cue sheet stays true.

**Change levels.** Velocities and levels in `score.py` are relative within an instrument.
`CALIBRATION` in `mixer.py` sets each instrument bus against a reference window (most buses by
their loudness in S06, the fullest section; whooshes by the first wipe's whoosh; other effects
by their loudest hit), then the master sets the whole film to `--target-lufs`. To hear one
instrument alone, use `--stems`.

**Add a scene.** Give it an entry in `SECTIONS`, then decide in `make_score()` which parts play
in it (they are placed per scene, so a new scene is silent apart from its pads until you do).

## Checking a render

- The printed report: 77.000 s (`total` / 30), about -16.0 LUFS, true peak -1.40 dBTP, the
  limiter reducing by well under 1 dB.
- `ffmpeg -i public/audio/music.wav -af ebur128=peak=true -f null -` should agree on the
  loudness, loudness range and true peak.
- `CUES.md` lists every cue with its time and frame; scrub the film in Remotion Studio
  (`npm run studio`) to the frames that matter.
- `--stems DIR` writes each bus, which makes timing easy to see in an audio editor.
