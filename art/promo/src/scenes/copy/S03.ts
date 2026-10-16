// S03 Parallel lanes: every word the scene shows, per locale. The scene file only lays it out
// and times it, so copy changes happen here. The connection limit comes from src/facts.json.
//
// Line breaks (`\n`) are part of the copy. The text sits in a narrow left column (sub 600 px)
// beside the lanes, and the chips stack under it from a fixed top, so keep the sub to two lines
// and check a still after changing it (README.md, "Single frames").
import {FACTS} from '../../facts';
import {defineCopy} from '../../locale';

export interface S03Strings {
  eyebrow: string;
  headline: string;
  sub: string;
  /** Two chips, one per beat, stacked under the sub. */
  chips: readonly [string, string];
}

export const S03_COPY = defineCopy<S03Strings>({
  en: {
    eyebrow: 'Parallel connections',
    headline: 'One file.\nMany lanes.',
    sub:
      `Up to ${FACTS.maxConnections} connections per download, ` +
      'each one a live lane you can watch.',
    chips: ['Change connections mid-download', 'Resumes after a restart'],
  },
  'zh-Hans': {
    eyebrow: '多连接下载',
    headline: '一个文件，\n多条线一起下。',
    // break after the limit, so each line is one thought
    sub: `单个下载最多 ${FACTS.maxConnections} 个连接，\n` + '每条线跑多快，一眼就能看到。',
    chips: ['下载中途也能加减连接', '重启之后接着下'],
  },
});
