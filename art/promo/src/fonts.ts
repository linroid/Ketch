// Loads the bundled fonts (public/fonts). loadFont() holds rendering (delayRender) until each
// face is ready, so importing this module once (Root.tsx does) is enough for every composition.
//
// CJK text is not bundled: the stacks in theme.ts (FONT) fall back to the system's PingFang SC
// (and Hiragino Sans, Apple SD Gothic Neo), which needs no loading on macOS. To render a CJK
// locale elsewhere, add a CJK face here (e.g. Noto Sans SC, OFL: one file per weight, 400–700)
// and put its family in CJK_FALLBACK.
import {loadFont} from '@remotion/fonts';
import {staticFile} from 'remotion';

const faces: {family: string; file: string; weight: string}[] = [
  {family: 'Inter', file: 'inter_regular.ttf', weight: '400'},
  {family: 'Inter', file: 'inter_medium.ttf', weight: '500'},
  {family: 'Inter', file: 'inter_semibold.ttf', weight: '600'},
  {family: 'Inter Display', file: 'interdisplay_semibold.ttf', weight: '600'},
  {family: 'Inter Display', file: 'interdisplay_bold.ttf', weight: '700'},
  {family: 'JetBrains Mono', file: 'jetbrainsmono_regular.ttf', weight: '400'},
];

/** Resolves once every face is in `document.fonts`. */
export const fontsReady: Promise<void> = Promise.all(
  faces.map((f) =>
    loadFont({
      family: f.family,
      url: staticFile(`fonts/${f.file}`),
      weight: f.weight,
      style: 'normal',
    }),
  ),
).then(() => undefined);
