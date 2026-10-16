// Renders the browser extension's real popup for the film (S06) in the film's theme, to
// public/stills[/<locale>]/popup-dark.png for the dark film or
// public/stills[/<locale>]/light/popup-light.png for the light one, then registers it in the
// manifest of the locale and theme (scripts/assets/sync.mjs).
//
//   node tools/extension-popup/render.mjs <locale> [--theme light]   (render.sh runs this)
//
// It copies app/browser-extension/src into a temporary folder, so the popup is always the
// extension as it is now; puts mock-chrome.js beside popup.html and loads it before popup.js
// (mock-head.html), so the popup runs without a browser extension API or a Ketch server; serves
// the folder over http; and captures the popup with the headless Chrome Remotion installs
// (`npx remotion browser ensure`), driven over the DevTools protocol: 360 CSS px wide at 2×,
// as tall as its content (its own bottom padding included), in the theme's colour scheme.
// The popup shows the locale's _locales folder (scripts/assets/locales.json) and the
// release in src/facts.json.
//
// See docs/ASSETS.md.
import {spawn, spawnSync} from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {
  fail,
  layoutOf,
  localeOf,
  promoRoot,
  repoRoot,
  takeTheme,
  writeAtomic,
} from '../../scripts/assets/lib.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));

/** The popup's width in CSS px, as browsers show it (popup.css), and the capture's scale. */
const WIDTH = 360;
const SCALE = 2;
/** How long the popup may take to connect and list the downloads, and captures to try. */
const READY_TIMEOUT_MS = 15_000;
const ATTEMPTS = 3;
/** The viewport's height while the popup loads: less than any popup is tall. */
const MIN_HEIGHT = 120;

const args = process.argv.slice(2);
/** The film's theme, the colour scheme the popup is rendered in: S06 shows it in the film's. */
const theme = takeTheme(args);
const [name] = args;
if (!name) fail('usage: node tools/extension-popup/render.mjs <locale> [--theme light]');
const locale = localeOf(name);
const version = JSON.parse(fs.readFileSync(path.join(promoRoot, 'src/facts.json'), 'utf8')).release;
const extensionSource = path.join(repoRoot, 'app/browser-extension/src');
if (!fs.existsSync(path.join(extensionSource, '_locales', locale.extensionLocale))) {
  fail(`the extension has no _locales/${locale.extensionLocale} for ${locale.name}`);
}

const work = fs.mkdtempSync(path.join(os.tmpdir(), 'ketch-popup-'));
let server;
let chrome;
try {
  // The extension as it is now, with the mock beside the popup and loaded before popup.js.
  const site = path.join(work, 'site');
  fs.cpSync(extensionSource, site, {recursive: true});
  fs.copyFileSync(path.join(here, 'mock-chrome.js'), path.join(site, 'popup/mock-chrome.js'));
  const popupHtml = path.join(site, 'popup/popup.html');
  const html = fs.readFileSync(popupHtml, 'utf8');
  const firstScript = html.indexOf('<script');
  if (firstScript < 0) throw new Error('popup.html has no <script> to load the mock before');
  const head = fs.readFileSync(path.join(here, 'mock-head.html'), 'utf8');
  fs.writeFileSync(popupHtml, html.slice(0, firstScript) + head + html.slice(firstScript));

  server = await serve(site);
  const query = new URLSearchParams({locale: locale.extensionLocale, version});
  const url = `http://127.0.0.1:${server.address().port}/popup/popup.html?${query}`;

  chrome = await launchChrome(path.join(work, 'profile'));
  const page = await chrome.newPage();
  const layout = layoutOf(locale, theme);
  fs.mkdirSync(path.join(promoRoot, 'public', layout.stills), {recursive: true});
  const png = await capture(page, url, theme);
  const target = path.join(promoRoot, 'public', layout.stills, `popup-${theme}.png`);
  writeAtomic(target, png);
  console.log(`${path.relative(promoRoot, target)} (${locale.extensionLocale}, Ketch ${version})`);
} finally {
  await chrome?.close();
  server?.close();
  fs.rmSync(work, {recursive: true, force: true, maxRetries: 5});
}

// The popup is in public/; the manifest lists it once sync has run.
const sync = spawnSync(
  process.execPath,
  [path.join(promoRoot, 'scripts/assets/sync.mjs'), locale.name, '--theme', theme],
  {stdio: 'inherit'},
);
process.exit(sync.status ?? 1);

/**
 * Loads the popup in `theme` and captures it once it lists the downloads, again when the
 * capture caught it still connecting.
 */
async function capture(page, url, theme) {
  await page.send('Emulation.setEmulatedMedia', {
    features: [{name: 'prefers-color-scheme', value: theme}],
  });
  for (let attempt = 1; attempt <= ATTEMPTS; attempt++) {
    // Shorter than the popup, so the page's scroll height is the popup's own.
    await page.send('Emulation.setDeviceMetricsOverride', {
      width: WIDTH,
      height: MIN_HEIGHT,
      deviceScaleFactor: SCALE,
      mobile: false,
    });
    await page.navigate(url);
    const height = await page.waitFor(
      `(async () => {
      await document.fonts.ready;
      const online = document.getElementById('status-dot')?.dataset.state === 'online';
      const listed = !document.getElementById('recent').hidden && document.querySelectorAll('#tasks li').length > 0;
      return online && listed ? Math.ceil(document.documentElement.scrollHeight) : null;
    })()`,
      READY_TIMEOUT_MS,
    );
    await page.send('Emulation.setDeviceMetricsOverride', {
      width: WIDTH,
      height,
      deviceScaleFactor: SCALE,
      mobile: false,
    });
    // Two frames, so the new size has been laid out and painted.
    await page.evaluate(
      'new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(done)))',
    );
    const {data} = await page.send('Page.captureScreenshot', {format: 'png', fromSurface: true});
    const still = await page.evaluate(
      `document.getElementById('status-dot')?.dataset.state === 'online'`,
    );
    if (still) return Buffer.from(data, 'base64');
    console.warn(`popup-${theme}: caught the popup connecting, attempt ${attempt} of ${ATTEMPTS}`);
  }
  throw new Error(`popup-${theme}: the popup never stayed connected`);
}

