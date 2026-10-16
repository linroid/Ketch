// S02 Modern UI: every word the scene shows, per locale. The scene file only lays it out and
// times it, so copy changes happen here.
//
// Line breaks (`\n`) are part of the copy: the headline breaks between its two sentences in
// every locale. The sub wraps on its own in the 780 px column (two lines in English, one in
// Chinese); the accent pill sits under it whatever its height.
//
// The sub names the accents with the app's own word for them in each language (Settings →
// Appearance → "Accent color", `settings_general_accent`: 强调色 in Chinese, not 主题色), since the
// pill under it shows those Settings names.
import {defineCopy} from '../../locale';
import type {AccentName} from '../../theme';

export interface S02Strings {
  eyebrow: string;
  headline: string;
  sub: string;
  /**
   * The accent pill's names, as the app's Settings → Appearance shows them in this language
   * (`settings_accent_*` in app/shared's composeResources); the colours come from theme.ts.
   */
  accents: Record<AccentName, string>;
}

export const S02_COPY = defineCopy<S02Strings>({
  en: {
    eyebrow: 'Modern UI',
    headline: 'Calm on the surface.\nFast underneath.',
    sub: 'Light and dark themes, four accents, and a layout made for every screen.',
    accents: {signal: 'Signal', harbor: 'Harbor', fathom: 'Fathom', beacon: 'Beacon'},
  },
  'zh-Hans': {
    eyebrow: '现代界面',
    headline: '看着清爽，\n跑起来飞快。',
    sub: '深色浅色随你切，四种强调色，大屏小屏都好用。',
    accents: {signal: '信号', harbor: '港湾', fathom: '深海', beacon: '灯塔'},
  },
});
