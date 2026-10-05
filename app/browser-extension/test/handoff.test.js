import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { MAX_TORRENT_BYTES, sendToKetch } from '../src/lib/handoff.js';
import { normalizeSettings } from '../src/lib/settings.js';

const instance = { id: 'nas', name: 'NAS', forwardCookies: true, url: 'http://nas:8642', token: '' };
const settings = normalizeSettings({ instances: [instance] });
const TORRENT_BYTES = new TextEncoder().encode('d8:announce0:e');

const TORRENT_DOWNLOAD = {
  url: 'https://tracker.example/download.php?id=7',
  fileName: 'ubuntu.torrent',
  mime: 'application/x-bittorrent',
};

/** A browser and a Ketch server in one fetch: requests to nas:8642 go to Ketch. */
function fakeDeps({
  cookies = [{ name: 'sid', value: '42' }],
  getCookies,
  siteResponse = () => new Response(TORRENT_BYTES),
} = {}) {
  const ketchCalls = [];
  const siteCalls = [];
  const cookieQueries = [];
  const fetch = async (url, init = {}) => {
    if (!url.startsWith(instance.url)) {
      siteCalls.push({ url, init });
      return siteResponse(init);
    }
    if (url.endsWith('/api/status')) return Response.json({ features: [] });
    const body = typeof init.body === 'string' ? JSON.parse(init.body) : init.body;
    ketchCalls.push({ path: url.slice(instance.url.length), body });
    if (url.includes('/api/resolve/content')) {
      return Response.json({ url: 'torrent:abc', sourceType: 'torrent' });
    }
    return Response.json({ taskId: 'task-1', request: body }, { status: 201 });
  };
  return {
    ketchCalls,
    siteCalls,
    cookieQueries,
    deps: {
      fetch,
      userAgent: 'Browser/1.0',
      getCookies: getCookies ?? (async (url, storeId) => {
        cookieQueries.push({ url, storeId });
        return cookies;
      }),
    },
  };
}

describe('sendToKetch', () => {
  test('sends cookies, referrer, user agent and the browser file name', async () => {
    const { deps, ketchCalls, cookieQueries } = fakeDeps();

    await sendToKetch(instance, {
      url: 'https://a.com/get?id=1',
      referrer: 'https://a.com/page',
      fileName: 'report.pdf',
      cookieStoreId: 'firefox-container-1',
    }, settings, { deps });

    assert.deepEqual(cookieQueries, [
      { url: 'https://a.com/get?id=1', storeId: 'firefox-container-1' },
    ]);
    assert.deepEqual(ketchCalls, [{
      path: '/api/tasks',
      body: {
        url: 'https://a.com/get?id=1',
        destination: 'report.pdf',
        headers: { Cookie: 'sid=42', Referer: 'https://a.com/page', 'User-Agent': 'Browser/1.0' },
        properties: { 'ketch.origin': 'browser' },
      },
    }]);
  });

  test('without cookie forwarding, reads no cookies and sends no referrer', async () => {
    const { deps, ketchCalls, cookieQueries } = fakeDeps();
    const private_ = { ...settings, forwardCookies: false };

    await sendToKetch(instance, { url: 'https://a.com/f', referrer: 'https://a.com/' }, private_,
      { deps });

    assert.deepEqual(cookieQueries, []);
    assert.deepEqual(ketchCalls[0].body.headers, { 'User-Agent': 'Browser/1.0' });
  });

  test('still sends the download when cookies cannot be read', async () => {
    const { deps, ketchCalls } = fakeDeps({
      getCookies: async () => {
        throw new Error('no permission');
      },
    });
    const originalWarn = console.warn;
    console.warn = () => {};
    try {
      await sendToKetch(instance, { url: 'https://a.com/f' }, settings, { deps });
    } finally {
      console.warn = originalWarn;
    }
    assert.equal(ketchCalls[0].body.headers.Cookie, undefined);
  });

  test('sends magnet links as they are, without browser headers', async () => {
    const { deps, ketchCalls, cookieQueries } = fakeDeps();

    await sendToKetch(instance, { url: 'magnet:?xt=urn:btih:abc', referrer: 'https://a.com/' },
      settings, { deps });

    assert.deepEqual(cookieQueries, []);
    assert.deepEqual(ketchCalls, [
      {
        path: '/api/tasks',
        body: { url: 'magnet:?xt=urn:btih:abc', properties: { 'ketch.origin': 'browser' } },
      },
    ]);
  });

  test('resolves a .torrent file whose link Ketch would not recognize from its content',
    async () => {
      const { deps, ketchCalls, siteCalls } = fakeDeps();

      await sendToKetch(instance, TORRENT_DOWNLOAD, settings, { deps });

      assert.equal(siteCalls.length, 1);
      assert.equal(siteCalls[0].init.credentials, 'include');
      assert.equal(ketchCalls[0].path, '/api/resolve/content?fileName=ubuntu.torrent');
      assert.deepEqual(new Uint8Array(ketchCalls[0].body), TORRENT_BYTES);
      assert.deepEqual(ketchCalls[1], {
        path: '/api/tasks',
        body: {
          url: 'torrent:abc',
          resolvedSource: { url: 'torrent:abc', sourceType: 'torrent' },
          properties: { 'ketch.origin': 'browser' },
        },
      });
    });

  test('gives up on a stalled .torrent fetch at the hand-off deadline', async () => {
    const { deps, ketchCalls } = fakeDeps({
      siteResponse: (init) => new Promise((resolve, reject) => {
        // AbortSignal.timeout doesn't keep Node's event loop alive; this timer does.
        const keepAlive = setTimeout(() => {}, 5_000);
        init.signal.addEventListener('abort', () => {
          clearTimeout(keepAlive);
          reject(init.signal.reason);
        });
      }),
    });
    const started = Date.now();

    await assert.rejects(
      sendToKetch(instance, TORRENT_DOWNLOAD, settings, { deps, timeoutMs: 20 }),
      /Timed out fetching the torrent file/,
    );
    assert.ok(Date.now() - started < 1_000);
    assert.deepEqual(ketchCalls, []);
  });

  test('stops reading a .torrent response once it exceeds the size limit', async () => {
    const chunk = new Uint8Array(1024 * 1024);
    let pulls = 0;
    const { deps, ketchCalls } = fakeDeps({
      siteResponse: () => new Response(new ReadableStream({
        pull(controller) {
          pulls++;
          if (pulls > 64) controller.close();
          else controller.enqueue(chunk);
        },
      })),
    });

    await assert.rejects(
      sendToKetch(instance, TORRENT_DOWNLOAD, settings, { deps }),
      /too large/,
    );
    assert.ok(pulls <= MAX_TORRENT_BYTES / chunk.length + 2, `read ${pulls} chunks`);
    assert.deepEqual(ketchCalls, []);
  });

  test('rejects a .torrent response that declares a size over the limit', async () => {
    const { deps, ketchCalls } = fakeDeps({
      siteResponse: () => new Response(TORRENT_BYTES, {
        headers: { 'Content-Length': String(MAX_TORRENT_BYTES + 1) },
      }),
    });

    await assert.rejects(
      sendToKetch(instance, TORRENT_DOWNLOAD, settings, { deps }),
      /too large/,
    );
    assert.deepEqual(ketchCalls, []);
  });

  test('lets Ketch fetch a .torrent link it recognizes, with the browser headers', async () => {
    const { deps, ketchCalls, siteCalls } = fakeDeps();

    await sendToKetch(instance, {
      url: 'https://tracker.example/ubuntu.torrent',
      fileName: 'ubuntu.torrent',
    }, settings, { deps });

    assert.equal(siteCalls.length, 0);
    assert.equal(ketchCalls[0].body.url, 'https://tracker.example/ubuntu.torrent');
    assert.equal(ketchCalls[0].body.headers.Cookie, 'sid=42');
  });

  test('refuses links that only exist in the browser', async () => {
    const { deps, ketchCalls } = fakeDeps();
    await assert.rejects(
      sendToKetch(instance, { url: 'blob:https://a.com/1' }, settings, { deps }),
      /can't download this kind of link/,
    );
    assert.deepEqual(ketchCalls, []);
  });
});

