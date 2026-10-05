/** Pending reviews are session-only and never contain cookies or instance credentials. */
import { ext } from './ext.js';
import { sendToKetch } from './handoff.js';
import { t } from './i18n.js';
import { isTorrentFile, isTorrentUrl } from './request.js';
import { loadSettings } from './settings.js';

const PREFIX = 'review:';
const locks = new Map();

// All mutations run in the background context, including sends from the review page.
function locked(id, action) {
  const previous = locks.get(id) ?? Promise.resolve();
  const next = previous.catch(() => {}).then(action);
  locks.set(id, next);
  return next.finally(() => { if (locks.get(id) === next) locks.delete(id); });
}

export async function openReview(instanceId, download) {
  const id = crypto.randomUUID();
  const entry = { instanceId, download, state: 'pending' };
  await ext.storage.session.set({ [PREFIX + id]: entry });
  try {
    const tab = await ext.tabs.create({ url: ext.runtime.getURL(`review/review.html?id=${id}`) });
    await ext.storage.session.set({ [PREFIX + id]: { ...entry, tabId: tab.id } });
  } catch (error) {
    await ext.storage.session.remove(PREFIX + id);
    throw error;
  }
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
    return task;
  });
}

export function cancelReview(id) {
  return locked(id, async () => {
    const entry = await readReview(id);
    if (!entry || entry.state !== 'pending') return;
    const browserId = entry.download.browserDownloadId;
    if (Number.isInteger(browserId)) await ext.downloads.resume(browserId).catch(() => {});
    await ext.storage.session.remove(PREFIX + id);
  });
}

export async function closeReviews(tabId) {
  const entries = await ext.storage.session.get(null);
  for (const [key, entry] of Object.entries(entries)) {
    if (key.startsWith(PREFIX) && entry.tabId === tabId) await cancelReview(key.slice(PREFIX.length));
  }
}
