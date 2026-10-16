// S10 Light: every word the scene shows, per locale. The sizes are not copy: they come from
// src/facts.json (refreshed from the latest release, see src/facts.ts), so a new release only
// changes that file. The scene file picks the card order and the highlight.
import {FACTS, type Sizes} from '../../facts';
import {defineCopy} from '../../locale';

/** What one download-size card says under its number. */
export interface SizeCardStrings {
  /** What the download is. */
  label: string;
  /** Optional qualifier, shown after a middle dot in a quieter colour. */
  note?: string;
}

export interface S10Strings {
  eyebrow: string;
  headline: string;
  /** One card per release download; the number on each comes from FACTS.sizes. */
  cards: Record<keyof Sizes, SizeCardStrings>;
  /**
   * The chips under the cards. STORYBOARD.md: never a bare "No dependencies" or "No Java"; the
   * torrent engine is pure Kotlin, but the JVM build still uses JNA for file-system calls, so no
   * "no native libraries" either.
   */
  chips: readonly string[];
  /** Tertiary footnote naming where the sizes come from (binary units, as GitHub shows them). */
  footnote: string;
}

export const S10_COPY = defineCopy<S10Strings>({
  en: {
    eyebrow: 'Lightweight',
    headline: 'Light. Self-contained.',
    cards: {
      extension: {label: 'Browser extension'},
      android: {label: 'Android app'},
      cli: {label: 'Command line', note: 'one native binary'},
      desktop: {label: 'Desktop app', note: 'runtime included'},
    },
    chips: ['No dependencies to install', 'Pure-Kotlin torrent engine'],
    footnote: `Download sizes, v${FACTS.release}`,
  },
  'zh-Hans': {
    eyebrow: '轻量',
    headline: '小巧，开箱即用。',
    cards: {
      extension: {label: '浏览器扩展'},
      android: {label: 'Android 应用'},
      // The point is one self-contained file; a literal 一个原生程序 says almost nothing.
      cli: {label: '命令行', note: '单文件原生程序'},
      desktop: {label: '桌面应用', note: '自带运行环境'},
    },
    chips: ['不用装任何依赖', '种子引擎纯 Kotlin 编写'],
    footnote: `v${FACTS.release} 下载大小`,
  },
});
