import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { sendToKetch } from '../src/lib/handoff.js';
import { normalizeSettings } from '../src/lib/settings.js';

const instance = { id: 'nas', name: 'NAS', url: 'http://nas:8642', token: '' };
const settings = normalizeSettings({ instances: [instance] });
const TORRENT_BYTES = new TextEncoder().encode('d8:announce0:e');

/** A browser and a Ketch server in one fetch: requests to nas:8642 go to Ketch. */
function fakeDeps({ cookies = [{ name: 'sid', value: '42' }], getCookies } = {}) {
  const ketchCalls = [];
  const siteCalls = [];
  const cookieQueries = [];
  const fetch = async (url, init = {}) => {
    if (!url.startsWith(instance.url)) {
      siteCalls.push({ url, init });
      return new Response(TORRENT_BYTES);
    }
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
      { path: '/api/tasks', body: { url: 'magnet:?xt=urn:btih:abc' } },
    ]);
  });

  test('resolves a .torrent file whose link Ketch would not recognize from its content',
    async () => {
      const { deps, ketchCalls, siteCalls } = fakeDeps();

      await sendToKetch(instance, {
        url: 'https://tracker.example/download.php?id=7',
        fileName: 'ubuntu.torrent',
        mime: 'application/x-bittorrent',
      }, settings, { deps });

      assert.equal(siteCalls.length, 1);
      assert.equal(siteCalls[0].init.credentials, 'include');
      assert.equal(ketchCalls[0].path, '/api/resolve/content?fileName=ubuntu.torrent');
      assert.deepEqual(new Uint8Array(ketchCalls[0].body), TORRENT_BYTES);
      assert.deepEqual(ketchCalls[1], {
        path: '/api/tasks',
        body: {
          url: 'torrent:abc',
          resolvedSource: { url: 'torrent:abc', sourceType: 'torrent' },
        },
      });
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
