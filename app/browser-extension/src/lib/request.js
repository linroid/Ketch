/** Builds the `DownloadRequest` bodies sent to Ketch from what the browser knows. */

const LINK_SCHEMES = ['http:', 'https:', 'ftp:', 'ftps:', 'magnet:'];

/**
 * Whether Ketch can download `url` on its own: HTTP(S), FTP(S) and magnet links. Links that only
 * exist inside the browser, such as `blob:` and `data:`, cannot be handed over.
 *
 * @param {string} url
 */
export function isSupportedLinkUrl(url) {
  return LINK_SCHEMES.includes(protocolOf(url));
}

/**
 * Whether Ketch recognizes `url` as a torrent by itself: a magnet link or a path ending in
 * `.torrent`.
 *
 * @param {string} url
 */
export function isTorrentUrl(url) {
  const protocol = protocolOf(url);
  if (protocol === 'magnet:') return true;
  if (protocol !== 'http:' && protocol !== 'https:') return false;
  return new URL(url).pathname.toLowerCase().endsWith('.torrent');
}

/**
 * Whether a browser download is `.torrent` metainfo, judging by its MIME type or file name.
 *
 * @param {{ mime?: string, fileName?: string }} download
 */
export function isTorrentFile({ mime, fileName }) {
  return mime?.toLowerCase() === 'application/x-bittorrent' ||
    Boolean(fileName?.toLowerCase().endsWith('.torrent'));
}

/**
 * Returns the last component of a local path, whichever separator the platform uses.
 *
 * @param {string | undefined} path
 * @returns {string}
 */
export function fileNameFromPath(path) {
  return String(path ?? '').split(/[\\/]/).pop().trim();
}

/**
 * Headers that let Ketch fetch a file the way the browser would: the site's cookies for
 * downloads that need a signed-in session, the page that linked to it, and the user agent.
 *
 * @param {{
 *   cookies?: { name: string, value: string }[],
 *   referrer?: string,
 *   userAgent?: string,
 * }} context
 * @returns {Record<string, string>}
 */
export function buildHeaders({ cookies = [], referrer, userAgent }) {
  const headers = {};
  if (cookies.length > 0) {
    headers.Cookie = cookies.map((cookie) => `${cookie.name}=${cookie.value}`).join('; ');
  }
  const referrerProtocol = protocolOf(referrer);
  if (referrerProtocol === 'http:' || referrerProtocol === 'https:') {
    // Fragments are never sent in a Referer header.
    headers.Referer = referrer.replace(/#.*$/, '');
  }
  if (userAgent) headers['User-Agent'] = userAgent;
  return headers;
}

/**
 * Builds the `DownloadRequest` for `url`.
 *
 * The browser's file name becomes a bare-name destination, so the file lands in Ketch's default
 * folder under the name the browser would have used; Ketch picks a free name if it is taken.
 * Torrents choose their own file layout, so they never get one.
 *
 * @param {{ url: string, fileName?: string, headers?: Record<string, string> }} download
 */
export function buildDownloadRequest({ url, fileName, headers = {} }) {
  const request = { url };
  const name = fileNameFromPath(fileName);
  if (name && name !== '.' && name !== '..' && !isTorrentUrl(url) && !isTorrentFile({ fileName })) {
    request.destination = name;
  }
  if (Object.keys(headers).length > 0) request.headers = headers;
  return request;
}

function protocolOf(url) {
  try {
    return new URL(url).protocol.toLowerCase();
  } catch {
    return '';
  }
}
