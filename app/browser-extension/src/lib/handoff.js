/** Hands a download over to a Ketch instance. */

import { ext } from './ext.js';
import { KetchClient } from './ketch-client.js';
import {
  buildDownloadRequest,
  buildHeaders,
  isSupportedLinkUrl,
  isTorrentFile,
  isTorrentUrl,
} from './request.js';

/** Same limit as the server's `POST /api/resolve/content`. */
const MAX_TORRENT_BYTES = 16 * 1024 * 1024;
const TORRENT_FETCH_TIMEOUT_MS = 15_000;

/**
 * @typedef {object} Download
 * @property {string} url the link to download
 * @property {string} [referrer] page the link was found on
 * @property {string} [fileName] name the browser chose for the file
 * @property {string} [mime] MIME type the browser saw, if it already requested the file
 * @property {string} [cookieStoreId] cookie store of the tab or download, e.g. a container
 */

/**
 * @typedef {object} HandoffDeps
 * @property {typeof fetch} fetch
 * @property {string | undefined} userAgent
 * @property {(url: string, storeId?: string) => Promise<{ name: string, value: string }[]>}
 *   getCookies
 */

/**
 * Creates a task for `download` on `instance`.
 *
 * Cookies, referrer and user agent go along so sites that need a session keep working. A
 * `.torrent` file whose URL Ketch would not recognize is fetched here and resolved from its
 * content, so it becomes a torrent download rather than a saved `.torrent` file.
 *
 * @param {import('./settings.js').Instance} instance
 * @param {Download} download
 * @param {import('./settings.js').Settings} settings
 * @param {{ timeoutMs?: number, deps?: HandoffDeps }} [options]
 * @returns {Promise<object>} snapshot of the created task
 */
export async function sendToKetch(instance, download, settings, options = {}) {
  const { url } = download;
  if (!isSupportedLinkUrl(url)) {
    throw new Error("Ketch can't download this kind of link");
  }
  const deps = options.deps ?? browserDeps();
  const client = new KetchClient(instance, { fetch: deps.fetch, timeoutMs: options.timeoutMs });

  if (url.toLowerCase().startsWith('magnet:')) {
    return client.createTask({ url });
  }
  if (isTorrentFile(download) && !isTorrentUrl(url)) {
    const content = await fetchTorrentFile(deps.fetch, url, settings.forwardCookies);
    const resolved = await client.resolveContent(content, download.fileName || undefined);
    return client.createTask({ url: resolved.url, resolvedSource: resolved });
  }
  const cookies = settings.forwardCookies
    ? await readCookies(deps, url, download.cookieStoreId)
    : [];
  const headers = buildHeaders({
    cookies,
    referrer: settings.forwardCookies ? download.referrer : undefined,
    userAgent: deps.userAgent,
  });
  return client.createTask(buildDownloadRequest({ url, fileName: download.fileName, headers }));
}

/**
 * Returns the cookie store a tab uses: a Firefox container, or a Chromium private window's
 * store. `undefined` means the default store.
 *
 * @param {{ id?: number, incognito?: boolean, cookieStoreId?: string } | undefined} tab
 * @returns {Promise<string | undefined>}
 */
export async function cookieStoreIdForTab(tab) {
  if (!tab) return undefined;
  if (tab.cookieStoreId) return tab.cookieStoreId;
  if (!tab.incognito) return undefined;
  const stores = await ext.cookies.getAllCookieStores();
  return stores.find((store) => store.tabIds.includes(tab.id))?.id;
}

async function readCookies(deps, url, storeId) {
  try {
    return await deps.getCookies(url, storeId);
  } catch (error) {
    // Without cookies the download still works on sites that don't need a session.
    console.warn('Ketch: could not read cookies for the download', error);
    return [];
  }
}

async function fetchTorrentFile(fetchImpl, url, includeCookies) {
  const response = await fetchImpl(url, {
    credentials: includeCookies ? 'include' : 'omit',
    signal: AbortSignal.timeout(TORRENT_FETCH_TIMEOUT_MS),
  });
  if (!response.ok) {
    throw new Error(`Couldn't fetch the torrent file (HTTP ${response.status})`);
  }
  const content = new Uint8Array(await response.arrayBuffer());
  if (content.byteLength > MAX_TORRENT_BYTES) {
    throw new Error('The torrent file is too large');
  }
  return content;
}

/** @returns {HandoffDeps} */
function browserDeps() {
  return {
    fetch: globalThis.fetch.bind(globalThis),
    userAgent: globalThis.navigator?.userAgent,
    getCookies: getBrowserCookies,
  };
}

async function getBrowserCookies(url, storeId) {
  const query = storeId ? { url, storeId } : { url };
  try {
    return await ext.cookies.getAll(query);
  } catch (error) {
    // Firefox's first-party isolation requires firstPartyDomain; null matches all of them.
    if (!String(error?.message).includes('firstPartyDomain')) throw error;
    return ext.cookies.getAll({ ...query, firstPartyDomain: null });
  }
}
