import assert from 'node:assert/strict';
import { test } from 'node:test';
import { STORE_ITEM_ID, uploadToStore } from '../store-upload.mjs';

const credentials = {
  clientId: 'client', clientSecret: 'secret', refreshToken: 'refresh', publisherId: 'pub',
};

/** A fetch answering each request with the next of `replies`, recording what was asked. */
function fakeFetch(replies) {
  const calls = [];
  const fetch = async (url, init = {}) => {
    calls.push({ url, ...init });
    const [status, body] = replies.shift();
    return new Response(JSON.stringify(body), { status });
  };
  return { fetch, calls };
}

function upload(fetch) {
  return uploadToStore({
    zip: new Uint8Array([1, 2, 3]), version: '0.3.1.7', credentials, fetch,
    sleep: async () => {}, log: () => {}, now: () => 0,
  });
}

test('uploads the package, waits for the store and submits it for review', async () => {
  const { fetch, calls } = fakeFetch([
    [200, { access_token: 'token' }],
    [200, { uploadState: 'IN_PROGRESS' }],
    [200, { lastAsyncUploadState: 'IN_PROGRESS' }],
    [200, { lastAsyncUploadState: 'SUCCEEDED' }],
    [200, { state: 'PENDING_REVIEW' }],
  ]);
  const published = await upload(fetch);
  assert.equal(published.state, 'PENDING_REVIEW');
  const item = `publishers/pub/items/${STORE_ITEM_ID}`;
  assert.deepEqual(calls.map((call) => call.url), [
    'https://oauth2.googleapis.com/token',
    `https://chromewebstore.googleapis.com/upload/v2/${item}:upload`,
    `https://chromewebstore.googleapis.com/v2/${item}:fetchStatus`,
    `https://chromewebstore.googleapis.com/v2/${item}:fetchStatus`,
    `https://chromewebstore.googleapis.com/v2/${item}:publish`,
  ]);
  assert.match(calls[0].body, /grant_type=refresh_token/);
  assert.equal(calls[1].headers.Authorization, 'Bearer token');
  assert.deepEqual([...calls[1].body], [1, 2, 3]);
  assert.deepEqual(JSON.parse(calls[4].body), { publishType: 'DEFAULT_PUBLISH' });
});

test('a refused upload, another version or an API error is never submitted', async () => {
  for (const [replies, message] of [
    [[[200, { access_token: 't' }], [200, { uploadState: 'FAILED' }]],
      /refused the upload: FAILED/],
    [[[200, { access_token: 't' }], [200, { uploadState: 'SUCCEEDED', crxVersion: '0.3.1.6' }]],
      /holds version 0\.3\.1\.6, not 0\.3\.1\.7/],
    [[[200, { access_token: 't' }], [400, { error: { message: 'Version must be greater' } }]],
      /upload failed with HTTP 400: Version must be greater/],
    [[[401, { error: 'invalid_grant', error_description: 'Token has been expired' }]],
      /HTTP 401: Token has been expired/],
  ]) {
    const { fetch, calls } = fakeFetch(replies);
    await assert.rejects(upload(fetch), message);
    assert.ok(!calls.some((call) => call.url.endsWith(':publish')));
    assert.ok(!calls.some((call) => JSON.stringify(call).includes('secret') &&
      !call.url.endsWith('/token')));
  }
});
