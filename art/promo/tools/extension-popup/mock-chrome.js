// Stands in for the browser and the Ketch server behind the extension's popup, so the real
// popup (app/browser-extension/src/popup) renders in a plain page for the promo's stills.
// render.mjs copies this next to popup.html and loads it, as a classic script, before popup.js
// (a module, so it runs later). It stubs the chrome.* APIs the popup uses and answers the
// popup's REST calls with the promo's sample data.
//
// The page's query string sets what differs between renders:
//   locale  the _locales folder whose messages the popup shows (en, zh_CN, …)
//   version the version the server reports, from src/facts.json
//
// The data matches the desktop clips and the web-app still: the popup follows the Studio
// server and lists the same downloads in the same states (S06 shows them side by side). Keep it
// in step with ShowcaseData / PromoSnapshots.kt when those change.
(function () {
  const params = new URLSearchParams(location.search);
  const locale = params.get('locale') ?? 'en';
  const version = params.get('version') ?? '0.0.0';

  // The locale's messages, loaded synchronously so chrome.i18n.getMessage can be synchronous.
  const xhr = new XMLHttpRequest();
  xhr.open('GET', `../_locales/${locale}/messages.json`, false);
  xhr.send();
  if (xhr.status !== 200) throw new Error(`No messages for ${locale}`);
  const messages = JSON.parse(xhr.responseText);

  /** chrome.i18n.getMessage: named placeholders, then $1…$9 and $$. */
  function getMessage(key, subs) {
    const entry = messages[key];
    if (!entry) return '';
    const values = (Array.isArray(subs) ? subs : subs == null ? [] : [subs]).map(String);
    const placeholders = new Map(
      Object.entries(entry.placeholders || {}).map(([name, placeholder]) => [
        name.toLowerCase(),
        placeholder.content,
      ]),
    );
    return entry.message
      .replace(/\$([\w@]+)\$/g, (match, name) => placeholders.get(name.toLowerCase()) ?? match)
      .replace(/\$(\$|[1-9])/g, (match, which) =>
        which === '$' ? '$' : (values[Number(which) - 1] ?? ''),
      );
  }

  // One server instance, Studio, which the web app beside the popup shows too.
  const store = {
    local: {
      settings: {
        instances: [
          {
            id: 'studio',
            type: 'server',
            name: 'Studio',
            url: 'http://studio.local:8642',
            token: '',
          },
        ],
        defaultInstanceId: 'studio',
        interceptDownloads: true,
        captureMagnetLinks: true,
        forwardCookies: true,
        minFileSizeMb: 0,
        excludedHosts: [],
        notifications: true,
      },
    },
    session: {},
  };
  function area(name) {
    return {
      get: async (key) => {
        const all = store[name];
        if (key == null) return {...all};
        const keys = Array.isArray(key) ? key : [key];
        return Object.fromEntries(keys.filter((k) => k in all).map((k) => [k, all[k]]));
      },
      set: async (items) => {
        Object.assign(store[name], items);
      },
      remove: async (key) => {
        delete store[name][key];
      },
    };
  }
  window.chrome = {
    i18n: {getMessage, getUILanguage: () => locale.replace('_', '-')},
    storage: {
      local: area('local'),
      session: area('session'),
      onChanged: {addListener() {}},
    },
    permissions: {contains: async () => true, request: async () => true},
    runtime: {
      openOptionsPage() {},
      getURL: (file) => `../${file}`,
      sendNativeMessage: async () => ({}),
    },
  };

  // Studio's downloads as the desktop clips show them, frozen so every render reads the same.
  const MB = 1024 * 1024;
  const GB = 1024 * MB;
  const now = Date.now();
  const ago = (minutes) => new Date(now - minutes * 60000).toISOString();
  const downloading = (downloadedBytes, totalBytes, bytesPerSecond) => ({
    type: 'downloading',
    progress: {downloadedBytes: Math.round(downloadedBytes), totalBytes, bytesPerSecond},
  });
  const tasks = [
    {
      taskId: 't1',
      createdAt: ago(1),
      request: {url: 'https://mirror.example.org/releases/26.10/server-26.10-amd64.iso'},
      state: downloading(1.47 * GB, 3435973632, 87.5 * MB),
    },
    {
      taskId: 't2',
      createdAt: ago(3),
      request: {url: 'https://data.example.net/corpus/dataset-shard-03.tar'},
      state: downloading(6.98 * GB, 13 * GB, 31 * MB),
    },
    {
      taskId: 't3',
      createdAt: ago(9),
      request: {
        url:
          'magnet:?xt=urn:btih:4c7f3e2b9d1a8f6e5c0b7a3d2e1f9c8b7a6d5e4f' +
          '&dn=sunrise-timelapse-8k.mov&tr=udp%3A%2F%2Ftracker.example.org%3A6969',
      },
      // Under its own 5 MB/s limit.
      state: downloading(1288490188 * 0.425, 1288490188, 3 * MB),
    },
    {
      taskId: 't4',
      createdAt: ago(14),
      request: {url: 'https://files.example.com/design/design-assets-2026.zip'},
      state: {type: 'queued'},
    },
    {
      taskId: 't5',
      createdAt: ago(30),
      request: {url: 'https://files.example.com/shared/quarterly-report.pdf'},
      state: {type: 'completed', outputPath: '/Users/ketch/Downloads/quarterly-report.pdf'},
    },
  ];

  const json = (body) =>
    new Response(JSON.stringify(body), {
      status: 200,
      headers: {'Content-Type': 'application/json'},
    });
  window.fetch = async (url, init = {}) => {
    const path = new URL(url).pathname;
    if (path.endsWith('/api/status')) {
      return json({name: 'Ketch', version, system: {os: 'macOS'}});
    }
    if (path.endsWith('/api/tasks') && (init.method ?? 'GET') === 'GET') {
      return json({tasks});
    }
    if (path.endsWith('/api/tasks')) {
      const request = JSON.parse(init.body);
      return json({
        taskId: 'new',
        createdAt: new Date().toISOString(),
        request,
        state: {type: 'queued'},
      });
    }
    return json({});
  };
})();
