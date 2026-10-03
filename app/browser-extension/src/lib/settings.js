import { ext } from './ext.js';
import { t } from './i18n.js';

/** Port the Ketch app and `ketch server` listen on unless configured otherwise. */
export const DEFAULT_PORT = 8642;

/** Id of the instance every installation starts with: Ketch on this computer. */
export const LOCAL_INSTANCE_ID = 'local';

const SETTINGS_KEY = 'settings';

/**
 * Mirrors `captureMagnetLinks` under its own key, so the content script can read it without
 * access to the rest of the settings, which hold access tokens.
 */
export const MAGNET_CAPTURE_KEY = 'captureMagnetLinks';

/**
 * @typedef {object} Instance
 * @property {string} id stable identifier, used in context menu ids
 * @property {'app' | 'server'} type `app` is the Ketch app on this computer, reached through
 *   native messaging; `server` is a Ketch server at `url`, such as `ketch server` or another
 *   device
 * @property {string} name label shown in menus and the popup
 * @property {string} [url] base address of a server, without a trailing slash
 * @property {string} [token] a server's bearer token; empty when it does not require one
 */

/**
 * @typedef {object} Settings
 * @property {Instance[]} instances Ketch servers downloads can be sent to; never empty
 * @property {string} defaultInstanceId instance that receives captured downloads and magnets
 * @property {boolean} interceptDownloads send downloads the browser starts to Ketch
 * @property {boolean} captureMagnetLinks send clicked magnet links to Ketch
 * @property {boolean} forwardCookies send the site's cookies and referrer with each download
 * @property {number} minFileSizeMb leave smaller downloads to the browser; 0 sends all
 * @property {string[]} excludedHosts sites whose downloads stay in the browser
 * @property {boolean} notifications notify when a download is sent or falls back
 */

/**
 * Name of the Ketch app instance unless the user renames it. It is saved with the instance, in
 * the language the browser had then.
 */
function localInstanceName() {
  return t('instance_this_computer');
}

/** @returns {Instance} the Ketch app on this computer */
export function localInstance() {
  return { id: LOCAL_INSTANCE_ID, type: 'app', name: localInstanceName() };
}

/** @returns {Settings} */
export function defaultSettings() {
  return {
    instances: [localInstance()],
    defaultInstanceId: LOCAL_INSTANCE_ID,
    interceptDownloads: true,
    captureMagnetLinks: true,
    forwardCookies: true,
    minFileSizeMb: 0,
    excludedHosts: [],
    notifications: true,
  };
}

/**
 * Turns what a user typed into the base URL of a Ketch server.
 *
 * A bare host such as `nas.local` becomes `http://nas.local:8642`; an explicit scheme keeps its
 * default port, so a server behind a reverse proxy can be reached at `https://host/ketch`.
 *
 * @param {string} input
 * @returns {string}
 * @throws {Error} with a message fit for the user when the address is unusable
 */
export function normalizeServerUrl(input) {
  const trimmed = String(input ?? '').trim();
  if (!trimmed) throw new Error(t('error_address_required'));
  const hasScheme = /^[a-z][a-z\d+.-]*:\/\//i.test(trimmed);
  let url;
  try {
    url = new URL(hasScheme ? trimmed : `http://${trimmed}`);
  } catch {
    throw new Error(t('error_address_invalid', trimmed));
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error(t('error_address_scheme'));
  }
  if (url.username || url.password) {
    throw new Error(t('error_address_credentials'));
  }
  if (!hasScheme && !url.port) url.port = String(DEFAULT_PORT);
  return `${url.origin}${url.pathname.replace(/\/+$/, '')}`;
}

/**
 * Whether `url` points at this computer, so traffic to it never leaves the machine.
 *
 * @param {string} url
 */
export function isLoopbackUrl(url) {
  let host;
  try {
    host = new URL(url).hostname.toLowerCase();
  } catch {
    return false;
  }
  return host === 'localhost' || host.endsWith('.localhost') || host === '[::1]' ||
    /^127(\.\d{1,3}){3}$/.test(host);
}

/**
 * Parses a list of sites, one per line or separated by commas or spaces, into bare host names.
 * Schemes, paths, ports and a leading `*.` are dropped, so pasted URLs work too.
 *
 * @param {string | string[]} value
 * @returns {string[]}
 */
