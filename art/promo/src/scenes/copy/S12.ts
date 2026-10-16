// S12 Open source + end card: every word the scene shows, per locale. The scene file only lays
// it out and times it, so copy changes happen here. The end card repeats the intro's wordmark
// and tagline, which live in ./S01.ts.
//
// The end card never shows linroid.com/Ketch (the owner's request): its only address is the
// repository.
import {FACTS} from '../../facts';
import {defineCopy} from '../../locale';
import {S01_COPY, S01_SHARED} from './S01';

export interface S12Strings {
  /** Beat A: the open-source statement. */
  eyebrow: string;
  headline: string;
  chips: readonly string[];
  /** Beat B, the end card: the tagline under the wordmark, the intro's (edit it in ./S01.ts). */
  tagline: string;
  /**
   * The end card's foot: every place Ketch runs, one line. Product names stay as they are; the
   * ordinary words (Web, CLI) take the words S06 uses for the same surfaces. iOS builds from
   * source; the film says so in S06.
   */
  platforms: string;
}

export const S12_COPY = defineCopy<S12Strings>({
  en: {
    eyebrow: 'Open source',
    headline: 'Free and open source.',
    chips: ['Apache-2.0', 'Kotlin Multiplatform', `${FACTS.tests} tests`, 'SDK on Maven Central'],
    tagline: S01_COPY.en.tagline,
    platforms: 'macOS · Windows · Linux · Android · iOS · Web · CLI',
  },
  'zh-Hans': {
    eyebrow: '开源',
    headline: '免费，而且开源。',
    // A library is 发布到 Maven Central; 上架 is app-store wording.
    chips: [
      'Apache-2.0',
      'Kotlin Multiplatform',
      `${FACTS.tests} 个测试`,
      'SDK 已发布到 Maven Central',
    ],
    tagline: S01_COPY['zh-Hans'].tagline,
    platforms: 'macOS · Windows · Linux · Android · iOS · 网页版 · 命令行',
  },
});

/** End-card text that is the same in every locale. */
export const S12_SHARED = {
  /** The app's name, as the wordmark (the intro's). */
  wordmark: S01_SHARED.wordmark,
  /** The one address on the end card. */
  url: 'GitHub: linroid/Ketch',
} as const;