/** Serves `root` on a free port of 127.0.0.1. */
function serve(root) {
  const types = {
    '.html': 'text/html',
    '.js': 'text/javascript',
    '.css': 'text/css',
    '.json': 'application/json',
    '.png': 'image/png',
    '.svg': 'image/svg+xml',
  };
  const server = http.createServer((request, response) => {
    const file = path.join(root, decodeURIComponent(new URL(request.url, 'http://x').pathname));
    if (!file.startsWith(root) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
      response.writeHead(404).end();
      return;
    }
    response.writeHead(200, {
      'Content-Type': types[path.extname(file)] ?? 'application/octet-stream',
    });
    fs.createReadStream(file).pipe(response);
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server)));
}

/** The headless Chrome Remotion downloads into node_modules/.remotion, or $CHROME. */
function chromePath() {
  if (process.env.CHROME) return process.env.CHROME;
  const base = path.join(promoRoot, 'node_modules/.remotion/chrome-headless-shell');
  const found = fs.existsSync(base)
    ? fs
        .readdirSync(base, {recursive: true})
        .find(
          (file) =>
            /chrome-headless-shell(\.exe)?$/.test(path.basename(String(file))) &&
            fs.statSync(path.join(base, String(file))).isFile(),
        )
    : undefined;
  if (!found)
    fail('no headless Chrome: run `npx remotion browser ensure`, or set CHROME to a Chrome binary');
  return path.join(base, String(found));
}

/** Starts Chrome with the DevTools protocol on a free port and connects to it. */
async function launchChrome(profile) {
  const child = spawn(
    chromePath(),
    [
      '--headless',
      '--remote-debugging-port=0',
      `--user-data-dir=${profile}`,
      '--no-first-run',
      '--no-default-browser-check',
      '--hide-scrollbars',
      '--force-color-profile=srgb',
      '--font-render-hinting=none',
      `--lang=${locale.extensionLocale.replace('_', '-')}`,
      'about:blank',
    ],
    {stdio: ['ignore', 'ignore', 'pipe']},
  );
  const endpoint = await new Promise((resolve, reject) => {
    let output = '';
    const timer = setTimeout(() => reject(new Error(`Chrome did not start:\n${output}`)), 20_000);
    child.stderr.on('data', (chunk) => {
      output += chunk;
      const match = output.match(/DevTools listening on (ws:\/\/\S+)/);
      if (match) {
        clearTimeout(timer);
        resolve(match[1]);
      }
    });
    child.on('exit', (code) => reject(new Error(`Chrome exited with ${code}:\n${output}`)));
  });
  const socket = new WebSocket(endpoint);
  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, {once: true});
    socket.addEventListener('error', reject, {once: true});
  });
  let next = 0;
  const pending = new Map();
  const listeners = new Set();
  socket.addEventListener('message', ({data}) => {
    const message = JSON.parse(data);
    if (message.id !== undefined) {
      const {resolve, reject} = pending.get(message.id);
      pending.delete(message.id);
      if (message.error) reject(new Error(message.error.message));
      else resolve(message.result);
    } else {
      for (const listener of listeners) listener(message);
    }
  });
  const send = (method, params = {}, sessionId) =>
    new Promise((resolve, reject) => {
      const id = ++next;
      pending.set(id, {resolve, reject});
      socket.send(JSON.stringify({id, method, params, sessionId}));
    });
  return {
    async newPage() {
      const {targetId} = await send('Target.createTarget', {url: 'about:blank'});
      const {sessionId} = await send('Target.attachToTarget', {targetId, flatten: true});
      const page = {
        send: (method, params) => send(method, params, sessionId),
        async evaluate(expression) {
          const {result, exceptionDetails} = await page.send('Runtime.evaluate', {
            expression,
            awaitPromise: true,
            returnByValue: true,
          });
          if (exceptionDetails)
            throw new Error(exceptionDetails.exception?.description ?? exceptionDetails.text);
          return result.value;
        },
        async navigate(url) {
          const loaded = new Promise((resolve) => {
            const listener = (message) => {
              if (message.sessionId === sessionId && message.method === 'Page.loadEventFired') {
                listeners.delete(listener);
                resolve();
              }
            };
            listeners.add(listener);
          });
          await page.send('Page.navigate', {url});
          await loaded;
        },
        async waitFor(expression, timeout) {
          const end = Date.now() + timeout;
          while (Date.now() < end) {
            const value = await page.evaluate(expression);
            if (value) return value;
            await new Promise((resolve) => setTimeout(resolve, 100));
          }
          throw new Error(`timed out waiting for ${expression}`);
        },
      };
      await page.send('Page.enable');
      return page;
    },
    /** Closes Chrome and waits for it to exit, so its profile can be deleted. */
    async close() {
      socket.close();
      const exited = new Promise((resolve) => child.once('exit', resolve));
      child.kill();
      await exited;
    },
  };
}
