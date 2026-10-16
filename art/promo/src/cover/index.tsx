// Entry point for the upload covers (kept apart from the film's root so it renders on its own),
// one per format, locale and film theme (`Cover-wide-en`, `Cover-tall-zh-Hans-light`, …):
//   npx remotion still src/cover/index.tsx Cover-wide-en out/covers/Cover-wide-en.png --scale=2
import React from 'react';
import {Composition, registerRoot} from 'remotion';
import {LOCALES} from '../locale';
import {FILM_THEMES, themedId} from '../theme-context';
import {Cover, type CoverFormat} from './Cover';

const SIZES: Record<CoverFormat, {width: number; height: number}> = {
  wide: {width: 1920, height: 1080},
  tall: {width: 1080, height: 1440},
};

const CoverRoot: React.FC = () => (
  <>
    {FILM_THEMES.flatMap((theme) =>
      LOCALES.flatMap((locale) =>
        (Object.keys(SIZES) as CoverFormat[]).map((format) => (
          <Composition
            key={`${theme}-${format}-${locale}`}
            id={themedId(`Cover-${format}-${locale}`, theme)}
            component={Cover}
            durationInFrames={1}
            fps={30}
            {...SIZES[format]}
            defaultProps={{locale, format, theme}}
          />
        )),
      ),
    )}
  </>
);

registerRoot(CoverRoot);
