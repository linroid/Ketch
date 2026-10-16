/** Pending reviews are session-only and never contain cookies or instance credentials. */
import { ext, remoteOnly } from './ext.js';
import { sendToKetch } from './handoff.js';
import { t } from './i18n.js';
import { fileNameFromPath, isTorrentFile, isTorrentUrl } from './request.js';
import { loadSettings } from './settings.js';

const PREFIX = 'review:';
const RECENT_FOLDERS_KEY = 'recentFolders';
const DEVICE_FOLDERS_KEY = 'deviceFolders';
const LAST_FOLDERS_KEY = 'lastFolders';
const MAX_RECENT_FOLDERS = 5;
/** Size of the dialog window; the page fits its height to what it shows. */
const DIALOG_WIDTH = 460;
const DIALOG_HEIGHT = 560;
const locks = new Map();

// All mutations run in the background context, including sends from the review page.
function locked(id, action) {
  const previous = locks.get(id) ?? Promise.resolve();
  const next = previous.catch(() => {}).then(action);
  locks.set(id, next);
  return next.finally(() => { if (locks.get(id) === next) locks.delete(id); });
}

/**
 * Opens the review: over the page in `options.tabId` when it can be shown there, otherwise in a
 * window of its own.
 *
 * @param {{ tabId?: number }} [options] the tab the download came from
 */
export async function openReview(instanceId, download, { tabId: pageTabId } = {}) {
  const id = crypto.randomUUID();
  return locked(id, async () => {
    try {
      const entry = { instanceId, download, state: 'pending' };
      await ext.storage.session.set({ [PREFIX + id]: entry });
      const url = ext.runtime.getURL(`review/review.html?id=${id}`);
      const tabId = await showOverlay(pageTabId, url) ? pageTabId : await openDialog(url);
      await ext.storage.session.set({ [PREFIX + id]: { ...entry, tabId } });
      // onRemoved may have run before the tab association reached storage.
      await ext.tabs.get(tabId);
    } catch (error) {
      await cancelPendingReview(id);
      throw error;
    }
  });
}

/**
 * Moves a review the page could not show (its frame never loaded) to a window of its own.
 *
 * @param {number} tabId the tab that should have shown it
 */
export function reopenReview(id, tabId) {
  return locked(id, async () => {
    const entry = await readReview(id);
    if (!entry || entry.state !== 'pending' || entry.tabId !== tabId) return;
    const dialogTabId = await openDialog(ext.runtime.getURL(`review/review.html?id=${id}`));
    await ext.storage.session.set({ [PREFIX + id]: { ...entry, tabId: dialogTabId } });
  });
}

/** Shows the review in a frame over the page in `tabId`; false where pages can't be scripted. */
async function showOverlay(tabId, url) {
  if (!Number.isInteger(tabId) || tabId < 0 || !ext.scripting?.executeScript) return false;
  try {
    await ext.scripting.executeScript({ target: { tabId }, func: mountReviewOverlay, args: [url] });
    return true;
  } catch (error) {
    // Browser pages, the extension stores and other protected pages.
    console.debug('Ketch: could not show the review over the page', error);
    return false;
  }
}

/**
 * Runs in the page: puts the review page in a frame over it, in a closed shadow root so the
 * page's styles can't reach it. The frame asks for its height and to be closed with
 * `postMessage`; one that doesn't load in time is handed back to a window.
 */
function mountReviewOverlay(src) {
  const api = globalThis.browser ?? globalThis.chrome;
  const host = document.createElement('div');
  host.style.cssText = 'all: initial; position: fixed; inset: 0; z-index: 2147483647;';
  const shadow = host.attachShadow({ mode: 'closed' });
  const style = document.createElement('style');
  style.textContent = `
    .backdrop { position: fixed; inset: 0; display: grid; place-items: center; padding: 16px;
      background: rgb(15 18 30 / 0.38); backdrop-filter: blur(2px);
      animation: fade 0.14s ease-out; }
    iframe { width: min(460px, calc(100vw - 32px)); height: 520px;
      max-height: calc(100vh - 32px); border: 0; border-radius: 14px; background: transparent;
      box-shadow: 0 24px 64px rgb(0 0 0 / 0.32), 0 0 0 1px rgb(0 0 0 / 0.06);
      color-scheme: normal; animation: rise 0.18s ease-out; }
    @keyframes fade { from { opacity: 0; } }
    @keyframes rise { from { opacity: 0; transform: translateY(8px) scale(0.98); } }
    @media (prefers-reduced-motion: reduce) { .backdrop, iframe { animation: none; } }`;
  const backdrop = document.createElement('div');
  backdrop.className = 'backdrop';
  const frame = document.createElement('iframe');
  frame.title = 'Ketch';
  frame.src = src;
  backdrop.append(frame);
  shadow.append(style, backdrop);
  let ready = false;
  const close = () => {
    window.removeEventListener('message', onMessage);
    host.remove();
  };
  const onMessage = (event) => {
    if (event.source !== frame.contentWindow || event.data?.ketchReview !== true) return;
    if (event.data.action === 'ready') {
      ready = true;
      frame.focus();
    } else if (event.data.action === 'resize' && Number.isFinite(event.data.height)) {
      frame.style.height = `${Math.ceil(event.data.height)}px`;
    } else if (event.data.action === 'close') {
      close();
    }
  };
  window.addEventListener('message', onMessage);
  // Clicking outside closes it, which cancels the review.
  backdrop.addEventListener('click', (event) => { if (event.target === backdrop) close(); });
  (document.body ?? document.documentElement).append(host);
  setTimeout(() => {
    if (ready) return;
    close();
    api.runtime.sendMessage({ type: 'review-overlay-failed',
      id: new URL(src).searchParams.get('id') }).catch(() => {});
  }, 5000);
}

