// The languages the film is built in, and how scenes read the current one.
//
// `Promo` and every scene composition render inside a LocaleRoot (Root.tsx registers one
// composition per locale: `Promo`, `Promo-zh-Hans`, `S01`, `S01-zh-Hans`, …). Scenes read their
// copy with `useCopy(copy)` and the locale with `useLocale()`; ClipPlayer and StillImage pick the
// locale's real-UI assets on their own (see assets.ts).
//
// Adding a language: add it to LOCALES and LOCALE_INFO, give every src/scenes/copy/<SceneId>.ts
// an entry for it (the compiler lists the missing ones), render its assets and, for a script
// Inter does not cover, check the fallback font in theme.ts (FONT). README.md has the recipe.
import React, {createContext, useContext} from 'react';
import {AbsoluteFill} from 'remotion';
import {FONT, type Mode} from './theme';
import {DEFAULT_THEME, ThemeProvider} from './theme-context';

/** Every locale the film is built in. The first is the default (bare composition ids). */
export const LOCALES = ['en', 'zh-Hans'] as const;
export type Locale = (typeof LOCALES)[number];
export const DEFAULT_LOCALE: Locale = 'en';
/** Locales other than the default. */
export type OtherLocale = Exclude<Locale, typeof DEFAULT_LOCALE>;

export interface LocaleInfo {
  /** BCP 47 tag for the `lang` attribute (picks the right glyph forms) and Intl APIs. */
  tag: string;
  /** The language's name in itself. */
  name: string;
  /**
   * Written in Han, kana or Hangul: the root turns off faux bold (`font-synthesis: none`; the
   * system CJK faces stop at Semibold). The Text components handle CJK runs by their content.
   */
  cjk: boolean;
}

export const LOCALE_INFO: Record<Locale, LocaleInfo> = {
  en: {tag: 'en', name: 'English', cjk: false},
  'zh-Hans': {tag: 'zh-Hans', name: '简体中文', cjk: true},
};

/** Narrows a string (a CLI argument, a composition prop) to a Locale. */
export const isLocale = (s: string): s is Locale => (LOCALES as readonly string[]).includes(s);

/** True for locales written in Han, kana or Hangul. */
export const isCJK = (locale: Locale): boolean => LOCALE_INFO[locale].cjk;

/** Composition id for a locale: the default keeps `id`, others get a suffix (`Promo-zh-Hans`). */
export const localizedId = (id: string, locale: Locale): string =>
  locale === DEFAULT_LOCALE ? id : `${id}-${locale}`;

// ---------------------------------------------------------------------------------------------
// Context

const LocaleContext = createContext<Locale>(DEFAULT_LOCALE);

/** Provides the locale to everything below. Without one, components see the default locale. */
export const LocaleProvider: React.FC<{locale: Locale; children: React.ReactNode}> = ({
  locale,
  children,
}) => <LocaleContext.Provider value={locale}>{children}</LocaleContext.Provider>;

/** The locale being rendered. */
export const useLocale = (): Locale => useContext(LocaleContext);

/**
 * Root style for a locale. Nothing for Latin locales (so English renders exactly as it did
 * before locales existed); `font-synthesis: none` for CJK, inherited by every text element.
 */
export const localeRootStyle = (locale: Locale): React.CSSProperties =>
  isCJK(locale) ? {fontSynthesis: 'none'} : {};

/**
 * A full-frame root for a composition in `locale` and the film's `theme` (dark by default,
 * theme-context.tsx): provides both, sets `lang` and the locale's root style, and the sans font
 * (headless Chrome falls back to Times otherwise).
 */
export const LocaleRoot: React.FC<{
  locale: Locale;
  theme?: Mode;
  children: React.ReactNode;
  style?: React.CSSProperties;
}> = ({locale, theme = DEFAULT_THEME, children, style}) => (
  <LocaleProvider locale={locale}>
    <ThemeProvider theme={theme}>
      <AbsoluteFill
        lang={LOCALE_INFO[locale].tag}
        style={{fontFamily: FONT.sans, ...localeRootStyle(locale), ...style}}
      >
        {children}
      </AbsoluteFill>
    </ThemeProvider>
  </LocaleProvider>
);

// ---------------------------------------------------------------------------------------------
// Copy

/** One value per locale, e.g. a scene's strings. */
export type LocalizedCopy<T> = {readonly [L in Locale]: T};

/**
 * Declares a scene's copy. The English entry defines the shape; every other locale must have
 * exactly the same keys (a missing or extra key is a compile error):
 *
 * ```ts
 * export const S03_COPY = defineCopy({
 *   en: {
 *     headline: 'One file.\nMany lanes.',
 *     sub: `Up to ${FACTS.maxConnections} connections per download…`,
 *   },
 *   'zh-Hans': {
 *     headline: '一个文件，\n多条线一起下。',
 *     sub: `单个下载最多 ${FACTS.maxConnections} 个连接…`,
 *   },
 * });
 * ```
 *
 * Pass the type explicitly (`defineCopy<S03Copy>({...})`) to pin tuple lengths or literal types.
 */
export const defineCopy = <T extends object>(
  copy: {readonly en: T} & {readonly [L in OtherLocale]: NoInfer<T>},
): LocalizedCopy<T> => copy;

/** The current locale's entry of `copy` (a hook: call it in a component). */
export const useCopy = <T,>(copy: LocalizedCopy<T>): T => copy[useLocale()];

/** `copy` for a given locale, outside components (e.g. in timing maths at module level). */
export const copyFor = <T,>(copy: LocalizedCopy<T>, locale: Locale): T => copy[locale];
