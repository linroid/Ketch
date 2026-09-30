import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { NATIVE_HOST, withEndpoint } from '../src/lib/connection.js';
import { FailureKind, KetchRequestError } from '../src/lib/ketch-client.js';

const app = { id: 'local', type: 'app', name: 'This computer' };
const server = { id: 'nas', type: 'server', name: 'NAS', url: 'http://nas:8642', token: 't' };
const endpoint = { url: 'http://127.0.0.1:51000', token: 'fresh' };

/** The Ketch app's native messaging host, answering with `replies` in turn. */
function fakeApp(...replies) {
  const messages = [];
  let stored;
  return {
    messages,
    stored: () => stored,
    deps: {
      sendNativeMessage: async (host, message) => {
        assert.equal(host, NATIVE_HOST);
        messages.push(message);
        const reply = replies.shift();
        if (reply instanceof Error) throw reply;
        return reply;
      },
      loadEndpoint: async () => stored,
      saveEndpoint: async (value) => {
        stored = value;
      },
    },
  };
}

const unreachable = () => new KetchRequestError(FailureKind.UNREACHABLE, 'gone');

describe('withEndpoint', () => {
  test('a server is used as it is, without native messaging', async () => {
    const { deps, messages } = fakeApp();
    assert.equal(await withEndpoint(server, async (it) => it, { deps }), server);
    assert.deepEqual(messages, []);
  });

  test('connects to the Ketch app, starting it, and keeps its endpoint', async () => {
    const { deps, messages, stored } = fakeApp(endpoint);

    assert.deepEqual(await withEndpoint(app, async (it) => it, { deps }), endpoint);

    assert.deepEqual(messages, [{ type: 'connect', launch: true }]);
    assert.deepEqual(stored(), endpoint);
  });

  test('uses the kept endpoint without asking the app again', async () => {
    const { deps, messages } = fakeApp(endpoint);
    await withEndpoint(app, async () => {}, { deps });

    await withEndpoint(app, async () => {}, { deps });

    assert.equal(messages.length, 1);
  });

  test('asks again and retries when the kept endpoint stops answering', async () => {
    const restarted = { url: 'http://127.0.0.1:52000', token: 'new' };
    const { deps, messages, stored } = fakeApp(endpoint, restarted);
    await withEndpoint(app, async () => {}, { deps });
    const used = [];

    const result = await withEndpoint(app, async (it) => {
      used.push(it.url);
      if (it.url === endpoint.url) throw unreachable();
      return 'created';
    }, { deps });

    assert.equal(result, 'created');
    assert.deepEqual(used, [endpoint.url, restarted.url]);
    assert.equal(messages.length, 2);
    assert.deepEqual(stored(), restarted);
  });

  test('does not repeat the action when the same app only dropped the connection', async () => {
    // The app kept its address and token, so it may have handled the first attempt already.
    const { deps, messages, stored } = fakeApp(endpoint, endpoint);
    await withEndpoint(app, async () => {}, { deps });
    let attempts = 0;
    const dropped = unreachable();

    await assert.rejects(withEndpoint(app, async () => {
      attempts++;
      throw dropped;
    }, { deps }), dropped);

    assert.equal(attempts, 1);
    assert.equal(messages.length, 2);
    assert.deepEqual(stored(), endpoint);
  });

  test('other failures are not retried', async () => {
    const { deps, messages } = fakeApp(endpoint);
    await withEndpoint(app, async () => {}, { deps });
    const rejected = new KetchRequestError(FailureKind.REJECTED, 'bad link');

    await assert.rejects(withEndpoint(app, async () => {
      throw rejected;
    }, { deps }), rejected);
    assert.equal(messages.length, 1);
  });

  test('without launching, a closed app is reported as not running', async () => {
    const { deps, messages } = fakeApp({ error: 'not_running', message: "Ketch isn't running" });

    await assert.rejects(
      withEndpoint(app, async () => {}, { launch: false, deps }),
      { kind: FailureKind.APP_NOT_RUNNING },
    );
    assert.deepEqual(messages, [{ type: 'connect', launch: false }]);
  });

  test('a missing host means the app is not set up, in Chromium and Firefox', async () => {
    for (const message of [
      'Specified native messaging host not found.',
      'No such native application com.linroid.ketch',
    ]) {
      const { deps } = fakeApp(new Error(message));
      await assert.rejects(
        withEndpoint(app, async () => {}, { deps }),
        { kind: FailureKind.APP_NOT_INSTALLED },
      );
    }
  });

  test('an app that could not start reports its reason', async () => {
    const { deps } = fakeApp({ error: 'not_started', message: "Ketch didn't start in time" });
    await assert.rejects(
      withEndpoint(app, async () => {}, { deps }),
      { kind: FailureKind.UNREACHABLE, message: "Ketch didn't start in time" },
    );
  });

  test('refuses an endpoint that is not on this computer', async () => {
    const { deps, stored } = fakeApp({ url: 'http://evil.example:8642', token: 't' });
    await assert.rejects(
      withEndpoint(app, async () => {}, { deps }),
      { kind: FailureKind.UNREACHABLE, message: 'The Ketch app sent an invalid reply' },
    );
    assert.equal(stored(), undefined);
  });

  test('downloads captured while the app starts share one launch', async () => {
    let answer;
    const messages = [];
    const deps = {
      sendNativeMessage: (host, message) => {
        messages.push(message);
        return new Promise((resolve) => {
          answer = resolve;
        });
      },
      loadEndpoint: async () => undefined,
      saveEndpoint: async () => {},
    };

    const first = withEndpoint(app, async (it) => it.token, { deps });
    const second = withEndpoint(app, async (it) => it.token, { deps });
    await new Promise((resolve) => setImmediate(resolve));
    answer(endpoint);

    assert.deepEqual(await Promise.all([first, second]), ['fresh', 'fresh']);
    assert.equal(messages.length, 1);
  });
});
