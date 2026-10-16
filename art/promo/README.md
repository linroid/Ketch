# Ketch promo film

A 77-second promotional film for Ketch (1920×1080, 30 fps, H.264 with an original music bed;
also as a 3840×2160, 60 fps master), built with [Remotion](https://www.remotion.dev/) 4 in
English and Simplified Chinese, in a dark version and a light one ([The light
film](#the-light-film)). Every screen of the app in it is the real app, rendered by the app's own
headless Compose snapshot harness; nothing is a mock-up.

- [STORYBOARD.md](STORYBOARD.md) is the creative spec: what each scene is for, the look, the
  motion and the do-not-claim rules. It points here for the words, numbers and frames.
- This file is the how-to: how the film is put together and how to change it.
- [docs/ASSETS.md](docs/ASSETS.md) covers the real-UI assets (the snapshot scenarios, the
  browser-extension popup, the manifests).
- [audio/README.md](audio/README.md) covers the music; [public/audio/CUES.md](public/audio/CUES.md)
  is its cue sheet (generated with it).

Run every command from this folder (`art/promo/`).

## From a fresh clone

Generated files (clips, stills, manifests, music, renders) are not in git; these steps make
all of them.

| needs | for |
|---|---|
| Node 22+ and npm | everything (`npm run assets` uses Node's built-in `WebSocket`) |
| the JDK the repository builds with | `npm run assets` (Gradle runs the snapshot harness) |
| Python 3.10+ | `npm run music` (it sets up `.venv` from `audio/requirements.txt` on first use) |
| ffmpeg (`ffprobe`) | `npm run film` prints the file's facts and scans it (`npm run check:film`) |
| macOS | Chinese text uses the system's PingFang SC (see [Fonts](#fonts)) |
| `gh` (GitHub CLI) | `npm run facts`, and the `facts` check of `npm run check` |

```sh
npm install                  # also writes empty src/manifest*.json so the project builds
npx remotion browser ensure  # headless Chrome for renders (npm run assets also makes sure of it)
npm run assets               # the app's screens into public/clips, public/stills + manifests
                             #   (about 50 min for both locales, ~4 GB of frames)
npm run music                # renders public/audio/music.wav from src/timing.json
npm run film:4k60            # the deliverable: out/ketch-promo-<locale>-4k60.mp4 (4K, 60 fps)
npm run film:all             # (optional) out/ketch-promo-<locale>.mp4, 1080p30, for quick checks
npm run check                # the checks that define "done" (see Check the film)

# the light film: its own assets, then the same render with --theme light
npm run assets -- all --theme light   # another ~50 min and ~4 GB
npm run film:4k60 -- --theme light    # out/ketch-promo-<locale>-light-4k60.mp4
```

The 4K60 films (dark and light, both locales) are the deliverables; the 1080p30 compositions
(`Promo`, `Promo-light`, …) keep working for previews, stills and quick renders, but nothing
requires their files: `npm run check` scans whichever films are in `out/`.

## How it fits together

```
 the app's UI                                    words, numbers, timing
 ─────────────                                   ──────────────────────
 PromoSnapshots.kt (Compose harness)             src/scenes/copy/<SceneId>.ts   per locale
 tools/extension-popup                           src/facts.json                 npm run facts
        │                                        src/timing.json                scene lengths
        │ npm run assets                                │          │
        ▼                                               │          │ npm run music
 public/clips/[<locale>/]<name>/0000.png …              │          ▼
 public/stills/[<locale>/]<name>.png                    │   public/audio/music.wav
 src/manifest[.<locale>].json                           │          │
   (size, density, frames, fps, UI element bounds)      │          │
        │                                               ▼          │
        └──────────▶  Remotion: src/scenes/S01…S12  ◀───┘          │
                      + src/components, theme.ts, locale.tsx       │
                                     │                             │
                                     ▼                             │
                      Promo, Promo-zh-Hans  ◀──────────────────────┘
                      (Promo-60, Promo-60-zh-Hans: the same at 60 fps;
                       Promo-light, …: the light film)
                                     │
                                     │ npm run film / film:all (film:4k60)
                                     ▼
                      out/ketch-promo-<locale>.mp4 (-4k60.mp4), with its first frame
                      (Thumbnail) as cover art; beside it -thumbnail.png and -cover.jpg
```

Data is kept apart from presentation: scenes read their words from the copy files, numbers from
`facts.json`, scene starts and lengths from `timing.json`, and asset sizes and UI positions from
the generated manifests. A scene file holds only how the scene looks and moves.

Time is counted in **design frames**: frames at `timing.json`'s 30 fps, the unit of every scene
length, beat, cut and scene constant. The film renders at 30 fps (`Promo`) or at 60 (`Promo-60`)
with the same timing: scenes read the time with `useDesignFrame()` (`src/time.tsx`), which is
the composition's frame converted to design frames (in half steps at 60 fps), and `Promo` places
the scenes with `DesignSequence`, a `<Sequence>` in design frames. At 30 fps both are exactly
`useCurrentFrame()` and `<Sequence>`, so the 1080p30 film is the same frames byte for byte.
Clips have a rate of their own (the manifest's `fps`, 30 by default): `ClipPlayer` shows the
clip's frame for the design time, so a 30 fps clip holds each frame for two frames of the 60 fps
film and a 60 fps clip advances on every one.

## Commands

| command | does |
|---|---|
| `npm run studio` | live preview of every composition |
| `npm run typecheck` | `tsc --noEmit` |
| `npm run format` / `npm run format:check` | Prettier over the code (`.prettierrc.json`: 100 columns, the repository's style) |
| `npm run check` (`-- types music …`) | every check that defines "done", or the ones named ([Check the film](#check-the-film)) |
| `npm run check:film` (`-- zh-Hans`, `-- en-4k60`, `-- out/x.mp4`) | scans rendered films for near-empty frames |
| `npm run stills -- out Promo:450,800 S08:0,150` | PNG frames, one bundle and one browser for all of them |
| `npm run stills -- --locale zh-Hans out Promo:450` | the same in another locale (`--locale all` for every one) |
| `npm run stills -- --scale 2 out Promo-60:900` | at 3840 × 2160, as the 4K60 film (Promo-60's frame 900 is Promo's 450) |
| `npm run stills -- --theme light out Promo:450` | the light film (`--theme all` for both) |
| `npm run film` / `npm run film:all` | the finished film, English / every locale, to `out/ketch-promo-<locale>.mp4`, with its cover art ([Thumbnails and cover art](#thumbnails-and-cover-art)) |
| `npm run film:all -- --master` | the same from PNG frames instead of JPEG 95: the best quality, about twice as long ([Render the final films](#render-the-final-films)) |
| `npm run film:4k60` (`scripts/render-film.sh --4k60 [--master] <locale>`) | the 60 fps film at 3840 × 2160, every locale (or one), to `out/ketch-promo-<locale>-4k60.mp4` with its 4K cover art ([The 4K60 film](#the-4k60-film)) |
| `npm run film:all -- --theme light` (`film`, `film:4k60` likewise) | the light film, to `out/ketch-promo-<locale>-light[-4k60].mp4` with its own cover art ([The light film](#the-light-film)) |
| `scripts/embed-cover.sh out/ketch-promo-en.mp4 out/ketch-promo-en-thumbnail.png` | puts a thumbnail into a rendered film as its cover art, rendering nothing (replaces any cover it has) |
| `npm run facts` (`-- v0.2.0`, `-- --dry-run`) | refreshes `src/facts.json` from a GitHub release |
| `npm run retime -- S05=285` | changes a scene's length and moves everything after it |
| `npm run assets` | re-records the dark film's real-UI assets, every locale: about 50 min ([docs/ASSETS.md](docs/ASSETS.md)) |
| `npm run assets -- all --theme light` | the light film's (`--theme all` for both films) |
| `npm run assets -- zh-Hans discover popup` | only some: a locale (or `all`), then `PromoSnapshots` tests and/or `popup` (the extension popup, seconds) |
| `node scripts/assets/sync.mjs en [--theme light]` | re-links a locale's rendered assets into `public/` and rewrites its manifest, rendering nothing (after a Gradle run of your own) |
| `npm run music` | regenerates the music bed from `src/timing.json` |
| `npm run music -- --check` | checks only the music's picture ties against the scene code (the `ties` check) |

Stills are named `<out>/<compositionId>-<frame>.png`; frames are global for `Promo` and local
for a scene. Look at them before rendering the film, and delete them afterwards: disk space is
precious, and the film is never rendered as a PNG sequence.

## Compositions

Every composition exists once per locale and theme: English keeps the bare id, other locales add
a suffix, and the light film adds `-light` after it (`Promo-zh-Hans-light`).

| id | frames | what |
|---|---|---|
| `Promo`, `Promo-zh-Hans` | `total` in `timing.json` (2310) | the film at 30 fps: every scene in a `<Sequence>`, lane wipes on the cuts in `timing.json`, music |
| `Promo-60`, `Promo-60-zh-Hans` | twice `total` (4620) | the same film at 60 fps, the same timing; rendered at 4K with `--scale=2` |
| `Thumbnail`, `Thumbnail-zh-Hans` | 1 | the film's first frame (S01 frozen at frame 0, on Promo's root and canvas): the thumbnail and cover art |
| `S01` … `S12`, `S01-zh-Hans` … | scene length | one scene alone, frames local to the scene (Studio folders "Scenes", "Scenes-zh-Hans") |
| `Kit`, `Kit-zh-Hans` | 150 | demo of the shared components; the zh-Hans one shows the CJK typography |
| `Promo-light`, `Promo-60-light`, `Thumbnail-light`, `S01-light` …, `Kit-light` (and `-zh-Hans-light`) | as above | the light film ([The light film](#the-light-film)); Studio folders "Scenes-light", "Scenes-zh-Hans-light" |
| `Cover-{wide,tall}-<locale>[-light]` (entry `src/cover/index.tsx`) | 1 | the upload covers (16:9 and 3:4), apart from the film's root ([Thumbnails and cover art](#thumbnails-and-cover-art)) |

The locale is a default prop (`{"locale": "zh-Hans"}`), so `--props` can switch it too, and so
are the theme (`{"theme": "light"}`) and the frame rate of every composition but the thumbnail
(`{"fps": 60}`, a multiple of 30;
`rateMetadata` in `src/time.tsx` sets the length from it): `npx remotion still … S03
--props='{"fps":60}'` renders a scene at 60 fps. Frames are then output frames, twice the design
frames.

## Recipes

### Single frames

Look at the frames a change touches before rendering the film, in every locale, with your own
eyes: `npm run stills -- --locale all out S04:40,149 Promo:1080` writes
`out/<compositionId>-<frame>.png` (local frames for a scene, global for `Promo`). Check the
first and last frames of a scene, the frames around a cut, and the moment the change is about.
For the 4K60 film, `npm run stills -- --scale 2 out Promo-60:901` (double the frame; an odd one
shows a moment between two of the 30 fps film's).
The timing header at the top of each scene file says when its copy is on screen; many scenes
clear their copy before the last frame, so a copy change needs a frame from the middle. Delete
the PNGs when done.

### Change some copy

1. Edit `src/scenes/copy/<SceneId>.ts`. Each file holds the scene's words for every locale side
   by side; English defines the shape and the compiler insists every locale has the same keys.
2. In the copy: `\n` breaks a headline's line, `` `backticks` `` set inline code in a sub, and
   numbers come from `FACTS` (`` `Up to ${FACTS.maxConnections} connections` ``), never as
   literals.
3. Chinese is written for its viewer, not translated: spoken, natural Chinese (口语化), how a
   person would say it. Short, everyday words; no translationese such as 自成一体, 为…而生 or
   说你的语言. CJK punctuation (，。：、), and a space between Chinese and Latin words or
   numbers (`支持 10 种语言`, `这台 Mac`). Name what the app shows by the app's own Chinese
   words, so the copy matches the screens beside it:

   | English | zh-Hans | | English | zh-Hans |
   |---|---|---|---|---|
   | Discover | 搜索 | | Urgent (priority) | 紧急 |
   | Slow lane | 低速模式 | | All devices | 所有设备 |
   | Full speed | 全速 | | This Mac | 这台 Mac |
   | Auto (speed mode) | 自动 | | accent (colour) | 强调色 |
   | command palette | 命令面板 | | torrent | 种子 |
   | Signal, Harbor, Fathom, Beacon | 信号、港湾、深海、灯塔 | | magnet link | 磁力链接 |

   Every other term is in the repository's
   [translation glossary](../../docs/development/translation-glossary.md); the app's strings
   (`app/shared/src/commonMain/composeResources/values-zh/`) settle any doubt. Product, protocol
   and file names stay as they are.
4. Check the claims against STORYBOARD.md's "Do not claim" list.
5. Look at it in both locales, on frames where the copy is up (the scene file's timing header):
   `npm run stills -- --locale all out S04:40,120` (S04's copy has left by frame 148). Longer
   copy may need a scene's width or position changed; see [constants](#where-constants-live).

### Update numbers after a release

```sh
npm run facts                 # latest release; or: npm run facts -- v0.2.0
```

Wait until the release's workflow has uploaded every file: the script refuses a release with no
files, or with none of one kind (extension, Android, CLI, desktop), but cannot tell a release
whose upload is still running, which would give a size range too narrow. It reads the release
with `gh`, rewrites `release` and `sizes` in `src/facts.json` (binary units, as GitHub shows
them), prints the diff (running it again changes nothing), and then checks the hand-kept facts
(`maxConnections`, `mcpTools`, `languages`, `tests`) against the repository; edit any that say
DIFFERS by hand.

When `release` changed, also run `npm run assets`: the app's screens (the phones' Devices
cards) and the extension popup show the release, read from `src/facts.json` as they render
([docs/ASSETS.md](docs/ASSETS.md)). Then look at S10 (the size cards), S03, S06 (its footnote
names the release), S09, S11 and S12 in both locales, since longer numbers take more room, and
render the films.

### The app's UI changed: re-record the assets

1. Adjust the scenarios in
   `app/shared/src/jvmTest/kotlin/com/linroid/ketch/app/snapshot/PromoSnapshots.kt` if the
   screens themselves should change ([docs/ASSETS.md](docs/ASSETS.md) explains each asset).
2. `npm run assets` re-renders every clip and still, for every locale, and regenerates the
   manifests, including the UI element bounds scenes aim at.
3. `grep -rn "LAYOUT:" src` lists the constants measured on the old UI; check each one (see
   [layout-dependent constants](#layout-dependent-constants)) and look at stills of every scene
   that uses a changed asset.

### Change a scene's length

1. `npm run retime -- S05=285` sets the length (a whole number of beats, 15 frames) and moves the
   later scenes, the total and the lane wipes in `src/timing.json`. Editing the file by hand works
   too: `src/timing.ts` refuses to load a timing that does not add up and says what is wrong.
2. Check the scene's own timings, the named constants at the top of its file (entrances, exits,
   camera keys). Anything that leaves on a soft cut starts leaving at `softCutExitAt(len)`. The
   global frames that scene headers and comments quote ("global 1845–1965") are as of writing;
   the code reads `timing.json`, so they need no change for the film to follow.
3. Clips that a scene plays for as long as it lasts must be at least that long, or they freeze on
   their last frame: `desktop-dark` covers S02 + S03 (`DESKTOP_FRAMES` in `PromoSnapshots.kt`)
   and `discover-dark` covers S08 (`DISCOVER_FRAMES`), and their light twins do the same in the
   light film. If S02, S03 or S08 grows, raise those and re-render the clip in both films (`npm
   run assets -- all --theme all desktop`, `… discover`); S02's other-theme clip and accent
   stills (`desktopOtherTheme`) and S08's events are timed there too (its `SCENE` constants).
   Every render and the Studio's console warn "Clip too short" until then (`CLIP_NEEDS` in
   `src/assets.ts`).
   S06 plays the phone clips (`PHONE_FRAMES`) only in its first beat. Lengths are compared in
   time: a 60 fps clip needs twice the frames (`clipDuration` in `src/assets.ts`).
4. `npm run music`: the score is built from `timing.json`, so cues move with the cuts. The film's
   render refuses music made for another timing (`scripts/check-music.mjs`). Read its `WARNING`
   lines: a picture tie it cannot match means a cue no longer lands on its animation. To undo a
   trial, retime back and run `npm run music` again: the same timing gives the same `music.wav`
   byte for byte.
5. Look at the cuts (`npm run stills -- out Promo:<cut-2>,<cut>,<cut+2>`), render, and let the
   scan check them (`npm run film` runs `npm run check:film`).

### Add a language

1. `src/locale.tsx`: add the locale to `LOCALES` and `LOCALE_INFO` (BCP 47 tag, its own name,
   whether it is CJK). Root registers `Promo-<locale>` and the scene compositions by itself.
2. `npm run typecheck` now lists every copy file without the new locale; add its words there
   (and in `src/Kit.tsx`'s demo copy).
3. Assets: add the locale to `scripts/assets/locales.json` (the app's and the extension's
   locale tags) and to the harness's locales in `PromoSnapshots.kt`, then render it
   (`npm run assets`, see [docs/ASSETS.md](docs/ASSETS.md)), which writes
   `public/clips/<locale>/…`, `public/stills/<locale>/…` and `src/manifest.<locale>.json`.
   Import that manifest in `src/assets.ts` (`MANIFESTS`); `npm install` creates an empty one
   until it is rendered. An asset the locale does not have falls back to the English one.
4. Fonts: Inter covers Latin, Greek and Cyrillic. For CJK, see [Fonts](#fonts); Japanese and
   Korean need their face first in the stack.
5. The terminal scenes stay English (the CLI is not localized).
6. `npm run stills -- --locale <locale> out Promo:…` for a look, then `npm run film:all`.

### Render the final films

The films to publish are the 4K60 ones ([The 4K60 film](#the-4k60-film), and `--theme light`
for [the light film](#the-light-film)); the 1080p30 renders below are for checking a change
quickly, and optional.

```sh
npm run film:all              # every locale; npm run film for English only
scripts/render-film.sh zh-Hans
npm run film:all -- --master  # the master: PNG frames (scripts/render-film.sh --master all)
```

The script first checks the music (`scripts/check-music.mjs`: with `MUSIC` on, `music.wav` must
be what `npm run music` makes from the current timing and score), then bundles once (with
`public/` symlinked, not copied), and for each locale:

1. renders `Promo` / `Promo-<locale>` with the settings in `remotion.config.ts` (JPEG 95 frames,
   ANGLE GPU, H.264 CRF 18, yuv420p bt709, AAC music) to `out/ketch-promo-<locale>.mp4`;
2. renders `Thumbnail` / `Thumbnail-<locale>` to `out/ketch-promo-<locale>-thumbnail.png`;
3. embeds it as the mp4's cover art (`scripts/embed-cover.sh`, which also writes
   `out/ketch-promo-<locale>-cover.jpg`; see [Thumbnails and cover art](#thumbnails-and-cover-art)).

Then it prints the files' codec, size, frame count, colour tags, audio and cover, and scans each
film for near-empty frames (`npm run check:film`), exiting 1 when one breaks the rule. Running it
again replaces all three files: the same thumbnail and cover byte for byte, and a film with one
cover, never two. A render takes about 1½ minutes per locale on an M4 Pro (both locales in
about 3 minutes, with the 4K-density assets), longer on slower machines.

`--master` renders the frames as PNG instead of JPEG 95 (Remotion's `--image-format=png`; the
frames stream into the encoder, nothing is written as an image sequence) for the best quality:
no JPEG step between the page and H.264, which keeps fine UI text and the canvas's gradients a
little cleaner. It takes about twice as long (on an M4 Pro, 2:16 for English alone against
2:05 for both locales from JPEG, measured with the earlier 2 px per dp assets). Use it for the
files you publish; the default is enough for checking a change.

Before you call it done:

1. `npm run check`: types, formatting, facts, music, picture ties and the scan, all green.
2. Watch both films through once, with sound.
3. Delete the stills and trial renders you made along the way.

### The 4K60 film

```sh
npm run film:4k60                         # every locale; scripts/render-film.sh --4k60 en for one
npm run film:4k60 -- --theme light        # the light film, every locale
scripts/render-film.sh --4k60 --master all
```

`--4k60` renders `Promo-60` / `Promo-60-<locale>` (60 fps, the same timing as the 30 fps film)
at 3840 × 2160 (`--scale=2`) to `out/ketch-promo-<locale>-4k60.mp4`, with the thumbnail and the
cover at 4K (`-4k60-thumbnail.png`, `-4k60-cover.jpg`), and scans it like the others. The H.264
is yuv420p bt709 at CRF 18 as always, High profile at level 5.2 (`remotion.config.ts` adds both
to the encoder above 1080p), with the same AAC music; about 110 MB for the 77 s. On an M4 Pro it
takes about 5 minutes per locale (about 65 ms a frame; both locales in 10 minutes), and longer
with `--master`, whose PNG frames cost more at 4K (about 14 minutes per locale, measured before
the assets were recorded denser).

Motion is twice as smooth: every frame moves, by half as much as a 30 fps frame, the app's
clips included, which are recorded at 60 fps. The app's screens are as sharp as the film shows
them: each clip and still is recorded at the density (px per dp) that gives at least 1 source
px per output px at 4K where a scene shows it largest (4.25 px per dp for S03's lane zoom, 4 for
S08's Discover, 2.5–3.75 for S05's tiles; [docs/ASSETS.md](docs/ASSETS.md), "The assets").

### Thumbnails and cover art

The film opens on its logo, so its first frame is the lock-up (sail, wordmark, tagline), and that
frame is also each file's cover art: the image Finder, Quick Look, players and upload sites show
for the mp4 before it plays.

- `Thumbnail` / `Thumbnail-<locale>` (`src/Thumbnail.tsx`) renders the film's first scene
  (`SCENES[0]`, S01) frozen at frame 0 on the same root and canvas colour as `Promo`, so the
  thumbnail is the film's first frame by construction, in every locale.
- `npm run film` renders it to `out/ketch-promo-<locale>-thumbnail.png` (1920 × 1080) and runs
  `scripts/embed-cover.sh`, which makes `out/ketch-promo-<locale>-cover.jpg` from it (the
  film's size, JPEG quality 1, about 300 KB, for sites that want a JPEG under 2 MB) and stores
  that in the mp4 as an attached picture. The film's streams are copied, not re-encoded; a cover
  from an earlier run is replaced, so running it twice gives the same file.
- To change the cover, change S01's frame 0 (`src/scenes/S01Intro.tsx`: everything on it must be
  complete and still), then render the films. To put the cover on a film again without
  rendering it: `scripts/embed-cover.sh out/ketch-promo-en.mp4 out/ketch-promo-en-thumbnail.png`.
- To look at it: `npm run stills -- --locale all out Thumbnail:0 Promo:0` (the two are the same
  image), and after a render

  ```sh
  ffprobe -v error -show_entries stream=index,codec_name,nb_frames:stream_disposition=attached_pic \
    -of compact=p=0 out/ketch-promo-en.mp4      # h264 (2310 frames; 4620 in -4k60), aac, mjpeg attached_pic=1
  qlmanage -t -s 640 -o /tmp out/ketch-promo-en.mp4   # macOS: the Quick Look thumbnail, as a PNG
  ```
- The light film has its own (`Thumbnail-light`, `Thumbnail-<locale>-light`): its first frame,
  the lock-up on the light canvas, which `--theme light` renders and embeds the same way
  (`out/ketch-promo-<locale>-light-thumbnail.png`, `-light-cover.jpg`).
- The upload covers for video sites (16:9 and 3:4, a headline beside the real app) are another
  entry point, `src/cover/index.tsx`, one composition per format, locale and theme
  (`Cover-wide-en`, `Cover-tall-zh-Hans`, `Cover-wide-en-light` …). Render them at 2×:

  ```sh
  for c in Cover-{wide,tall}-{en,zh-Hans}{,-light}; do
    npx remotion still src/cover/index.tsx "$c" "out/covers/$c.png" --scale=2
  done
  ```

### The light film

The whole film also exists in a light version: the same content, timing, copy and music, with
every screen of the app recorded in the app's light theme and the film's own canvas and graphics
in the light palette (STORYBOARD.md, "Look"). One switch, the film's theme, drives both:

- **Compositions:** each one also exists with a `-light` suffix after its locale's
  (`Promo-light`, `Promo-zh-Hans-light`, `Promo-60-light`, `Thumbnail-light`, `S05-light` …),
  the same component with the `theme` prop (`{"theme": "light"}`); the dark film's ids and props
  are unchanged.
- **Scenes and components** read the theme from `LocaleRoot` (`src/theme-context.tsx`):
  `usePalette()` for the palette's colours, `useThemed({dark, light})` for a value that differs
  by more than a token (a shadow's depth, a glow), and `useAssets().themed('desktop')` for the
  asset of the app in the film's theme (`desktop-dark`, `desktop-light`). Components default to
  the film's theme; the terminal (and S09's config card) is a dark window in both films. Never
  write `DARK.…` or an asset's `-dark` name in a scene: it would stay dark in the light film.
- **Assets:** the light film's are recorded by their own run, `npm run assets -- <locale>
  --theme light`, into folders and manifests of their own ([docs/ASSETS.md](docs/ASSETS.md),
  "Themes"). S02's beat in the other theme shows the dark film's own `desktop-dark` clip and dark
  accent stills that the light run records.
- **Renders:** `--theme light` on `scripts/render-film.sh` (`npm run film:4k60 -- --theme
  light`, the deliverable; `npm run film:all -- --theme light` for 1080p30) writes
  `out/ketch-promo-<locale>-light-4k60.mp4` (`-light.mp4`) with its thumbnail and cover; `npm run stills -- --theme light …` and
  `--theme all` look at it. `npm run check:film` scans the light films too.
- **A change to a scene** shows in both films: look at stills of it in both (`npm run stills --
  --locale all --theme all out S05:100`). A change to the app's screens needs both films' assets
  re-recorded (`npm run assets -- all --theme all`).

### Check the film

`npm run check` runs every check below and prints a summary; name some to run only those
(`npm run check -- types music`). Each is also a script of its own.

| check | runs | fails when |
|---|---|---|
| `types` | `tsc --noEmit` | the code does not type-check (also: a copy file misses a locale's key) |
| `format` | `prettier --check .` | code is not formatted; `npm run format` fixes it |
| `facts` | `scripts/update-facts.mjs --check` (needs `gh`) | `src/facts.json` differs from the release it names, or a hand-kept fact from the repository (a newer release is only noted) |
| `music` | `scripts/check-music.mjs` | `music.wav` is missing, not the film's length, or not byte for byte what `npm run music` makes now (it renders the music again into a temporary folder, about 10 s; `--quick` checks only the length); `CUES.md` likewise |
| `ties` | `audio/make-music.sh --check` | a picture tie in `audio/score.py` differs from the scene code or cannot be found there, so a cue no longer lands on its animation (the `WARNING` lines of `npm run music`; [audio/README.md](audio/README.md)) |
| `films` | `scripts/check-film.mjs` | a rendered film (`out/ketch-promo-*.mp4`, the 4K60 and light ones too) has more than 3 near-empty design frames in a row outside a lane wipe (cut ± 9, from `src/timing.json`) and the final fade (20 frames); the film opens on the lock-up, so its first frames get no allowance (`OPENING` 0). A 60 fps film is counted in design frames (two frames each) |

The film scan scales each frame to 480 × 270, runs ffmpeg's edge detection and counts a frame as
near-empty when its edges average under 0.6: text, UI and the sail have edges, the bare canvas
has none, dark or light (edges measure change, not brightness). It prints the frames, the scene and its local frames of every run it rejects, e.g.
`frames 1080–1083 (S06 local 120–123): 4 near-empty`: look at stills there.

## Where constants live

| kind | where |
|---|---|
| fps, size, beat, scene starts and lengths, lane-wipe cuts | `src/timing.json` (typed and checked by `src/timing.ts`); its fps is the design rate |
| the output rates (30, 60), design time (`useDesignFrame`, `DesignSequence`) | `src/time.tsx` |
| a clip's frame rate | the manifests (`fps`, 30 when absent), read through `src/assets.ts` |
| numbers about Ketch: release, download sizes, connections, tools, languages, tests | `src/facts.json` (`src/facts.ts`) |
| every word on screen | `src/scenes/copy/<SceneId>.ts` |
| the locales | `src/locale.tsx` |
| colours, fonts, type scale, safe area, window shadows, CJK typography | `src/theme.ts`; font files `src/fonts.ts` + `public/fonts/` |
| the film's theme (dark, light), how scenes read it (`usePalette`, `useThemed`) | `src/theme-context.tsx` |
| easing curves and springs | `src/easing.ts`; the text motion in `src/components/Text.tsx` |
| when things happen inside a scene | named constants at the top of the scene file (local frames) |
| asset sizes, frame counts, UI element bounds | the generated manifests, read through `src/assets.ts`; never hard-coded |
| rects measured on the real UI | scene files, tagged `LAYOUT:`; better, `assetBounds` |
| render settings (and the 4K H.264 profile and level) | `remotion.config.ts` |
| the music on/off switch | `src/config.ts` (`MUSIC`) |
| clips the scenes play end to end, and how long they must be | `src/assets.ts` (`CLIP_NEEDS`); the clips' lengths in `PromoSnapshots.kt` |
| sizes of the shared components, for scenes that lay them out | `chipHeight`, `chipPadding` (`Chip.tsx`), `TEXT_BLOCK_METRICS`, `headlineLineHeight` (`Text.tsx`), `terminalHeight`, `TERMINAL_METRICS` (`TerminalWindow.tsx`) |
| the near-empty scan's allowances | `scripts/check-film.mjs` |
| the music itself | `audio/` (its cue sheet is `public/audio/CUES.md`) |

### Layout-dependent constants

Two kinds of numbers break silently when something else changes:

- **Measured on the app's UI**: crops, camera targets and landmarks in an asset (S04's sheet,
  S05's tile shots, S07's node on the All devices row, S08's landmarks, S03's lanes, the phone
  margins in `parts/c-parts.tsx`, at the time of writing). They are wrong once the app's layout
  changes, and may be wrong in another locale's assets, where text lengths move things. The
  light film's assets have the dark ones' layout, so one measure serves both.
- **Measured on the copy**: positions or widths fitted to how long the English words are (S01's
  wordmark width, a sub's width chosen so it wraps well). Another locale or new copy can need
  other values; a copy file can carry them per locale.

Mark each with a comment that starts with `LAYOUT:` and says what it was measured on, so
`grep -rn "LAYOUT:" src` finds them all. Better still, have the asset pipeline measure the
element and read it from the manifest:

```ts
const {assetBoundsPx} = useAssets();
// where the inspector is on this locale's desktop clip, in source px (fallback in fractions)
const target = assetBoundsPx('desktop-dark', 'inspector', {x: 0.58, y: 0.36, w: 0.42, h: 0.42});
```

`assetBounds(name, key, fallback)` returns fractions of the asset (0..1), `assetBoundsPx` source
pixels (the units of `Camera` keys and px crops), and `assetBoundsRef` reference px: px at 2 px
per dp, the unit of every constant measured by hand on an asset (crops, insets, radii, fades,
camera scales). Assets have densities of their own (the manifest's `density`), so never mix a
literal with source px: work in reference px and convert with `assetRefScale(name)` (source px
per reference px) or `assetRefSize(name)`. The fallback is used until the manifest has
those bounds. In the same way `clipEvent(name, key, fallback)` gives the clip frame of a named
moment the pipeline recorded (when the Discover results appear, say), so a scene times its
camera and copy against the clip instead of frame numbers read off a render.

## Writing a scene

```ts
// src/scenes/copy/S03.ts (abridged)
import {FACTS} from '../../facts';
import {defineCopy} from '../../locale';

export interface S03Strings {
  eyebrow: string;
  headline: string;
  sub: string;
  /** Two chips, one per beat, stacked under the sub. */
  chips: readonly [string, string];
}

export const S03_COPY = defineCopy<S03Strings>({
  en: {
    eyebrow: 'Parallel connections',
    headline: 'One file.\nMany lanes.',
    sub:
      `Up to ${FACTS.maxConnections} connections per download, ` +
      'each one a live lane you can watch.',
    chips: ['Change connections mid-download', 'Resumes after a restart'],
  },
  'zh-Hans': {
    eyebrow: '多连接下载',
    headline: '一个文件，\n多条线一起下。',
    sub: `单个下载最多 ${FACTS.maxConnections} 个连接，\n` + '每条线跑多快，一眼就能看到。',
    chips: ['下载中途也能加减连接', '重启之后接着下'],
  },
});
```

```tsx
// src/scenes/S03Lanes.tsx
export const S03Lanes: React.FC = () => {
  const copy = useCopy(S03_COPY); // this locale's words
  const cjk = isCJK(useLocale()); // for the rare layout that differs by script
  const {assetSize, assetBoundsPx} = useAssets(); // this locale's assets, English as fallback
  // …
  return <TextBlock eyebrow={copy.eyebrow} headline={copy.headline} sub={copy.sub} />;
};
```

- Read the time with `useDesignFrame()` (`src/time.tsx`), never `useCurrentFrame()`, and place
  a nested timeline with `DesignSequence`, never `<Sequence>`: everything is timed in design
  frames, which are fractional in the 60 fps film. A clip's time goes to `ClipPlayer` in design
  frames too; `clipEvent` and `clipDuration` give design frames, whatever the clip's rate.
- Copy files use `defineCopy({en: {...}, 'zh-Hans': {...}})` (`src/locale.tsx`); pass a type
  (`defineCopy<S10Strings>(…)`) to document the fields.
- `ClipPlayer` and `StillImage` pick the locale's asset by themselves. Helpers called outside a
  component take the locale as their last argument (`assetSize(name, locale)`), or use
  `assetsFor(locale)`.
- A scene that lays out the shared components itself (a column under a row of chips, a card
  around a terminal) sizes them with the components' exported metrics (`chipHeight(size)`,
  `TEXT_BLOCK_METRICS`, `headlineLineHeight(text)`, `terminalHeight(lines, scale)`), never a
  copy of their numbers, so a change to a component moves the layouts with it.
- Format with `npm run format` (Prettier, 100 columns, as the repository's `.editorconfig`).
- `size('android')` parses a size from `facts.json` and `formatSize(s, t)` prints it scaled by
  `t` for a count-up, keeping its decimals and range:
  `formatSize(size('cli'), countValue(frame, {to: 1, start: 4, duration: 36}))` counts up to
  "33–39 MB".
- The Text components (`TextBlock`, `Headline`, `Sub`, `Eyebrow`, `Chip`) handle Chinese by
  themselves: words come from `Intl.Segmenter`, punctuation stays with its word, CJK runs are not
  tracked and headlines get more leading. For a scene's own text elements, wrap strings in
  `<TextRuns text={…} />` to drop the tracking on CJK runs.

## Fonts

Inter, Inter Display and JetBrains Mono are bundled in `public/fonts/` and loaded by
`src/fonts.ts`. Chinese text falls back to the system's PingFang SC, named right after Inter in
every stack (`CJK_FALLBACK` in `src/theme.ts`), so Latin words, digits and punctuation stay
Inter. PingFang's heaviest weight is Semibold: CJK locales render with `font-synthesis: none`
so the browser never fakes a bold, and headlines show PingFang Semibold. PingFang is a macOS
font: on another system install a CJK face (such as Noto Sans SC, OFL) and add it to
`CJK_FALLBACK`, or bundle it in `public/fonts/`.

## Files

```
STORYBOARD.md            creative spec (scenes, look, motion, rules)
remotion.config.ts       render settings (CLI renders)
.prettierrc.json         code format (npm run format)
src/
  index.ts, Root.tsx     registerRoot and the compositions (one set per locale)
  Thumbnail.tsx          the film's first frame, for the thumbnail and cover art
  Promo.tsx              the film
  Kit.tsx                component demo
  timing.json, timing.ts scene timing (data) and its checked, typed view
  time.tsx               design time at any frame rate: useDesignFrame, DesignSequence
  facts.json, facts.ts   numbers the film states
  locale.tsx             LOCALES, LocaleRoot, useLocale, defineCopy / useCopy
  theme-context.tsx      the film's theme: FILM_THEMES, ThemeProvider, usePalette, useThemed
  assets.ts              manifests per locale and theme, asset paths, sizes and bounds
  cover/                 the upload covers (Cover-wide-en, …), an entry point of their own
  manifest*.json         generated by npm run assets (git-ignored)
  theme.ts, fonts.ts     design tokens and font loading
  easing.ts, config.ts   motion curves; the MUSIC switch
  components/            the shared kit (see each file's header comment)
  scenes/S01Intro.tsx …  one file per scene; parts/ holds pieces shared within a scene group
  scenes/copy/S01.ts …   each scene's words, per locale
scripts/
  render-film.sh         the finished films (--4k60 too), with their thumbnails and cover art
  embed-cover.sh         a thumbnail into a film as its cover art (render-film.sh runs it)
  check.mjs              npm run check: every check below, with a summary
  check-film.mjs         near-empty frames in a rendered film
  check-music.mjs        music.wav and CUES.md against the timing and the score
  stills.mjs             PNG frames for checking
  update-facts.mjs       facts.json from a GitHub release
  retime.mjs             scene lengths in timing.json
  bundle.mjs, lib/       one bundle with public/ symlinked (used by the above)
  ensure-manifests.mjs   empty manifests on a fresh clone (postinstall)
  assets/                the real-UI asset pipeline: render-ui.sh, sync.mjs (docs/ASSETS.md)
tools/extension-popup/   renders the browser extension's real popup (docs/ASSETS.md); its
                         mock-head.html carries one TEMPORARY CSS fix until the extension ships it
docs/ASSETS.md           the real-UI assets: scenarios, bounds, events, locales
audio/                   the music generator (audio/README.md)
public/
  fonts/ brand/          bundled fonts and the brand SVGs (in git)
  clips/ stills/         real-UI assets (generated, git-ignored)
  audio/                 music.wav (generated, git-ignored) and CUES.md
out/                     renders (git-ignored)
```

## When something looks wrong

- **Times New Roman somewhere**: an element without a font; set `fontFamily` (headless Chrome
  falls back to Times). Compositions set Inter on their root.
- **"src/timing.json does not add up"**: a length or start was edited by hand; the message lists
  each problem, or use `npm run retime`.
- **"Clip too short" in a render's output**: a scene grew past the clip it plays; see
  [Change a scene's length](#change-a-scenes-length), step 3.
- **"music.wav is not what src/timing.json and audio/ make now"**: the film was retimed or the
  score changed since the music was rendered; `npm run music`.
- **`WARNING: … the music no longer matches the picture` or `could not check …`** from
  `npm run music` (the `ties` check fails): a scene constant the music follows changed, moved or
  was renamed. The warning names both sides; fix the scene or `audio/score.py`
  ([audio/README.md](audio/README.md), "Picture ties"), then `npm run music`.
- **"no headless Chrome"** from the popup renderer: `npx remotion browser ensure` once.
- **`check:film` rejects a run of frames**: something leaves too early or arrives too late at a
  cut; look at stills of the frames it names and move the exit or the entrance (see the soft-cut
  rule in STORYBOARD.md, "Motion").
- **A missing image (404) in a render**: the asset is not rendered or not in the manifest; run
  `npm run assets`. With empty manifests every asset is missing.
- **Chinese in a fallback font or faux bold**: render on macOS, or add a CJK font (see
  [Fonts](#fonts)).
- **A Chinese line breaking oddly**: line breaks fall between words as `Intl.Segmenter` splits
  them; put a `\n` in the copy where the line should break.
