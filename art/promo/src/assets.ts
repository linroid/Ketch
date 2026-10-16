// Typed access to the real-UI assets, per locale.
//
// The asset pipeline (scripts/assets/, docs/ASSETS.md) renders the app's screens with its
// headless Compose snapshot harness into public/clips and public/stills and records each one in
// a manifest (size, frames, the bounds of named UI elements, the frames of named events):
// src/manifest.json for English (clips/<name>/…, stills/<name>.png) and
// src/manifest.<locale>.json for every other locale (clips/<locale>/<name>/…,
// stills/<locale>/<name>.png). The manifests are generated and git-ignored; `npm install` writes
// empty ones (scripts/ensure-manifests.mjs) so a fresh clone type-checks and bundles.
//
// A locale's missing entry falls back to the English asset, entry by entry, so a locale can
// start with a few localized screens.
//
// The film is made in two themes (theme-context.tsx), and so are its assets: every asset is
// named for the theme it shows (`desktop-dark`, `desktop-light`; `themedAsset`, or `themed` on
// a resolver), and each film has its own manifests, which the asset pipeline writes from its
// own run (`npm run assets -- <locale> --theme light`): the dark film's are the ones above, the
// light film's src/manifest.light.json and src/manifest.<locale>.light.json, with their files
// under clips/[<locale>/]light/ and stills/[<locale>/]light/. A film looks an asset up in its
// own theme's manifests first (its locale's, then English), then in the dark film's, so the
// light film also finds the dark assets its S02 shows (`desktop-dark`), and the dark film never
// sees the light film's.
//
// Assets have a density of their own (`density`, px per dp, 2 when the manifest names none):
// the same dp window can be recorded sharper, so its normalized bounds stay valid while its pixel
// size grows. Constants hand-measured on the assets (crops, rects, insets, radii, fades, camera
// scales) are in "reference px", px at REFERENCE_DENSITY (2 px per dp, the density every asset
// had when they were measured), so the film frames an asset the same at any density and only
// looks sharper: assetRefScale converts reference px to the asset's source px, assetRefSize and
// assetBoundsRef give its size and bounds in reference px (assetSize and assetBoundsPx give
// source px). At density 2 the factor is exactly 1, so nothing changes.
//
// Clips have a frame rate of their own (`fps`, 30 when the manifest names none). Scenes never
// count a clip's frames: they give clip time in design frames (30 fps, src/time.tsx) and these
// helpers map it to the clip's frames, so a 30 fps clip holds each frame for two output frames
// of a 60 fps film and a 60 fps clip advances on every one. Frame counts, `events` and
// `syncedTo` are in the clip's own frames in the manifest; clipEvent, clipDuration and
// clipToDesignFrames return design frames. A clip's frames are <path>/0000.<ext> … (`ext`, png
// when the manifest names none; large clips may be stored as jpg); clipFramePath names them.
// Every helper takes the locale as its last argument (default English); in a component,
// `useAssets()` returns them bound to the current locale.
import enLightManifest from './manifest.light.json';
import enManifest from './manifest.json';
import zhHansLightManifest from './manifest.zh-Hans.light.json';
import zhHansManifest from './manifest.zh-Hans.json';
import {DEFAULT_LOCALE, LOCALES, useLocale, type Locale} from './locale';
import type {Mode} from './theme';
import {DEFAULT_THEME, FILM_THEMES, useTheme} from './theme-context';
import {DESIGN_FPS} from './time';
import {scene} from './timing';

/**
 * A rect in fractions (0..1) of the asset's width and height, e.g. where a UI element is. The
 * asset pipeline measures these on the real UI, so a scene that aims a camera or a crop at
 * `bounds` stays right when the layout or the language changes.
 */
export interface NormRect {
  x: number;
  y: number;
  w: number;
  h: number;
}

/**
 * The frame of another clip an asset matches, so a scene can cut between them. In the frames of
 * `clip` (and of the clip that carries it, which has the same rate).
 */
export interface SyncedTo {
  /** The other clip. */
  clip: string;
  /** For a clip: its frame k shows what `clip`'s frame k + offset does. */
  offset?: number;
  /** For a still: the frame of `clip` it shows. */
  frame?: number;
}

