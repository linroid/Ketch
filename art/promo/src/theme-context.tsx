// The film's theme, and how scenes read it. The film is made in two themes, by one switch: the
// dark film (the default: `Promo`, `S01`, `Thumbnail`, …) and the light one (`Promo-light`,
// `S01-light`, `Thumbnail-light`, …, Root.tsx). The theme is a prop of every composition
// (`{"theme": "light"}`), which LocaleRoot provides to everything below it.
//
// - Colours: `usePalette()` is the film's palette (theme.ts, DARK or LIGHT); `useThemed({dark,
//   light})` picks a value that differs by more than a palette token (a shadow's strength, a
//   glow that suits one canvas only).
// - Assets: `useAssets()` (assets.ts) resolves the film's theme's manifests, and `themed(base)`
//   names the asset of the app in a theme (`desktop` → `desktop-dark` / `desktop-light`).
// - S02's beat turns the film to the other theme (`otherMode`) and back, blending with
//   `lightMix` (0 = dark, 1 = light, theme.ts).
//
// Components default to the film's theme; a component that is the same in every film (the
// terminal, a dark window in both) says so with an explicit `mode`.
import React, {createContext, useContext} from 'react';
import {palette, type Mode, type Palette} from './theme';

/** The themes the film is made in. The first is the default (bare composition ids). */
export const FILM_THEMES = ['dark', 'light'] as const satisfies readonly Mode[];
export const DEFAULT_THEME: Mode = 'dark';

/** Narrows a string (a CLI argument, a composition prop) to a theme. */
export const isTheme = (s: string): s is Mode => (FILM_THEMES as readonly string[]).includes(s);

/**
 * Composition id in a theme: the default keeps `id`, another adds a suffix after the locale's
 * (`Promo-light`, `Promo-zh-Hans-light`, `Promo-60-zh-Hans-light`).
 */
export const themedId = (id: string, theme: Mode): string =>
  theme === DEFAULT_THEME ? id : `${id}-${theme}`;

const ThemeContext = createContext<Mode>(DEFAULT_THEME);

/** Provides the film's theme to everything below. Without one, components see the default. */
export const ThemeProvider: React.FC<{theme: Mode; children: React.ReactNode}> = ({
  theme,
  children,
}) => <ThemeContext.Provider value={theme}>{children}</ThemeContext.Provider>;

/** The film's theme. */
export const useTheme = (): Mode => useContext(ThemeContext);

/**
 * The palette of `mode`, the film's theme by default, blended by `lightMix` when given
 * (theme.ts, `palette`).
 */
export const usePalette = (mode?: Mode, lightMix?: number): Palette => {
  const theme = useTheme();
  return palette(mode ?? theme, lightMix);
};

/** The film's theme's entry of `values`: for looks that differ by more than a palette token. */
export const useThemed = <T,>(values: Readonly<Record<Mode, T>>): T => values[useTheme()];
