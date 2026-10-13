/**
 * Background script: captures browser downloads, adds the "Download with Ketch" context menus
 * and receives magnet links from the content script. Listeners are registered synchronously at
 * the top level because the browser may stop this script whenever it is idle.
 */

import { ext, remoteOnly } from './lib/ext.js';
import { taskName } from './lib/format.js';
import { cookieStoreIdForTab } from './lib/handoff.js';
import { t } from './lib/i18n.js';
import {
  cancelReview, closeReviews, directReview, openReview, sendReview, startBrowserDownload,
} from './lib/review.js';
import { RequestContext } from './lib/request-context.js';
import { captureDecision } from './lib/intercept.js';
import { fileNameFromPath, isSupportedLinkUrl } from './lib/request.js';
import { findInstance, loadSettings, onSettingsChanged, saveSettings } from './lib/settings.js';

const requestContext = new RequestContext();
if (!remoteOnly) ext.webRequest?.onBeforeRequest.addListener((details) => requestContext.observe(details),
  { urls: ['http://*/*', 'https://*/*'] });

const MENUS = [
  { kind: 'link', contexts: ['link'], title: t('menu_download_link') },
  { kind: 'image', contexts: ['image'], title: t('menu_download_image') },
  { kind: 'video', contexts: ['video'], title: t('menu_download_video') },
  { kind: 'audio', contexts: ['audio'], title: t('menu_download_audio') },
];

ext.runtime.onInstalled.addListener(async ({ reason }) => {
  // Stores normalized settings, which also publishes the magnet flag to the content script.
  const settings = await saveSettings(await loadSettings());
  await rebuildMenus(settings);
  if (reason === 'install') await ext.runtime.openOptionsPage();
});

ext.runtime.onStartup.addListener(async () => rebuildMenus(await loadSettings()));

onSettingsChanged((settings) => rebuildMenus(settings));

ext.contextMenus.onClicked.addListener((info, tab) => {
  handleMenuClick(info, tab).catch((error) => console.error('Ketch: menu action failed', error));
});

ext.tabs?.onRemoved.addListener((id) => {
  closeReviews(id).catch((error) => console.warn('Ketch: could not close review', error));
});

ext.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (sender.id === ext.runtime.id && sender.url?.startsWith(ext.runtime.getURL('popup/')) &&
    message?.type === 'review-open') {
    openReview(message.instanceId, message.download).then(() => sendResponse({ ok: true }),
      (error) => sendResponse({ ok: false, message: error.message }));
    return true;
  }
  if (sender.id === ext.runtime.id && sender.url?.startsWith(ext.runtime.getURL('review/')) &&
    ['review-send', 'review-cancel', 'review-direct'].includes(message?.type)) {
    const action = message.type === 'review-send'
      ? sendReview(message.id, message.instanceId, message.fileName, message.folder)
        .then((task) => notifyReviewSent(message.instanceId, task))
      : message.type === 'review-direct'
        ? directReview(message.id, message.fileName)
        : cancelReview(message.id);
    action.then(() => sendResponse({ ok: true }), (error) =>
      sendResponse({ ok: false, message: error.message, kind: error.kind }));
    return true;
  }
  if (sender.id !== ext.runtime.id || message?.type !== 'magnet') return false;
  handleMagnet(message.url).then(sendResponse, (error) => {
    console.error('Ketch: magnet hand-off failed', error);
    sendResponse({ handled: false });
  });
  return true;
});

if (!remoteOnly && ext.downloads?.onDeterminingFilename) {
  // Chromium: the download waits here before any "Save as" dialog, until suggest() is called.
  ext.downloads.onDeterminingFilename.addListener((item, suggest) => {
    captureDownload(item, {
      hold: async () => {},
      release: async () => suggest(),
    });
    return true;
  });
} else if (!remoteOnly && ext.downloads?.onCreated) {
  // Firefox: the download has started; pause it while Ketch is asked to take over.
  ext.downloads.onCreated.addListener((item) => {
    let paused = false;
    captureDownload(item, {
      hold: async () => {
        try {
          await ext.downloads.pause(item.id);
          paused = true;
        } catch (error) {
          console.debug('Ketch: could not pause the browser download', error);
        }
      },
      release: async () => {
        if (paused) await ext.downloads.resume(item.id);
      },
    });
  });
}

/**
 * Pauses a download the browser started and opens its review, aimed at the default instance.
 * `gate.release()` lets the browser go on; it runs for skipped downloads too.
 */
async function captureDownload(item, gate) {
  const release = () => gate.release().catch((error) => {
    console.error('Ketch: could not give the download back to the browser', error);
  });
  let settings;
  try {
    settings = await loadSettings();
  } catch (error) {
    console.error('Ketch: could not load settings', error);
    return release();
  }
  const decision = captureDecision(
    requestContext.forDownload(item), settings, ext.runtime.id);
  if (!decision.capture) {
    console.debug(`Ketch: leaving download ${item.id} to the browser: ${decision.reason}`);
    return release();
  }
  await gate.hold();
  const instance = findInstance(settings);
  // Finish Chromium's filename hook before asking the browser to pause. If the download
  // already completed or cannot pause, keep it in the browser without submitting a copy.
  await release();
  try {
    await ext.downloads.pause(item.id);
    await openReview(instance.id, {
      url: item.finalUrl || item.url, referrer: item.referrer,
      fileName: fileNameFromPath(item.filename), mime: item.mime,
      cookieStoreId: item.cookieStoreId, browserDownloadId: item.id,
      size: item.totalBytes > 0 ? item.totalBytes : item.fileSize > 0 ? item.fileSize : undefined,
    });
  } catch {
    await ext.downloads.resume(item.id).catch(() => {});
  }
}

