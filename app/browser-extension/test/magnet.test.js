import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { runInNewContext } from 'node:vm';

const source = readFileSync(new URL('../src/content/magnet.js', import.meta.url), 'utf8');

test('magnet clicks follow capture settings and ignore the removed pause flag',
  async () => {
    let click;
    let storageChanged;
    let sent = 0;
    class Anchor {
      href = 'magnet:?xt=urn:btih:abc';
    }
    runInNewContext(source, {
      chrome: {
        runtime: {
          id: 'ketch',
          sendMessage: async () => { sent++; return { handled: true }; },
        },
        storage: {
          local: { get: async () => ({ captureMagnetLinks: false, capturePaused: true }) },
          onChanged: { addListener: (listener) => { storageChanged = listener; } },
        },
      },
      HTMLAnchorElement: Anchor,
      document: { addEventListener: (_, listener) => { click = listener; } },
    });
    await Promise.resolve();
    const clickMagnet = () => {
      let prevented = false;
      click({
        isTrusted: true,
        button: 0,
        composedPath: () => [new Anchor()],
        preventDefault: () => { prevented = true; },
      });
      return prevented;
    };
    assert.equal(clickMagnet(), false);
    assert.equal(sent, 0);
    storageChanged({ captureMagnetLinks: { newValue: true } }, 'local');
    assert.equal(clickMagnet(), true);
    assert.equal(sent, 1);

    storageChanged({ capturePaused: { newValue: true } }, 'local');
    assert.equal(clickMagnet(), true);
    assert.equal(sent, 2);

    storageChanged({ captureMagnetLinks: { newValue: false } }, 'local');
    assert.equal(clickMagnet(), false);
    assert.equal(sent, 2);
  });