test('remote credentials require both global and per-server permission', async () => {
  const { deps, ketchCalls, cookieQueries } = fakeDeps();
  await sendToKetch({ ...instance, forwardCookies: false },
    { url: 'https://a.com/file', referrer: 'https://a.com/' }, settings, { deps });
  assert.deepEqual(cookieQueries, []);
  assert.deepEqual(ketchCalls[0].body.headers, { 'User-Agent': 'Browser/1.0' });
});

test('remote torrent-content resolution also obeys credential permission', async () => {
  const { deps, siteCalls } = fakeDeps();
  await sendToKetch({ ...instance, forwardCookies: false }, TORRENT_DOWNLOAD, settings, { deps });
  assert.equal(siteCalls[0].init.credentials, 'omit');
});

test('review destination applies after resolving a torrent and can clear a suggested file name',
  async () => {
    const { deps, ketchCalls } = fakeDeps();
    await sendToKetch(instance, { ...TORRENT_DOWNLOAD, destination: '/downloads/' }, settings,
      { deps });
    assert.equal(ketchCalls.at(-1).body.url, 'torrent:abc');
    assert.equal(ketchCalls.at(-1).body.destination, '/downloads/');
    await sendToKetch(instance, { url: 'https://example.com/get', fileName: 'suggested.zip',
      destination: undefined }, settings, { deps });
    assert.equal(ketchCalls.at(-1).body.destination, undefined);
  });

test('a restart after submission reconnects and reconciles the same receipt without another POST',
  async () => {
    for (const retained of [true, false]) {
      const app = { id: 'local', type: 'app', name: 'App' };
      const old = { url: 'http://127.0.0.1:10001', token: 'old' };
      const fresh = { url: 'http://127.0.0.1:10002', token: 'new' };
      let cached = old;
      let submitted;
      let writes = 0;
      let reconnects = 0;
      const deps = {
        userAgent: 'Test', getCookies: async () => [],
        connection: {
          loadEndpoint: async () => cached,
          saveEndpoint: async (value) => { cached = value; },
          sendNativeMessage: async () => { reconnects++; return fresh; },
        },
        fetch: async (url, init) => {
          if (url.endsWith('/api/status')) return Response.json({ features: ['task.requestId'] });
          if (init.method === 'POST') {
            writes++;
            submitted = JSON.parse(init.body);
            throw new TypeError('app restarted before the response');
          }
          if (url.startsWith(old.url)) throw new TypeError('old port closed');
          assert.equal(init.headers.Authorization, 'Bearer new');
          return Response.json({ tasks: retained ? [{ taskId: 'kept', request: submitted }] : [] });
        },
      };
      const send = sendToKetch(app, { url: 'https://example.com/file' }, settings, { deps });
      if (retained) assert.equal((await send).taskId, 'kept');
      else await assert.rejects(send, { kind: 'uncertain' });
      assert.equal(writes, 1);
      assert.equal(reconnects, 1);
      assert.ok(submitted.requestId);
    }
  });
