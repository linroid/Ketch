import assert from 'node:assert/strict';
import { test } from 'node:test';

const stored = {};
globalThis.chrome = {
  storage: {
    local: {
      get: async (key) => ({ [key]: stored[key] }),
      set: async (values) => Object.assign(stored, values),
    },
  },
};
const {
  loadCapturePause,
  setCapturePaused,
} = await import('../src/lib/capture-pause.js');
const { loadSettings, saveSettings } = await import('../src/lib/settings.js');

test('pause survives reloads and preference edits; resuming preserves capture preferences',
  async (context) => {
    context.mock.timers.enable({ apis: ['Date'], now: 1_000 });
    await saveSettings({ interceptDownloads: false, captureMagnetLinks: true });
    await setCapturePaused(true);
    assert.equal(await loadCapturePause(), true);

    await saveSettings({ ...(await loadSettings()), notifications: false });
    assert.equal(await loadCapturePause(), true);
    context.mock.timers.tick(7 * 24 * 60 * 60 * 1_000);
    assert.equal(await loadCapturePause(), true);
    await setCapturePaused(false);
    assert.equal(await loadCapturePause(), false);
    const settings = await loadSettings();
    assert.equal(settings.interceptDownloads, false);
    assert.equal(settings.captureMagnetLinks, true);
    assert.equal(settings.notifications, false);
  });

test('missing and malformed pause flags do not disable capturing', async () => {
  for (const value of [undefined, null, 'true', 1, 0, false]) {
    stored.capturePaused = value;
    assert.equal(await loadCapturePause(), false);
  }
});
