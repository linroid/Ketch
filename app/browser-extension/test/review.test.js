import assert from 'node:assert/strict';
import { test } from 'node:test';

const data = {};
const browserActions = [];
const session = {
  get: async (key) => key === null ? structuredClone(data) : { [key]: structuredClone(data[key]) },
  set: async (values) => Object.assign(data, structuredClone(values)),
  remove: async (key) => { delete data[key]; },
};
const local = { settings: {
  instances: [{ id: 'server', type: 'server', name: 'Server', url: 'http://localhost:8642' }],
} };
globalThis.chrome = {
  storage: { session, local: {
    get: async (key) => ({ [key]: structuredClone(local[key]) }),
    set: async (values) => Object.assign(local, structuredClone(values)),
  } },
  runtime: { getURL: (path) => `chrome-extension://test/${path}` },
  tabs: { create: async () => ({ id: 7 }), get: async () => ({ id: 7 }) },
  downloads: {
    search: async () => [{ url: 'https://example.com/file', paused: true, state: 'in_progress' }],
    resume: async (id) => browserActions.push(['resume', id]),
    pause: async (id) => browserActions.push(['pause', id]),
    cancel: async (id) => browserActions.push(['cancel', id]),
    erase: async ({ id }) => browserActions.push(['erase', id]),
    download: async (options) => browserActions.push(['download', options]),
  },
};
const {
  openReview, sendReview, cancelReview, closeReviews, reviewDestination, resourceDestination,
  directReview, canDownloadDirectly, reopenReview, recentFolders, categoryFor, rememberDeviceFolders,
  knownDeviceFolders, lastFolder,
} = await import('../src/lib/review.js');

