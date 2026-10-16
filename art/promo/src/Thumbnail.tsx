// The films' cover art and thumbnail: the first frame of `Promo` / `Promo-<locale>` (and of the
// light film, `Promo-light` / `Promo-<locale>-light`), by construction. It renders the film's first scene (SCENES[0], S01 Intro) frozen at its frame 0,
// inside the same LocaleRoot and canvas colour as Promo, so the picture every player shows before
// playback (the first frame) and the cover art scripts/render-film.sh embeds in the mp4 are one
// image. Root registers it once per locale and theme as a 1-frame composition: `Thumbnail`,
// `Thumbnail-zh-Hans`, `Thumbnail-light`, `Thumbnail-zh-Hans-light`. To change the cover, change S01's frame 0 (src/scenes/S01Intro.tsx).
import React from 'react';
import {Freeze} from 'remotion';
import {LocaleRoot, type Locale} from './locale';
import {SCENE_COMPONENTS} from './scenes';
import {palette, type Mode} from './theme';
import {SCENES} from './timing';

export type ThumbnailProps = {locale: Locale; theme?: Mode};

/** The film's opening scene; Promo starts it at global frame 0. */
const FIRST = SCENES[0];

export const Thumbnail: React.FC<ThumbnailProps> = ({locale, theme = 'dark'}) => {
  const Scene = SCENE_COMPONENTS[FIRST.id];
  return (
    <LocaleRoot locale={locale} theme={theme} style={{backgroundColor: palette(theme).canvas}}>
      <Freeze frame={0}>
        <Scene />
      </Freeze>
    </LocaleRoot>
  );
};
