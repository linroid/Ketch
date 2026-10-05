/** Decides which browser downloads are sent to Ketch. */

import { isTorrentFile, isTorrentUrl } from './request.js';

const MEGABYTE = 1024 * 1024;

/**
 * Whether `host` is one of `patterns` or a subdomain of one.
 *
 * @param {string} host
 * @param {string[]} patterns bare host names, as produced by `parseHostList`
 */
export function hostMatches(host, patterns) {
  const lower = host.toLowerCase();
  return patterns.some((pattern) => lower === pattern || lower.endsWith(`.${pattern}`));
}

/**
 * Decides whether a download the browser started should be sent to Ketch.
 *
 * @param {{
 *   url: string,
 *   finalUrl?: string,
 *   state?: string,
 *   incognito?: boolean,
 *   byExtensionId?: string,
 *   filename?: string,
 *   mime?: string,
 *   totalBytes?: number,
 *   fileSize?: number,
 * }} item a `downloads.DownloadItem`
 * @param {import('./settings.js').Settings} settings
 * @param {string} extensionId this extension's id; its own downloads are never captured
 * @returns {{ capture: boolean, reason: string }} the reason explains a skipped download
 */
export function captureDecision(item, settings, extensionId) {
  if (!settings.interceptDownloads) return skip('capturing is turned off');
  if (item.byExtensionId) {
    return skip('started by an extension');
  }
  if (item.incognito) return skip('private window');
  if (item.requestMethod && item.requestMethod !== 'GET') return skip('requires browser request');
  if (/\.(?:html?|xhtml|mhtml?|xml)$/i.test(item.filename ?? '')) return skip('saved web page');
  if (['text/html', 'application/xhtml+xml', 'text/xml', 'application/xml']
    .includes(item.mime?.split(';')[0].trim().toLowerCase())) return skip('web document');
  if (item.state && item.state !== 'in_progress') return skip(`already ${item.state}`);

  let url;
  try {
    url = new URL(item.finalUrl || item.url);
  } catch {
    return skip('invalid URL');
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    return skip(`${url.protocol} links only exist in the browser`);
  }
  if (servedByKetch(url, settings)) return skip('served by Ketch');
  if ([item.url, item.finalUrl, item.referrer, item.pageUrl].some((value) => {
    try { return hostMatches(new URL(value).hostname, settings.excludedHosts); }
    catch { return false; }
  })) return skip('excluded site');
  const names = [item.filename, url.pathname].filter(Boolean);
  const matchesType = settings.fileExtensions?.some((extension) =>
    names.some((name) => name.toLowerCase().endsWith(`.${extension}`)));
  if ((settings.fileTypeMode === 'only' && !matchesType) ||
    (settings.fileTypeMode === 'except' && matchesType)) return skip('file type rule');

  // A .torrent file is tiny but stands for a large download, so the size limit doesn't apply.
  const isTorrent = isTorrentUrl(url.href) ||
    isTorrentFile({ mime: item.mime, fileName: item.filename });
  const size = Math.max(item.totalBytes ?? -1, item.fileSize ?? -1);
  if (!isTorrent && size < 0 && settings.captureUnknownSize === false) {
    return skip('unknown size');
  }
  // Unknown sizes follow their own preference; zero-byte files are known to be small.
  if (!isTorrent && size >= 0 && size < settings.minFileSizeMb * MEGABYTE) {
    return skip('smaller than minimum');
  }
  return { capture: true, reason: '' };
}

/** @returns {boolean} whether `url` is a file served by one of the configured instances */
function servedByKetch(url, settings) {
  return settings.instances.some((instance) =>
    instance.url && new URL(instance.url).origin === url.origin);
}

function skip(reason) {
  return { capture: false, reason };
}
