# Real-UI assets

Every screen of the app in the film is the real app: the clips and stills are rendered by the
app's own headless Compose snapshot harness, over brand-free sample devices and downloads, in
each language the film is made in and in the theme of each film (the dark film and the light
one). The browser extension's popup is the real popup, rendered in headless Chrome. Nothing is a
mock-up, and nothing is drawn by hand.

This page explains how the assets are made, what each one shows, how scenes find things in
them (bounds), and how to add a screen, a clip or a language. The how-to for the rest of the
film is in [README.md](../README.md).

## Quick start

From `art/promo/`, with the JDK the repository builds with and `npm install` done:

```sh
npm run assets                                       # the dark film's, every locale (~50 min)
npm run assets -- all --theme light                  # the light film's, every locale (~50 min)
scripts/assets/render-ui.sh zh-Hans                  # every asset of one locale
scripts/assets/render-ui.sh en discover              # one clip (a PromoSnapshots test)
scripts/assets/render-ui.sh en --theme light discover    # the same clip for the light film
scripts/assets/render-ui.sh zh-Hans stillPalette popup   # a still and the extension popup
node scripts/assets/show-bounds.mjs en discover-dark # draw an asset's bounds, to check them
```

Clips are recorded on virtual time (see [Timing](#how-a-clip-is-timed)), so a busy machine
only makes them slower, never different. The generated files are not in git; these commands
rebuild all of them.

## How it fits together

```
app/shared/src/jvmTest/…/snapshot/PromoSnapshots.kt         tools/extension-popup/
  per-locale content (PromoLocale), bounds (AssetBounds)       mock-chrome.js, mock-head.html
        │ ./gradlew :app:shared:jvmTest -Psnapshots              │ render.mjs <locale> [--theme …]
        │   [-PsnapshotLocale=<locale>]                          │ (copies app/browser-extension/src)
        │   [-PsnapshotTheme=light] --tests …                    │
        ▼                                                        │
app/shared/build/snapshots/promo/<locale>/[light/]               │
  <clip>/0000.png …   stills/<still>.png                         │
  <asset>.json        (record: size, density, frames, fps, bounds, events)
                                                                 │
  <asset>.semantics.txt  <clip>.txt                              │
        │ scripts/assets/sync.mjs <locale> [--theme light]       │
        ▼                                                        ▼
public/clips/[<locale>/][light/]<clip>/0000.png …   public/stills/[<locale>/][light/]popup-<theme>.png
public/stills/[<locale>/][light/]<still>.png
src/manifest[.<locale>][.light].json
        │
        ▼
src/assets.ts: clipFramePath, stillPath, assetSize, assetBounds … (English as the fallback; the
light film falls back to the dark film's assets for the ones it shows from it)
```

`scripts/assets/render-ui.sh` (`npm run assets`) runs all of it: one Gradle run per locale and
theme, then `sync.mjs`, then the popup. Every input is in the repository:

| file | what it holds |
|---|---|
| `app/shared/src/jvmTest/kotlin/com/linroid/ketch/app/snapshot/PromoSnapshots.kt` | the scenarios, the per-locale content (`PromoLocale`), the bounds (`AssetBounds`) and the timing constants |
| `ShowcaseData.kt`, `SampleData.kt` (same folder) | the devices and downloads every screen shows (shared with the README showcase) |
| `scripts/assets/locales.json` | the locales the assets are rendered in, and each one's Gradle and extension locale |
| `app/shared/build.gradle.kts` | `-PsnapshotLocale` and `-PsnapshotTheme`, which pick a run's locale and film |
| `scripts/assets/render-ui.sh` | the whole pipeline |
| `scripts/assets/sync.mjs`, `scripts/assets/lib.mjs` | public/ and the manifests; `lib.mjs` has the themes and where each film's files go |
| `scripts/assets/show-bounds.mjs` | draws bounds on an asset |
| `tools/extension-popup/` | the popup: `render.sh`/`render.mjs`, `mock-chrome.js` (sample data), `mock-head.html` |
| `src/facts.json` | the release every device reports (the phones' Devices cards) and the popup's server reports; read at render time by `PromoSnapshots.kt` (`PromoVersion`) and `render.mjs` |

## The assets

Every asset is named for the theme it shows. The table lists the dark film's; the light film has
the same ones with `-light` in place of `-dark`, from the same tests, except S02's beat (see
[Themes](#themes)). Desktop windows are a `MacWindow` (with its title bar),
1280×800 dp (the desktop clips 1312×820 dp). Each asset is recorded at the pixel density (px
per dp) that the film needs to show it at least 1:1 at 4K, from the largest scale a scene's
camera gives it: the 4K60 film renders the 1920×1080 design canvas at 2×, so an asset a scene
shows at s design px per dp needs 2s px per dp. Clips are recorded at 60 fps. Frame counts,
sizes and densities are what the manifest says; scenes read them from there, never from code.

| name | kind | Gradle test | px per dp | px | frames at 60 fps | shows | bounds |
|---|---|---|---|---|---|---|---|
| `desktop-dark` | clip | `desktop` | 4.25 | 5576×3485 | 840 (14 s) | Studio's Downloads table, the ISO inspected on Connections, lanes moving | desktop set |
| `desktop-light` | clip | `desktopOtherTheme` | 4.25 | 5576×3485 | 240 (4 s) | S02's light beat: the same, light; frame k = `desktop-dark` frame k + 60 (`syncedTo`) | desktop set |
| `desktop-accent-{signal,harbor,fathom,beacon}-light` | stills | `desktopOtherTheme` | 4.25 | 5576×3485 | | the same in each accent, at dark frames 300 / 336 / 372 / 408 (`syncedTo`) | desktop set |
| `discover-dark` | clip | `discover` | 4 | 5120×3200 | 600 (10 s) | Discover: the query typed and sent, the agent's steps, the results, the top one picked | discover set |
| `ios-lanes-dark` | clip | `iosLanes` | 2 | 1276×2184, alpha | 360 (6 s) | a framed iPhone, the ISO's Connections sheet | |
| `android-devices-dark` | clip | `androidDevices` | 2 | 1266×2174, alpha | 360 (6 s) | a framed Android phone, the Devices page scrolled past its sentence to the cards, so the second card's speed clears the bottom bar in every language (`scrollPastFirstLine`) | |
| `downloads-dark` | still | `stillDownloads` | 3.75 | 4800×3000 | | Downloads with the WAITING group ("Starts today at 23:00") | `waitingGroup`, `scheduledRow` |
| `inspector-overview-dark` | still | `stillInspectorOverview` | 3.75 | 4800×3000 | | the ISO's Overview: speed, connections, priority | `priority` |
| `all-devices-dark` | still | `stillAllDevices` | 2 | 2560×1600 | | All devices with the Device column | |
| `palette-dark` | still | `stillPalette` | 2.5 | 3200×2000 | | the ⌘K palette with its commands | `palette` |
| `intake-torrent-dark` | still | `stillIntakeTorrent` | 3.25 | 4160×2600 | | the add sheet choosing a torrent's files (two left out) | `sheet`, `files` |
| `settings-speed-dark` | still | `stillSettingsSpeed` | 3 | 3840×2400 | | Settings → Speed: Full speed / Slow lane / Auto | `speedMode` |
| `settings-network-dark` | still | `stillSettingsNetwork` | 3 | 3840×2400 | | Settings → Network: several interfaces | `networks` |
| `web-dark` | still | `stillWeb` | 2 | 2048×1440 | | the web app, 1024×720 dp | |
| `lang-<tag>-dark` | stills | `stillLanguages` | 2.75 | 3520×2200 | | Downloads in ja, zh-Hans, zh-Hant, de, ko, es, pt-BR, fr, ru | |
| `popup-dark` | still | `popup` (render.sh) | 2 | 720 × as tall as the popup (1044 in en) | | the extension popup following Studio, 360 CSS px at 2×, in the film's colour scheme | |

The densities are constants in `PromoSnapshots.kt` (`CLIP_SCALE`, `DISCOVER_SCALE`,
`DOWNLOADS_SCALE`, …, marked SCENE). A scene's hand-measured constants are in reference px, px
at 2 px per dp (what every asset was when they were measured), and `src/assets.ts` converts them
with the manifest's `density` (`assetRefScale`, `assetBoundsRef`, `assetRefSize`), so raising
an asset's density makes it sharper without moving anything on screen. Keep the window sizes in
dp: bounds and scene layouts are fractions of them.

The desktop set: `inspector`, `inspectorTabs`, `connections`, `connectionsHeader`, `lanes`,
`connectionsBlock` (the tab row down to the bottom of the Connections tab, as S03 frames it).
The discover set: `composer`, `request`, `steps`, `summary`, `results`, `topResult`,
`modelChip`, `pageTitle` (the header's two ends, which S08 keeps inside the frame).

### Per locale

Each locale has its own copy of every asset, except the `lang-*` stills, which show the app in
other languages and are the same in every film: only the default locale (English) renders them
(`stillLanguages` skips itself elsewhere), and other locales fall back to them.

| | en (default) | zh-Hans |
|---|---|---|
| Gradle | no `-PsnapshotLocale` (the harness's en-US) | `-PsnapshotLocale=zh-Hans` |
| app language | follows the JVM | `zh-Hans` (Settings → Language) |
| clips | `public/clips/<name>/` | `public/clips/zh-Hans/<name>/` |
| stills | `public/stills/<name>.png` | `public/stills/zh-Hans/<name>.png` |
| manifest | `src/manifest.json` | `src/manifest.zh-Hans.json` |
| extension messages | `_locales/en` | `_locales/zh_CN` |
| Discover query | "Latest Ubuntu Server LTS ISO" | "帮我找最新的 Ubuntu Server LTS 镜像" |
| `lang-*` stills | rendered | English ones (fallback) |

What a locale changes besides the app's own translations (device names, file names and URLs
stay as they are in every language) is in one table, `PromoLocale` in `PromoSnapshots.kt`: the
Discover clip's query, the two example searches that replace ones naming real products, the
agent's four steps, its summary and its results' words. A locale missing from a manifest, or an
asset missing from a locale's manifest, falls back to English in `src/assets.ts`.

### Themes

The film is made in two themes (README.md, "The light film"), and each film has its own assets,
recorded by its own run with one switch, `--theme` (Gradle's `-PsnapshotTheme`, which
`PromoSnapshots.kt` reads as `Film`): every test records its asset with the app in the film's
`ThemeMode`, at the same frames, sizes, densities and virtual time, and names it for that theme.
The dark film's run is the default, and its files and manifests are where they always were.

| | dark film (default) | light film |
|---|---|---|
| command | `npm run assets [-- <locale>]` | `npm run assets -- <locale> --theme light` |
| Gradle | `-PsnapshotTheme=dark` (or none) | `-PsnapshotTheme=light` |
| build folder | `promo/<locale>/` | `promo/<locale>/light/` |
| clips | `public/clips/[<locale>/]<name>/` | `public/clips/[<locale>/]light/<name>/` |
| stills | `public/stills/[<locale>/]<name>.png` | `public/stills/[<locale>/]light/<name>.png` |
| manifest | `src/manifest[.<locale>].json` | `src/manifest[.<locale>].light.json` |
| assets | `desktop-dark`, `discover-dark`, … `popup-dark` | `desktop-light`, `discover-light`, … `popup-light` |
| S02's beat (`desktopOtherTheme`) | `desktop-light` (240 frames, 60 behind) and `desktop-accent-*-light` | `desktop-accent-*-dark` only; the beat plays the dark film's `desktop-dark` |
| `desktop` clip | no `syncedTo` | `syncedTo` `desktop-dark`, offset 0 (it runs with it) |

`src/assets.ts` looks an asset up in the film's own manifests first (its locale's, then
English), then in the dark film's, so the light film finds the dark clip its S02 shows; the dark
film never reads the light film's manifests. The light film's records and manifests say
`"theme": "light"`, and `sync.mjs` refuses a record of one film in the other's folder.

## How a clip is timed

Everything runs on virtual time: the snapshot harness's `SnapshotClock`, a dispatcher on the AWT
event thread whose delays wait for a clock that only moves when the test lets it. The app is
built on it (`InstanceManager` and `AppController` get `SnapshotHarness.ui` as their context,
the task list's dispatcher and `SnapshotClock.timeSource`), so its timers (list rows every
250 ms, speed samples and lane rates every second, toasts, debounces) keep virtual time too.

Frame n of a recording shows exactly n / 60 s after its timeline starts: the data (the
downloads' bytes and speeds, `Traffic`) moves on to that time, the clock moves on to it, running
every app timer that falls due on the way, and the frame renders at that time on Compose's
clock. Frames are encoded on other threads. Nothing waits for real time, so a slow frame or a
busy machine changes nothing: the same run gives the same frames, and the app's meters read
exactly the rates the data moves at (every timer fires on a frame, as 250 ms and 1 s are whole
numbers of 60 fps frames). Each lane and download wanders up to ±6 % around its rate, a new
value every second of data time; the lane meter, which measures a second, reads the average of
what the lane moved in it.

- Before recording, a scene settles, plays 16 s of speed history (the sparklines), then runs
  600 frames (10 s) of pre-roll so the lane averages settle; only the last 60 render, for the
  animations they leave in place.
- `<clip>.txt` in the build folder records each recording: its frames, rate and format, the
  wall time it took and the render times (about 170 ms a frame for the desktop clips at
  4.25 px per dp, 100 ms for Discover, 4 ms for a phone's screen).
- Wall time (Gradle included), on an M4 Pro: about 50 min for everything in both locales
  (`npm run assets`): 29 min for English, of which the nine `lang-*` stills take 8, and 21 min
  for Chinese. The recordings themselves are short (`desktop-dark` 2½ min, Discover 1 min, a
  phone a few seconds); most of the time goes into settling each scene at its density before it
  records: `desktop` takes 5½ min, `desktopOtherTheme` 4 (about 5 in the light film's run, which
  only records stills but runs the timeline to the last accent), Discover 2, the dense stills 1–3 each, the 2 px per dp ones 15–30 s, the
  popups 10 s. The light film's run (`--theme light`) takes about as long: 29 min for each
  locale, 58 min for both, measured while films rendered beside it. (render-ui.sh and README.md
  quote the same 50 min; change them together.)

### Events in the Discover clip

The same frames in every locale (the manifest has them as `events`, so S08 can read them):

| frame (60 fps) | event | name |
|---|---|---|
| 8–116 | the query typed, a character at a time, evenly (any length takes these frames) | `typing`, `typed` |
| 132 | sent | `send` |
| 144 | the composer lets go of the keyboard (no blinking caret after) | `blur` |
| 160, 208, 256, 304 | the agent's four steps | `step1` … `step4` |
| 360 | the results (the app's own crossfade from the skeleton takes a frame) | `results` |
| 510 | the top result picked: the add bar reads "1 selected" | `select` |

They are constants in `PromoSnapshots.kt` (`TYPE_FROM`, `TYPED_AT`, `SEND_AT`, `CueFrames`,
`SELECT_AT`, marked SCENE). The desktop clips' sync (`LIGHT_OFFSET_FRAMES`, `AccentFrames`) is
in the manifest as `syncedTo`.

## Bounds

A scene that frames part of a screen (S03's zoom into the lanes, S04's sheet, S05's tiles,
S08's camera) reads where that part is from the manifest instead of a measured constant, so it
stays right when the layout or the language changes:

```ts
const {assetBoundsPx, assetBoundsRef} = useAssets(); // the scene's locale, English as fallback
const lanes = assetBoundsPx('desktop-dark', 'lanes', {x: 0.7378, y: 0.5201, w: 0.2439, h: 0.239});
// beside constants measured by hand, which are in reference px (2 px per dp):
const card = assetBoundsRef('settings-speed-dark', 'speedMode', SPEED_FALLBACK);
```

Bounds are fractions (0–1) of the image, `{x, y, w, h}`, so they hold at any density. For a clip they are measured on its
last frame and hold for the whole clip (nothing they name moves).

**How they are found.** After the asset's last frame renders, `describe()` in
`PromoSnapshots.kt` reads the screen's semantics tree, the one screen readers use, and each
`Bound` in `AssetBounds` finds its part through it:

- anchors: a node that reads a text (`text(…)`) or is described as one (`described(…)`,
  `describedLike(resource, args)` for text with numbers in it). Anchors are the app's own string
  resources loaded in the screen's language, or the scenario's own data (file names, the
  locale's query), never English text typed into the test;
- `enclosing(a, b, …)`: the content box of the innermost layout holding all the anchors, such
  as a column of rows;
- `surface(a, …)`: the box of the nearest background painted around the anchors, such as a
  card or a sheet;
- `node.control()`: the clickable that holds an anchor, such as a row or a button;
- `node.box`, `union(…)`.

**What there is to anchor on.** Each asset also gets `<asset>.semantics.txt` in the build
folder: every node with its text, description, role, whether it clicks, and its box in pixels.

**Checking them.** `node scripts/assets/show-bounds.mjs <locale> <asset>` draws each bound as a
coloured box on the asset (on a clip's last frame, or `--frame <n>`) into
`out/bounds/<locale>-<asset>.png`; `--build` reads the record before a sync.

**Adding one.** Add a `Bound("key") { … }` to the screen's list in `AssetBounds`, find anchors
in its `.semantics.txt`, render the asset and look at it with `show-bounds`. A bound that finds
nothing fails the test after the PNG, the record and the dump are written, naming the bound.

## The manifest

`sync.mjs` writes it whole, assets by name, fields in a fixed order, through a temporary file.
Do not edit it by hand.

```json
{
  "locale": "zh-Hans",
  "theme": "dark",
  "clips": {
    "discover-dark": {
      "path": "clips/zh-Hans/discover-dark",
      "frames": 600, "fps": 60, "width": 5120, "height": 3200, "density": 4, "alpha": false,
      "bounds": {"composer": {"x": 0.2984, "y": 0.8337, "w": 0.5687, "h": 0.0963}, "…": {}},
      "events": {"typing": 8, "typed": 116, "send": 132, "…": 0}
    }
  },
  "stills": {
    "desktop-accent-beacon-light": {
      "path": "stills/desktop-accent-beacon-light.png", "width": 5576, "height": 3485,
      "density": 4.25,
      "bounds": {"…": {}},
      "syncedTo": {"clip": "desktop-dark", "frame": 408}
    }
  }
}
```

- `path`: a clip's folder of `NNNN.png` frames (from `0000`), or a still's file, under
  `public/`.
- `ext`: the clip's frame format, `jpg` for JPEG frames; left out for `png`, the default. Every
  clip is PNG today; `LARGE_CLIP_FORMAT` in `PromoSnapshots.kt` switches the desktop and
  Discover clips to JPEG (quality 93) should their PNG frames outgrow the disk.
- `density`: the asset's px per dp (2 when left out, as in manifests from before it). Scenes
  convert their reference-px constants with it (`assetRefScale` in `src/assets.ts`).
- `fps`: the rate the clip was recorded at, a whole multiple of 30 (30 or 60); left out at 30,
  the default. The record names it (`"fps": 60`) and `sync.mjs` copies it. The film plays a clip
  at its own rate whatever the film's rate: scenes give clip time in 30 fps design frames and
  `ClipPlayer` picks the clip's frame (`clipFrameIndex` in `src/assets.ts`), so a 60 fps clip
  advances on every frame of the 60 fps film and a 30 fps one holds each frame for two.
- `alpha`: the frames are transparent around their content (the framed phones).
- `events`: frames of the clip where something happens, in the clip's own frames (at `fps`);
  `clipEvent` returns them as design frames.
- `syncedTo`: for a clip, its frame k shows `clip`'s frame k + `offset`; for a still, it shows
  `clip`'s frame `frame` (in that clip's frames; clips synced to each other share a rate).

The manifest also names its locale and its film's `theme` (`dark` or `light`).

`sync.mjs` checks every clip's frames are numbered without gaps, count what the record says and
match its size (PNG or JPEG), and that bounds lie inside the image. It keeps entries the run did not render
while their files exist, and warns about files in `public/` no entry uses. `public/` gets hard
links to the build folder's files, so a locale's clips take their disk space once.

## Changing things

### Re-record after the app changed

`npm run assets`, then check the scenes that use what changed (`npm run stills`), and
`grep -rn "LAYOUT:" src` for constants measured by hand. Bounds follow on their own; check the
ones a scene relies on with `show-bounds`.

### Re-record after a release

The devices in the clips and stills report the release in `src/facts.json` (the phones'
Devices cards read "Ketch <release> · macOS arm64"), and so does the popup's server. Neither
keeps a copy: `PromoSnapshots.kt` (`PromoVersion`) and `render.mjs` read the file when they
render. After `npm run facts` moves it to a new release, `npm run assets` shows the new version
everywhere; `scripts/assets/render-ui.sh all --theme all androidDevices popup` re-renders just the
assets that show it today.

### Add a still

1. Add a test to `PromoSnapshots`:
   ```kotlin
   @Test
   fun stillHistory() = desktopStill("history-dark", bounds = AssetBounds.History) { env ->
     env.controller.state.openSettings(SettingsTarget(SettingsTarget.Page.General))
   }
   ```
   `desktopStill` warms the scene up like the clips, runs the setup, settles and renders one
   frame. `device` picks the app (a shared laptop for Settings pages, the web app), `size` the
   window in dp and `scale` its px per dp (2 by default; more when a scene shows it larger than
   1 dp per 2 design px, see [The assets](#the-assets)).
2. Optionally its bounds in `AssetBounds`.
3. `scripts/assets/render-ui.sh all stillHistory`, then use it in a scene by name.

### Add a clip

Write it like `discoverClip`: `shoot` a scene at its density, `warmUp`, take a `timeline()`,
`skip` the pre-roll, `record(clip, frames, format) { frame, time -> … }` moving the data with
`Traffic.advance` and the UI with the scenario's state, then `describe(AssetRecord(clip,
AssetKind.Clip, frames = …, events = …), bounds, env.controller)` and `finish()`. Frames are
60 fps: count them, and the events, at that rate. Name the frames a scene keys to as events.
Wait for anything that should take time with the timeline (its frames), not with `delay`: the
app's timers keep the timeline's virtual time.

Click things by what they are, not where they are: `clickTab(title)` for a tab,
`scene.clickOnText(text)` and `scene.clickOn(description)` for the rest, with the app's own
strings (`Res.string.….text().load()`, `InspectorTab.Connections.title.load()`). A position in
dp fits one language: the iPhone clip once opened Activity instead of Connections in Chinese,
whose tab titles are narrower. The only positions left are the drag that holds the iPhone's
sheet up (`SheetGrip`, `SheetHeld`, marked LAYOUT).

### Add a locale

1. `PromoLocale` in `PromoSnapshots.kt`: the tag (as `-PsnapshotLocale` takes it), the app
   language Settings saves, and its Discover script. The steps count must match `CueFrames`.
2. `scripts/assets/locales.json`: its `snapshotLocale` and the extension's `_locales` folder.
3. The film's side (src/locale.tsx, the copy files, `src/assets.ts`'s manifests): see
   [README.md](../README.md).
4. `scripts/assets/render-ui.sh <tag>`, and look at every still and at frames of every clip.

### Change the popup

`tools/extension-popup/mock-chrome.js` holds what the popup's server answers (the downloads, as
the desktop clips show them on Studio) and the browser it pretends to be. The popup itself is
always `app/browser-extension/src` as it is: `render.mjs` copies it, puts the mock beside
`popup.html` and loads it first (`mock-head.html`), serves the copy on 127.0.0.1, and captures
it with Remotion's headless Chrome over the DevTools protocol, 360 CSS px wide at 2×, as tall
as its content, in the film's colour scheme (`--theme`: `popup-dark`, or `popup-light` for the
light film). It captures again when it caught the popup still "Connecting…".

To see a change to the extension in the popup before it lands, render a copy of `render.mjs`
whose `extensionSource` points at the changed files; never patch the extension only for the
film, which must show the popup as the extension ships it.

One temporary exception: `mock-head.html` also injects
`.add > button { flex: none; white-space: nowrap; }`, the fix for the Chinese Download button
(see [Known issues](#known-issues-in-the-assets)), until the extension ships it in
`popup.css`. It changes nothing in English (the English popup renders byte for byte as without
it). Once the fix is in `app/browser-extension/src/popup/popup.css`, delete that `<style>` and
its comment, run `tools/extension-popup/render.sh all`, and check that both popups are unchanged.

## Troubleshooting

- **Everything jumps instead of gliding.** macOS Reduce Motion is on (System Settings →
  Accessibility → Display); the app follows it. render-ui.sh refuses to start while it is.
- **A blank or single-colour image.** sync.mjs warns when a frame or a still is small enough to be one flat
  colour. Look at it: usually the scene failed to compose (the test fails too) or the window
  closed early.
- **"Couldn't find <bound> on <asset>".** The UI changed under an anchor. Open
  `<asset>.semantics.txt` in `app/shared/build/snapshots/promo/<locale>/`, find the part, fix
  the `Bound`.
- **"No promo locale for -PsnapshotLocale=…".** The tag passed to Gradle has no `PromoLocale`;
  use the tags in `locales.json`.
- **"No tests found for given includes".** A test name passed to render-ui.sh is not a
  `PromoSnapshots` test (they are case-sensitive).
- **Chinese text in Times, boxes or the wrong forms.** The harness renders CJK with the
  system's fonts: render on macOS.
- **The popup is missing in a manifest.** `tools/extension-popup/render.sh <locale>`; it needs
  `npx remotion browser ensure` once.
- **A clip shows another tab or page in one language.** A scenario clicked a position; see
  [Add a clip](#add-a-clip).
- **Disk.** A locale's clips take about 1.9 GB as PNG (`desktop-dark` 1 GB, Discover 380 MB,
  `desktop-light` 330 MB, the phones 210 MB) and its stills 10–20 MB; both locales about
  3.9 GB. The light film's add about as much again (1.9 GB in English, with the `lang-*` stills,
  1.8 GB in Chinese): its `desktop-light` is the full 840 frames, and it has no 240-frame beat
  clip. `public/` holds hard links to the files in
  `app/shared/build/snapshots/promo/<locale>/`, so the two cost the space once; deleting the
  build folder after a sync frees only the phones' raw screens (`*-screen/`, about 115 MB a
  locale), and a re-render frees the old files once it is synced. Never render the film as PNGs.

## Known issues in the assets

Issues of the app or the extension that show in the assets; fix them there and re-render.

- The extension's popup in Chinese (and any CJK language): the 下载 button wraps to one
  character per line, as CJK text may break between any two characters and the button shrinks
  beside the link field, which also makes the popup taller (1016 px against 978). The fix is
  `.add > button { flex: none; white-space: nowrap; }` in
  `app/browser-extension/src/popup/popup.css`. Until the extension ships it, the renderer applies
  it itself (`tools/extension-popup/mock-head.html`, marked TEMPORARY), so `popup-dark` shows the
  button on one line in every locale; remove the override once the fix lands (see
  [Change the popup](#change-the-popup)).
- The inspector's metric line breaks inside a word in Chinese: "剩余13秒 · 预 / 计 14:30 完成" in
  the desktop clips (S02, S06) and "预计 14:30 完 / 成" in the iPhone clip. The fix belongs in
  the app (keep each " · " part of the line whole); a wider inspector in the Chinese clips would
  need about 48 dp more, squeezing the Downloads table, and cannot help the iPhone.
- The Discover clip's intro in Chinese breaks 添加 ("只有手动添 / 加后才会开始下载。"). The body is
  capped at the app's `BodyWidth` (`DiscoverSetup.kt`), so a wider window does not move the
  break; the app's Chinese string or its line breaking has to.
- `settings-network-dark` says "Laptop" where the window's sidebar says "This Mac" (the
  sample laptop's device name).
