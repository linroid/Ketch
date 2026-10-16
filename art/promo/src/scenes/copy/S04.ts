// S04 Any link: every word the scene shows, per locale. The scene file only lays it out and times
// it (and picks each chip's icon), so copy changes happen here.
//
// Line breaks (`\n`) are part of the copy, chosen for the scene's text column (TEXT_LAYOUT in
// S04AnyLink.tsx): check a still after changing a headline or the sub (README.md, "Single
// frames").
import {defineCopy} from '../../locale';

/** The kinds of link the chips name, in the order they drop in. */
export const LINK_KINDS = ['http', 'ftp', 'bittorrent', 'magnet', 'torrentFile', 'stream'] as const;
export type LinkKind = (typeof LINK_KINDS)[number];

export interface S04Strings {
  eyebrow: string;
  headline: string;
  sub: string;
  /** One chip per kind of link Ketch opens. Protocol names stay as they are in every locale. */
  links: Record<LinkKind, string>;
}

export const S04_COPY = defineCopy<S04Strings>({
  en: {
    eyebrow: 'Every kind of link',
    headline: 'Any link.\nOne queue.',
    sub: 'Torrents run on a pure-Kotlin engine, with a choice of files.',
    links: {
      http: 'HTTP · HTTPS',
      ftp: 'FTP · FTPS',
      bittorrent: 'BitTorrent v1 · v2',
      magnet: 'Magnet links',
      torrentFile: '.torrent files',
      // Finite, unencrypted HLS and DASH (docs/media.md, since v0.2.1): no live or DRM claims.
      stream: 'HLS · DASH streams',
    },
  },
  'zh-Hans': {
    eyebrow: '各种链接',
    // Two short lines like the English; each fits the column beside the sheet at 84 px.
    headline: '什么链接都行，\n一个队列搞定。',
    sub: '种子用纯 Kotlin 引擎下载，\n要哪些文件，自己挑。',
    links: {
      http: 'HTTP · HTTPS',
      ftp: 'FTP · FTPS',
      bittorrent: 'BitTorrent v1 · v2',
      magnet: '磁力链接',
      torrentFile: '.torrent 文件',
      stream: 'm3u8 · DASH 视频流',
    },
  },
});
