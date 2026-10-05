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
globalThis.chrome = {
  runtime: {
    id: 'ketch-extension',
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
  downloads: { onDeterminingFilename: event() },
};

await import('../src/background.js');
const { setCapturePaused, loadCapturePause } = await import('../src/lib/capture-pause.js');
const { saveSettings, loadSettings } = await import('../src/lib/settings.js');
const ext = globalThis.chrome;
// Storage and menu listeners launch async work, as they do in the browser.
const settle = () => new Promise((resolve) => setImmediate(resolve));

test('Ketch submenu groups download actions and keeps the pause checkbox in sync', async () => {
  await ext.runtime.onStartup.emit();
  assert.equal(menus.get('ketch').title, 'Ketch');
  assert.deepEqual(menus.get('ketch').contexts, ['all']);
  assert.equal(menus.get('pause-capture').parentId, 'ketch');
  assert.equal(menus.get('pause-capture').type, 'checkbox');
  assert.deepEqual(menus.get('pause-capture').contexts, ['all']);
  assert.equal(menus.get('pause-capture').checked, false);
  for (const kind of ['link', 'image', 'video', 'audio']) {
    assert.equal(menus.get(kind).parentId, 'ketch');
    assert.deepEqual(menus.get(kind).contexts, [kind]);
  }

  // The same write as the popup must refresh the context menu.
  await setCapturePaused(true);
  await settle();
  assert.equal(menus.get('pause-capture').checked, true);

  await ext.contextMenus.onClicked.emit({ menuItemId: 'pause-capture', checked: false });
  await settle();
  assert.equal(await loadCapturePause(), false);
  assert.equal(menus.get('pause-capture').checked, false);

  await ext.contextMenus.onClicked.emit({ menuItemId: 'pause-capture', checked: true });
  await settle();
  assert.equal(await loadCapturePause(), true);
  await ext.runtime.onStartup.emit();
  assert.equal(menus.get('pause-capture').checked, true);

  // Paused browser downloads are released and magnet hand-offs are declined.
  let released = false;
  await ext.downloads.onDeterminingFilename.emit(
    { id: 1, url: 'https://example.com/file.zip' },
    () => { released = true; },
  );
  await settle();
  assert.equal(released, true);
  const response = await new Promise((resolve) => {
    ext.runtime.onMessage.emit(
      { type: 'magnet', url: 'magnet:?xt=urn:btih:abc' },
      { id: ext.runtime.id }, resolve,
    );
  });
  assert.deepEqual(response, { handled: false });

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
  assert.equal(menus.get('pause-capture').checked, true);
});
