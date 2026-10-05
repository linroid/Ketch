import assert from 'node:assert/strict';
import { test } from 'node:test';

// Exercise the actual page handlers with storage held pending between the first two clicks.
test('batch send locks before awaiting storage and prevents a competing send or scan', async () => {
  class Element {
    children = []; listeners = {}; value = ''; disabled = false; checked = false;
    constructor(tag) { this.tag = tag; }
    append(...children) { for (const child of children) { child.parent = this; this.children.push(child); } }
    replaceChildren(...children) { this.children = []; this.append(...children); }
    addEventListener(name, fn) { this.listeners[name] = fn; }
    closest(tag) { return this.tag === tag ? this : this.parent.closest(tag); }
    querySelector(tag) { return this.children.find((it) => it.tag === tag); }
  }
  const elements = Object.fromEntries(['scan', 'kind', 'all', 'resources', 'send', 'instance',
    'status', 'folder'].map((id) => [id, new Element(id)]));
  const boxes = () => elements.resources.children.map((row) => row.children[0].children[0]);
  globalThis.document = {
    documentElement: {}, getElementById: (id) => elements[id],
    createElement: (tag) => new Element(tag),
    querySelectorAll: (selector) => selector.startsWith('#resources input')
      ? boxes().filter((box) => !box.disabled && (!selector.includes(':checked') || box.checked)) : [],
    querySelector: () => boxes().find((box) => box.checked),
  };
  globalThis.Option = class extends Element { constructor(name, value) { super('option'); this.value = value; } };
  globalThis.location = { href: 'chrome-extension://test/resources/resources.html?tab=1' };
  const settings = { instances: [{ id: 'server', type: 'server', url: 'https://server.example',
    forwardCookies: false }], defaultInstanceId: 'server' };
  let hold;
  let writes = 0;
  let scans = 0;
  globalThis.chrome = {
    storage: { local: { get: async () => { if (hold) await hold; return { settings }; } } },
    tabs: { get: async () => ({ url: 'https://example.com/page' }) },
    scripting: { executeScript: async () => {
      scans++;
      return [{ result: [{ url: 'https://example.com/file.zip', name: 'reports/file.pdf', kind: 'file',
        pageUrl: 'https://example.com/page' }] }];
    } },
  };
  globalThis.fetch = async (url, init) => {
    if (url.endsWith('/api/status')) return Response.json({ features: [] });
    assert.equal(init.method, 'POST');
    assert.equal(JSON.parse(init.body).destination, 'file.pdf');
    writes++;
    return Response.json({ taskId: 'one', request: JSON.parse(init.body) });
  };
  await import('../src/resources/resources.js');
  await new Promise((resolve) => setImmediate(resolve));
  elements.instance.value = 'server';
  elements.kind.value = 'all';
  elements.kind.listeners.change();
  boxes()[0].checked = true;
  elements.resources.listeners.change();
  let release;
  hold = new Promise((resolve) => { release = resolve; });
  const first = elements.send.listeners.click();
  assert.equal(elements.send.disabled, true);
  assert.equal(elements.scan.disabled, true);
  const second = elements.send.listeners.click();
  await elements.scan.listeners.click();
  release();
  hold = undefined;
  await Promise.all([first, second]);
  assert.equal(writes, 1);
  assert.equal(scans, 1);
  assert.equal(boxes()[0].disabled, true);
});
