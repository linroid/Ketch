/** Submission receipts live only for the browser session; diagnostics never contain URLs. */
import { ext } from './ext.js';
import { t } from './i18n.js';
import { FailureKind, KetchRequestError } from './ketch-client.js';

const PREFIX = 'handoff:';
const LOG_KEY = 'handoffDiagnostics';
const OUTCOMES = new Set(['sent', 'recovered', 'uncertain', 'rejected', 'unreachable']);

export async function fingerprint(value) {
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
  return Array.from(new Uint8Array(bytes), (byte) => byte.toString(16).padStart(2, '0')).join('');
}

/** Bind recovery to the exact endpoint and credential without storing either in the journal. */
export function endpointIdentity(endpoint) {
  return fingerprint(endpoint.receiptIdentity ??
    JSON.stringify([endpoint.url, endpoint.token ?? '']));
}

export function receiptEndpoint(instance, endpoint) {
  return instance.type === 'app'
    ? { ...endpoint, receiptIdentity: `app:${instance.id}` } : endpoint;
}

export function uncertainError() {
  return new KetchRequestError(FailureKind.UNCERTAIN, t('error_handoff_uncertain'));
}

export async function recordOutcome(outcome, storage = ext?.storage.local) {
  if (!storage || !OUTCOMES.has(outcome)) return;
  const write = async () => {
    const previous = (await storage.get(LOG_KEY))[LOG_KEY];
    const entries = Array.isArray(previous) ? previous.filter(validEvent) : [];
    await storage.set({ [LOG_KEY]: [...entries, { at: Date.now(), outcome }].slice(-100) });
  };
  // Different extension pages can finish submissions at the same time.
  try {
    if (globalThis.navigator?.locks) await navigator.locks.request(LOG_KEY, write);
    else await write();
  } catch { /* Diagnostics must never change a submission's result. */ }
}

function validEvent(entry) {
  return Number.isFinite(entry?.at) && OUTCOMES.has(entry?.outcome);
}

export async function diagnosticReport(storage = ext.storage.local) {
  const saved = (await storage.get(LOG_KEY))[LOG_KEY];
  return (Array.isArray(saved) ? saved : []).filter(validEvent)
    .map(({ at, outcome }) => ({ at, outcome })).slice(-100);
}

export async function pendingSubmissions(storage = ext?.storage.session) {
  if (!storage) return [];
  const entries = await storage.get(null);
  return Object.entries(entries).filter(([key, value]) => key.startsWith(PREFIX) &&
    key === PREFIX + value?.id && typeof value?.endpoint === 'string').map(([, value]) => value);
}

/** Read-only reconciliation: an absent receipt never causes a second submission. */
export async function reconcileSubmissions(client, endpoint, storage = ext?.storage.session) {
  const identity = await endpointIdentity(endpoint);
  const pending = (await pendingSubmissions(storage)).filter((entry) => entry.endpoint === identity);
  if (!pending.length) return [];
  const tasks = await client.listTasks();
  const recovered = [];
  for (const entry of pending) {
    const task = tasks.find((it) => it.request?.requestId === entry.id);
    if (!task) continue;
    // Keep the receipt until the caller has dealt with the browser's original download.
    recovered.push({ entry, task });
  }
  return recovered;
}

export async function forgetSubmission(id, storage = ext?.storage.session) {
  await storage?.remove(PREFIX + id);
}

/** Submit once, then look for the receipt if the response was lost. Never replay legacy writes. */
export async function submitDownload(client, endpoint, request, options = {}) {
  const storage = options.storage ?? ext?.storage.session;
  const endpointKey = await endpointIdentity(endpoint);
  const urlHash = await fingerprint(options.browserUrl ?? request.url);
  const pending = await pendingSubmissions(storage);
  if (pending.some((entry) => entry.endpoint === endpointKey && entry.urlHash === urlHash)) {
    throw uncertainError();
  }
  const status = await client.status();
  if (/\.(m3u8|mpd)(?:[?#]|$)/i.test(request.url) &&
    !status.features?.includes('media.finite')) {
    throw new KetchRequestError(FailureKind.REJECTED, t('error_media_unsupported'));
  }
  const supported = status.features?.includes('task.requestId');
  const id = crypto.randomUUID();
  const submitted = supported ? { ...request, requestId: id } : request;
  const entry = { id, endpoint: endpointKey, urlHash, at: Date.now(),
    browserDownloadId: options.browserDownloadId, supported: Boolean(supported) };
  if (pending.length >= 100) throw uncertainError();
  // Failure to record a submission is a failure before any side effect.
  await storage?.set({ [PREFIX + id]: entry });
  try {
    const task = await client.createTask(submitted);
    if (!task?.taskId || (supported && task.request?.requestId !== id)) throw uncertainError();
    await forgetSubmission(id, storage);
    await recordOutcome('sent');
    return task;
  } catch (error) {
    if (error.kind === FailureKind.REJECTED || error.kind === FailureKind.UNAUTHORIZED) {
      await forgetSubmission(id, storage);
      await recordOutcome('rejected');
      throw error;
    }
    if (supported) {
      try {
        const tasks = await client.listTasks();
        const task = tasks.find((it) => it.request?.requestId === id);
        if (task) {
          await forgetSubmission(id, storage);
          await recordOutcome('recovered');
          return task;
        }
      } catch { /* Keep the pending receipt for a later connection. */ }
    }
    await recordOutcome('uncertain');
    throw uncertainError();
  }
}