/**
 * Opens the review as a small window centered over the browser window, or as a tab where the
 * browser has no popup windows (Safari). Closing either closes the review.
 *
 * @returns {Promise<number>} the id of the tab showing it
 */
async function openDialog(url) {
  let created;
  try {
    if (ext.windows?.create) {
      const options = { url, type: 'popup', width: DIALOG_WIDTH, height: DIALOG_HEIGHT };
      const parent = await ext.windows.getLastFocused().catch(() => undefined);
      if ([parent?.left, parent?.top, parent?.width, parent?.height].every(Number.isFinite)) {
        options.left = Math.max(0, Math.round(parent.left + (parent.width - DIALOG_WIDTH) / 2));
        options.top = Math.max(0, Math.round(parent.top +
          Math.min(120, (parent.height - DIALOG_HEIGHT) / 2)));
      }
      created = await ext.windows.create(options);
      const tabId = created.tabs?.[0]?.id ??
        (await ext.tabs.query({ windowId: created.id }))[0]?.id;
      if (Number.isInteger(tabId)) return tabId;
      await ext.windows.remove(created.id);
    }
  } catch (error) {
    console.debug('Ketch: could not open the review window', error);
    if (created) return (await ext.tabs.query({ windowId: created.id }))[0].id;
  }
  return (await ext.tabs.create({ url })).id;
}

export async function readReview(id) {
  return (await ext.storage.session.get(PREFIX + id))[PREFIX + id];
}

