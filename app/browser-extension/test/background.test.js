import assert from 'node:assert/strict';
import { test } from 'node:test';

function event() {
  const listeners = [];
  return {
    addListener: (listener) => listeners.push(listener),
    emit: (...args) => Promise.all(listeners.map((listener) => listener(...args))),
  };
}

const stored = {};
const menus = new Map();
const changed = event();
const directDownloads = [];
const openedTabs = [];
const notifications = [];
let downloadError;
let releasedDirectDownloads = 0;
globalThis.chrome = {
  runtime: {
    id: 'ketch-extension',
    getURL: (path) => `chrome-extension://ketch-extension/${path}`,
    onInstalled: event(),
    onStartup: event(),
    onMessage: event(),
  },
  storage: {
    local: {
      get: async (key) => ({ [key]: stored[key] }),
      set: async (values) => {
        const changes = {};
        for (const [key, value] of Object.entries(values)) {
          changes[key] = { oldValue: stored[key], newValue: value };
          stored[key] = value;
        }
        await changed.emit(changes, 'local');
      },
    },
    onChanged: changed,
  },
  contextMenus: {
    onClicked: event(),
    removeAll: async () => menus.clear(),
    create: (properties, callback) => {
      assert.ok(!menus.has(properties.id));
      if (properties.parentId) assert.ok(menus.has(properties.parentId));
      menus.set(properties.id, properties);
      callback();
    },
  },
  downloads: {
    onDeterminingFilename: event(),
    download: async (options) => {
      directDownloads.push(options);
      if (downloadError) throw downloadError;
      const id = directDownloads.length;
      await globalThis.chrome.downloads.onDeterminingFilename?.emit(
        { id, url: options.url, byExtensionId: 'ketch-extension' },
        () => { releasedDirectDownloads++; },
      );
      return id;
    },
  },
  tabs: {
    onRemoved: event(),
    create: async (options) => { openedTabs.push(options); },
  },
  webRequest: { onBeforeRequest: event() },
  notifications: { create: async (message) => notifications.push(message) },
};

await import('../src/background.js');
const { saveSettings, loadSettings } = await import('../src/lib/settings.js');
const ext = globalThis.chrome;
// Storage and menu listeners launch async work, as they do in the browser.
const settle = () => new Promise((resolve) => setImmediate(resolve));

test('Ketch submenu groups page resources and download actions', async () => {
  await ext.runtime.onStartup.emit();
  assert.equal(menus.get('ketch').title, 'Ketch');
  assert.deepEqual(menus.get('ketch').contexts, ['all']);
  assert.equal(menus.has('pause-capture'), false);
  assert.equal(menus.get('page-resources').parentId, 'ketch');
  assert.equal(menus.get('page-resources').title, 'Page resources');
  assert.deepEqual(menus.get('page-resources').contexts, ['all']);
  assert.deepEqual(menus.get('page-resources').documentUrlPatterns, ['http://*/*', 'https://*/*']);
  assert.equal(menus.get('download-directly').parentId, 'ketch');
  assert.deepEqual(menus.get('download-directly').contexts, ['link', 'image', 'video', 'audio']);
  assert.deepEqual(menus.get('download-directly').targetUrlPatterns, ['http://*/*', 'https://*/*']);
  for (const kind of ['link', 'image', 'video', 'audio']) {
    assert.equal(menus.get(kind).parentId, 'ketch');
    assert.deepEqual(menus.get(kind).contexts, [kind]);
  }

  await saveSettings({
    ...(await loadSettings()),
    instances: [
      { id: 'local', type: 'app', name: 'Local' },
      { id: 'nas', type: 'server', name: 'NAS', url: 'http://nas:8642' },
    ],
    defaultInstanceId: 'nas',
  });
  await settle();
  const linkTargets = [...menus.values()].filter((menu) => menu.parentId === 'link');
  assert.deepEqual(linkTargets.map((menu) => menu.id), ['link:nas', 'link:local']);
});

test('page resources opens the picker for the clicked tab', async () => {
  const start = openedTabs.length;
  await ext.contextMenus.onClicked.emit(
    { menuItemId: 'page-resources', frameId: 3, srcUrl: 'https://example.com/image.png' },
    { id: 42, url: 'https://example.com/page' },
  );
  await settle();
  assert.deepEqual(openedTabs.slice(start), [{
    url: 'chrome-extension://ketch-extension/resources/resources.html?tab=42',
  }]);
});

test('page resources ignores menu events without a valid tab', async () => {
  const start = openedTabs.length;
  for (const tab of [undefined, {}, { id: -1 }, { id: '42' }]) {
    await ext.contextMenus.onClicked.emit({ menuItemId: 'page-resources' }, tab);
    await settle();
  }
  assert.equal(openedTabs.length, start);
});

test('direct links and media bypass capture without changing preferences', async () => {
  const settings = await loadSettings();
  const start = directDownloads.length;
  const releasedBefore = releasedDirectDownloads;
  for (const info of [
    { linkUrl: 'https://example.com/file.zip', srcUrl: 'https://example.com/thumbnail.png' },
    { srcUrl: 'https://example.com/video.mp4' },
  ]) {
    await ext.contextMenus.onClicked.emit({ menuItemId: 'download-directly', ...info });
    await settle();
  }
  assert.deepEqual(directDownloads.slice(start), [
    { url: 'https://example.com/file.zip' },
    { url: 'https://example.com/video.mp4' },
  ]);
  assert.equal(releasedDirectDownloads - releasedBefore, 2);
  assert.deepEqual(await loadSettings(), settings);
});

test('direct Firefox downloads keep the tab container and private context', async () => {
  const chromiumHook = ext.downloads.onDeterminingFilename;
  delete ext.downloads.onDeterminingFilename;
  try {
    const start = directDownloads.length;
    for (const tab of [
      { cookieStoreId: 'firefox-container-2' },
      { cookieStoreId: 'firefox-private', incognito: true },
    ]) {
      await ext.contextMenus.onClicked.emit(
        { menuItemId: 'download-directly', linkUrl: 'https://example.com/file.zip' }, tab,
      );
      await settle();
    }
    assert.deepEqual(directDownloads.slice(start), [
      { url: 'https://example.com/file.zip', cookieStoreId: 'firefox-container-2' },
      { url: 'https://example.com/file.zip', cookieStoreId: 'firefox-private', incognito: true },
    ]);
  } finally {
    ext.downloads.onDeterminingFilename = chromiumHook;
  }
});

test('unsupported or failed direct downloads report a browser fallback without changing capture',
  async () => {
    const settings = await loadSettings();
    const start = directDownloads.length;
    const noticeCount = notifications.length;
    for (const linkUrl of ['magnet:?xt=urn:btih:abc', 'javascript:alert(1)', 'blob:https://a/1']) {
      await ext.contextMenus.onClicked.emit({ menuItemId: 'download-directly', linkUrl });
      await settle();
    }
    await ext.contextMenus.onClicked.emit(
      { menuItemId: 'download-directly', linkUrl: 'https://example.com/private.zip' },
      { incognito: true },
    );
    await settle();
    assert.equal(directDownloads.length, start);
    assert.equal(notifications.length - noticeCount, 4);

    downloadError = new Error('Browser download failed');
    try {
      await ext.contextMenus.onClicked.emit(
        { menuItemId: 'download-directly', linkUrl: 'https://example.com/file.zip' },
      );
      await settle();
      assert.equal(notifications.length - noticeCount, 5);
      assert.equal(notifications.at(-1).title, 'Could not start the browser download');
      assert.deepEqual(await loadSettings(), settings);
    } finally {
      downloadError = undefined;
    }
  });
