import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { FailureKind, KetchClient } from '../src/lib/ketch-client.js';

function recordingFetch(respond) {
  const calls = [];
  const fetch = async (url, init) => {
    calls.push({ url, init });
    return respond(url, init);
  };
  return { calls, fetch };
}

/** A fetch that never answers until its request is aborted. */
function hangingFetch(url, init) {
  return new Promise((resolve, reject) => {
    // AbortSignal.timeout doesn't keep Node's event loop alive; this timer does.
    const keepAlive = setTimeout(() => {}, 5_000);
    init.signal.addEventListener('abort', () => {
      clearTimeout(keepAlive);
      reject(init.signal.reason);
    });
  });
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('KetchClient', () => {
  test('creates a task with a bearer token and a JSON body', async () => {
    const { calls, fetch } = recordingFetch(() => json({ taskId: 't1' }, 201));
    const client = new KetchClient({ url: 'http://nas:8642/', token: 'secret' }, { fetch });

    const task = await client.createTask({ url: 'https://a.com/f' });

    assert.equal(task.taskId, 't1');
    assert.equal(calls[0].url, 'http://nas:8642/api/tasks');
    assert.equal(calls[0].init.method, 'POST');
    assert.equal(calls[0].init.headers.Authorization, 'Bearer secret');
    assert.equal(calls[0].init.headers['Content-Type'], 'application/json');
    assert.deepEqual(JSON.parse(calls[0].init.body), { url: 'https://a.com/f' });
  });

  test('sends no Authorization header without a token', async () => {
    const { calls, fetch } = recordingFetch(() => json({ tasks: [] }));
    await new KetchClient({ url: 'http://nas:8642' }, { fetch }).listTasks();
    assert.equal(calls[0].init.headers.Authorization, undefined);
  });

  test('posts content to resolve with the file name in the query', async () => {
    const { calls, fetch } = recordingFetch(() => json({ url: 'torrent:abc' }));
    const client = new KetchClient({ url: 'http://nas:8642' }, { fetch });

    await client.resolveContent(new Uint8Array([100, 49]), 'my file.torrent');

    assert.equal(calls[0].url, 'http://nas:8642/api/resolve/content?fileName=my%20file.torrent');
    assert.equal(calls[0].init.headers['Content-Type'], 'application/octet-stream');
  });

  test('a refused connection is reported as unreachable', async () => {
    const fetch = async () => {
      throw new TypeError('fetch failed');
    };
    await assert.rejects(
      new KetchClient({ url: 'http://127.0.0.1:8642' }, { fetch }).status(),
      { kind: FailureKind.UNREACHABLE, message: "Can't reach Ketch at http://127.0.0.1:8642" },
    );
  });

  test('a server that never answers times out', async () => {
    const client = new KetchClient(
      { url: 'http://10.0.0.1:8642' }, { fetch: hangingFetch, timeoutMs: 10 });
    await assert.rejects(client.status(), { kind: FailureKind.TIMEOUT });
  });

  test('a shared deadline ends a request before its own timeout', async () => {
    const client = new KetchClient({ url: 'http://10.0.0.1:8642' }, {
      fetch: hangingFetch,
      timeoutMs: 60_000,
      signal: AbortSignal.timeout(10),
    });
    await assert.rejects(client.status(), { kind: FailureKind.TIMEOUT });
  });

  test('401 asks for a token, or a different one when a token was sent', async () => {
    const { fetch } = recordingFetch(() => new Response('', { status: 401 }));
    await assert.rejects(
      new KetchClient({ url: 'http://nas:8642' }, { fetch }).status(),
      { kind: FailureKind.UNAUTHORIZED, message: 'Ketch requires an access token' },
    );
    await assert.rejects(
      new KetchClient({ url: 'http://nas:8642', token: 'old' }, { fetch }).status(),
      { kind: FailureKind.UNAUTHORIZED, message: 'Ketch rejected the access token' },
    );
  });

  test('an error response carries the server message', async () => {
    const { fetch } = recordingFetch(
      () => json({ error: 'bad_request', message: 'URL must not be blank' }, 400));
    await assert.rejects(
      new KetchClient({ url: 'http://nas:8642' }, { fetch }).createTask({ url: ' ' }),
      { kind: FailureKind.REJECTED, status: 400, message: 'URL must not be blank' },
    );
  });

  test('an error response without a message names the status', async () => {
    const { fetch } = recordingFetch(() => new Response('<html>', { status: 404 }));
    await assert.rejects(
      new KetchClient({ url: 'http://nas:8642' }, { fetch }).status(),
      { kind: FailureKind.REJECTED, message: 'Ketch answered with HTTP 404' },
    );
  });
});