/** A clip's frame rate when its manifest entry names none: the clips recorded before `fps`. */
export const DEFAULT_CLIP_FPS = 30;

/** An asset's px per dp when its manifest entry names none: the assets recorded before `density`. */
export const DEFAULT_ASSET_DENSITY = 2;

/**
 * The px per dp of "reference px", the unit of every constant hand-measured on an asset (crops,
 * insets, radii, fades, camera scales): the assets were all recorded at 2 px per dp when those
 * were measured. assetRefScale converts them to an asset's source px.
 */
export const REFERENCE_DENSITY = 2;

/** A clip's frame file extension when its manifest entry names none. */
export const DEFAULT_CLIP_EXT = 'png';

export interface ClipEntry {
  /** Number of frames (0000.<ext> …), at `fps`. */
  frames: number;
  /** Frames per second the clip was recorded at. Default DEFAULT_CLIP_FPS (30). */
  fps?: number;
  /** File extension of the frames ('png' or 'jpg'). Default DEFAULT_CLIP_EXT ('png'). */
  ext?: string;
  /** Size of the frames in px. */
  width: number;
  height: number;
  /** px per dp the clip was recorded at. Default DEFAULT_ASSET_DENSITY (2). */
  density?: number;
  alpha?: boolean;
  /** Folder of the frames, relative to public/. Default clips/<name> (clips/<locale>/<name>). */
  path?: string;
  /** Still shown while the clip has no frames yet. */
  fallback?: string;
  /** Named UI elements on the clip (constant over its frames). */
  bounds?: Record<string, NormRect>;
  /** Clip frames (at `fps`) where something happens, by name (e.g. when results appear). */
  events?: Record<string, number>;
  syncedTo?: SyncedTo;
}
export interface StillEntry {
  /** Size of the image in px. */
  width: number;
  height: number;
  /** px per dp the still was recorded at. Default DEFAULT_ASSET_DENSITY (2). */
  density?: number;
  /** Image path relative to public/. */
  path: string;
  /** Named UI elements on the still. */
  bounds?: Record<string, NormRect>;
  syncedTo?: SyncedTo;
}
export interface Manifest {
  clips: Record<string, ClipEntry>;
  stills: Record<string, StillEntry>;
}

/**
 * Every locale's manifests, one per film's theme (empty objects until the asset pipeline has
 * run).
 */
const MANIFESTS: Record<Locale, Record<Mode, Manifest>> = {
  en: {dark: enManifest as unknown as Manifest, light: enLightManifest as unknown as Manifest},
  'zh-Hans': {
    dark: zhHansManifest as unknown as Manifest,
    light: zhHansLightManifest as unknown as Manifest,
  },
};

/** The English manifest of the dark film. */
export const manifest: Manifest = MANIFESTS.en.dark;

/** The manifest of `locale` for the film in `theme` (empty when its assets are not rendered). */
export const manifestFor = (locale: Locale, theme: Mode = DEFAULT_THEME): Manifest =>
  MANIFESTS[locale][theme];

/** The name of asset `base` showing the app in `theme`: `desktop` → `desktop-light`. */
export const themedAsset = (base: string, theme: Mode): string => `${base}-${theme}`;

/** Zero-padded clip frame file name: 7 -> "0007". */
export const pad4 = (n: number): string => String(Math.max(0, Math.floor(n))).padStart(4, '0');

/**
 * Where a clip's frames are by default: clips/<name> for English, clips/<locale>/<name> else,
 * under a light/ folder for the light film (as scripts/assets/lib.mjs lays them out).
 */
const defaultClipDir = (name: string, locale: Locale, theme: Mode): string => {
  const local = locale === DEFAULT_LOCALE ? 'clips' : `clips/${locale}`;
  return theme === DEFAULT_THEME ? `${local}/${name}` : `${local}/${theme}/${name}`;
};

/** A manifest entry plus the locale and the film's theme of the manifest it came from. */
export type Resolved<E> = E & {locale: Locale; theme: Mode};

/**
 * The manifests the film in `theme` looks an asset up in, in order: its own theme's (the
 * locale's, then English), then the dark film's (likewise). The dark film only looks in its own.
 */
