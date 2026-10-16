# Ketch promo film: storyboard

The creative spec of the film: what each scene is for, how it looks and moves, and what the film
must never claim. It holds no words, numbers or frame numbers of its own, so it cannot drift from
the film; those live in the data the film is built from:

| what | where |
|---|---|
| every word on screen, per locale | `src/scenes/copy/<SceneId>.ts` (English and Chinese side by side) |
| every number about Ketch (sizes, release, connections, tools, languages, tests) | `src/facts.json` (`npm run facts`) |
| scene starts and lengths, the lane-wipe cuts | `src/timing.json` (`npm run retime`) |
| when things happen inside a scene | the timing header at the top of its scene file (`src/scenes/S01Intro.tsx` …) |
| the app screens the film shows | [docs/ASSETS.md](docs/ASSETS.md) |
| how to change any of it | [README.md](README.md) |

## Format

- 1920 × 1080, 30 fps, H.264 (yuv420p, bt709) with an AAC music bed; about 77 s (`total` in
  `src/timing.json`). Also a 4K60 master: 3840 × 2160 at 60 fps, the same film with twice the
  frames (H.264 High, level 5.2).
- Time is designed at 30 fps: every length, beat, cut and frame number in this spec, the timing
  data and the scene files is a 30 fps "design frame", at either output rate. The 60 fps film
  samples the same motion twice as often (half-frame steps), retiming nothing.
- Music at 120 BPM: one beat is 15 frames, a bar of four beats 60. Every scene is a whole number
  of beats long, so every cut lands on a beat (`src/timing.ts` refuses a timing that does not).
- One film per locale and rate: `Promo` (English) and `Promo-zh-Hans` at 30 fps, `Promo-60` and
  `Promo-60-zh-Hans` at 60, each scene in a `<Sequence>`; every scene is also a composition of
  its own (`S01` …, `S01-zh-Hans` …) for previewing, with frames local to the scene.
- Two themes, one switch: the dark film above, and a light version of each
  (`Promo-light`, `Promo-zh-Hans-light`, `Promo-60-light`, `S01-light`, `Thumbnail-light` …):
  the same content, timing, copy and music, with every screen of the app recorded in its light
  theme and the film's own canvas and graphics in the light palette ("Look"). Everything in this
  spec holds for both; where the light film differs, the scene says so. The theme is a prop
  (`{"theme": "light"}`) that every scene and component reads (README.md, "The light film").
- The app's clips are played at their own frame rate (30 or 60 fps, from the manifest) whatever
  the film's, so a clip never runs fast or slow.
- The film opens on the logo: its first frame is the finished lock-up, so the picture a player
  shows before playback is the brand. The same frame is each film's cover art, embedded in the
  mp4 and kept beside it as a PNG and a JPEG (`Thumbnail`, `Thumbnail-zh-Hans`; README.md,
  "Thumbnails and cover art").

## Languages

- English and Simplified Chinese. The Chinese is written for its viewer: spoken, natural Chinese
  (口语化), never a translation of the English. It lives next to the English in each scene's copy
  file; tone and the app's own terms are in README.md ("Change some copy") and
  [docs/development/translation-glossary.md](../../docs/development/translation-glossary.md).
- The app screens are rendered in the film's language; an asset a locale does not have falls back
  to the English one.
- The terminal scenes stay English in every locale (the CLI is not localized). Product, protocol
  and file names stay as they are.
- Chinese typography (no tracking on CJK runs, more leading in headlines, punctuation kept with
  its word, PingFang SC) is handled by the shared Text components.

## Look

Premium, calm, dark-first, the app's own brand, with a light version of the whole film. Not
neon, not glossy stock-template. The values below are the design; `src/theme.ts` holds them as
tokens.

- **Canvas (dark, default):** `#0C0E13`, wash `linear-gradient(160deg, #151A33 0%, #0E1016 50%,
  #1C1310 100%)`, plus two slow-drifting blooms: accent `rgba(85,99,240,.26)` radius ~1000 px
  near the top left, ember `radial(#FFB25B@.15 → #E0482B@.08 → transparent)` radius ~660 px near
  the bottom right. Blooms drift ≤ 40 px over a scene (noise), never pulse.
