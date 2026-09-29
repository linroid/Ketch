# Ketch browser extension

Hands downloads from your browser to Ketch, on this computer or on a remote server such as a NAS.

- **Captures downloads**: files you download in the browser go to Ketch instead. When Ketch
  can't be reached, the browser downloads them as usual.
- **Context menu**: right-click a link, image, video or audio and choose **Download with
  Ketch**. With several Ketch instances, a submenu lets you pick one.
- **Magnet links**: clicking a magnet link adds it to Ketch. Alt+click opens it in another app.
- **Torrent files**: a `.torrent` download becomes a torrent task, even when the site serves it
  from a script such as `download.php?id=7`.
- **Popup**: shows each instance's connection and recent downloads, pauses and resumes them, and
  adds a pasted link or magnet link.
- **Signed-in downloads**: the site's cookies, the referring page and the browser's user agent go
  along, so downloads that need a session work in Ketch too.

## Supported browsers

| Browser | Minimum version | Build |
|---|---|---|
| Chrome, Edge, Brave, Opera, Vivaldi and other Chromium browsers | Chromium 116 | `build/chrome` |
| Firefox (desktop) | 128 | `build/firefox` |

Safari has no downloads API for extensions, so it isn't supported.

## Setting up Ketch

The extension talks to Ketch's REST API, so the Ketch server must be running:

- **Ketch app on this computer**: open Settings → Remote access and turn on **Server**. Turn on
  **Start automatically** as well, so the extension works whenever the app is open. The extension
  is set up for this out of the box, at `http://127.0.0.1:8642`.
- **CLI**: run `ketch server`.
- **Another device**: on that device, turn on **Server** and **Allow other devices** and generate
  an access token (for `ketch server`, set `apiToken` in `config.toml`). In the extension's
  settings, choose **Add remote instance** and enter its address, such as
  `http://192.168.1.20:8642`, and the token. A bare host such as `nas.local` gets port 8642.

## Instances

The extension can send downloads to several Ketch instances. One of them is the default:

- captured downloads and clicked magnet links go to the default instance;
- the context menu lists every instance, default first;
- the popup has a picker to watch any instance, and adds pasted links to the one shown.

Each instance card in the settings page shows whether it can be reached. Captured downloads fall
back to the browser only when the default instance fails; they never switch to another instance.

## How capturing works

In Chromium browsers the extension holds each download while its file name is decided, before
any "Save as" dialog. It asks Ketch to take the download, then cancels it in the browser. If Ketch
refuses or doesn't answer within 6 seconds, the browser continues as if nothing happened.

Firefox has no such hook, so the extension pauses the download once it has started, then cancels
or resumes it. If Firefox is set to ask where to save each file, that dialog appears first.

Downloads are left to the browser when:

- capturing is turned off (in the popup or the settings page);
- they come from a private window;
- the link only exists in the browser (`blob:`, `data:`), as with media a page streams itself;
- they come from a site in the exclusion list, or from a configured Ketch instance itself;
- they are smaller than the minimum size, if one is set. Files of unknown size and `.torrent`
  files are always captured.

The file name the browser chose (from the `download` attribute or `Content-Disposition`) becomes
the file name in Ketch's download folder; Ketch picks a free name if it is taken.

## Privacy and permissions

Nothing is sent anywhere except to the Ketch instances you add.

| Permission | Why |
|---|---|
| `downloads` | Capture downloads and cancel them in the browser once Ketch has them |
| `cookies` | Send a site's cookies to Ketch so downloads that need a session work |
| `contextMenus` | The **Download with Ketch** menu items |
| `notifications` | Tell you when a download was sent, or why it wasn't |
| `storage` | Settings, including instance addresses and access tokens |
| Access to all sites | Read cookies for any download, see magnet link clicks, and reach Ketch at any address |

Things to know:

- With **Send cookies and referrer** on (the default), Ketch stores the cookies with the task so
  it can resume it later, in its task database on the device that downloads. Turn the option off
  for downloads that don't need a session.
- Access tokens are stored in the browser's extension storage, like the apps store them in
  `config.toml`.
- Over plain `http://` to another device, the token and cookies travel unencrypted. The settings
  page warns about such addresses; prefer `https://` behind a reverse proxy, or use a trusted
  network.
- The magnet link script only reacts to real clicks, so a page can't add downloads by scripting
  clicks, and it never sees the access tokens.

## Limitations

- Downloads started by submitting a form (POST) can't be repeated by Ketch; exclude such sites or
  turn capturing off in the popup.
- Cookies are read from the tab's cookie store (including Firefox containers), but cookies a
  browser keeps partitioned for third-party frames aren't sent.
- Streaming media (HLS, DASH, `blob:` videos) has no single file to download.
- Firefox for Android isn't supported: it has no downloads API.

## Development

The extension is plain JavaScript modules with no dependencies or bundler. `src/` is the
Chromium build, so it can be loaded as is while developing:

- **Chromium**: open `chrome://extensions`, turn on **Developer mode**, choose **Load unpacked**
  and select `src/` (or `build/chrome`).
- **Firefox**: run `npm run build`, open `about:debugging#/runtime/this-firefox`, choose **Load
  Temporary Add-on** and select `build/firefox/manifest.json`.

```shell
npm test        # unit tests, with Node's built-in test runner (Node 22.2+)
npm run build   # build/chrome, build/firefox and a zip of each for the stores
```

The build copies `src/`, rewrites the manifest for Firefox (an event page instead of a service
worker, plus its add-on id), checks that every file the manifest names exists, and zips each
build. Keep `version` in `package.json` and `src/manifest.json` the same; the build fails
otherwise. The icons are rendered from `art/icon-app.svg` by `art/render-icons.sh`.

| Path | Contents |
|---|---|
| `src/background.js` | Download capture, context menus, magnet link hand-off |
| `src/content/magnet.js` | Sends clicked magnet links to the background script |
| `src/popup/` | Toolbar popup |
| `src/options/` | Settings page |
| `src/lib/settings.js` | Settings, instances and their validation |
| `src/lib/ketch-client.js` | Client for the Ketch REST API |
| `src/lib/handoff.js` | Builds a download request with cookies and creates the task |
| `src/lib/intercept.js` | Which browser downloads are captured |
| `test/` | Unit tests |
