/** Hands a download over to a Ketch instance. */

import { withEndpoint } from './connection.js';
import { ext } from './ext.js';
import { t } from './i18n.js';
import { KetchClient } from './ketch-client.js';
import { mayForwardCookies } from './settings.js';
import { receiptEndpoint, submitDownload } from './submissions.js';
import {
  buildDownloadRequest,
  buildHeaders,
  isSupportedLinkUrl,
  isTorrentFile,
  isTorrentUrl,
} from './request.js';

/** Same limit as the server's `POST /api/resolve/content`. */
export const MAX_TORRENT_BYTES = 16 * 1024 * 1024;
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
 * @property {import('./connection.js').ConnectionDeps} [connection] reaches the Ketch app
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
 * @param {{ timeoutMs?: number, deps?: HandoffDeps }} [options] `timeoutMs` bounds fetching a
 *   `.torrent` file and the requests to Ketch, so a held download is released in time. Starting
 *   the Ketch app takes longer and has its own limit.
 * @returns {Promise<object>} snapshot of the created task
 */
export async function sendToKetch(instance, download, settings, options = {}) {
  const { url } = download;
  if (!isSupportedLinkUrl(url)) {
    throw new Error(t('error_unsupported_link'));
  }
  const deps = options.deps ?? browserDeps();
  const forwardCookies = mayForwardCookies(instance, settings);
  const deadline = () => options.timeoutMs === undefined
    ? undefined
    : AbortSignal.timeout(options.timeoutMs);

  let request;
  let torrentContent;
  if (url.toLowerCase().startsWith('magnet:')) {
    request = buildDownloadRequest({ url });
  } else if (isTorrentFile(download) && !isTorrentUrl(url)) {
    torrentContent = await fetchTorrentFile(deps.fetch, url, forwardCookies, deadline());
  } else {
    const cookies = forwardCookies
      ? await readCookies(deps, url, download.cookieStoreId)
      : [];
    const headers = buildHeaders({
      cookies,
      referrer: forwardCookies ? download.referrer : undefined,
      userAgent: deps.userAgent,
    });
    request = buildDownloadRequest({ url, fileName: download.fileName, headers });
  }

  return withEndpoint(instance, async (endpoint) => {
    const client = new KetchClient(endpoint, {
      fetch: deps.fetch,
      timeoutMs: options.timeoutMs,
      signal: deadline(),
    });
    if (torrentContent) {
      const resolved = await client.resolveContent(torrentContent, download.fileName || undefined);
      request = buildDownloadRequest({ url: resolved.url, resolvedSource: resolved });
    }
    if (Object.hasOwn(download, 'destination')) {
      if (download.destination) request.destination = download.destination;
      else delete request.destination;
    }
    return submitDownload(client, receiptEndpoint(instance, endpoint), request, {
      browserDownloadId: download.browserDownloadId, browserUrl: url,
      recoverTasks: instance.type === 'app' ? () => withEndpoint(instance, (current) => {
        const recovery = new KetchClient(current, {
          fetch: deps.fetch, timeoutMs: options.timeoutMs,
        });
        return recovery.listTasks();
      }, { deps: deps.connection }) : undefined,
    });
  }, { deps: deps.connection });
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

async function fetchTorrentFile(fetchImpl, url, includeCookies, deadline) {
  try {
    const response = await fetchImpl(url, {
      credentials: includeCookies ? 'include' : 'omit',
      signal: deadline ?? AbortSignal.timeout(TORRENT_FETCH_TIMEOUT_MS),
    });
    if (!response.ok) {
      throw new Error(t('error_torrent_http', response.status));
    }
    return await readAtMost(response, MAX_TORRENT_BYTES);
  } catch (error) {
    if (error?.name === 'TimeoutError' || error?.name === 'AbortError') {
      throw new Error(t('error_torrent_timeout'), { cause: error });
    }
    throw error;
  }
}

/**
 * Reads a response body, giving up as soon as it exceeds `limit`, so a mislabeled or endless
 * response can't exhaust memory.
 */
async function readAtMost(response, limit) {
  const tooLarge = () => new Error(t('error_torrent_too_large'));
  if (Number(response.headers.get('Content-Length')) > limit) {
    await response.body?.cancel();
    throw tooLarge();
  }
  if (!response.body) return new Uint8Array(0);
  const reader = response.body.getReader();
  const chunks = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > limit) {
      await reader.cancel();
      throw tooLarge();
    }
    chunks.push(value);
  }
  const content = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    content.set(chunk, offset);
    offset += chunk.byteLength;
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
