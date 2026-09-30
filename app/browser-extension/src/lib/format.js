/** Text shown for tasks, sizes and connection problems in the popup and options page. */

import { FailureKind } from './ketch-client.js';
import { isLoopbackUrl } from './settings.js';

const UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];

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
  return `${value.toFixed(digits)} ${UNITS[unit]}`;
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
    return name || 'Magnet link';
  }
  if (/^torrent:/i.test(url)) return 'Torrent';
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
      const amount = totalBytes > 0
        ? `${formatBytes(downloadedBytes)} of ${formatBytes(totalBytes)}`
        : formatBytes(downloadedBytes);
      return {
        text: `${amount} · ${formatBytes(bytesPerSecond)}/s`,
        progress: fraction,
        tone: 'active',
        canPause: true,
        canResume: false,
      };
    }
    case 'paused': {
      const { downloadedBytes = 0, totalBytes = 0 } = state.progress ?? {};
      const fraction = totalBytes > 0 ? Math.min(downloadedBytes / totalBytes, 1) : null;
      const text = fraction === null ? 'Paused' : `Paused · ${Math.floor(fraction * 100)}%`;
      return { text, progress: fraction, tone: 'idle', canPause: false, canResume: true };
    }
    case 'queued':
      return { text: 'Queued', progress: null, tone: 'idle', canPause: true, canResume: false };
    case 'scheduled':
      return { text: 'Scheduled', progress: null, tone: 'idle', canPause: false, canResume: false };
    case 'completed':
      return { text: 'Completed', progress: null, tone: 'done', canPause: false, canResume: false };
    case 'failed': {
      const reason = state.error?.message || state.error?.type || 'unknown error';
      return {
        text: `Failed: ${reason}`,
        progress: null,
        tone: 'error',
        canPause: false,
        canResume: true,
      };
    }
    case 'canceled':
      return { text: 'Canceled', progress: null, tone: 'idle', canPause: false, canResume: false };
    default:
      return { text: '', progress: null, tone: 'idle', canPause: false, canResume: false };
  }
}

/**
 * Summarizes a server's `KetchStatus`, naming the server only when it was given its own name.
 *
 * @param {{ name?: string, version?: string, system?: { os?: string } }} status
 * @param {{ withOs?: boolean }} [options]
 */
export function describeStatus(status, { withOs = false } = {}) {
  const named = status.name && status.name !== 'Ketch' ? ` to ${status.name}` : '';
  const os = withOs && status.system?.os ? ` on ${status.system.os}` : '';
  return `Connected${named} · Ketch ${status.version ?? ''}${os}`.trimEnd();
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
      return 'Install the Ketch desktop app and open it once, then try again. With "ketch ' +
        'server", or a browser installed as a Flatpak or Snap, add Ketch as a server instead.';
    case FailureKind.APP_NOT_RUNNING:
      return 'Ketch opens by itself when you send it a download.';
    case FailureKind.UNREACHABLE:
    case FailureKind.TIMEOUT:
      if (app) return 'Open Ketch and try again.';
      return isLoopbackUrl(instance.url)
        ? 'Open Ketch and turn on Settings → Remote access → Server, or run "ketch server".'
        : 'Check that the server is running and this address is reachable from here.';
    case FailureKind.UNAUTHORIZED:
      return 'Enter the access token shown in Ketch under Settings → Remote access.';
    default:
      return '';
  }
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
