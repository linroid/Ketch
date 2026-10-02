import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import {
  buildDownloadRequest,
  buildHeaders,
  fileNameFromPath,
  isSupportedLinkUrl,
  isTorrentFile,
  isTorrentUrl,
} from '../src/lib/request.js';

describe('isSupportedLinkUrl', () => {
  test('accepts links Ketch can fetch itself', () => {
    const urls = ['https://a.com/f', 'http://a.com', 'ftp://a.com/f', 'magnet:?xt=urn:btih:x'];
    for (const url of urls) {
      assert.ok(isSupportedLinkUrl(url), url);
    }
  });

  test('rejects links that only exist in the browser', () => {
    for (const url of ['blob:https://a.com/1', 'data:text/plain,hi', 'file:///tmp/a', 'nonsense']) {
      assert.equal(isSupportedLinkUrl(url), false, url);
    }
  });
});

describe('torrent detection', () => {
  test('isTorrentUrl matches magnets and .torrent paths, ignoring the query', () => {
    assert.ok(isTorrentUrl('magnet:?xt=urn:btih:abc'));
    assert.ok(isTorrentUrl('https://a.com/ubuntu.TORRENT?passkey=1'));
    assert.equal(isTorrentUrl('https://a.com/download.php?file=ubuntu.torrent'), false);
  });

  test('isTorrentFile matches the MIME type or file name', () => {
    assert.ok(isTorrentFile({ mime: 'application/x-bittorrent' }));
    assert.ok(isTorrentFile({ fileName: 'Ubuntu.Torrent' }));
    assert.equal(isTorrentFile({ mime: 'application/zip', fileName: 'a.zip' }), false);
  });
});

describe('fileNameFromPath', () => {
  test('takes the last component of POSIX and Windows paths', () => {
    assert.equal(fileNameFromPath('/Users/me/Downloads/a.zip'), 'a.zip');
    assert.equal(fileNameFromPath('C:\\Users\\me\\Downloads\\b.iso'), 'b.iso');
    assert.equal(fileNameFromPath('c.txt'), 'c.txt');
    assert.equal(fileNameFromPath(undefined), '');
  });
});

describe('buildHeaders', () => {
  test('joins cookies into one header and sends the referrer without its fragment', () => {
    const headers = buildHeaders({
      cookies: [{ name: 'sid', value: 'abc' }, { name: 'lang', value: 'en' }],
      referrer: 'https://a.com/page?x=1#section',
      userAgent: 'Browser/1.0',
    });
    assert.deepEqual(headers, {
      Cookie: 'sid=abc; lang=en',
      Referer: 'https://a.com/page?x=1',
      'User-Agent': 'Browser/1.0',
    });
  });

  test('leaves out empty cookies and referrers that are not web pages', () => {
    assert.deepEqual(buildHeaders({ cookies: [], referrer: 'chrome://newtab/' }), {});
  });
});

describe('buildDownloadRequest', () => {
  const properties = { 'ketch.origin': 'browser' };

  test('the browser file name becomes a bare-name destination', () => {
    assert.deepEqual(
      buildDownloadRequest({ url: 'https://a.com/get?id=1', fileName: '/tmp/Report.pdf' }),
      { url: 'https://a.com/get?id=1', destination: 'Report.pdf', properties },
    );
  });

  test('tags every request with the browser origin, never as a header', () => {
    const requests = [
      buildDownloadRequest({ url: 'https://a.com/f', headers: { Cookie: 'a=1' } }),
      buildDownloadRequest({ url: 'magnet:?xt=urn:btih:abc' }),
      buildDownloadRequest({ url: 'torrent:abc', resolvedSource: { url: 'torrent:abc' } }),
    ];
    for (const request of requests) {
      assert.deepEqual(request.properties, properties, request.url);
      assert.equal(request.headers?.['ketch.origin'], undefined, request.url);
    }
  });

  test('passes a resolved source through unchanged', () => {
    const resolvedSource = { url: 'torrent:abc', sourceType: 'torrent' };
    assert.deepEqual(
      buildDownloadRequest({ url: 'torrent:abc', resolvedSource }),
      { url: 'torrent:abc', resolvedSource, properties },
    );
  });

  test('torrents never get a destination name', () => {
    const request = buildDownloadRequest({ url: 'https://a.com/x.torrent', fileName: 'x.torrent' });
    assert.equal(request.destination, undefined);
  });

  test('includes headers only when there are some', () => {
    assert.deepEqual(
      buildDownloadRequest({ url: 'https://a.com/f' }),
      { url: 'https://a.com/f', properties },
    );
    assert.deepEqual(
      buildDownloadRequest({ url: 'https://a.com/f', headers: { Cookie: 'a=1' } }).headers,
      { Cookie: 'a=1' },
    );
  });
});
