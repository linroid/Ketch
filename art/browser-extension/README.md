# Browser extension store assets

The icons and store screenshots for the Ketch browser extension, and how to regenerate them. It is
for maintainers who update the extension's store listings.

| File | Contents |
| --- | --- |
| `icon-128.png` | Chrome Web Store icon, 128 × 128 pixels |
| `icon-16.png`, `icon-32.png`, `icon-48.png` | The other extension icon sizes |
| `screenshot-1280x800.png` | English store screenshot, 1280 × 800 pixels |
| `screenshot-1280x800-zh-CN.png` | Simplified Chinese version, with the localized popup |
| `store-screenshot.html` | The store artwork around the popup |
| `render-screenshot.mjs` | Renders the screenshots |

## Icons

Run `art/render-icons.sh` from the repository root to regenerate the icons from
`art/icon-app.svg`. It also copies them into the extension's `src/icons/`.

## Screenshots

The screenshot embeds the real extension popup and its styles, with fixed sample downloads and
stubbed browser and server APIs. It does not connect to an existing browser profile or Ketch
instance.

To regenerate, make Playwright available to Node (for example through `NODE_PATH`) and run:

```sh
node art/browser-extension/render-screenshot.mjs
```

```sh
node art/browser-extension/render-screenshot.mjs --locale zh-CN
```

- On macOS the renderer uses the installed Google Chrome. Set `CHROME_PATH` to use another Chrome
  or Chromium executable; on other platforms it defaults to Playwright's Chromium.
- The script starts a temporary loopback server, checks that the popup loads without JavaScript
  errors and fits the frame, writes the PNG, and closes the server and browser.

Review the PNG after rendering.
