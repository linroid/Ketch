#!/usr/bin/env node
/**
 * Uploads the Chrome Web Store package to the store's item and submits it for review, with the
 * Chrome Web Store API v2.
 *
 * Usage: node store-upload.mjs [build/chrome-store]
 *
 * Reads the package from `build/ketch-extension-<version>-chrome-store.zip` next to that folder,
 * whose manifest gives the version the store must report back. Credentials come from the
 * environment, never the command line:
 *
 *   CWS_CLIENT_ID, CWS_CLIENT_SECRET   an OAuth client of a Google Cloud project with the
 *                                      Chrome Web Store API enabled
 *   CWS_REFRESH_TOKEN                  a refresh token for the store account, with the
 *                                      `chromewebstore` scope
 *   CWS_PUBLISHER_ID                   the publisher's id (Developer Dashboard: Account)
 */
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/** The Chrome Web Store item of the extension. */
export const STORE_ITEM_ID = 'flnjeochbgpaipiofdjmoijaeooemhka';

const TOKEN_URL = 'https://oauth2.googleapis.com/token';
const API = 'https://chromewebstore.googleapis.com';

/** How long the store may take to process an upload, and how often to ask. */
const PROCESSING_TIMEOUT_MS = 5 * 60_000;
const POLL_INTERVAL_MS = 5_000;

/**
 * Uploads `zip` and submits it for review.
 *
 * @param {{
 *   zip: Uint8Array,
 *   version: string,
 *   credentials: { clientId: string, clientSecret: string, refreshToken: string,
 *     publisherId: string },
 *   fetch?: typeof fetch,
 *   sleep?: (ms: number) => Promise<void>,
 *   log?: (message: string) => void,
 *   now?: () => number,
 * }} options `version` is the manifest version the store must report for the upload
 * @returns {Promise<object>} the publish response: the submission's `state` and any warnings
 */
export async function uploadToStore({
  zip, version, credentials, fetch = globalThis.fetch,
  sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  log = console.log, now = Date.now,
}) {
  const token = await accessToken(fetch, credentials);
  const item = `publishers/${encodeURIComponent(credentials.publisherId)}/items/${STORE_ITEM_ID}`;
  const auth = { Authorization: `Bearer ${token}` };

  log(`Uploading version ${version}`);
  let upload = await call(fetch, `${API}/upload/v2/${item}:upload`, {
    method: 'POST', headers: { ...auth, 'Content-Type': 'application/zip' }, body: zip,
  });
  let state = upload.uploadState;
  const deadline = now() + PROCESSING_TIMEOUT_MS;
  while (state === 'IN_PROGRESS') {
    if (now() > deadline) {
      throw new Error('The store is still processing the upload; check the dashboard');
    }
    await sleep(POLL_INTERVAL_MS);
    const status = await call(fetch, `${API}/v2/${item}:fetchStatus`, { headers: auth });
    state = status.lastAsyncUploadState;
  }
  if (state !== 'SUCCEEDED') {
    throw new Error(`The store refused the upload: ${state ?? 'no state'}`);
  }
  // Only a finished upload names its version.
  if (upload.crxVersion && upload.crxVersion !== version) {
    throw new Error(`The store holds version ${upload.crxVersion}, not ${version}`);
  }

  log('Submitting it for review');
  const published = await call(fetch, `${API}/v2/${item}:publish`, {
    method: 'POST', headers: { ...auth, 'Content-Type': 'application/json' },
    body: JSON.stringify({ publishType: 'DEFAULT_PUBLISH' }),
  });
  log(`Submitted: ${published.state ?? 'no state reported'}`);
  for (const warning of published.warningInfo?.warnings ?? []) {
    log(`Warning: ${warning.reason}: ${warning.description}`);
  }
  return published;
}

async function accessToken(fetch, { clientId, clientSecret, refreshToken }) {
  const response = await call(fetch, TOKEN_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      client_id: clientId, client_secret: clientSecret, refresh_token: refreshToken,
      grant_type: 'refresh_token',
    }).toString(),
  });
  if (!response.access_token) throw new Error('Google returned no access token');
  return response.access_token;
}

/** Calls `url`, failing with Google's own message, and never echoing credentials. */
async function call(fetch, url, init) {
  const response = await fetch(url, init);
  const text = await response.text();
  let body;
  try {
    body = text ? JSON.parse(text) : {};
  } catch {
    body = {};
  }
  if (!response.ok) {
    const reason = body.error?.message ?? body.error_description ?? body.error ?? '';
    const where = new URL(url).pathname.split(':').pop();
    throw new Error(`${where} failed with HTTP ${response.status}${reason ? `: ${reason}` : ''}`);
  }
  return body;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const dir = resolve(process.argv[2] ?? join(dirname(fileURLToPath(import.meta.url)),
    'build', 'chrome-store'));
  const manifest = JSON.parse(readFileSync(join(dir, 'manifest.json'), 'utf8'));
  const name = manifest.version_name ?? manifest.version;
  const zip = readFileSync(join(dirname(dir), `ketch-extension-${name}-chrome-store.zip`));
  const env = (key) => {
    const value = process.env[key]?.trim();
    if (!value) throw new Error(`${key} is not set`);
    return value;
  };
  try {
    await uploadToStore({
      zip, version: manifest.version,
      credentials: {
        clientId: env('CWS_CLIENT_ID'), clientSecret: env('CWS_CLIENT_SECRET'),
        refreshToken: env('CWS_REFRESH_TOKEN'), publisherId: env('CWS_PUBLISHER_ID'),
      },
    });
  } catch (error) {
    console.error(`Chrome Web Store: ${error.message}`);
    process.exitCode = 1;
  }
}