const lookupOrder = (locale: Locale, theme: Mode): {locale: Locale; theme: Mode}[] => {
  const locales = locale === DEFAULT_LOCALE ? [locale] : [locale, DEFAULT_LOCALE];
  const themes = theme === DEFAULT_THEME ? [theme] : [theme, DEFAULT_THEME];
  return themes.flatMap((t) => locales.map((l) => ({locale: l, theme: t})));
};

/**
 * The clip entry for `name` in `locale`, else the English one (the film in `theme`'s manifests
 * first, then the dark film's); its `path` is always set.
 */
export const clipInfo = (
  name: string,
  locale: Locale = DEFAULT_LOCALE,
  theme: Mode = DEFAULT_THEME,
): Resolved<ClipEntry> | undefined => {
  for (const at of lookupOrder(locale, theme)) {
    const e = MANIFESTS[at.locale][at.theme].clips[name];
    if (e) return {...e, path: e.path ?? defaultClipDir(name, at.locale, at.theme), ...at};
  }
  return undefined;
};

/** The still entry for `name` in `locale`, else the English one (as clipInfo looks them up). */
export const stillInfo = (
  name: string,
  locale: Locale = DEFAULT_LOCALE,
  theme: Mode = DEFAULT_THEME,
): Resolved<StillEntry> | undefined => {
  for (const at of lookupOrder(locale, theme)) {
    const e = MANIFESTS[at.locale][at.theme].stills[name];
    if (e) return {...e, ...at};
  }
  return undefined;
};

/** True when the clip exists with real frames. */
export const hasClipFrames = (
  name: string,
  locale: Locale = DEFAULT_LOCALE,
  theme: Mode = DEFAULT_THEME,
): boolean => (clipInfo(name, locale, theme)?.frames ?? 0) > 0;

/** Number of frames of a clip, at its own rate (0 for placeholders or unknown names). */
export const clipFrames = (
  name: string,
  locale: Locale = DEFAULT_LOCALE,
  theme: Mode = DEFAULT_THEME,
): number => clipInfo(name, locale, theme)?.frames ?? 0;

/** A clip's frame rate (DEFAULT_CLIP_FPS when the manifest names none, or for unknown names). */
export const clipFps = (
  name: string,
  locale: Locale = DEFAULT_LOCALE,
  theme: Mode = DEFAULT_THEME,
): number => clipInfo(name, locale, theme)?.fps ?? DEFAULT_CLIP_FPS;

/** Frames of clip `name` (at its own rate) as design frames: 120 frames of a 60 fps clip are 60. */
export const clipToDesignFrames = (
  name: string,
  clipFrame: number,
  locale: Locale = DEFAULT_LOCALE,
  theme: Mode = DEFAULT_THEME,
): number => {
  const fps = clipFps(name, locale, theme);
  return fps === DESIGN_FPS ? clipFrame : (clipFrame * DESIGN_FPS) / fps;
};

/**
 * How long a clip plays, in design frames (0 for placeholders): a scene playing it from clip time
 * 0 reaches its last frame at clipDuration − 1 (at 30 fps; a little later for a faster clip).
 */
export const clipDuration = (
  name: string,
  locale: Locale = DEFAULT_LOCALE,
  theme: Mode = DEFAULT_THEME,
): number => clipToDesignFrames(name, clipFrames(name, locale, theme), locale, theme);

/**
 * The frame index of clip `name` shown at clip time `designFrame` (design frames since the
 * clip's frame 0, fractional at 60 fps): the clip's frame at that moment, clamped to
 * [0, frames - 1].
 */
export const clipFrameIndex = (
  name: string,
  designFrame: number,
  locale: Locale = DEFAULT_LOCALE,
  theme: Mode = DEFAULT_THEME,
): number => {
  const c = clipInfo(name, locale, theme);
  if (!c || c.frames <= 0) return 0;
  const fps = c.fps ?? DEFAULT_CLIP_FPS;
  const at = fps === DESIGN_FPS ? designFrame : (designFrame * fps) / DESIGN_FPS;
  // The epsilon keeps a frame that lands exactly on a clip frame (2 × 0.5) from rounding down.
  return Math.min(c.frames - 1, Math.max(0, Math.floor(at + 1e-6)));
};

