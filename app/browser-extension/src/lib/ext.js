/**
 * The WebExtension API namespace: `browser` on Firefox, `chrome` on Chromium browsers.
 * Both return promises from the Manifest V3 APIs this extension uses.
 */
export const ext = globalThis.browser ?? globalThis.chrome;