- **Canvas (light: the light film, and S02's light beat in the dark film):** `#EEF1F8`, wash
  `linear-gradient(160deg, #E9EEFC, #F1F0FA, #FBF0EA)`, ember `rgba(255,217,194,.35)` bottom
  left, a faint accent `rgba(79,93,228,.09)` top right.
- **Text colours (dark):** primary `#EEF0F4`, secondary `#AEB4C0`, tertiary `#858D9C`, accent
  text `#8E9BFF`, accent fill `#5563F0`, hairline `#2A2E37`, surface `#16181D`, raised `#1E2128`.
  (Light: primary `#141A26`, secondary `#4B5466`, tertiary `#6E7687`, accent text `#3C47B7`,
  fill `#4F5DE4`, hairline `#E2E6EE`, surface `#FFFFFF`.)
- **Accents:** Signal `#4F5DE4`/`#5563F0`, Harbor `#00818D`, Fathom `#007F35`, Beacon `#C9431C`.
- **Ember (brand):** `linear-gradient(135deg, #FFB25B, #E0482B)`. Only for the logo, the
  lane-wipe leading edge, and at most one highlight per scene (one underline or one stat).
- **Logo:** on the dark canvas the sail is white (`public/brand/icon-white.svg`), on the light
  canvas it is the brand's mark for light surfaces (`public/brand/icon.svg`: `#EE5F2F`, the
  mizzen at 65%); row 0 refills in the ember on both, and the app icon tile is the same.
- **Status:** success `#45C27A`, paused `#E8A93F`, failed `#FF6B6B` (avoid showing failures).
- **Type** (bundled in `public/fonts/`; Chinese falls back to PingFang SC):
  - Headlines: Inter Display Bold, 84 px, line-height 1.02, letter-spacing −0.035em; hero and
    end-card titles up to 140 px.
  - Eyebrow: Inter SemiBold 20 px, uppercase, letter-spacing 0.16em, accent text colour.
  - Sub-lines: Inter Medium 32 px, line-height 1.35, secondary colour, max width ~780 px.
  - Chips: Inter Medium 22–26 px, pill radius, `rgba(255,255,255,.06)` fill, 1 px
    `rgba(255,255,255,.10)` rim (light: `rgba(20,26,38,.05)` and `rgba(20,26,38,.10)`),
    10 × 20 px padding at 22 px.
  - Numbers: Inter Display SemiBold with tabular figures (`tnum`), so count-ups never jitter.
  - Code and terminal: JetBrains Mono 400.
- **Layout:** safe area 120 px left and right, 90 px top and bottom. The default text block is
  top left (eyebrow at y ≈ 110, headline, sub) or a left column (x = 120, width ≤ 700) with the
  visual on the right. Never text over busy UI without a scrim.
- **App windows:** radius 14 px (scaled with the window), rim 1 px `rgba(255,255,255,.11)` on
  dark and `#E2E6EE` on light, layered `#0F172A` shadow (y/blur/alpha 3/8/.19, 8/20/.34,
  16/32/.29, 40/80/.53 spread −14 on the dark canvas, in a near-black ink there; on the light
  canvas the same layers at 42% of those alphas).
- **Cards** (S05's tiles, S10's size cards): the surface colour, radius 20, a 1 px rim
  (`rgba(255,255,255,.08)` on dark, `rgba(20,26,38,.09)` on light) and a soft drop shadow
  (`rgba(0,0,0,.6–.7)` on dark, `rgba(15,23,42,.16)` on light); white cards on the light canvas.
