import assert from 'node:assert/strict';
import { test } from 'node:test';
import { RequestContext } from '../src/lib/request-context.js';

test('non-GET traffic wins when URL observations are ambiguous and expires after 30 seconds', () => {
  let now = 0;
  const context = new RequestContext(() => now);
  const url = 'https://example.com/download';
  context.observe({ requestId: 'post', url, method: 'POST', documentUrl: 'https://example.com/' });
  context.observe({ requestId: 'get', url, method: 'GET' });
  assert.equal(context.forDownload({ url }).requestMethod, 'POST');
  now = 30_001;
  assert.equal(context.forDownload({ url }).requestMethod, undefined);
});

test('request metadata is bounded and excludes headers and request bodies', () => {
  const context = new RequestContext(() => 1);
  for (let id = 0; id < 513; id++) context.observe({ requestId: String(id), method: 'POST',
    url: `https://example.com/${id}`, requestBody: 'secret', headers: 'secret' });
  assert.equal(context.forDownload({ url: 'https://example.com/0' }).requestMethod, undefined);
  assert.equal(JSON.stringify(context.forDownload({ url: 'https://example.com/512' }))
    .includes('secret'), false);
});