function reset() {
  for (const key of Object.keys(data)) delete data[key];
  delete local.recentFolders;
  delete local.deviceFolders;
  delete local.lastFolders;
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

test('untrusted batch names are sanitized while typed destinations remain strict', () => {
  const resource = { url: 'https://example.com/get', kind: 'file' };
  assert.equal(resourceDestination({ ...resource, name: 'reports/file.pdf' }, '/data'),
    '/data/file.pdf');
  assert.equal(resourceDestination({ ...resource, name: 'bad:*?<>|.pdf' }, ''), 'bad.pdf');
  assert.equal(resourceDestination({ ...resource, name: 'CON.txt' }, '/data'), '/data/');
  assert.equal(resourceDestination({ ...resource, name: 'reports/file.torrent' }, '/data'), '/data/');
  assert.equal(resourceDestination({ ...resource, name: '.*?' }, ''), undefined);
  assert.ok(new TextEncoder().encode(resourceDestination({ ...resource, name: '字'.repeat(300) }, ''))
    .length <= 255);
  assert.throws(() => reviewDestination(resource, 'reports/file.pdf', ''));
});

test('closing before tab association is saved resumes the original through the existence check',
  async () => {
    reset();
    const set = session.set;
    const getTab = globalThis.chrome.tabs.get;
    let unblock;
    let associating;
    const ready = new Promise((resolve) => { associating = resolve; });
    session.set = async (values) => {
      if (Object.values(values).some((entry) => entry.tabId === 7)) {
        associating();
        await new Promise((resolve) => { unblock = resolve; });
      }
      await set(values);
    };
    try {
      const opening = review();
      const rejected = assert.rejects(opening, /closed/);
      await ready;
      await closeReviews(7); // No tabId in storage yet.
      const id = Object.keys(data)[0].slice('review:'.length);
      const premature = assert.rejects(sendReview(id, 'server', '', '',
        async () => assert.fail('a closed review must not send')));
      globalThis.chrome.tabs.get = async () => { throw new Error('closed'); };
      unblock();
      await Promise.all([rejected, premature]);
      assert.deepEqual(browserActions, [['resume', 42]]);
      assert.deepEqual(data, {});
    } finally {
      session.set = set;
      globalThis.chrome.tabs.get = getTab;
    }
  });

test('the review opens as a window centered over the browser window', async () => {
  reset();
  const created = [];
  globalThis.chrome.windows = {
    getLastFocused: async () => ({ left: 100, top: 50, width: 1460, height: 900 }),
    create: async (options) => {
      created.push(options);
      return { id: 3, tabs: [{ id: 9 }] };
    },
  };
  const get = globalThis.chrome.tabs.get;
  globalThis.chrome.tabs.get = async (tabId) => ({ id: tabId });
  try {
    const id = await review();
    assert.equal(created[0].type, 'popup');
    assert.equal(created[0].left, 100 + (1460 - created[0].width) / 2);
    assert.equal(data[`review:${id}`].tabId, 9);
    await closeReviews(9);
    assert.deepEqual(browserActions, [['resume', 42]]);
  } finally {
    delete globalThis.chrome.windows;
    globalThis.chrome.tabs.get = get;
  }
});

test('sent folders are remembered per device, most recent first and without repeats', async () => {
  reset();
  for (const folder of ['/data/a', '/data/b', '/data/a', '']) {
    const id = await review({ url: 'https://example.com/file' });
    await sendReview(id, 'server', '', folder, async () => ({ taskId: 'accepted' }));
  }
  assert.deepEqual(await recentFolders('server'), ['/data/a', '/data/b']);
  assert.deepEqual(await recentFolders('other'), []);
});

test('the review starts in the folder last sent to each device, Auto included', async () => {
  reset();
  assert.equal(await lastFolder('server'), undefined);
  let id = await review({ url: 'https://example.com/file' });
  await sendReview(id, 'server', '', ' /data/movies ', async () => ({ taskId: 'accepted' }));
  assert.equal(await lastFolder('server'), '/data/movies');
  assert.equal(await lastFolder('other'), undefined);
  id = await review({ url: 'https://example.com/file' });
  await sendReview(id, 'server', '', '', async () => ({ taskId: 'accepted' }));
  assert.equal(await lastFolder('server'), '');
  assert.deepEqual(await recentFolders('server'), ['/data/movies']);
});

test('downloading in the browser resumes a captured original', async () => {
  reset();
  const id = await review();
  await directReview(id, 'ignored.bin');
  assert.deepEqual(browserActions, [['resume', 42]]);
  assert.deepEqual(data, {});
  await assert.rejects(directReview(id));
});

/** A review of a download the browser holds back, unpaused, as Chromium holds its file name. */
async function heldReview() {
  const id = await openReview('server', { url: 'https://example.com/file', browserDownloadId: 43 },
    { release: async () => browserActions.push(['release', 43]) });
  return Object.keys(data).find((key) => data[key].download.browserDownloadId === 43)
    .slice('review:'.length);
}

async function withUnpausedOriginal(action) {
  const search = globalThis.chrome.downloads.search;
  globalThis.chrome.downloads.search = async () =>
    [{ url: 'https://example.com/file', paused: false, state: 'in_progress' }];
  try {
    await action();
  } finally {
    globalThis.chrome.downloads.search = search;
  }
}

test('a held original goes back to the browser as it was, never as a new download', async () => {
  reset();
  await directReview(await heldReview(), 'renamed.bin');
  assert.deepEqual(browserActions, [['release', 43]]);
  assert.deepEqual(data, {});
  reset();
  await cancelReview(await heldReview());
  assert.deepEqual(browserActions, [['release', 43]]);
  assert.deepEqual(data, {});
});

test('a held original is removed only once Ketch accepts the download', async () => {
  await withUnpausedOriginal(async () => {
    reset();
    const id = await heldReview();
    await sendReview(id, 'server', '', '', async () => {
      assert.deepEqual(browserActions, []);
      return { taskId: 'accepted' };
    });
    assert.deepEqual(browserActions, [['cancel', 43], ['erase', 43]]);
    await cancelReview(id);
    assert.deepEqual(browserActions, [['cancel', 43], ['erase', 43]]);
  });
});

test('after an uncertain send a held original goes back to the browser paused', async () => {
  await withUnpausedOriginal(async () => {
    reset();
    const id = await heldReview();
    await assert.rejects(sendReview(id, 'server', '', '', async () => {
      throw { kind: 'uncertain' };
    }));
    assert.deepEqual(browserActions, [['release', 43], ['pause', 43]]);
    assert.equal(data[`review:${id}`].state, 'uncertain');
  });
});

test('downloading a reviewed link in the browser starts a download with a valid name', async () => {
  reset();
  const id = await review({ url: 'https://example.com/file', cookieStoreId: 'store' });
  await directReview(id, 'report.pdf');
  assert.deepEqual(browserActions, [['download', {
    url: 'https://example.com/file', cookieStoreId: 'store', filename: 'report.pdf',
  }]]);
  reset();
  const unnamed = await review({ url: 'https://example.com/file' });
  await directReview(unnamed, '../escape');
  assert.deepEqual(browserActions, [['download', { url: 'https://example.com/file' }]]);
});

test('only links the browser can fetch in their own context are offered to it', () => {
  assert.equal(canDownloadDirectly({ url: 'magnet:?xt=urn:btih:abc', browserDownloadId: 1 }), true);
  assert.equal(canDownloadDirectly({ url: 'magnet:?xt=urn:btih:abc' }), false);
  assert.equal(canDownloadDirectly({ url: 'ftp://example.com/file' }), false);
  assert.equal(canDownloadDirectly({ url: 'https://example.com/file' }), true);
  globalThis.chrome.downloads.onDeterminingFilename = {};
  try {
    assert.equal(canDownloadDirectly({ url: 'https://example.com/file', incognito: true }), false);
  } finally {
    delete globalThis.chrome.downloads.onDeterminingFilename;
  }
});

test('category folders match like the device does: type or MIME type, and site', () => {
  const categories = [
    { folder: 'Work', hosts: ['*.corp.example'] },
    { folder: 'Videos', extensions: ['.mp4', 'mkv'], mimeTypes: ['video/*'] },
    { folder: 'Archives', extensions: ['tar.gz'] },
    { folder: 'Nothing' },
  ];
  const match = (...args) => categoryFor(categories, ...args)?.folder;
  assert.equal(match('Clip.MP4', undefined, 'cdn.example.org'), 'Videos');
  assert.equal(match('stream', 'video/webm; codecs=vp9', undefined), 'Videos');
  assert.equal(match('report.pdf', 'application/pdf', 'files.corp.example'), 'Work');
  assert.equal(match('src.tar.gz', undefined, 'example.org'), 'Archives');
  assert.equal(match('.mp4', undefined, undefined), undefined);
  assert.equal(match('notes.txt', 'text/plain', 'example.org'), undefined);
});

test('device folders are remembered from its status for when it cannot be asked', async () => {
  reset();
  assert.equal(await knownDeviceFolders('local'), undefined);
  await rememberDeviceFolders('local', { system: {}, config: {} });
  assert.equal(await knownDeviceFolders('local'), undefined);
  await rememberDeviceFolders('local', {
    system: { downloadDirectory: '/Users/me/Downloads', usableSpace: 1 },
    config: { categories: [{ folder: 'Videos', extensions: ['mp4'] }, { folder: ' ' }] },
  });
  assert.deepEqual(await knownDeviceFolders('local'), {
    directory: '/Users/me/Downloads', categories: [{ folder: 'Videos', extensions: ['mp4'] }],
  });
  assert.equal(await knownDeviceFolders('nas'), undefined);
});

test('the review shows over the page it came from, or in a window where it cannot', async () => {
  reset();
  const injected = [];
  globalThis.chrome.scripting = { executeScript: async (options) => {
    if (options.target.tabId === 13) throw new Error('Cannot access a chrome:// URL');
    injected.push(options);
  } };
  const get = globalThis.chrome.tabs.get;
  globalThis.chrome.tabs.get = async (tabId) => ({ id: tabId });
  try {
    await openReview('server', { url: 'https://example.com/file' }, { tabId: 11 });
    const [id] = Object.keys(data).map((key) => key.slice('review:'.length));
    assert.equal(data[`review:${id}`].tabId, 11);
    assert.equal(injected[0].target.tabId, 11);
    assert.equal(injected[0].args[0], `chrome-extension://test/review/review.html?id=${id}`);

    reset();
    await openReview('server', { url: 'https://example.com/file' }, { tabId: 13 });
    const [other] = Object.keys(data).map((key) => key.slice('review:'.length));
    assert.equal(data[`review:${other}`].tabId, 7);

    // A frame that never loaded moves to a window, but only from the tab that showed it.
    reset();
    await openReview('server', { url: 'https://example.com/file' }, { tabId: 11 });
    const [moved] = Object.keys(data).map((key) => key.slice('review:'.length));
    await reopenReview(moved, 12);
    assert.equal(data[`review:${moved}`].tabId, 11);
    await reopenReview(moved, 11);
    assert.equal(data[`review:${moved}`].tabId, 7);
  } finally {
    delete globalThis.chrome.scripting;
    globalThis.chrome.tabs.get = get;
  }
});
