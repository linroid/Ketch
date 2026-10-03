import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

// A browser showing messages of its own: the English ones, in German number formats, with the
// Add button reworded. Set before the extension's modules load, as ext.js picks the API
// namespace when it is first imported; each test file runs in its own process.
const messages = JSON.parse(
  readFileSync(new URL('../src/_locales/en/messages.json', import.meta.url), 'utf8'));
messages.language_tag.message = 'de';
messages.popup_add_button.message = 'Herunterladen';
const calls = [];
globalThis.chrome = {
  i18n: {
    getMessage(key, substitutions) {
      calls.push(substitutions);
      return formatMessage(messages, key, substitutions);
    },
  },
};
const { formatMessage, t } = await import('../src/lib/i18n.js');
const { describeStatus, describeTaskState, formatBytes } = await import('../src/lib/format.js');

test('messages come from the browser, with substitutions as strings', () => {
  assert.equal(t('popup_add_button'), 'Herunterladen');
  assert.equal(t('error_http_status', 404), 'Ketch answered with HTTP 404');
  assert.deepEqual(calls.at(-1), ['404']);
});

test('an unknown message falls back to its key', () => {
  assert.equal(t('no_such_message'), 'no_such_message');
});

test('numbers in sizes and speeds follow the language', () => {
  assert.equal(formatBytes(1536), '1,5 KB');
  assert.equal(formatBytes(1023), '1023 B');
  assert.equal(
    describeTaskState({
      state: {
        type: 'downloading',
        progress: { downloadedBytes: 512, totalBytes: 2048, bytesPerSecond: 1024 },
      },
    }).text,
    '512 B of 2,0 KB · 1,0 KB/s',
  );
  assert.equal(
    describeTaskState({
      state: { type: 'paused', progress: { downloadedBytes: 40, totalBytes: 100 } },
    }).text,
    'Paused · 40%',
  );
});

test('the status picks the sentence for what is known', () => {
  assert.equal(
    describeStatus({ name: 'NAS', version: '1.2.0', system: { os: 'Linux' } }, { withOs: true }),
    'Connected to NAS · Ketch 1.2.0 on Linux',
  );
});
