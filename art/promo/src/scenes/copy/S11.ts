// S11 Languages: the copy per locale, and the languages the ticker names (the same in every
// locale: each in its own language). The scene file picks the screenshot and the typeface for
// each language.
import {FACTS} from '../../facts';
import {defineCopy} from '../../locale';

export interface S11Strings {
  /** Two lines, broken with `\n` (the text column, TEXT_W in S11Languages.tsx, is 680 px wide). */
  headline: string;
  /** One line, or two broken with `\n`: it must end above the ticker. */
  sub: string;
}

export const S11_COPY = defineCopy<S11Strings>({
  en: {
    headline: 'Speaks your\nlanguage.',
    sub: `${FACTS.languages} languages, every string translated.`,
  },
  'zh-Hans': {
    headline: '换个语言？\n随便换。',
    sub: `支持 ${FACTS.languages} 种语言，\n界面文字全部翻译到位。`,
  },
});

/** One app language as the ticker shows it. */
export interface TickerLanguage {
  /**
   * BCP 47 tag, as the iOS app's `.lproj` folders name it. The scene shows the still
   * `lang-<tag>-dark` for it, and sets it as the row's `lang` so CJK glyphs take their own forms.
   */
  tag: string;
  /** The language's own name for itself. */
  name: string;
}

/**
 * The app's languages (docs/development/localization.md#languages), in ticker order. The sub
 * states their number from FACTS.languages; the check below keeps the two in step.
 */
export const S11_LANGUAGES: readonly TickerLanguage[] = [
  {tag: 'en', name: 'English'},
  {tag: 'zh-Hans', name: '简体中文'},
  {tag: 'zh-Hant', name: '繁體中文'},
  {tag: 'ja', name: '日本語'},
  {tag: 'ko', name: '한국어'},
  {tag: 'es', name: 'Español'},
  {tag: 'pt-BR', name: 'Português (Brasil)'},
  {tag: 'de', name: 'Deutsch'},
  {tag: 'fr', name: 'Français'},
  {tag: 'ru', name: 'Русский'},
];

if (S11_LANGUAGES.length !== FACTS.languages) {
  throw new Error(
    `S11: the ticker lists ${S11_LANGUAGES.length} languages but facts.json says ${FACTS.languages}; ` +
      'update S11_LANGUAGES or `languages` in src/facts.json',
  );
}