export function parseHostList(value) {
  const entries = Array.isArray(value) ? value : String(value ?? '').split(/[\s,]+/);
  const hosts = [];
  for (const entry of entries) {
    let host = String(entry).trim().toLowerCase();
    if (!host) continue;
    host = host.replace(/^[a-z][a-z\d+.-]*:\/\//, '').replace(/[/?#].*$/, '');
    host = host.replace(/^\*\./, '').replace(/:\d+$/, '').replace(/\.$/, '');
    if (host && !hosts.includes(host)) hosts.push(host);
  }
  return hosts;
}

/**
 * Fills missing fields with defaults and repairs invalid ones, so stored settings from an older
 * version, or edited by hand, are always safe to use.
 *
 * @param {Partial<Settings> | undefined} raw
 * @returns {Settings}
 */
export function normalizeSettings(raw) {
  const defaults = defaultSettings();
  const source = raw && typeof raw === 'object' ? raw : {};
  const instances = [];
  for (const candidate of Array.isArray(source.instances) ? source.instances : []) {
    const instance = normalizeInstance(candidate);
    if (!instance || instances.some((it) => it.id === instance.id)) continue;
    // There is one Ketch app on this computer.
    if (instance.type === 'app' && instances.some((it) => it.type === 'app')) continue;
    instances.push(instance);
  }
  if (instances.length === 0) instances.push(localInstance());
  const defaultInstanceId = instances.some((it) => it.id === source.defaultInstanceId)
    ? source.defaultInstanceId
    : instances[0].id;
  const minFileSizeMb = Number(source.minFileSizeMb);
  return {
    instances,
    defaultInstanceId,
    interceptDownloads: booleanOr(source.interceptDownloads, defaults.interceptDownloads),
    captureMagnetLinks: booleanOr(source.captureMagnetLinks, defaults.captureMagnetLinks),
    forwardCookies: booleanOr(source.forwardCookies, defaults.forwardCookies),
    minFileSizeMb: Number.isFinite(minFileSizeMb) && minFileSizeMb > 0 ? minFileSizeMb : 0,
    excludedHosts: parseHostList(source.excludedHosts ?? []),
    notifications: booleanOr(source.notifications, defaults.notifications),
  };
}

/** @returns {Instance | null} */
function normalizeInstance(candidate) {
  if (!candidate || typeof candidate !== 'object') return null;
  const id = typeof candidate.id === 'string' ? candidate.id.trim() : '';
  if (!id) return null;
  const name = typeof candidate.name === 'string' ? candidate.name.trim() : '';
  // Instances saved before there were types all had an address.
  const type = candidate.type === 'app' || (candidate.type !== 'server' && !candidate.url)
    ? 'app'
    : 'server';
  if (type === 'app') return { id, type, name: name || localInstanceName() };
  let url;
  try {
    url = normalizeServerUrl(candidate.url);
  } catch {
    return null;
  }
  return {
    id,
    type,
    name: name || new URL(url).host,
    url,
    token: typeof candidate.token === 'string' ? candidate.token.trim() : '',
  };
}

function booleanOr(value, fallback) {
  return typeof value === 'boolean' ? value : fallback;
}

/**
 * Returns the instance with `id`, or the default instance when there is none.
 *
 * @param {Settings} settings
 * @param {string | undefined} [id]
 * @returns {Instance}
 */
export function findInstance(settings, id) {
  return settings.instances.find((it) => it.id === id) ??
    settings.instances.find((it) => it.id === settings.defaultInstanceId) ??
    settings.instances[0];
}

/** @returns {string} a new, practically unique instance id */
export function newInstanceId() {
  return `remote-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

/** @returns {Promise<Settings>} */
export async function loadSettings() {
  const stored = await ext.storage.local.get(SETTINGS_KEY);
  return normalizeSettings(stored[SETTINGS_KEY]);
}

/**
 * Normalizes and stores `settings`.
 *
 * @param {Settings} settings
 * @returns {Promise<Settings>} what was stored
 */
export async function saveSettings(settings) {
  const normalized = normalizeSettings(settings);
  await ext.storage.local.set({
    [SETTINGS_KEY]: normalized,
    [MAGNET_CAPTURE_KEY]: normalized.captureMagnetLinks,
  });
  return normalized;
}

/**
 * Calls `listener` with the new settings whenever they change in any extension context.
 *
 * @param {(settings: Settings) => void} listener
 */
export function onSettingsChanged(listener) {
  ext.storage.onChanged.addListener((changes, area) => {
    if (area === 'local' && changes[SETTINGS_KEY]) {
      listener(normalizeSettings(changes[SETTINGS_KEY].newValue));
    }
  });
}