- **Terminal:** body `#16181D`, 38 px title bar `#1E2128` with a centred "ketch" (Inter Medium
  13 px `#858D9C`) and traffic lights `#FF5F57 #FEBC2E #28C840` at 60%, radius 12, padding
  20/16, text `#EEF0F4`, prompt `~ ❯ ` in `#8E9BFF`, block cursor `#AEB4C0` (at scale 1;
  `TerminalWindow` scales it). The terminal (and S09's config card, drawn the same way) stays a
  dark window in the light film; only its shadow follows the canvas.

## Motion

- Easing `cubic-bezier(0.2,0,0,1)` for UI moves, `cubic-bezier(0.05,0.7,0.1,1)` for entrances.
  Springs: `{damping: 200}` (no bounce) for text, `{damping: 18, stiffness: 120}` at most for
  objects. Nothing wobbles, nothing spins.
- Text enters word by word (Chinese by word as `Intl.Segmenter` splits it): rise 28 px and fade,
  a 2-frame stagger, about 18 frames each; it leaves with a fade and a 16 px rise over 10 frames.
- Camera moves are slow pushes (scale ≤ 1.08 per 4 s) unless a zoom is the point of the shot.
- **Lane wipe**, the signature transition: six horizontal bands (the sail's six rows, equal
  height, gap 16/60 of a band) sweep left to right in a 2-frame staircase, cover the frame, and
  carry on off the right, revealing the next scene. The bands take the next scene's canvas colour;
  band 0 carries a 6 px ember leading edge. 18 frames centred on the cut, fully covering the frame
  on the cut. `Promo` places it on the cuts listed in `wipes` in `src/timing.json` (S01→S02,
  S05→S06, S08→S09 and S11→S12).
- **Other cuts** are soft or hard, inside the scenes. Anything that leaves at a soft cut is still
  fading on the scene's last frame and gone on the cut (its exit starts at `softCutExitAt(len)` in
  `src/timing.ts`), or stays at full through it; when everything leaves, it must not be gone a
  frame early, or one bare frame blinks between two pictures. The incoming scene shows content by
  its frame 0–2. Scenes look complete up to their last frame and from their first.
- **No empty frames:** no run of more than 3 near-empty frames, except under a lane wipe and the
  final fade to black (the last 20 frames). The film opens on the finished lock-up, with no fade
  from black. `npm run check:film` scans a render for this, and `npm run film` runs it.

## Do not claim (hard rules)

- No store badges and no "available on the App Store / Google Play / Chrome Web Store". The iOS
  app builds from source, and S06 says so in a footnote.
- Pre-1.0: never "stable", "1.0" or "production-ready".
- Discover is a **Preview**, on desktop, Android and the CLI, with your own model: never "free
  AI", providers as plain text, never logos. Do not say it asks before it opens a site: page
  access can allow sites without asking, and the clip's agent opens a page without an approval
  card. Say that you choose which sites it opens.
- Never a bare "No dependencies" or "No Java": say "No dependencies to install", "self-contained"
  or "runtime included". Never "no native libraries": the torrent engine is pure Kotlin, but the
  JVM build uses JNA for file-system calls.
- No speed multipliers ("10× faster"). No "full BitTorrent client", no seeding claims.
- Send / Move to another device does not carry the downloaded bytes. No cloud, no relay. No star
  counts.
- Never name another download manager. Avoid real third-party brands in UI data, except the
  Discover query and its results.
