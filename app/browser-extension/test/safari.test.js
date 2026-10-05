import assert from 'node:assert/strict';
import { test } from 'node:test';

function event() { return { addListener() {} }; }
const menus = new Map();
const data = {};
const stored = {
  get: async (key) => key === null ? { ...data } : { [key]: data[key] },
  set: async (values) => Object.assign(data, values),
  remove: async (key) => { delete data[key]; },
};
globalThis.browser = {
  runtime: {
    id: 'safari-extension',
    getManifest: () => ({ permissions: ['contextMenus', 'cookies', 'storage', 'scripting', 'activeTab'] }),
    getURL: (path) => `safari-web-extension://id/${path}`,
    onInstalled: event(), onStartup: event(), onMessage: event(),
  },
  storage: { local: stored, session: stored, onChanged: event() },
  tabs: { onRemoved: event(), create: async () => ({ id: 7 }) },
  contextMenus: {
    onClicked: event(), removeAll: async () => menus.clear(),
    create: (options, callback) => { menus.set(options.id, options); callback(); },
  },
};
for (const name of ['downloads', 'webRequest', 'notifications']) {
  Object.defineProperty(globalThis.browser, name, { get() { throw new Error(`Unsupported: ${name}`); } });
}
const { normalizeSettings } = await import('../src/lib/settings.js');

test('Safari starts with a configured-server entry and cannot enable unavailable features', () => {
  const settings = normalizeSettings({ interceptDownloads: true, confirmDownloads: false,
    notifications: true, instances: [{ id: 'app', type: 'app' }] });
  assert.equal(settings.instances[0].type, 'server');
  assert.equal(settings.instances[0].url, 'http://127.0.0.1:8642');
  assert.equal(settings.interceptDownloads, false);
  assert.equal(settings.confirmDownloads, true);
  assert.equal(settings.notifications, false);
});

test('background loads with no downloads, native messaging, web request or notification APIs',
  async () => {
    let startup;
    globalThis.browser.runtime.onStartup.addListener = (listener) => { startup = listener; };
    await import('../src/background.js');
    await startup();
    assert.ok(menus.has('link'));
    assert.ok(menus.has('video'));
    assert.ok(!menus.has('download-directly'));
    assert.ok(!menus.has('pause-capture'));
  });
