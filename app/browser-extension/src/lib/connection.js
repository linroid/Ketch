/**
 * Reaches a Ketch instance: a server by its address, or the Ketch app on this computer through
 * native messaging, which needs no server or token set up in the app.
 */

import { ext } from './ext.js';
import { FailureKind, KetchRequestError } from './ketch-client.js';
import { isLoopbackUrl } from './settings.js';

/** Name the Ketch desktop app registers its native messaging host under. */
export const NATIVE_HOST = 'com.linroid.ketch';

/** Starting the app takes a while: a JVM, its database and its window. */
export const APP_START_TIMEOUT_MS = 45_000;

const ENDPOINT_KEY = 'appEndpoint';

/**
 * @typedef {{ url: string, token: string }} Endpoint
 */

/**
 * @typedef {object} ConnectionDeps
 * @property {(host: string, message: object) => Promise<any>} sendNativeMessage
 * @property {() => Promise<Endpoint | undefined>} loadEndpoint
 * @property {(endpoint: Endpoint | undefined) => Promise<void>} saveEndpoint
 */

/** A connection being set up by this context, which callers that start meanwhile share. */
let connecting;

/**
 * Runs `action` with the address and token of `instance`.
 *
 * For the Ketch app on this computer, the app hands them over through native messaging, starting
 * first if needed. They are kept for the browser session and asked for again when the app no
 * longer answers there, which also restarts it. `action` then runs again only if the app came
 * back with a new address or token: that app can't have seen the first attempt, whereas one that
 * merely dropped a connection may have created a task already.
 *
 * @template T
 * @param {import('./settings.js').Instance} instance
 * @param {(endpoint: Endpoint) => Promise<T>} action
 * @param {{ launch?: boolean, deps?: ConnectionDeps }} [options] with `launch: false`, fails
 *   with {@link FailureKind.APP_NOT_RUNNING} rather than starting the app
 * @returns {Promise<T>}
 */
export async function withEndpoint(instance, action, { launch = true, deps } = {}) {
  if (instance.type !== 'app') return action(instance);
  const connection = deps ?? browserDeps();
  const cached = await connection.loadEndpoint();
  let staleError;
  if (cached) {
    try {
      return await action(cached);
    } catch (error) {
      // The app restarted on another port and token, or quit.
      if (error?.kind !== FailureKind.UNREACHABLE && error?.kind !== FailureKind.UNAUTHORIZED) {
        throw error;
      }
      staleError = error;
      await connection.saveEndpoint(undefined);
    }
  }
  let endpoint;
  if (launch) {
    connecting ??= connectToApp(connection, true).finally(() => {
      connecting = undefined;
    });
    endpoint = await connecting;
  } else {
    endpoint = await connectToApp(connection, false);
  }
  await connection.saveEndpoint(endpoint);
  if (staleError && endpoint.url === cached.url && endpoint.token === cached.token) {
    throw staleError;
  }
  return action(endpoint);
}

/** @returns {Promise<Endpoint>} */
async function connectToApp(deps, launch) {
  let reply;
  try {
    reply = await withTimeout(
      deps.sendNativeMessage(NATIVE_HOST, { type: 'connect', launch }),
      APP_START_TIMEOUT_MS,
    );
  } catch (error) {
    if (error?.name === 'TimeoutError') {
      throw new KetchRequestError(FailureKind.TIMEOUT, "Ketch didn't start in time", {
        cause: error,
      });
    }
    // Chromium: "Specified native messaging host not found."; Firefox: "No such native
    // application com.linroid.ketch".
    if (/not found|no such native application/i.test(String(error?.message))) {
      throw new KetchRequestError(
        FailureKind.APP_NOT_INSTALLED,
        "The Ketch app isn't set up on this computer",
        { cause: error },
      );
    }
    throw new KetchRequestError(FailureKind.UNREACHABLE, "Couldn't reach the Ketch app", {
      cause: error,
    });
  }
  if (reply?.error === 'not_running') {
    throw new KetchRequestError(FailureKind.APP_NOT_RUNNING, "Ketch isn't running");
  }
  if (reply?.error) {
    throw new KetchRequestError(
      FailureKind.UNREACHABLE, reply.message || "The Ketch app couldn't connect");
  }
  // Downloads carry cookies, so only ever send them to this computer.
  if (typeof reply?.url !== 'string' || !isLoopbackUrl(reply.url) ||
    typeof reply.token !== 'string' || !reply.token) {
    throw new KetchRequestError(FailureKind.UNREACHABLE, 'The Ketch app sent an invalid reply');
  }
  return { url: reply.url, token: reply.token };
}

function withTimeout(promise, timeoutMs) {
  let timer;
  const timeout = new Promise((resolve, reject) => {
    timer = setTimeout(() => {
      reject(new DOMException('Timed out', 'TimeoutError'));
    }, timeoutMs);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

/** @returns {ConnectionDeps} */
function browserDeps() {
  // Session storage lives in memory and, unlike local storage, content scripts can't read it.
  const session = ext.storage.session;
  return {
    sendNativeMessage: (host, message) => ext.runtime.sendNativeMessage(host, message),
    loadEndpoint: async () => (await session.get(ENDPOINT_KEY))[ENDPOINT_KEY],
    saveEndpoint: (endpoint) => endpoint
      ? session.set({ [ENDPOINT_KEY]: endpoint })
      : session.remove(ENDPOINT_KEY),
  };
}