- Never show `linroid.com/Ketch` (the owner's request): the end card's only address is the
  repository, and S06's web app shows the address `ketch server` serves it on.
- Numbers come from `src/facts.json` only, never typed into copy; download sizes in binary units
  as GitHub shows them, from the release the footnote names.

## Scenes

Each scene: what it is for, what the picture does, and its notes. Its words are in
`src/scenes/copy/<SceneId>.ts`, its frames in its scene file's header, its length in
`src/timing.json`. Asset names are the dark film's; the light film shows each one's light twin
(`desktop-light`, `discover-light`, …), except where a scene says otherwise.

### S01 Intro (`S01Intro.tsx`)

- **For:** the brand, from the very first frame. The film opens on the logo, so every player's
  default thumbnail (its first frame) and the files' cover art are the lock-up.
- **Picture:** opens on the finished lock-up, nothing in motion and nothing fading in: the sail
  glyph (the geometry of `public/brand/icon.svg`: six main-sail rows, four mizzen rows, the hull),
  the wordmark beside it and the tagline under it, on one line in every locale. Then the sail's
  segments refill like download lanes, a few frames apart on the music's sparkle notes, in fill
  order (main rows top to bottom, then the mizzen): each segment drains right to left to a dim
  floor of its own colour (it never vanishes, so nothing blinks) and refills left to right, row 0
  in the ember, the rest white as in the mark (in the light film the brand's orange, as in
  `icon.svg`, with row 0 in the ember). Each refilled segment then gets a completion
  glint, a pale slanted band of light crossing the lane once, as the app's lane strips gleam
  when a download completes. At most a few segments move at once, so the mark always reads. The
  wordmark and the tagline hold at full until the lane wipe, and the whole lock-up pushes in
  slowly, calm and unhurried.
- **Notes:** the first frame is the cover art: the `Thumbnail` compositions render it, and
  `scripts/render-film.sh` embeds it in every mp4 (README.md, "Thumbnails and cover art"). Keep
  it complete and still: nothing may be mid-animation on it.

### S02 Modern UI (`S02ModernUI.tsx`)

- **For:** the app is calm and modern, light or dark, in four accents, on any screen.
- **Picture:** copy top left. The real desktop window (`desktop-dark`) rises from the bottom
  right with a perspective tilt and settles large, cropped by the frame's edge, pushing in
  slowly. A diagonal wipe turns the window, the canvas and the text light (`desktop-light`); the
  accent pill appears under the sub and the window recolours through the four accents
  (`desktop-accent-*-light`), the active dot growing. The same wipe turns everything dark again,
  so S03 starts on exactly the window S02 ends on.
- **Light film:** the same beat the other way round: the light window (`desktop-light`) and
  canvas, a wipe to dark (the dark film's own `desktop-dark`, at the same frame), the four
  accents in dark (`desktop-accent-*-dark`), and the wipe back to light for S03.
- **Notes:** the pill names the accents as the app's Settings does in each language. The other
  theme's clip and the accent stills are timed against the dark clip where they are rendered
  (PromoSnapshots.kt, its SCENE constants).

### S03 Parallel lanes (`S03Lanes.tsx`)

- **For:** speed you can see: one file over many connections.
- **Picture:** continues S02's window without a jump (the same clip, `desktopClipFrame`), then a
  deliberate camera zoom into the inspector's Connections lanes, aimed by the clip's measured
  bounds. The lanes keep moving. The window dissolves into the canvas on the left, where the copy
  sits, and two chips follow, one per beat. The lanes stay at full through the cut.
- **Notes:** the connection limit is `maxConnections`.

### S04 Any link (`S04AnyLink.tsx`)

- **For:** every kind of link goes into one queue, torrents included, with a choice of files.
- **Picture:** copy on the left; the link-type chips drop in one per beat, like a queue. The Add
  sheet with a torrent's file list (`intake-torrent-dark`), cropped out of the real window,
  slides in on the right as a framed panel and stays through the hard cut.

### S05 Full featured (`S05Features.tsx`)

- **For:** depth without clutter.
- **Picture:** the headline, then a 3 × 2 bento of tiles (raised surface, rim, radius 20), each a
  title, a one-line caption and a crop of real UI dissolving into the tile: speed modes
  (`settings-speed-dark`), priorities (`inspector-overview-dark`), scheduling and the smart queue
  (`downloads-dark`), several networks (`settings-network-dark`) and the ⌘K palette
  (`palette-dark`). The tiles stagger in; each gets a spotlight in turn (it brightens and grows,
  the others dim) while the grid pushes in; the grid pulls back to rest under the lane wipe.
- **Notes:** feature names are the app's own words in each language.

### S06 Cross-platform (`S06CrossPlatform.tsx`)

- **For:** every device you own, and wherever you work.
- **Picture:** two beats that swap on a bar line with a short crossover, so the screen never
  empties. Beat A: the desktop window (`desktop-dark`), the Android phone
  (`android-devices-dark`) and the iPhone (`ios-lanes-dark`, lanes moving) assemble in a
  cascade, with the platform pills and a footnote that the iOS app builds from source. Beat B:
  a terminal types `ketch <url>` (its progress line counts up to 100%, then "Download
  completed") and `ketch server` (its banner, with the release and port 8642); a browser with the
  extension's popup (`popup-dark`) and one with the web app (`web-dark`) at the server's address;
  the surface pills. Beat B's terminal lands over Beat A's fading stage, and the headlines, which
  share one place, never overlap. Soft cut out.
- **Notes:** the terminal follows the real CLI's output, in English. No store badges.

### S07 All your devices (`S07Devices.tsx`)

- **For:** pair once, then watch and control every device from any of them.
- **Picture:** copy top left. The All devices window (`all-devices-dark`, with its Device
  column) rises from the bottom right and pushes in slowly. Three device pills on the left, under
  the copy (the devices the window lists), send thin lines that converge on the window's All
  devices row, with dots flowing along them. Hard cut out on the whole picture.
- **Notes:** nothing implies that bytes move between devices, or a cloud.

### S08 AI Discover (`S08Discover.tsx`)

- **For:** describe what you want; an agent finds, checks and ranks the downloads.
- **Picture:** the real Discover page (`discover-dark`, rendered in each language: the query is
  typed, the steps tick, the ranked results arrive, the top one is selected) in a large window
  that hard-cuts in at full. The camera follows the action, from the composer to the steps, the
  results and the selection bar, aiming by the clip's measured bounds and timing itself by its
  recorded events. Two qualifier chips top right.
- **Notes:** the eyebrow always says Preview; the chips say you choose which sites it opens and
  that the model is your own (see "Do not claim"). The query and results may name real sites.

### S09 Ready for agents (`S09Agents.tsx`)

- **For:** AI assistants can drive Ketch.
- **Picture:** two columns centred on the frame: the copy on the left; on the right an editor
  card (`mcp.json`) with the MCP client config, from which tool-name pills flow across to settle
  under the sentence that counts them. The last pill counts the tools the others do not name, so
  they add up to `mcpTools`. Everything holds at full; a hard cut on the cymbal.
- **Notes:** the config and the tool names are code, the same in every locale.

### S10 Light (`S10Light.tsx`)

- **For:** small downloads, and nothing else to install.
- **Picture:** four download-size cards from the release in a 2 × 2 grid, smallest first (sorted
  by size, so a new release keeps the order): the browser extension, the Android app, the command
  line and the desktop app. Each number counts up as its card enters; one card carries the ember.
  Then a row of two chips, with a tertiary footnote naming the release at the right. Soft cut
  out.
- **Notes:** the sizes and the release are `sizes` and `release`. The chips follow the
  dependency rules in "Do not claim".

### S11 Languages (`S11Languages.tsx`)

- **For:** Ketch speaks your language.
- **Picture:** the headline on the left, then a ticker of language names, each in its own
  language and script, stepping up with the snare roll. On the right the app, large, dissolving
  into the canvas, changes language in step (`lang-<tag>-dark`; a language without a screen is
  passed over, so the name in focus and the window always match). Lane wipe out.
- **Notes:** the list of languages is in the copy file and must match `languages`.

### S12 Open source and end card (`S12OpenSource.tsx`)

- **For:** free and open source, and where to find it.
- **Picture:** Beat A: a centred statement with four chips (license, Kotlin Multiplatform, the
  test count, the SDK on Maven Central), then six thin lanes under them fill like one download.
  Beat B, the end card: the app icon tile (`public/brand/icon-app.svg`) with a soft ember glow
  lands on the final chord with the wordmark, then the intro's tagline, the repository
  (`github.com/linroid/Ketch`) and the platform line. It holds with a barely visible push and
  fades to black over the last 20 frames (the light film fades into its light canvas instead).
- **Notes:** the repository is the only address. The test count is `tests`.

## Assets

The film shows the real app only, rendered by its own headless Compose snapshot harness
(`PromoSnapshots.kt`), never mock-ups; clips are PNG sequences played frame by frame. Each film
has its own: the light film's are recorded in the app's light theme, at the same frames, sizes
and densities. [docs/ASSETS.md](docs/ASSETS.md) lists every asset by the names used above, what
it shows, its size, and how to render it again.

## Checking your work

- Look at stills of what changed, in every locale and both themes (`npm run stills -- --locale
  all --theme all out …`, see README.md), and delete them afterwards.
- `npm run check` runs the checks that define "done": types, formatting, facts, music, and the
  near-empty scan of the rendered films.
- Text stays inside the safe area, nothing overlaps, no text sits on busy UI without a scrim, and
  no frame shows Times (an element without a font).
