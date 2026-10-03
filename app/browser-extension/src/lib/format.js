/** Text shown for tasks, sizes and connection problems in the popup and options page. */

import { languageTag, t } from './i18n.js';
import { FailureKind } from './ketch-client.js';
import { isLoopbackUrl } from './settings.js';

/** Formats a number of each unit, from bytes to terabytes. */
const UNITS = [
  (size) => t('size_bytes', size),
  (size) => t('size_kilobytes', size),
  (size) => t('size_megabytes', size),
  (size) => t('size_gigabytes', size),
  (size) => t('size_terabytes', size),
];

/** Number formats of the messages' language, by the number of decimals. */
const numberFormats = new Map();

/**
 * Formats a byte count with binary multiples, e.g. `1.5 MB`.
 *
 * @param {number} bytes
 */
export function formatBytes(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) return '—';
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < UNITS.length - 1) {
    value /= 1024;
    unit++;
  }
  const digits = unit === 0 || value >= 10 ? 0 : 1;
  return UNITS[unit](formatNumber(value, digits));
}

/** Formats `value` with `digits` decimals, as the messages' language writes numbers. */
function formatNumber(value, digits) {
  let format = numberFormats.get(digits);
  if (!format) {
    format = new Intl.NumberFormat(languageTag(), {
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
      useGrouping: false,
    });
    numberFormats.set(digits, format);
  }
  return format.format(value);
}

/**
 * A name for a task: the saved file when known, otherwise one derived from the link.
 *
 * @param {object} task a `TaskSnapshot`
 * @returns {string}
 */
export function taskName(task) {
  const state = task.state ?? {};
  if (state.type === 'completed' && state.outputPath) return lastSegment(state.outputPath);
  const destination = task.request?.destination;
  if (destination && !/[\\/]$/.test(destination)) return lastSegment(destination);
  const suggested = task.request?.resolvedSource?.suggestedFileName;
  if (suggested) return suggested;
  return nameFromUrl(task.request?.url ?? '');
}

/**
 * @param {string} url
 * @returns {string}
 */
export function nameFromUrl(url) {
  if (/^magnet:/i.test(url)) {
    const name = new URLSearchParams(url.slice(url.indexOf('?') + 1)).get('dn');
    return name || t('task_magnet_link');
  }
  if (/^torrent:/i.test(url)) return t('task_torrent');
  try {
    const parsed = new URL(url);
    const segment = parsed.pathname.split('/').filter(Boolean).pop();
    return segment ? safeDecode(segment) : parsed.hostname;
  } catch {
    return url;
  }
}

/**
 * Describes a task's state for the popup.
 *
 * @param {object} task a `TaskSnapshot`
 * @returns {{
 *   text: string,
 *   progress: number | null,
 *   tone: 'active' | 'done' | 'error' | 'idle',
 *   canPause: boolean,
 *   canResume: boolean,
 * }} `progress` is a fraction from 0 to 1, or `null` when there is no bar to show
 */
export function describeTaskState(task) {
  const state = task.state ?? {};
  switch (state.type) {
    case 'downloading': {
      const { downloadedBytes = 0, totalBytes = 0, bytesPerSecond = 0 } = state.progress ?? {};
      const fraction = totalBytes > 0 ? Math.min(downloadedBytes / totalBytes, 1) : null;
      const speed = t('speed_per_second', formatBytes(bytesPerSecond));
      return {
        text: totalBytes > 0
          ? t('task_downloading_of', formatBytes(downloadedBytes), formatBytes(totalBytes), speed)
          : t('task_downloading', formatBytes(downloadedBytes), speed),
        progress: fraction,
        tone: 'active',
        canPause: true,
        canResume: false,
      };
    }
    case 'paused': {
      const { downloadedBytes = 0, totalBytes = 0 } = state.progress ?? {};
      const fraction = totalBytes > 0 ? Math.min(downloadedBytes / totalBytes, 1) : null;
      const text = fraction === null
        ? t('task_paused')
        : t('task_paused_percent', formatNumber(Math.floor(fraction * 100), 0));
      return { text, progress: fraction, tone: 'idle', canPause: false, canResume: true };
    }
    case 'queued':
      return stateWithoutBar(t('task_queued'), 'idle', { canPause: true });
    case 'scheduled':
      return stateWithoutBar(t('task_scheduled'), 'idle');
    case 'completed':
      return stateWithoutBar(t('task_completed'), 'done');
    case 'failed': {
      const reason = state.error?.message || state.error?.type || t('task_unknown_error');
      return stateWithoutBar(t('task_failed', reason), 'error', { canResume: true });
    }
    case 'canceled':
      return stateWithoutBar(t('task_canceled'), 'idle');
    default:
      return stateWithoutBar('', 'idle');
  }
}

function stateWithoutBar(text, tone, { canPause = false, canResume = false } = {}) {
  return { text, progress: null, tone, canPause, canResume };
}

/**
 * Summarizes a server's `KetchStatus`, naming the server only when it was given its own name.
 *
 * @param {{ name?: string, version?: string, system?: { os?: string } }} status
 * @param {{ withOs?: boolean }} [options]
 */
export function describeStatus(status, { withOs = false } = {}) {
  const name = status.name && status.name !== 'Ketch' ? status.name : undefined;
  const os = withOs ? status.system?.os : undefined;
  const version = status.version ?? '';
  let text;
  if (name && os) text = t('status_connected_to_on', name, version, os);
  else if (name) text = t('status_connected_to', name, version);
  else if (os) text = t('status_connected_on', version, os);
  else text = t('status_connected', version);
  return text.trimEnd();
}

/**
 * What the user can do about a failed request to `instance`.
 *
 * @param {unknown} error
 * @param {import('./settings.js').Instance} instance
 * @returns {string} empty when there is nothing specific to suggest
 */
export function failureHint(error, instance) {
  const app = instance.type === 'app';
  switch (error?.kind) {
    case FailureKind.APP_NOT_INSTALLED:
      return t('hint_app_not_installed');
    case FailureKind.APP_NOT_RUNNING:
      return t('hint_app_not_running');
    case FailureKind.UNREACHABLE:
    case FailureKind.TIMEOUT:
      if (app) return t('hint_open_app');
      return isLoopbackUrl(instance.url) ? t('hint_local_server') : t('hint_remote_server');
    case FailureKind.UNAUTHORIZED:
      return t('hint_access_token');
    default:
      return '';
  }
}

/**
 * Joins an error message and what to do about it into one text.
 *
 * @param {string} message
 * @param {string} hint empty when there is nothing to add
 */
export function withHint(message, hint) {
  return hint ? t('error_with_hint', message, hint) : message;
}

function lastSegment(path) {
  return String(path).split(/[\\/]/).filter(Boolean).pop() ?? String(path);
}

function safeDecode(value) {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}