/**
 * Path (relative to public/) of the still to show for `name`: the manifest still, else a clip's
 * fallback, else `stills/<name>.png`.
 */
export const stillPath = (
  name: string,
  locale: Locale = DEFAULT_LOCALE,
  theme: Mode = DEFAULT_THEME,
): string =>
  stillInfo(name, locale, theme)?.path ??
  clipInfo(name, locale, theme)?.fallback ??
  `stills/${name}.png`;

/**
 * Path of the image for clip `name` at clip time `designFrame` (design frames since its frame 0;
 * clipFrameIndex picks the clip's frame, clamped to [0, frames - 1]). Falls back to the clip's
 * fallback still (or the still of the same name) while it has no frames.
 */
export const clipFramePath = (
  name: string,
  designFrame: number,
  locale: Locale = DEFAULT_LOCALE,
  theme: Mode = DEFAULT_THEME,
): string => {
  const c = clipInfo(name, locale, theme);
  if (!c || c.frames <= 0) return c?.fallback ?? stillPath(name, locale, theme);
  return `${c.path}/${pad4(clipFrameIndex(name, designFrame, locale, theme))}.${c.ext ?? DEFAULT_CLIP_EXT}`;
};

/** Pixel size of an asset (clip or still), whatever its density; 2560×1600 when unknown. */
export const assetSize = (
  name: string,
  locale: Locale = DEFAULT_LOCALE,
  theme: Mode = DEFAULT_THEME,
): {width: number; height: number} => {
  const e = clipInfo(name, locale, theme) ?? stillInfo(name, locale, theme);
  return e ? {width: e.width, height: e.height} : {width: 2560, height: 1600};
};

/**
 * px per dp of an asset (clip or still): the manifest's `density`, DEFAULT_ASSET_DENSITY (2)
 * when it names none or the asset is unknown.
 */
export const assetDensity = (
  name: string,
  locale: Locale = DEFAULT_LOCALE,
  theme: Mode = DEFAULT_THEME,
): number =>
  (clipInfo(name, locale, theme) ?? stillInfo(name, locale, theme))?.density ??
  DEFAULT_ASSET_DENSITY;

/**
 * Source px of asset `name` per reference px (px at 2 px per dp, the unit of the constants
 * measured on the assets): density / REFERENCE_DENSITY, exactly 1 for an asset at 2 px per dp.
 * Multiply a hand-measured length by it to get the asset's px; divide a camera scale (viewport px
 * per source px) measured on a 2 px per dp asset by it, so the asset shows at the same size.
 */
export const assetRefScale = (
  name: string,
  locale: Locale = DEFAULT_LOCALE,
  theme: Mode = DEFAULT_THEME,
): number => assetDensity(name, locale, theme) / REFERENCE_DENSITY;

/**
 * Size of an asset in reference px (px at 2 px per dp): what assetSize was when the constants
 * were measured, whatever the asset's density (2560×1600 for a 1280×800 dp window).
 */
export const assetRefSize = (
  name: string,
  locale: Locale = DEFAULT_LOCALE,
  theme: Mode = DEFAULT_THEME,
): {width: number; height: number} => {
  const {width, height} = assetSize(name, locale, theme);
  const k = assetRefScale(name, locale, theme);
  return {width: width / k, height: height / k};
};

/** width / height of an asset. */
export const assetAspect = (
  name: string,
  locale: Locale = DEFAULT_LOCALE,
  theme: Mode = DEFAULT_THEME,
): number => {
  const s = assetSize(name, locale, theme);
  return s.width / s.height;
};

/**
 * Where the UI element `key` is on asset `name`, in fractions of the asset (see NormRect), as
 * the asset pipeline measured it; `fallback` (also in fractions) when the manifest has no such
 * bounds, e.g. before the pipeline records them. Bounds come from the same entry as the image,
 * so a locale that falls back to the English asset also gets the English bounds.
 */
export const assetBounds = (
  name: string,
  key: string,
  fallback: NormRect,
  locale: Locale = DEFAULT_LOCALE,
  theme: Mode = DEFAULT_THEME,
): NormRect =>
  (clipInfo(name, locale, theme) ?? stillInfo(name, locale, theme))?.bounds?.[key] ?? fallback;

