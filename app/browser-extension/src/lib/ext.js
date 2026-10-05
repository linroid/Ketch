/**
 * The WebExtension API namespace: `browser` on Firefox, `chrome` on Chromium browsers.
 * Both return promises from the Manifest V3 APIs this extension uses.
 */
export const ext = globalThis.browser ?? globalThis.chrome;

/** The Safari package omits native messaging and browser-download interception. */
export const remoteOnly = ext?.runtime?.getManifest
  ? !ext.runtime.getManifest().permissions.includes('nativeMessaging') : false;
