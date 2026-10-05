import assert from 'node:assert/strict';
import { test } from 'node:test';

const data = {};
const browserActions = [];
const session = {
  get: async (key) => key === null ? structuredClone(data) : { [key]: structuredClone(data[key]) },
  set: async (values) => Object.assign(data, structuredClone(values)),
  remove: async (key) => { delete data[key]; },
};
globalThis.chrome = {
  storage: { session, local: { get: async () => ({ settings: {
    instances: [{ id: 'server', type: 'server', name: 'Server', url: 'http://localhost:8642' }],
  } }) } },
  runtime: { getURL: (path) => `chrome-extension://test/${path}` },
  tabs: { create: async () => ({ id: 7 }) },
  downloads: {
    search: async () => [{ url: 'https://example.com/file', paused: true, state: 'in_progress' }],
    resume: async (id) => browserActions.push(['resume', id]),
    cancel: async (id) => browserActions.push(['cancel', id]),
    erase: async ({ id }) => browserActions.push(['erase', id]),
  },
};
const { openReview, sendReview, cancelReview, closeReviews, reviewDestination } =
  await import('../src/lib/review.js');

function reset() {
  for (const key of Object.keys(data)) delete data[key];
  browserActions.length = 0;
}
async function review(download = { url: 'https://example.com/file', browserDownloadId: 42 }) {
  await openReview('server', download);
  return Object.keys(data)[0].slice('review:'.length);
}

test('review makes no submission and cancellation restores the browser download', async () => {
  reset();
  const id = await review();
  assert.equal(data[`review:${id}`].state, 'pending');
  assert.deepEqual(browserActions, []);
  await closeReviews(7);
  assert.deepEqual(browserActions, [['resume', 42]]);
  assert.deepEqual(data, {});
});

test('acceptance uses the chosen destination before removing the browser original', async () => {
  reset();
  const id = await review();
  await sendReview(id, 'server', 'report.pdf', '/downloads', async (instance, download) => {
    assert.equal(instance.id, 'server');
    assert.equal(download.destination, '/downloads/report.pdf');
    assert.equal(data[`review:${id}`].state, 'sending');
    assert.deepEqual(browserActions, []);
    return { taskId: 'accepted' };
  });
  assert.deepEqual(browserActions, [['cancel', 42], ['erase', 42]]);
  assert.deepEqual(data, {});
});

test('closing a review while sending waits and never resumes an accepted original', async () => {
  reset();
  const id = await review();
  let finish;
  let started;
  const ready = new Promise((resolve) => { started = resolve; });
  const sending = sendReview(id, 'server', '', '', async () => {
    started();
    return new Promise((resolve) => { finish = resolve; });
  });
  await ready;
  const closing = closeReviews(7);
  const duplicate = sendReview(id, 'server', '', '', async () => assert.fail('duplicate send'));
  const rejected = assert.rejects(duplicate);
  finish({ taskId: 'accepted' });
  await Promise.all([sending, closing, rejected]);
  assert.deepEqual(browserActions, [['cancel', 42], ['erase', 42]]);
});

test('known rejection can return to the browser but an uncertain handoff stays paused', async () => {
  for (const kind of ['rejected', 'uncertain']) {
    reset();
    const id = await review();
    await assert.rejects(sendReview(id, 'server', '', '', async () => { throw { kind }; }));
    await cancelReview(id);
    assert.deepEqual(browserActions, kind === 'rejected' ? [['resume', 42]] : []);
    assert.equal(data[`review:${id}`]?.state, kind === 'uncertain' ? 'uncertain' : undefined);
  }
});

test('destinations preserve remote paths and torrents keep their own file layout', () => {
  const download = { url: 'https://example.com/file' };
  assert.equal(reviewDestination(download, 'file.zip', ''), 'file.zip');
  assert.equal(reviewDestination(download, '', '/'), '/');
  assert.equal(reviewDestination(download, '', 'C:\\Downloads\\'), 'C:\\Downloads/');
  assert.equal(reviewDestination({ ...download, fileName: 'source.torrent' }, 'ignored', '/data'),
    '/data/');
  for (const name of ['../secret', 'folder\\file', '..', 'bad\nname']) {
    assert.throws(() => reviewDestination(download, name, ''));
  }
  assert.throws(() => reviewDestination(download, '', '/bad\0path'));
});

test('a browser original resumed independently cannot be submitted from an old review', async () => {
  reset();
  const id = await review();
  const search = globalThis.chrome.downloads.search;
  globalThis.chrome.downloads.search = async () => [{ paused: false, state: 'complete' }];
  try {
    await assert.rejects(sendReview(id, 'server', '', '', async () => assert.fail('must not send')));
    assert.equal(data[`review:${id}`].state, 'pending');
  } finally {
    globalThis.chrome.downloads.search = search;
  }
});
