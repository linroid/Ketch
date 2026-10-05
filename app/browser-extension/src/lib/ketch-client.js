/**
 * Minimal client for the Ketch daemon REST API (`library/server`), shared by the background
 * script, the popup and the options page.
 */

import { t } from './i18n.js';

/** Why a request to Ketch failed, so callers can tell the user what to fix. */
export const FailureKind = Object.freeze({
  /** Nothing answered: the server is off, or the address is wrong. */
  UNREACHABLE: 'unreachable',
  /** The server may have accepted the submission; check its receipt before trying again. */
  UNCERTAIN: 'uncertain',
  /** The server did not answer in time. */
  TIMEOUT: 'timeout',
  /** The server requires a different access token. */
  UNAUTHORIZED: 'unauthorized',
  /** The server answered but refused the request, e.g. an unsupported link. */
  REJECTED: 'rejected',
  /** The Ketch desktop app hasn't registered itself with this browser. */
  APP_NOT_INSTALLED: 'app_not_installed',
  /** The Ketch app is closed, and the caller asked not to start it. */
  APP_NOT_RUNNING: 'app_not_running',
});

/** A failed request to a Ketch server. */
export class KetchRequestError extends Error {
  /**
   * @param {string} kind one of {@link FailureKind}
   * @param {string} message
   * @param {{ status?: number, cause?: unknown }} [details]
   */
  constructor(kind, message, details = {}) {
    super(message, { cause: details.cause });
    this.name = 'KetchRequestError';
    this.kind = kind;
    this.status = details.status;
  }
}

const DEFAULT_TIMEOUT_MS = 10_000;

/** Talks to one Ketch instance. */
export class KetchClient {
  #baseUrl;
  #token;
  #fetch;
  #timeoutMs;
  #signal;

  /**
   * @param {{ url: string, token?: string }} instance
   * @param {{ fetch?: typeof fetch, timeoutMs?: number, signal?: AbortSignal }} [options]
   *   `timeoutMs` limits each request; `signal` aborts every request, e.g. at a deadline
   *   shared by several of them
   */
  constructor(instance, options = {}) {
    this.#baseUrl = instance.url.replace(/\/+$/, '');
    this.#token = instance.token ?? '';
    this.#fetch = options.fetch ?? globalThis.fetch.bind(globalThis);
    this.#timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
    this.#signal = options.signal;
  }

  /** @returns {Promise<object>} the server's `KetchStatus` */
  status() {
    return this.#request('GET', '/api/status');
  }

  /** @returns {Promise<object[]>} snapshots of every task on the server */
  async listTasks() {
    const response = await this.#request('GET', '/api/tasks');
    return response.tasks ?? [];
  }

  /**
   * Starts a download.
   *
   * @param {object} request a `DownloadRequest`
   * @returns {Promise<object>} the new task's snapshot
   */
  createTask(request) {
    return this.#request('POST', '/api/tasks', { json: request });
  }

  /** @param {string} taskId */
  pauseTask(taskId) {
    return this.#request('POST', `/api/tasks/${encodeURIComponent(taskId)}/pause`);
  }

  /** @param {string} taskId */
  resumeTask(taskId) {
    return this.#request('POST', `/api/tasks/${encodeURIComponent(taskId)}/resume`);
  }

  /**
   * Resolves file content, such as `.torrent` metainfo, into a source Ketch can download.
   *
   * @param {ArrayBuffer | Uint8Array} content
   * @param {string | undefined} fileName
   * @returns {Promise<object>} a `ResolvedSource`
   */
  resolveContent(content, fileName) {
    const query = fileName ? `?fileName=${encodeURIComponent(fileName)}` : '';
    return this.#request('POST', `/api/resolve/content${query}`, { body: content });
  }

  async #request(method, path, { json, body } = {}) {
    const headers = { Accept: 'application/json' };
    if (this.#token) headers.Authorization = `Bearer ${this.#token}`;
    if (json !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(json);
    } else if (body !== undefined) {
      headers['Content-Type'] = 'application/octet-stream';
    }
    const doFetch = this.#fetch;
    const timeout = AbortSignal.timeout(this.#timeoutMs);
    const signal = this.#signal ? AbortSignal.any([timeout, this.#signal]) : timeout;
    let response;
    try {
      response = await doFetch(`${this.#baseUrl}${path}`, {
        method,
        headers,
        body,
        credentials: 'omit',
        signal,
      });
    } catch (error) {
      if (error?.name === 'TimeoutError' || error?.name === 'AbortError') {
        throw new KetchRequestError(
          FailureKind.TIMEOUT, t('error_no_response', this.#baseUrl), { cause: error });
      }
      throw new KetchRequestError(
        FailureKind.UNREACHABLE, t('error_unreachable', this.#baseUrl), { cause: error });
    }
    if (response.status === 401 || response.status === 403) {
      throw new KetchRequestError(
        FailureKind.UNAUTHORIZED,
        this.#token ? t('error_token_rejected') : t('error_token_required'),
        { status: response.status },
      );
    }
    const text = await response.text();
    const payload = parseJson(text);
    if (!response.ok) {
      const message = payload?.message || t('error_http_status', response.status);
      throw new KetchRequestError(FailureKind.REJECTED, message, { status: response.status });
    }
    return payload ?? {};
  }
}

function parseJson(text) {
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}
