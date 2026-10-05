/** Runs in an isolated browser world on explicit request; it never fetches or changes the page. */
export function collectPageResources() {
  const resources = [];
  const seen = new Set();
  const media = /\.(mp4|webm|mov|m4v|mp3|m4a|ogg|oga|wav|flac|aac|jpg|jpeg|png|gif|webp|avif|svg)$/i;
  const files = /\.(zip|gz|bz2|xz|7z|rar|iso|dmg|exe|msi|apk|deb|rpm|pdf|epub|torrent)$/i;
  function add(value, kind, name = '') {
    if (resources.length >= 500 || !value) return;
    let url;
    try { url = new URL(value, document.baseURI); } catch { return; }
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return;
    if (/\.(m3u8|mpd)$/i.test(url.pathname)) return;
    url.hash = '';
    if (seen.has(url.href)) return;
    seen.add(url.href);
    resources.push({ url: url.href, kind, name: name.slice(0, 255), pageUrl: location.href });
  }
  function kindFor(url) {
    let path;
    try { path = new URL(url, document.baseURI).pathname; } catch { return null; }
    if (/\.(mp4|webm|mov|m4v)$/i.test(path)) return 'video';
    if (/\.(mp3|m4a|ogg|oga|wav|flac|aac)$/i.test(path)) return 'audio';
    if (media.test(path)) return 'image';
    return files.test(path) ? 'file' : null;
  }
  for (const element of document.querySelectorAll('img, video, audio, source, a[href]')) {
    if (element.tagName === 'A') {
      const kind = kindFor(element.href);
      if (kind || element.hasAttribute('download')) {
        add(element.href, kind ?? 'file', element.getAttribute('download') ?? '');
      }
    } else if (element.tagName === 'IMG') {
      add(element.currentSrc || element.src, 'image');
    } else {
      const kind = element.tagName === 'AUDIO' || element.parentElement?.tagName === 'AUDIO'
        ? 'audio' : 'video';
      add(element.currentSrc || element.src, kind);
    }
  }
  for (const entry of performance.getEntriesByType('resource')) {
    const kind = kindFor(entry.name);
    if (kind) add(entry.name, kind);
  }
  return resources;
}

/** Validate results crossing from page contexts and deduplicate across accessible frames. */
export function mergePageResources(results) {
  const resources = new Map();
  for (const result of results) {
    for (const candidate of Array.isArray(result.result) ? result.result : []) {
      if (resources.size >= 500) break;
      try {
        const url = new URL(candidate.url);
        const page = new URL(candidate.pageUrl);
        if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password ||
          !['http:', 'https:'].includes(page.protocol)) continue;
        if (!['file', 'image', 'audio', 'video'].includes(candidate.kind)) continue;
        url.hash = '';
        if (resources.has(url.href)) continue;
        resources.set(url.href, { url: url.href, kind: candidate.kind, pageUrl: page.href,
          name: typeof candidate.name === 'string' ? candidate.name.slice(0, 255) : '' });
      } catch { /* Ignore malformed page data. */ }
    }
  }
  return [...resources.values()];
}
