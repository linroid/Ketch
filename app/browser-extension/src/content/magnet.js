/**
 * Content script: sends clicked magnet links to Ketch. Alt+click leaves a link to the browser.
 * If Ketch can't take the link, it opens as it would have without the extension.
 */
(() => {
  const ext = globalThis.browser ?? globalThis.chrome;
  // Mirrored by the settings under its own key, so this script never sees access tokens.
  const CAPTURE_KEY = 'captureMagnetLinks';
  const PAUSE_KEY = 'capturePaused';
  let enabled = true;
  let capturePaused = false;

  ext.storage.local.get([CAPTURE_KEY, PAUSE_KEY]).then((stored) => {
    enabled = stored[CAPTURE_KEY] ?? true;
    capturePaused = stored[PAUSE_KEY] === true;
  }, () => {});
  ext.storage.onChanged.addListener((changes, area) => {
    if (area === 'local' && changes[CAPTURE_KEY]) enabled = changes[CAPTURE_KEY].newValue ?? true;
    if (area === 'local' && changes[PAUSE_KEY]) capturePaused = changes[PAUSE_KEY].newValue === true;
  });

  document.addEventListener('click', (event) => {
    // Only real clicks: a page must not be able to add downloads by scripting clicks.
    if (!enabled || !event.isTrusted || event.defaultPrevented || event.button !== 0) return;
    if (capturePaused) return;
    if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
    const anchor = event.composedPath().find((node) => node instanceof HTMLAnchorElement);
    const url = anchor?.href;
    // runtime.id is gone once the extension is updated or removed and this script is orphaned.
    if (!url || !/^magnet:/i.test(url) || !ext.runtime?.id) return;
    event.preventDefault();
    const openInBrowser = () => location.assign(url);
    try {
      ext.runtime.sendMessage({ type: 'magnet', url }).then((response) => {
        if (!response?.handled) openInBrowser();
      }, openInBrowser);
    } catch {
      openInBrowser();
    }
  }, true);
})();