/**
 * assetBounds in source pixels of the asset (the units of Camera rects and px crops). For rects
 * combined with hand-measured constants, assetBoundsRef gives reference px.
 */
export const assetBoundsPx = (
  name: string,
  key: string,
  fallback: NormRect,
  locale: Locale = DEFAULT_LOCALE,
  theme: Mode = DEFAULT_THEME,
): NormRect => {
  const b = assetBounds(name, key, fallback, locale, theme);
  const {width, height} = assetSize(name, locale, theme);
  return {x: b.x * width, y: b.y * height, w: b.w * width, h: b.h * height};
};

/**
 * assetBounds in reference px of the asset (px at 2 px per dp, see assetRefScale): the units of
 * the hand-measured rects, insets and radii it is combined with. Crops built from them use
 * assetRefSize, so they stay right at any density.
 */
export const assetBoundsRef = (
  name: string,
  key: string,
  fallback: NormRect,
  locale: Locale = DEFAULT_LOCALE,
  theme: Mode = DEFAULT_THEME,
): NormRect => {
  const b = assetBounds(name, key, fallback, locale, theme);
  const {width, height} = assetRefSize(name, locale, theme);
  return {x: b.x * width, y: b.y * height, w: b.w * width, h: b.h * height};
};

/**
 * When event `key` happens on clip `name` (e.g. when the Discover results appear), as clip time
 * in design frames: the clip frame the asset pipeline recorded, converted from the clip's rate;
 * `fallback` (design frames) when the manifest has no such event. Scenes time their camera and
 * copy against these instead of frame numbers read off a render.
 */
export const clipEvent = (
  name: string,
  key: string,
  fallback: number,
  locale: Locale = DEFAULT_LOCALE,
  theme: Mode = DEFAULT_THEME,
): number => {
  const at = clipInfo(name, locale, theme)?.events?.[key];
  return at === undefined ? fallback : clipToDesignFrames(name, at, locale, theme);
};

// ---------------------------------------------------------------------------------------------
// Clip lengths

/** A clip the scenes play for a number of frames that timing.json sets. */
export interface ClipNeed {
  /** The clip in every theme (`desktop`): each film plays its own (themedAsset). */
  clip: string;
  /** Design frames the scenes play of it. */
  frames: number;
  /** Which scenes, and how. */
  playedBy: string;
}

/**
 * Clips that scenes play for as long as they last, so a longer scene needs a longer clip; each
 * film plays the one of its theme. The clips' lengths are set where they are rendered, in
 * PromoSnapshots.kt (DESKTOP_FRAMES, DISCOVER_FRAMES): when one of these scenes grows (`npm run
 * retime`), raise its clip there and re-render it in both themes (`npm run assets -- all
 * --theme all`). A clip that is too short freezes on its last frame (clipFramePath clamps),
 * which nothing else would show; Root warns about it. The phone clips (PHONE_FRAMES, 360 at
 * 60 fps) only play during S06's first beat, which no retime lengthens.
 */
export const CLIP_NEEDS: readonly ClipNeed[] = [
  {
    clip: 'desktop',
    frames: scene('S02').len + scene('S03').len,
    playedBy: 'S02 and S03 play it end to end (desktopClipFrame in timing.ts)',
  },
  {
    clip: 'discover',
    frames: scene('S08').len,
    playedBy: 'S08 plays it through the scene',
  },
];

/**
 * The clips of `locale` (or the English ones it falls back to) in the film in `theme` that are
 * shorter than CLIP_NEEDS asks, as messages; clips that are not rendered yet are left out.
 */
export const clipLengthProblems = (
  locale: Locale = DEFAULT_LOCALE,
  theme: Mode = DEFAULT_THEME,
): string[] =>
  CLIP_NEEDS.flatMap(({clip: base, frames, playedBy}) => {
    const clip = themedAsset(base, theme);
    const info = clipInfo(clip, locale, theme);
    const plays = clipDuration(clip, locale, theme);
    if (!info || info.frames <= 0 || plays >= frames) return [];
    const fps = clipFps(clip, locale, theme);
    const has =
      fps === DESIGN_FPS ? `${info.frames} frames` : `${plays} (${info.frames} at ${fps} fps)`;
    return [
      `${info.path} has ${has}, but ${playedBy}: ${frames} frames at ${DESIGN_FPS} fps. It freezes on its ` +
        'last frame until it is rendered longer: raise its length in PromoSnapshots.kt and run ' +
        'npm run assets (README.md, "Change a scene\'s length").',
    ];
  });