async function handleMagnet(url) {
  const settings = await loadSettings();
  if (!settings.captureMagnetLinks || !/^magnet:/i.test(String(url))) return { handled: false };
  try {
    await openReview(findInstance(settings).id, { url });
    return { handled: true };
  } catch (error) {
    console.error('Ketch: could not open the review for a magnet link', error);
    return { handled: false };
  }
}

async function handleMenuClick(info, tab) {
  if (info.menuItemId === 'page-resources') {
    if (!Number.isInteger(tab?.id) || tab.id < 0) return;
    await ext.tabs.create({
      url: ext.runtime.getURL(`resources/resources.html?tab=${tab.id}`),
    });
    return;
  }
  if (info.menuItemId === 'download-directly') {
    await downloadDirectly(info, tab);
    return;
  }
  const target = parseMenuId(String(info.menuItemId));
  if (!target) return;
  const settings = await loadSettings();
  const instance = findInstance(settings, target.instanceId);
  const url = target.kind === 'link' ? info.linkUrl : info.srcUrl;
  if (!url || !isSupportedLinkUrl(url)) {
    if (remoteOnly) { await openReview(instance.id, { url }); return; }
    notify(t('notify_unsupported'), url?.startsWith('blob:')
      ? t('notify_unsupported_stream')
      : t('notify_unsupported_scheme'));
    return;
  }
  // The review can still hand a link back to the browser, in the tab's context.
  await openReview(instance.id, {
    url,
    referrer: info.frameUrl || info.pageUrl,
    cookieStoreId: await cookieStoreIdForTab(tab),
    incognito: tab?.incognito || undefined,
  });
}

/** Downloads the clicked item in the browser, leaving Ketch out of this one download. */
async function downloadDirectly(info, tab) {
  try {
    await startBrowserDownload(info.linkUrl || info.srcUrl, {
      cookieStoreId: tab?.cookieStoreId, incognito: tab?.incognito,
    });
  } catch {
    console.warn('Ketch: could not start the browser download');
    notify(t('notify_direct_failed'), t('notify_direct_hint'));
  }
}

let menuUpdate = Promise.resolve();

/**
 * Recreates the context menus. With several instances, each menu gets a submenu listing them,
 * default first; with one, a click sends straight to it. Updates run one after another, since
 * creating an id that still exists fails.
 */
function rebuildMenus(settings) {
  menuUpdate = menuUpdate.then(async () => {
    await ext.contextMenus.removeAll();
    await createMenu({ id: 'ketch', title: 'Ketch', contexts: ['all'] });
    await createMenu({
      id: 'page-resources',
      parentId: 'ketch',
      title: t('resources_title'),
      contexts: ['all'],
      documentUrlPatterns: ['http://*/*', 'https://*/*'],
    });
    if (!remoteOnly) await createMenu({
      id: 'download-directly',
      parentId: 'ketch',
      title: t('menu_download_directly'),
      contexts: ['link', 'image', 'video', 'audio'],
      targetUrlPatterns: ['http://*/*', 'https://*/*'],
    });
    const instances = [
      ...settings.instances.filter((it) => it.id === settings.defaultInstanceId),
      ...settings.instances.filter((it) => it.id !== settings.defaultInstanceId),
    ];
    for (const menu of MENUS) {
      await createMenu({
        id: menu.kind,
        parentId: 'ketch',
        title: menu.title,
        contexts: menu.contexts,
      });
      if (instances.length < 2) continue;
      for (const instance of instances) {
        const isDefault = instance.id === settings.defaultInstanceId;
        await createMenu({
          id: `${menu.kind}:${instance.id}`,
          parentId: menu.kind,
          title: isDefault ? t('instance_name_default', instance.name) : instance.name,
          contexts: menu.contexts,
        });
      }
    }
  }).catch((error) => console.error('Ketch: could not update the context menus', error));
  return menuUpdate;
}

function createMenu(properties) {
  return new Promise((resolve) => {
    ext.contextMenus.create(properties, () => {
      const error = ext.runtime.lastError;
      if (error) console.warn(`Ketch: could not create menu ${properties.id}`, error.message);
      resolve();
    });
  });
}

/** @returns {{ kind: string, instanceId: string | undefined } | null} */
function parseMenuId(menuId) {
  const separator = menuId.indexOf(':');
  const kind = separator < 0 ? menuId : menuId.slice(0, separator);
  if (!MENUS.some((menu) => menu.kind === kind)) return null;
  return { kind, instanceId: separator < 0 ? undefined : menuId.slice(separator + 1) };
}

async function notifyReviewSent(instanceId, task) {
  const settings = await loadSettings().catch(() => undefined);
  if (settings) notifySent(settings, findInstance(settings, instanceId), taskName(task));
}

function notifySent(settings, instance, name) {
  if (!settings.notifications) return;
  const where = settings.instances.length > 1
    ? t('notify_sent_to', instance.name)
    : t('notify_sent_to_ketch');
  notify(where, name || t('notify_download_added'));
}

function notify(title, message) {
  if (remoteOnly || !ext.notifications) return;
  ext.notifications.create({
    type: 'basic',
    iconUrl: ext.runtime.getURL('icons/icon-128.png'),
    title,
    message,
  }).catch((error) => console.warn('Ketch: could not show a notification', error));
}
