import assert from 'node:assert/strict';
import { test } from 'node:test';
import { collectPageResources, mergePageResources } from '../src/lib/page-resources.js';

const pageUrl = 'https://example.com/page';
test('frame results keep signed URLs intact, deduplicate and reject browser-only resources', () => {
  const candidate = { url: 'https://cdn.example.com/a.mp4?signature=a%2Fb#fragment',
    kind: 'video', pageUrl };
  const results = mergePageResources([{ result: [candidate, candidate,
    { ...candidate, url: 'blob:https://example.com/id' },
    { ...candidate, url: 'https://user:secret@example.com/a.mp4' },
    { ...candidate, kind: 'script' },
  ] }]);
  assert.equal(results.length, 1);
  assert.equal(results[0].url, 'https://cdn.example.com/a.mp4?signature=a%2Fb');
});

test('scanning inspects rendered media and downloadable links without fetching', () => {
  globalThis.location = { href: pageUrl };
  globalThis.document = {
    baseURI: pageUrl,
    querySelectorAll: () => [
      { tagName: 'IMG', currentSrc: '/picture.avif', src: '/old.jpg' },
      { tagName: 'VIDEO', currentSrc: 'blob:https://example.com/video' },
      { tagName: 'A', href: '/get?id=7', hasAttribute: () => true,
        getAttribute: () => 'report.pdf' },
      { tagName: 'SOURCE', src: '/playlist.m3u8' },
    ],
  };
  const original = globalThis.performance;
  globalThis.performance = { getEntriesByType: () => [
    { name: 'https://cdn.example.com/song.mp3?token=abc' },
    { name: 'https://cdn.example.com/script.js' },
  ] };
  try {
    const resources = collectPageResources();
    assert.deepEqual(resources.map((it) => it.kind), ['image', 'file', 'stream', 'audio']);
    assert.equal(resources[1].name, 'report.pdf');
    assert.equal(resources[3].url, 'https://cdn.example.com/song.mp3?token=abc');
  } finally {
    delete globalThis.document;
    delete globalThis.location;
    globalThis.performance = original;
  }
});

test('the result list is capped even across several frames', () => {
  const result = Array.from({ length: 600 }, (_, i) => ({
    url: `https://example.com/${i}.png`, pageUrl, kind: 'image',
  }));
  assert.equal(mergePageResources([{ result }]).length, 500);
});
