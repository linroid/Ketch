import assert from 'node:assert/strict';
import { test } from 'node:test';
import { submitDownload, pendingSubmissions, reconcileSubmissions,
  diagnosticReport, recordOutcome } from '../src/lib/submissions.js';

function storage() {
  const data = {};
  return {
    get: async (key) => key === null ? { ...data } : { [key]: data[key] },
    set: async (values) => Object.assign(data, values),
    remove: async (key) => { delete data[key]; },
  };
}
const endpoint = { url: 'http://127.0.0.1:1234', token: 'secret' };
const request = { url: 'https://example.com/download?secret=123' };

test('a lost response recovers the task receipt without submitting twice', async () => {
  const journal = storage();
  let task;
  let creates = 0;
  const client = {
    status: async () => ({ features: ['task.requestId'] }),
    createTask: async (request) => {
      creates++;
      task = { taskId: 'task-1', request };
      throw new TypeError('connection closed');
    },
    listTasks: async () => [task],
  };
  assert.equal((await submitDownload(client, endpoint, request, { storage: journal })).taskId,
    'task-1');
  assert.equal(creates, 1);
  assert.deepEqual(await pendingSubmissions(journal), []);
});

test('uncertain writes block a second send and recovery stays bound to credentials', async () => {
  const journal = storage();
  let task;
  let creates = 0;
  const client = {
    status: async () => ({ features: ['task.requestId'] }),
    createTask: async (request) => {
      creates++;
      task = { taskId: 'task-1', request };
      throw new TypeError('connection closed');
    },
    listTasks: async () => { throw new TypeError('offline'); },
  };
  await assert.rejects(submitDownload(client, endpoint, request, { storage: journal }),
    { kind: 'uncertain' });
  await assert.rejects(submitDownload(client, endpoint, request, { storage: journal }),
    { kind: 'uncertain' });
  assert.equal(creates, 1);
  const saved = JSON.stringify(await journal.get(null));
  assert.ok(!saved.includes('secret'));
  client.listTasks = async () => [task];
  assert.deepEqual(await reconcileSubmissions(client, { ...endpoint, token: 'new' }, journal), []);
  assert.equal((await reconcileSubmissions(client, endpoint, journal))[0].task.taskId, 'task-1');
});

test('older servers are never automatically resubmitted after a lost response', async () => {
  let calls = 0;
  const journal = storage();
  const client = {
    status: async () => ({ features: [] }),
    createTask: async (request) => {
      assert.equal(request.requestId, undefined);
      calls++;
      throw new TypeError('offline');
    },
    listTasks: async () => { throw new Error('must not list legacy receipts'); },
  };
  await assert.rejects(submitDownload(client, endpoint, request, { storage: journal }),
    { kind: 'uncertain' });
  assert.equal(calls, 1);
});

test('diagnostics are bounded and discard arbitrary fields and unknown outcomes', async () => {
  const log = storage();
  await log.set({ handoffDiagnostics: [{ at: 1, outcome: 'sent', cookie: 'secret' }] });
  for (let i = 0; i < 105; i++) await recordOutcome('sent', log);
  await recordOutcome('https://example.com/private', log);
  const report = await diagnosticReport(log);
  assert.equal(report.length, 100);
  assert.deepEqual(Object.keys(report[0]), ['at', 'outcome']);
});

test('media playlists require the advertised engine capability before a write', async () => {
  let writes = 0;
  const client = {
    status: async () => ({ features: [] }),
    createTask: async (request) => { writes++; return { taskId: 'media', request }; },
  };
  const media = { url: 'https://example.com/clip.m3u8?token=123' };
  const journal = storage();
  await assert.rejects(submitDownload(client, endpoint, media, { storage: journal }),
    { kind: 'rejected' });
  assert.equal(writes, 0);
  assert.deepEqual(await pendingSubmissions(journal), []);
  client.status = async () => ({ features: ['media.finite'] });
  await submitDownload(client, endpoint, media, { storage: journal });
  assert.equal(writes, 1);
});