/** A destination is interpreted on the selected device, never on the browser's filesystem. */
export function reviewDestination(download, fileName, folder) {
  const name = String(fileName ?? '').trim();
  const directory = String(folder ?? '').trim();
  const torrent = isTorrentUrl(download.url) || isTorrentFile(download);
  if (/[\x00-\x1f\x7f]/.test(directory) || directory.length > 4096 ||
    (!torrent && (/[\\/:*?"<>|\x00-\x1f\x7f]/.test(name) || name.length > 255 ||
      name === '.' || name === '..'))) throw new Error(t('review_invalid_destination'));
  const prefix = directory ? directory.replace(/[\\/]+$/, '') + '/' : '';
  return prefix + (torrent ? '' : name) || undefined;
}

/** Page-provided names have no editor in the batch picker; reduce them to safe basenames. */
export function resourceDestination(resource, folder) {
  let name = fileNameFromPath(resource.name)
    .replace(/[/:*?"<>|\x00-\x1f\x7f-\x9f\u202a-\u202e\u2066-\u2069]/g, '')
    .replace(/[. ]+$/, '');
  const chars = [...name];
  while (new TextEncoder().encode(chars.join('')).length > 255) chars.pop();
  name = chars.join('');
  if (/^(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\.|$)/i.test(name)) name = '';
  return reviewDestination({ url: resource.url, fileName: resource.name },
    resource.kind === 'stream' ? '' : name, folder);
}

export function sendReview(id, instanceId, fileName, folder, send = sendToKetch) {
  return locked(id, async () => {
    const entry = await readReview(id);
    if (!entry || entry.state !== 'pending') throw new Error(t('review_unavailable'));
    const settings = await loadSettings();
    const instance = settings.instances.find((it) => it.id === instanceId);
    if (!instance) throw new Error(t('review_unavailable'));
    const browserId = entry.download.browserDownloadId;
    if (Number.isInteger(browserId)) {
      const [original] = await ext.downloads.search({ id: browserId });
      if (!original?.paused || original.state !== 'in_progress' ||
        (original.finalUrl || original.url) !== entry.download.url) {
        throw new Error(t('review_unavailable'));
      }
    }
    const destination = reviewDestination(entry.download, fileName, folder);
    entry.state = 'sending';
    await ext.storage.session.set({ [PREFIX + id]: entry });
    let task;
    try {
      task = await send(instance, { ...entry.download, destination }, settings);
    } catch (error) {
      entry.state = error.kind === 'uncertain' ? 'uncertain' : 'pending';
      await ext.storage.session.set({ [PREFIX + id]: entry });
      throw error;
    }
    // A crash after acceptance must never resume the original browser download.
    entry.state = 'sent';
    await ext.storage.session.set({ [PREFIX + id]: entry });
    if (Number.isInteger(browserId)) {
      await ext.downloads.cancel(browserId).catch(() => {});
      await ext.downloads.erase({ id: browserId }).catch(() => {});
    }
    await ext.storage.session.remove(PREFIX + id);
    await rememberFolder(instanceId, folder);
    return task;
  });
}

/**
 * Whether the reviewed download can be saved by the browser instead: a paused original goes
 * back, and an HTTP(S) link starts a new browser download. Chromium's background cannot start
 * one in a private window's context.
 */
export function canDownloadDirectly(download) {
  if (remoteOnly || !ext.downloads) return false;
  if (Number.isInteger(download.browserDownloadId)) return true;
  return /^https?:\/\//i.test(download.url) && !(download.incognito && !isFirefox());
}

/** Gives the review's download to the browser: the paused original, or a new download. */
export function directReview(id, fileName) {
  return locked(id, async () => {
    const entry = await readReview(id);
    if (!entry || entry.state !== 'pending') throw new Error(t('review_unavailable'));
    const { download } = entry;
    if (!canDownloadDirectly(download)) throw new Error(t('notify_direct_hint'));
    if (Number.isInteger(download.browserDownloadId)) {
      await ext.downloads.resume(download.browserDownloadId);
    } else {
      const name = String(fileName ?? '').trim();
      let valid = false;
      try {
        valid = Boolean(name) && reviewDestination({ url: download.url }, name, '') === name;
      } catch {
        // The browser picks the name instead.
      }
      await startBrowserDownload(download.url, {
        cookieStoreId: download.cookieStoreId,
        incognito: download.incognito,
        filename: valid ? name : undefined,
      });
    }
    await ext.storage.session.remove(PREFIX + id);
  });
}

/**
 * Starts a browser download; its `byExtensionId` keeps it out of automatic capture. Without a
 * `filename`, the browser chooses the name and whether to show its Save As dialog.
 *
 * @param {string} url
 * @param {{ cookieStoreId?: string, incognito?: boolean, filename?: string }} [context] the
 *   tab's cookie store and private state, which only Firefox can pass on
 */
export async function startBrowserDownload(url, { cookieStoreId, incognito, filename } = {}) {
  const firefox = isFirefox();
  if (!/^https?:\/\//i.test(url ?? '') || (incognito && !firefox)) {
    throw new Error(t('notify_direct_hint'));
  }
  const options = { url };
  if (firefox) {
    if (cookieStoreId) options.cookieStoreId = cookieStoreId;
    if (incognito) options.incognito = true;
  }
  if (filename) {
    try {
      return await ext.downloads.download({ ...options, filename });
    } catch (error) {
      console.debug('Ketch: the browser refused the file name', error);
    }
  }
  return ext.downloads.download(options);
}

function isFirefox() {
  return !ext.downloads?.onDeterminingFilename;
}

/**
 * The first of a device's category folders (`DownloadConfig.categories`) a download matches, as
 * `DownloadCategory.matches` decides: its name's extension or its MIME type, and its site.
 *
 * @param {{ folder: string, extensions?: string[], mimeTypes?: string[], hosts?: string[] }[]}
 *   categories
 * @param {string} fileName
 * @param {string | undefined} contentType
 * @param {string | undefined} host
 */
export function categoryFor(categories, fileName, contentType, host) {
  const rules = (list, prefixed) => (Array.isArray(list) ? list : [])
    .map((rule) => String(rule).trim().toLowerCase())
    .map((rule) => prefixed ? rule.replace(/^\*?\./, '') : rule)
    .filter(Boolean);
  const name = String(fileName ?? '').trim().toLowerCase();
  const type = String(contentType ?? '').split(';')[0].trim().toLowerCase();
  const site = String(host ?? '').trim().replace(/\.$/, '').toLowerCase();
  return categories.find((category) => {
    const extensions = rules(category.extensions, true);
    const types = rules(category.mimeTypes, false);
    const hosts = rules(category.hosts, true).map((it) => it.replace(/\.$/, ''));
    if (!extensions.length && !types.length && !hosts.length) return false;
    const typeMatches = (!extensions.length && !types.length) ||
      extensions.some((it) => name.length > it.length + 1 && name.endsWith(`.${it}`)) ||
      (type !== '' && types.some((it) => it.endsWith('/*')
        ? type.startsWith(it.slice(0, -1)) : type === it));
    const siteMatches = !hosts.length ||
      (site !== '' && hosts.some((it) => site === it || site.endsWith(`.${it}`)));
    return typeMatches && siteMatches;
  });
}

/**
 * @typedef {{ directory: string, categories: { folder: string }[] }} DeviceFolders where a
 *   device saves downloads and its category folders, as its `KetchStatus` last reported them
 */

/** Reads a device's folders from its `KetchStatus`; `undefined` when it names none. */
export function deviceFolders(status) {
  const directory = status?.system?.downloadDirectory;
  if (typeof directory !== 'string' || !directory) return undefined;
  const categories = (Array.isArray(status.config?.categories) ? status.config.categories : [])
    .filter((category) => typeof category?.folder === 'string' && category.folder.trim());
  return { directory, categories };
}

/**
 * Keeps a device's folders from a status it reported, so the review can show them while it
 * can't be asked, as when the Ketch app is closed.
 */
export async function rememberDeviceFolders(instanceId, status) {
  const folders = deviceFolders(status);
  if (!folders) return;
  try {
    const stored = (await ext.storage.local.get(DEVICE_FOLDERS_KEY))[DEVICE_FOLDERS_KEY] ?? {};
    if (JSON.stringify(stored[instanceId]) === JSON.stringify(folders)) return;
    await ext.storage.local.set({ [DEVICE_FOLDERS_KEY]: { ...stored, [instanceId]: folders } });
  } catch (error) {
    console.warn('Ketch: could not remember the device folders', error);
  }
}

/** @returns {Promise<DeviceFolders | undefined>} the folders a device last reported */
export async function knownDeviceFolders(instanceId) {
  try {
    const stored = (await ext.storage.local.get(DEVICE_FOLDERS_KEY))[DEVICE_FOLDERS_KEY];
    return deviceFolders({ system: { downloadDirectory: stored?.[instanceId]?.directory },
      config: { categories: stored?.[instanceId]?.categories } });
  } catch {
    return undefined;
  }
}

/** Folders last used on `instanceId`, most recent first. */
export async function recentFolders(instanceId) {
  try {
    const stored = (await ext.storage.local.get(RECENT_FOLDERS_KEY))[RECENT_FOLDERS_KEY];
    const folders = stored?.[instanceId];
    return Array.isArray(folders) ? folders.filter((it) => typeof it === 'string') : [];
  } catch {
    return [];
  }
}

/**
 * The folder the last download sent to `instanceId` went to, which the review starts with: empty
 * when it was left to the device, `undefined` before any.
 */
export async function lastFolder(instanceId) {
  try {
    const folder = (await ext.storage.local.get(LAST_FOLDERS_KEY))[LAST_FOLDERS_KEY]?.[instanceId];
    return typeof folder === 'string' ? folder : undefined;
  } catch {
    return undefined;
  }
}

async function rememberFolder(instanceId, folder) {
  const directory = String(folder ?? '').trim();
  try {
    const last = (await ext.storage.local.get(LAST_FOLDERS_KEY))[LAST_FOLDERS_KEY] ?? {};
    if (last[instanceId] !== directory) {
      await ext.storage.local.set({ [LAST_FOLDERS_KEY]: { ...last, [instanceId]: directory } });
    }
    if (!directory) return;
    const stored = (await ext.storage.local.get(RECENT_FOLDERS_KEY))[RECENT_FOLDERS_KEY] ?? {};
    const folders = [directory, ...(await recentFolders(instanceId))
      .filter((it) => it !== directory)].slice(0, MAX_RECENT_FOLDERS);
    await ext.storage.local.set({ [RECENT_FOLDERS_KEY]: { ...stored, [instanceId]: folders } });
  } catch (error) {
    // The download was sent; only the suggestion is lost.
    console.warn('Ketch: could not remember the folder', error);
  }
}

export function cancelReview(id) {
  return locked(id, () => cancelPendingReview(id));
}

async function cancelPendingReview(id) {
  const entry = await readReview(id);
  if (!entry || entry.state !== 'pending') return;
  const browserId = entry.download.browserDownloadId;
  if (Number.isInteger(browserId)) await ext.downloads.resume(browserId).catch(() => {});
  await ext.storage.session.remove(PREFIX + id);
}

export async function closeReviews(tabId) {
  const entries = await ext.storage.session.get(null);
  for (const [key, entry] of Object.entries(entries)) {
    if (key.startsWith(PREFIX) && entry.tabId === tabId) await cancelReview(key.slice(PREFIX.length));
  }
}
