#!/usr/bin/env node
// Renders the real popup with fixed sample data, without contacting a Ketch instance.
// Requires Playwright (resolvable via NODE_PATH) and Chrome or Playwright Chromium.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { dirname, extname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import { formatMessage } from '../../app/browser-extension/src/lib/i18n.js';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const { values } = parseArgs({ options: { locale: { type: 'string', default: 'en' } } });
assert.ok(['en', 'zh-CN'].includes(values.locale), 'Supported locales: en, zh-CN');
const chinese = values.locale === 'zh-CN';
const locale = chinese ? 'zh-CN' : 'en-US';
const computerName = chinese ? '本机' : 'This computer';
const nasName = chinese ? '家庭 NAS' : 'Home NAS';
const taskNames = chinese
  ? ['风景照片.zip', '设计素材.zip', '开源代码归档.tar.gz', '野外笔记.pdf']
  : ['Landscape photos.zip', 'Design resources.zip', 'Open source archive.tar.gz', 'Field notes.pdf'];
const filename = `screenshot-1280x800${chinese ? '-zh-CN' : ''}.png`;
const art = dirname(fileURLToPath(import.meta.url));
const source = resolve(art, '../../app/browser-extension/src');
const messageFile = `_locales/${chinese ? 'zh_CN' : 'en'}/messages.json`;
const messages = JSON.parse(await readFile(resolve(source, messageFile), 'utf8'));
const manifest = JSON.parse(await readFile(resolve(source, 'manifest.json'), 'utf8'));
const types = { '.html': 'text/html', '.css': 'text/css', '.js': 'text/javascript',
  '.png': 'image/png' };
const server = createServer(async (request, response) => {
  const path = new URL(request.url, 'http://localhost').pathname;
  const extension = path.startsWith('/extension/');
  const root = extension ? source : art;
  const file = resolve(root, '.' + (extension ? path.slice('/extension'.length) : path));
  if (!file.startsWith(root + sep)) {
    response.writeHead(403).end();
    return;
  }
  try {
    const content = await readFile(file);
    response.writeHead(200, { 'Content-Type': types[extname(file)] ?? 'application/octet-stream' });
    response.end(content);
  } catch {
    response.writeHead(404).end();
  }
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
const mb = 1024 ** 2;
const tasks = [
  [taskNames[0], 'downloading', 384, 640, 24],
  [taskNames[1], 'downloading', 86, 240, 12],
  [taskNames[2], 'paused', 72, 180, 0],
  [taskNames[3], 'completed', 8, 8, 0],
].map(([name, type, downloaded, total, speed], index) => ({
  taskId: `sample-${index}`,
  createdAt: `2026-10-06T10:0${4 - index}:00Z`,
  request: { url: `https://example.com/${encodeURIComponent(name)}`, destination: name },
  state: { type, progress: { downloadedBytes: downloaded * mb,
    totalBytes: total * mb, bytesPerSecond: speed * mb } },
}));

let browser;
try {
  browser = await chromium.launch({
    executablePath: process.env.CHROME_PATH ?? (process.platform === 'darwin'
      ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' : undefined),
  });
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 },
    deviceScaleFactor: 1, colorScheme: 'light', locale });
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.addInitScript({ content: `
    const messages = ${JSON.stringify(messages)};
    const formatMessage = ${formatMessage.toString()};
    globalThis.browser = {
      i18n: { getMessage: (key, values) => formatMessage(messages, key, values) },
      runtime: { getManifest: () => (${JSON.stringify(manifest)}) },
      storage: { local: { get: async () => ({ settings: {
        instances: [
          { id: 'computer', type: 'server', name: ${JSON.stringify(computerName)},
            url: ${JSON.stringify(origin)} },
          { id: 'nas', type: 'server', name: ${JSON.stringify(nasName)},
            url: ${JSON.stringify(origin)} }
        ],
        defaultInstanceId: 'computer', interceptDownloads: true
      } }) } }
    };
  ` });
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    assert.ok(['/api/status', '/api/tasks'].includes(path), `Unexpected request: ${path}`);
    await route.fulfill({ json: path === '/api/tasks' ? { tasks }
      : { name: computerName, version: '0.2.3' } });
  });
  await page.goto(`${origin}/store-screenshot.html`);
  if (chinese) {
    await page.evaluate(() => {
      document.documentElement.lang = 'zh-CN';
      const copy = {
        eyebrow: '浏览器里的下载，由你掌控',
        headlineFirst: '下载任务，',
        headlineSecond: '交给 Ketch。',
        introFirst: '下载到本机，',
        introSecond: '或你自己的服务器。',
        capture: '自动接管 Chrome 下载',
        send: '发送链接、文件和磁力链接',
        manage: '在弹窗中暂停和继续下载',
        footer: '开源软件 · 需配合 Ketch 应用或服务器使用',
      };
      for (const [key, text] of Object.entries(copy)) {
        document.querySelector(`[data-copy="${key}"]`).textContent = text;
      }
    });
  }
  const popup = page.frameLocator('iframe');
  await popup.locator('.task').nth(3).waitFor();
  await popup.locator('[data-state="online"]').waitFor();
  await popup.locator('body').evaluate(() => document.fonts.ready);
  const fits = await popup.locator('body').evaluate((body) => {
    return body.scrollWidth <= body.clientWidth;
  });
  assert.ok(fits, 'Popup fits horizontally');
  const height = await popup.locator('body').evaluate((body) => body.scrollHeight);
  assert.ok(height * 1.35 < 700, `Popup too tall: ${height}`);
  await page.locator('iframe').evaluate((frame, height) => {
    frame.style.height = `${height}px`;
  }, height);
  await page.locator('.popup').evaluate((container, height) => {
    container.style.top = `${(800 - height * 1.35 - 2) / 2}px`;
  }, height);
  await page.evaluate(() => document.fonts.ready);
  assert.deepEqual(errors, [], 'Popup rendered without JavaScript errors');
  await page.screenshot({ path: resolve(art, filename), animations: 'disabled' });
  console.log(`Rendered art/browser-extension/${filename}`);
} finally {
  await browser?.close();
  await new Promise((resolve) => server.close(resolve));
}
