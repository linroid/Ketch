/**
 * Background script: captures browser downloads, adds the "Download with Ketch" context menus
 * and receives magnet links from the content script. Listeners are registered synchronously at
 * the top level because the browser may stop this script whenever it is idle.
 */

import { FailureKind } from './lib/ketch-client.js';
import {
  CAPTURE_PAUSED_KEY,
  loadCapturePause,
  setCapturePaused,
} from './lib/capture-pause.js';
import { ext } from './lib/ext.js';
import { failureHint, nameFromUrl, taskName, withHint } from './lib/format.js';
import { cookieStoreIdForTab, sendToKetch } from './lib/handoff.js';
import { t } from './lib/i18n.js';
import { RequestContext } from './lib/request-context.js';
import { captureDecision } from './lib/intercept.js';
import { fileNameFromPath, isSupportedLinkUrl } from './lib/request.js';
import { findInstance, loadSettings, onSettingsChanged, saveSettings } from './lib/settings.js';

/** Captured downloads wait for Ketch this long before the browser takes them back. */
const CAPTURE_TIMEOUT_MS = 6_000;
const requestContext = new RequestContext();
ext.webRequest.onBeforeRequest.addListener((details) => requestContext.observe(details),
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

ext.storage.onChanged.addListener((changes, area) => {
  if (area !== 'local' || !changes[CAPTURE_PAUSED_KEY]) return;
  loadSettings().then(rebuildMenus).catch((error) => {
    console.error('Ketch: could not update the capture menu', error);
  });
});

ext.contextMenus.onClicked.addListener((info, tab) => {
  handleMenuClick(info, tab).catch((error) => console.error('Ketch: menu action failed', error));
});

ext.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (sender.id !== ext.runtime.id || message?.type !== 'magnet') return false;
  handleMagnet(message.url).then(sendResponse, (error) => {
    console.error('Ketch: magnet hand-off failed', error);
    sendResponse({ handled: false });
  });
  return true;
});

if (ext.downloads.onDeterminingFilename) {
  // Chromium: the download waits here before any "Save as" dialog, until suggest() is called.
  ext.downloads.onDeterminingFilename.addListener((item, suggest) => {
    captureDownload(item, {
      hold: async () => {},
      release: async () => suggest(),
    });
    return true;
  });
} else {
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
 * Sends a download the browser started to the default instance. `gate.release()` gives the
 * download back to the browser; it runs for skipped downloads and when Ketch can't take it.
 */
async function captureDownload(item, gate) {
  const release = () => gate.release().catch((error) => {
    console.error('Ketch: could not give the download back to the browser', error);
  });
  let settings;
  let capturePaused;
  try {
    [settings, capturePaused] = await Promise.all([loadSettings(), loadCapturePause()]);
  } catch (error) {
    console.error('Ketch: could not load settings', error);
    return release();
  }
  const decision = captureDecision(
    requestContext.forDownload(item), settings, ext.runtime.id, capturePaused);
  if (!decision.capture) {
    console.debug(`Ketch: leaving download ${item.id} to the browser: ${decision.reason}`);
    return release();
  }
  await gate.hold();
  const instance = findInstance(settings);
  const fileName = fileNameFromPath(item.filename);
  let task;
  try {
    task = await sendToKetch(instance, {
      url: item.finalUrl || item.url,
      referrer: item.referrer,
      fileName,
      mime: item.mime,
      cookieStoreId: item.cookieStoreId,
      browserDownloadId: item.id,
    }, settings, { timeoutMs: CAPTURE_TIMEOUT_MS });
  } catch (error) {
    await release();
    if (error.kind === FailureKind.UNCERTAIN) {
      await ext.downloads.pause(item.id).catch(() => {});
      notifyFailure(t('options_diagnostics'), instance, error);
    } else {
      notifyFailure(t('notify_fallback_browser'), instance, error);
    }
    return;
  }
  await removeBrowserDownload(item.id);
  notifySent(settings, instance, taskName(task) || fileName);
}

async function removeBrowserDownload(downloadId) {
  try {
    await ext.downloads.cancel(downloadId);
    await ext.downloads.erase({ id: downloadId });
  } catch (error) {
    // The browser may have finished a tiny file already; Ketch has its own copy either way.
    console.warn('Ketch: could not remove the browser download', error);
  }
}

async function handleMagnet(url) {
  if (await loadCapturePause()) return { handled: false };
  const settings = await loadSettings();
  if (!settings.captureMagnetLinks || !/^magnet:/i.test(String(url))) return { handled: false };
  const instance = findInstance(settings);
  try {
    const task = await sendToKetch(instance, { url }, settings, { timeoutMs: CAPTURE_TIMEOUT_MS });
    notifySent(settings, instance, taskName(task));
    return { handled: true };
  } catch (error) {
    notifyFailure(error.kind === FailureKind.UNCERTAIN
      ? t('options_diagnostics') : t('notify_fallback_magnet'), instance, error);
    return { handled: error.kind === FailureKind.UNCERTAIN };
  }
}

async function handleMenuClick(info, tab) {
  if (info.menuItemId === 'pause-capture') {
    await setCapturePaused(info.checked);
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
    notify(t('notify_unsupported'), url?.startsWith('blob:')
      ? t('notify_unsupported_stream')
      : t('notify_unsupported_scheme'));
    return;
  }
  try {
    const task = await sendToKetch(instance, {
      url,
      referrer: info.frameUrl || info.pageUrl,
      cookieStoreId: await cookieStoreIdForTab(tab),
    }, settings);
    notifySent(settings, instance, taskName(task) || nameFromUrl(url));
  } catch (error) {
    notifyFailure(t('notify_send_failed', instance.name), instance, error);
  }
}

/** Starts a browser download; its byExtensionId keeps it out of automatic capture. */
async function downloadDirectly(info, tab) {
  const url = info.linkUrl || info.srcUrl;
  const firefox = !ext.downloads.onDeterminingFilename;
  // Chromium's spanning background cannot select a private download context. Leave that
  // case to the browser's native save command instead of using the regular cookie store.
  if (!/^https?:\/\//i.test(url ?? '') || (tab?.incognito && !firefox)) {
    notify(t('notify_direct_failed'), t('notify_direct_hint'));
    return;
  }
  const options = { url };
  if (firefox) {
    if (tab?.cookieStoreId) options.cookieStoreId = tab.cookieStoreId;
    if (tab?.incognito) options.incognito = true;
  }
  try {
    // Let the browser choose the name, cookies and whether to show its Save As dialog.
    await ext.downloads.download(options);
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
    const capturePaused = await loadCapturePause();
    await ext.contextMenus.removeAll();
    await createMenu({ id: 'ketch', title: 'Ketch', contexts: ['all'] });
    await createMenu({
      id: 'pause-capture',
      parentId: 'ketch',
      title: t('menu_pause_capture'),
      type: 'checkbox',
      checked: capturePaused,
      contexts: ['all'],
    });
    await createMenu({
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

function notifySent(settings, instance, name) {
  if (!settings.notifications) return;
  const where = settings.instances.length > 1
    ? t('notify_sent_to', instance.name)
    : t('notify_sent_to_ketch');
  notify(where, name || t('notify_download_added'));
}

function notifyFailure(title, instance, error) {
  const message = String(error?.message ?? error).replace(/\.$/, '');
  notify(title, withHint(message, failureHint(error, instance)));
}

function notify(title, message) {
  ext.notifications.create({
    type: 'basic',
    iconUrl: ext.runtime.getURL('icons/icon-128.png'),
    title,
    message,
  }).catch((error) => console.warn('Ketch: could not show a notification', error));
}
