import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import {
  describeStatus,
  describeTaskState,
  failureHint,
  formatBytes,
  nameFromUrl,
  taskName,
} from '../src/lib/format.js';
import { FailureKind, KetchRequestError } from '../src/lib/ketch-client.js';

describe('formatBytes', () => {
  test('uses binary units with one decimal below ten', () => {
    assert.equal(formatBytes(0), '0 B');
    assert.equal(formatBytes(1023), '1023 B');
    assert.equal(formatBytes(1536), '1.5 KB');
    assert.equal(formatBytes(10 * 1024), '10 KB');
    assert.equal(formatBytes(5.25 * 1024 ** 3), '5.3 GB');
  });

  test('shows a dash for unknown sizes', () => {
    assert.equal(formatBytes(-1), '—');
  });
});

describe('taskName', () => {
  test('prefers the saved file, then the destination, then the resolved name', () => {
    const request = {
      url: 'https://a.com/get?id=1',
      destination: 'chosen.zip',
      resolvedSource: { suggestedFileName: 'resolved.zip' },
    };
    const completed = { type: 'completed', outputPath: 'C:\\Downloads\\chosen (1).zip' };
    assert.equal(taskName({ request, state: completed }), 'chosen (1).zip');
    assert.equal(taskName({ request, state: { type: 'queued' } }), 'chosen.zip');
    assert.equal(
      taskName({ request: { ...request, destination: '/downloads/' }, state: {} }),
      'resolved.zip',
    );
  });

  test('falls back to a name from the link', () => {
    assert.equal(nameFromUrl('https://a.com/files/My%20Report.pdf?x=1'), 'My Report.pdf');
    assert.equal(nameFromUrl('https://a.com/'), 'a.com');
    assert.equal(nameFromUrl('magnet:?xt=urn:btih:abc&dn=Ubuntu+24.04'), 'Ubuntu 24.04');
    assert.equal(nameFromUrl('magnet:?xt=urn:btih:abc'), 'Magnet link');
  });
});

describe('describeTaskState', () => {
  test('downloading shows progress and speed', () => {
    const view = describeTaskState({
      state: {
        type: 'downloading',
        progress: { downloadedBytes: 512, totalBytes: 2048, bytesPerSecond: 1024 },
      },
    });
    assert.equal(view.text, '512 B of 2.0 KB · 1.0 KB/s');
    assert.equal(view.progress, 0.25);
    assert.equal(view.canPause, true);
  });

  test('an unknown total size has no progress fraction', () => {
    const view = describeTaskState({
      state: { type: 'downloading', progress: { downloadedBytes: 10, totalBytes: 0 } },
    });
    assert.equal(view.progress, null);
  });

  test('failed tasks show the error and can be retried', () => {
    const view = describeTaskState({
      state: { type: 'failed', error: { type: 'http', message: 'HTTP error 404: Not Found' } },
    });
    assert.equal(view.text, 'Failed: HTTP error 404: Not Found');
    assert.equal(view.canResume, true);
  });
});

describe('describeStatus', () => {
  test('names the server only when it has its own name', () => {
    assert.equal(describeStatus({ name: 'Ketch', version: '1.2.0' }), 'Connected · Ketch 1.2.0');
    assert.equal(
      describeStatus({ name: 'NAS', version: '1.2.0', system: { os: 'Linux' } }, { withOs: true }),
      'Connected to NAS · Ketch 1.2.0 on Linux',
    );
  });
});

describe('failureHint', () => {
  const unreachable = new KetchRequestError(FailureKind.UNREACHABLE, 'down');

  test('points at the app for a server on this computer', () => {
    const local = { type: 'server', url: 'http://127.0.0.1:8642' };
    assert.match(failureHint(unreachable, local), /Settings → Sharing/);
  });

  test('points at the network for a remote server', () => {
    assert.match(failureHint(unreachable, { type: 'server', url: 'http://nas:8642' }), /reachable/);
  });

  test('guides setting up the Ketch app when it is missing', () => {
    const missing = new KetchRequestError(FailureKind.APP_NOT_INSTALLED, 'missing');
    assert.match(failureHint(missing, { type: 'app' }), /Install the Ketch desktop app/);
  });

  test('has nothing to add for a rejected request', () => {
    const rejected = new KetchRequestError(FailureKind.REJECTED, 'bad');
    assert.equal(failureHint(rejected, { type: 'server', url: 'http://nas:8642' }), '');
  });
});
