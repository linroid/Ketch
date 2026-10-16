// Registers the compositions, once per locale and film theme (ids from localizedId and
// themedId: `Promo` / `Promo-zh-Hans` / `Promo-light` / `Promo-zh-Hans-light`):
//   Promo        the film, 30 fps (1080p30)
//   Promo-60     the film at 60 fps (`Promo-60`, `Promo-60-zh-Hans`, `Promo-60-light`, …),
//                rendered at 4K with --scale=2 (scripts/render-film.sh --4k60); the same timing,
//                twice the frames
//   Thumbnail    its first frame (S01 frozen at 0), 1 frame: the cover art render-film.sh embeds
//   S01 … S12    one scene alone, frames local to the scene (folders "Scenes", "Scenes-zh-Hans",
//                "Scenes-light", "Scenes-zh-Hans-light")
//   Kit          demo of the shared components
// The locale, the theme and the frame rate are props, so `--props='{"locale":"zh-Hans",
// "theme":"light","fps":60}'` also switches them: every composition but Thumbnail takes its fps
// and length from `fps` (rateMetadata in time.tsx; frames are then output frames, twice the
// design frames at 60). The dark film is the default theme (theme-context.tsx).
import React from 'react';
import {Composition, Folder} from 'remotion';
import {clipLengthProblems} from './assets';
import './fonts';
import {KIT_LENGTH, Kit} from './Kit';
import {LOCALES, LocaleRoot, localizedId, type Locale} from './locale';
import {Promo} from './Promo';
import {SCENE_COMPONENTS} from './scenes';
import type {Mode} from './theme';
import {DEFAULT_THEME, FILM_THEMES, themedId} from './theme-context';
import {Thumbnail} from './Thumbnail';
import {DESIGN_FPS, FILM_RATES, rateId, rateMetadata} from './time';
import {HEIGHT, SCENES, TOTAL, WIDTH, type SceneId} from './timing';

/** Size and the design rate; rateMetadata overrides fps and length from the `fps` prop. */
const base = {fps: DESIGN_FPS, width: WIDTH, height: HEIGHT} as const;

// A scene made longer than the clip it plays (CLIP_NEEDS in assets.ts) still renders, frozen on
// the clip's last frame; say so in the Studio's console and in the terminal of every render.
for (const problem of new Set(
  FILM_THEMES.flatMap((t) => LOCALES.flatMap((l) => clipLengthProblems(l, t))),
)) {
  console.warn(`Clip too short: ${problem}`);
}

type SceneProps = {scene: SceneId; locale: Locale; theme?: Mode; fps?: number};

/** One scene alone, in a locale and a theme. */
const SceneComposition: React.FC<SceneProps> = ({scene, locale, theme}) => {
  const Scene = SCENE_COMPONENTS[scene];
  return (
    <LocaleRoot locale={locale} theme={theme}>
      <Scene />
    </LocaleRoot>
  );
};

/** Composition id in a locale and a theme: `Promo-60` → `Promo-60-zh-Hans-light`. */
const id = (name: string, locale: Locale, theme: Mode): string =>
  themedId(localizedId(name, locale), theme);

/**
 * Default props in a locale and a theme: the theme only when it is not the default, so the dark
 * film's props stay as they were.
 */
const props = (locale: Locale, theme: Mode) =>
  theme === DEFAULT_THEME ? {locale} : {locale, theme};

export const RemotionRoot: React.FC = () => (
  <>
    {FILM_THEMES.flatMap((theme) =>
      FILM_RATES.flatMap((fps) =>
        LOCALES.map((locale) => (
          <Composition
            key={`${theme}-${fps}-${locale}`}
            id={id(rateId('Promo', fps), locale, theme)}
            component={Promo}
            durationInFrames={TOTAL}
            defaultProps={
              fps === DESIGN_FPS ? props(locale, theme) : {...props(locale, theme), fps}
            }
            calculateMetadata={rateMetadata(TOTAL)}
            {...base}
          />
        )),
      ),
    )}
    {FILM_THEMES.flatMap((theme) =>
      LOCALES.map((locale) => (
        <Composition
          key={`${theme}-${locale}`}
          id={id('Thumbnail', locale, theme)}
          component={Thumbnail}
          durationInFrames={1}
          defaultProps={props(locale, theme)}
          {...base}
        />
      )),
    )}
    {FILM_THEMES.flatMap((theme) =>
      LOCALES.map((locale) => (
        <Folder key={`${theme}-${locale}`} name={id('Scenes', locale, theme)}>
          {SCENES.map(({id: scene, len}) => (
            <Composition
              key={scene}
              id={id(scene, locale, theme)}
              component={SceneComposition}
              durationInFrames={len}
              defaultProps={{scene, ...props(locale, theme)}}
              calculateMetadata={rateMetadata(len)}
              {...base}
            />
          ))}
        </Folder>
      )),
    )}
    {FILM_THEMES.flatMap((theme) =>
      LOCALES.map((locale) => (
        <Composition
          key={`${theme}-${locale}`}
          id={id('Kit', locale, theme)}
          component={Kit}
          durationInFrames={KIT_LENGTH}
          defaultProps={props(locale, theme)}
          calculateMetadata={rateMetadata(KIT_LENGTH)}
          {...base}
        />
      )),
    )}
  </>
);