/** The helpers above, bound to one locale and the film's theme. */
export interface AssetResolver {
  locale: Locale;
  /** The film's theme, whose manifests the helpers read first. */
  theme: Mode;
  /** The name of asset `base` in `theme`, the film's by default: `desktop` → `desktop-light`. */
  themed: (base: string, theme?: Mode) => string;
  clipInfo: (name: string) => Resolved<ClipEntry> | undefined;
  stillInfo: (name: string) => Resolved<StillEntry> | undefined;
  hasClipFrames: (name: string) => boolean;
  clipFrames: (name: string) => number;
  clipFps: (name: string) => number;
  clipDuration: (name: string) => number;
  clipToDesignFrames: (name: string, clipFrame: number) => number;
  clipFrameIndex: (name: string, designFrame: number) => number;
  stillPath: (name: string) => string;
  clipFramePath: (name: string, designFrame: number) => string;
  assetSize: (name: string) => {width: number; height: number};
  assetDensity: (name: string) => number;
  assetRefScale: (name: string) => number;
  assetRefSize: (name: string) => {width: number; height: number};
  assetAspect: (name: string) => number;
  assetBounds: (name: string, key: string, fallback: NormRect) => NormRect;
  assetBoundsPx: (name: string, key: string, fallback: NormRect) => NormRect;
  assetBoundsRef: (name: string, key: string, fallback: NormRect) => NormRect;
  clipEvent: (name: string, key: string, fallback: number) => number;
}

/** The asset helpers bound to `locale` and the film in `theme` (for code outside components). */
export const assetsFor = (locale: Locale, theme: Mode = DEFAULT_THEME): AssetResolver => ({
  locale,
  theme,
  themed: (base, t = theme) => themedAsset(base, t),
  clipInfo: (name) => clipInfo(name, locale, theme),
  stillInfo: (name) => stillInfo(name, locale, theme),
  hasClipFrames: (name) => hasClipFrames(name, locale, theme),
  clipFrames: (name) => clipFrames(name, locale, theme),
  clipFps: (name) => clipFps(name, locale, theme),
  clipDuration: (name) => clipDuration(name, locale, theme),
  clipToDesignFrames: (name, clipFrame) => clipToDesignFrames(name, clipFrame, locale, theme),
  clipFrameIndex: (name, designFrame) => clipFrameIndex(name, designFrame, locale, theme),
  stillPath: (name) => stillPath(name, locale, theme),
  clipFramePath: (name, designFrame) => clipFramePath(name, designFrame, locale, theme),
  assetSize: (name) => assetSize(name, locale, theme),
  assetDensity: (name) => assetDensity(name, locale, theme),
  assetRefScale: (name) => assetRefScale(name, locale, theme),
  assetRefSize: (name) => assetRefSize(name, locale, theme),
  assetAspect: (name) => assetAspect(name, locale, theme),
  assetBounds: (name, key, fallback) => assetBounds(name, key, fallback, locale, theme),
  assetBoundsPx: (name, key, fallback) => assetBoundsPx(name, key, fallback, locale, theme),
  assetBoundsRef: (name, key, fallback) => assetBoundsRef(name, key, fallback, locale, theme),
  clipEvent: (name, key, fallback) => clipEvent(name, key, fallback, locale, theme),
});

const RESOLVERS = Object.fromEntries(
  LOCALES.map((l) => [l, Object.fromEntries(FILM_THEMES.map((t) => [t, assetsFor(l, t)]))]),
) as Record<Locale, Record<Mode, AssetResolver>>;

/**
 * The asset helpers bound to the current locale and the film's theme (a hook: call it in a
 * component).
 */
export const useAssets = (): AssetResolver => RESOLVERS[useLocale()][useTheme()];
