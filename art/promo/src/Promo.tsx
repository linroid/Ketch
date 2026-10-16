// The whole film in one locale and theme: every scene in its own Sequence, lane wipes above the
// cuts that get one, and the music bed when MUSIC is on. Root registers it once per locale, theme
// and frame rate (`Promo`, `Promo-zh-Hans` at 30 fps; `Promo-60`, `Promo-60-zh-Hans` at 60; the
// light film's `Promo-light`, `Promo-zh-Hans-light`, …); scenes read the locale and the theme
// through LocaleRoot. Timing is in design frames (timing.json, 30 fps): DesignSequence
// places each scene on the output frames of the composition's rate (src/time.tsx).
import React from 'react';
import {staticFile} from 'remotion';
import {Audio} from '@remotion/media';
import {LaneWipe} from './components/LaneWipe';
import {MUSIC} from './config';
import {LocaleRoot, type Locale} from './locale';
import {SCENE_COMPONENTS} from './scenes';
import {palette, type Mode} from './theme';
import {DesignSequence} from './time';
import {SCENES, WIPES, WIPE_LEN} from './timing';

export type PromoProps = {
  locale: Locale;
  /** The film's theme: dark (the default) or light (theme-context.tsx). */
  theme?: Mode;
  /**
   * Output frame rate, a multiple of timing.json's 30 (default 30). Root's calculateMetadata
   * sets the composition's fps and length from it; the picture is timed the same at any rate.
   */
  fps?: number;
};

export const Promo: React.FC<PromoProps> = ({locale, theme = 'dark'}) => (
  <LocaleRoot locale={locale} theme={theme} style={{backgroundColor: palette(theme).canvas}}>
    {SCENES.map(({id, start, len}) => {
      const Scene = SCENE_COMPONENTS[id];
      return (
        <DesignSequence key={id} name={id} from={start} durationInFrames={len}>
          <Scene />
        </DesignSequence>
      );
    })}
    {WIPES.map((cut) => (
      <DesignSequence
        key={`wipe-${cut}`}
        name={`Lane wipe ${cut}`}
        from={cut - WIPE_LEN / 2}
        durationInFrames={WIPE_LEN}
      >
        <LaneWipe at={WIPE_LEN / 2} />
      </DesignSequence>
    ))}
    {MUSIC && <Audio src={staticFile('audio/music.wav')} />}
  </LocaleRoot>
);
