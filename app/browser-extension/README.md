# Ketch browser extension

Hands downloads from your browser to Ketch: the Ketch app on this computer, which the extension
opens when it needs it, or a Ketch server elsewhere, such as a NAS.

- **Captures downloads**: files you download in the browser go to Ketch instead. When Ketch
  can't be reached, the browser downloads them as usual.
- **Context menu**: right-click a link, image, video or audio and choose **Download link with
  Ketch** (or image, video, audio). With several Ketch instances, a submenu lets you pick one.
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

## Installing

Each [GitHub release](https://github.com/linroid/Ketch/releases) includes
`ketch-extension-<version>-chrome.zip` and `ketch-extension-<version>-firefox.zip`. Until the
extension is in the browser stores:

- **Chromium browsers**: unzip the Chrome package, open `chrome://extensions` (or
  `edge://extensions`), turn on **Developer mode**, choose **Load unpacked** and select the
  unzipped folder.
- **Firefox**: release versions of Firefox only install add-ons signed by Mozilla, and the
  Firefox package is what gets submitted to addons.mozilla.org for signing. Until it is signed,
  load it for the current session from `about:debugging#/runtime/this-firefox` with **Load
  Temporary Add-on**, or install it in Firefox Developer Edition or Nightly with
  `xpinstall.signatures.required` set to `false` in `about:config`.

## Setting up Ketch

- **Ketch app on this computer**: install the desktop app and open it once. It registers itself
  with the browsers you have used (Chrome, Chromium, Edge, Brave, Vivaldi, Arc and Firefox), and
  the extension is set up for it out of the box. From then on the extension opens Ketch when a
  download needs it; there is no server to turn on or token to copy. For a browser installed
  after Ketch, open Ketch once more.
- **CLI, or a browser installed as a Flatpak or Snap** (which can't start other apps): run
  `ketch server`, or in the app open **Settings → Sharing** and choose **Allow another device**.
  Then choose **Add server** in the extension's settings and enter `http://127.0.0.1:8642`.
  Sharing from the app always has an access code: enter the one under **Advanced** as the access
  token. For `ketch server`, enter its `apiToken` or the token it printed when it created one
  (see [its access token](../../cli/README.md#access-token)); on `--host 127.0.0.1` it needs
  none unless you give it one.
- **Another device**: on that device, open **Settings → Sharing** and choose **Allow another
  device**; the access code is under **Advanced** (for `ketch server`, the token it uses, as
  above). In the extension's settings, choose **Add server** and enter the address Ketch
  shows, such as `http://192.168.1.20:8642`, and the code as the access token. A bare host such
  as `nas.local` gets port 8642.

## Instances

The extension can send downloads to several Ketch instances. One of them is the default:

- captured downloads and clicked magnet links go to the default instance;
- the context menu lists every instance, default first;
- the popup has a picker to watch any instance, and adds pasted links to the one shown.

Each instance card in the settings page shows whether it can be reached. Captured downloads fall
back to the browser only when the default instance fails; they never switch to another instance.

## How the extension reaches the Ketch app

The Ketch app on this computer is reached through
[native messaging](https://developer.chrome.com/docs/extensions/develop/concepts/native-messaging):

1. The browser starts the host `com.linroid.ketch`, which is the Ketch app's own launcher run with
   `--native-messaging-host`. Only this extension may start it.
2. The host asks the running app for a connection, over the channel the app already uses to keep
   a single instance. If Ketch is closed, the host opens it and waits for it, up to 30 seconds.
3. The app replies with the address and token of a server only for the extension: it listens on
   `127.0.0.1` only, on a port the system picks, with a token made for this run of the app. It is
   separate from sharing in Settings → Sharing, which stays off unless you turn it on.
4. The extension keeps that address and token in memory for the browser session and talks to it
   like to any Ketch server. When the app is restarted, it asks the host again.

Looking at the popup or the settings page never opens Ketch; sending a download, **Open Ketch**
in the popup and **Test connection** do. A captured download waits for Ketch to start (up to 45
seconds) before the browser takes it back.

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
| `nativeMessaging` | Reach and open the Ketch app on this computer |
| `cookies` | Send a site's cookies to Ketch so downloads that need a session work |
| `contextMenus` | The **Download … with Ketch** menu items |
| `notifications` | Tell you when a download was sent, or why it wasn't |
| `storage` | Settings, including instance addresses and access tokens |
| Access to all sites | Read cookies for any download, see magnet link clicks, and reach Ketch at any address |

If the browser hasn't granted access to all sites, the settings page shows an **Allow access**
button.

Things to know:

- With **Send cookies and referrer** on (the default), Ketch stores the cookies with the task so
  it can resume it later, in its task database on the device that downloads. Turn the option off
  for downloads that don't need a session.
- Access tokens of servers are stored in the browser's extension storage, like the apps store
  them in `config.toml`. The Ketch app's token only lives in memory, for the browser session.
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
- The Ketch app registers with the browsers listed under [Setting up Ketch](#setting-up-ketch).
  In Opera, and in browsers installed as a Flatpak or Snap, add Ketch as a server instead.
- Uninstalling the app leaves its registration behind: files in the browsers'
  `NativeMessagingHosts` directories, or registry values on Windows.
- Native messaging was tested with Chrome on macOS. The Windows and Linux registration and
  launch code has unit tests but hasn't run against real browsers yet.

## Development

The extension is plain JavaScript modules with no dependencies or bundler. `src/` is the
Chromium build, so it can be loaded as is while developing:

- **Chromium**: open `chrome://extensions`, turn on **Developer mode**, choose **Load unpacked**
  and select `src/` (or `build/chrome`).
- **Firefox**: run `npm run build`, open `about:debugging#/runtime/this-firefox`, choose **Load
  Temporary Add-on** and select `build/firefox/manifest.json`.

```shell
npm test        # unit tests, with Node's built-in test runner (Node 22.3+)
npm run build   # build/chrome, build/firefox and a zip of each
```

The `key` in `src/manifest.json` pins the Chromium extension id to
`kddcjkhnjcjhekohejnehplbnjclbdbl`, whether it is loaded unpacked or from a release zip, because
the Ketch app only lets the ids it lists start its host (`NativeHostRegistration` in
`app/desktop`). Extension stores assign their own id: add it there when publishing, and leave the
`key` out of a store upload. Firefox identifies the add-on by its gecko id instead.

The build copies `src/`, rewrites the manifest for Firefox (an event page instead of a service
worker, its add-on id, and no `key`), checks that every file the manifest names exists, and zips
each build. Keep `version` in `package.json` and `src/manifest.json` the same; the build fails
otherwise. The icons are rendered from the repository's `art/icon-app.svg` by
`art/render-icons.sh`.

For a release tag, the release workflow runs `node build.mjs --version <version> --build <run
number>` and attaches the zips to the GitHub release. Browsers only accept versions made of
numbers, so `0.0.1-rc12` becomes `0.0.1.<run number>` in the manifests, and Chromium shows
`0.0.1-rc12` as the version name. The run number makes every release newer than the one before,
including a final release after its release candidates, which the stores require for updates.

| Path | Contents |
|---|---|
| `src/background.js` | Download capture, context menus, magnet link hand-off |
| `src/content/magnet.js` | Sends clicked magnet links to the background script |
| `src/popup/` | Toolbar popup |
| `src/options/` | Settings page |
| `src/ui/common.css` | Styles shared by the popup and the settings page |
| `src/lib/settings.js` | Settings, instances and their validation |
| `src/lib/ketch-client.js` | Client for the Ketch REST API |
| `src/lib/connection.js` | Reaches the Ketch app through native messaging |
| `src/lib/handoff.js` | Creates the task with cookies, or resolves a fetched `.torrent` file first |
| `src/lib/request.js` | Builds the download request and headers, tagged `ketch.origin: browser`; recognizes torrents |
| `src/lib/intercept.js` | Which browser downloads are captured |
| `src/lib/format.js` | Text for tasks, sizes and connection problems |
| `src/lib/i18n.js` | Messages in the browser's language, for scripts and pages |
| `src/lib/ext.js` | The `browser` or `chrome` API namespace |
| `src/_locales/` | Messages of each language |
| `test/` | Unit tests |

## Translations

The extension shows its text in the browser's language, in the
[languages of the apps](../../docs/development/localization.md#languages). It falls back to
English for other languages and for any message a translation lacks. The terms follow the
[translation glossary](../../docs/development/translation-glossary.md) of the apps.

Messages live in `src/_locales/<language>/messages.json`, in the
[`i18n` format](https://developer.chrome.com/docs/extensions/reference/api/i18n) both browser
families read. English, in `en`, is the source: each message has a `description` saying where it
shows and what fills its placeholders. Translations hold only each `message` and the
`placeholders` with their `content`.

- **Scripts** call `t('key', ...values)` from `src/lib/i18n.js`. Each value fills the next
  placeholder: a message `"Sent to $NAME$"` with `"name": { "content": "$1" }` gets its name from
  the first value. Outside a browser, as in the tests, `t` reads the English file.
- **Pages** mark elements with `data-i18n="key"` for their text, `data-i18n-markup="key"` for text
  with `<b>` or `<code>`, and `data-i18n-title`, `data-i18n-placeholder` or
  `data-i18n-aria-label` for those attributes; `localizePage()` fills them when the page loads.
  The English text stays in the HTML.
- **The manifest** uses `__MSG_key__`.

To add a string, add it to `en/messages.json` with a description, use it as above and add it to
the other languages; `test/locales.test.js` fails until every language has it, with the same
placeholders. Write a whole sentence per message with placeholders for what changes, rather than
joining pieces, and keep counts out of sentences: the `i18n` format has no plural forms. To add a
language, copy a translation's folder under the language's
[locale code](https://developer.chrome.com/docs/extensions/reference/api/i18n#locales), translate
it and set `language_tag` to the language's tag, such as `pt-BR`.
