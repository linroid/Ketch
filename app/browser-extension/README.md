# Ketch browser extension

Hands downloads from your browser to Ketch: the Ketch app on this computer, which the extension
opens when it needs it, or a Ketch server elsewhere, such as a NAS. Chromium and Firefox
support automatic capture and native app launching. The limited Safari target sends reviewed
links and page resources to a configured server.

- **Captures downloads**: files you download in the browser go to Ketch instead. When Ketch
  can't be reached, the browser downloads them as usual.
- **Context menu**: right-click and open **Ketch → Page resources** to scan the page. On a
  link, image, video or audio, choose **Download link with Ketch** (or image, video, audio) in
  that menu. With several Ketch instances, a submenu lets you pick one.
- **Download directly**: choose **Ketch → Download directly** on an HTTP(S) link, image, video
  or audio to save it in the browser for this download only, without changing capture settings.
  On a linked image, this downloads the link. In Chromium private windows, use the browser's
  built-in save command instead.
- **Magnet links**: clicking a magnet link adds it to Ketch. Alt+click opens it in another app.
- **Torrent files**: a `.torrent` download becomes a torrent task, even when the site serves it
  from a script such as `download.php?id=7`.
- **Popup**: shows each instance's connection and recent downloads, pauses and resumes them, and
  adds a pasted link or magnet link.
- **Signed-in downloads**: the site's cookies, the referring page and the browser's user agent go
  along, so downloads that need a session work in Ketch too. They stay with that site: when a
  download redirects to another host, Ketch sends it the user agent and only the origin of the
  referring page, not the cookies.

## Supported browsers

| Browser | Minimum version | Build |
|---|---|---|
| Chrome, Edge, Brave, Opera, Vivaldi and other Chromium browsers | Chromium 116 | `build/chrome` |
| Firefox (desktop) | 128 | `build/firefox` |
| Safari (macOS, limited features) | 16.4 | `build/safari` |

Safari supports manual links, magnets, page resources and task controls, with review before
sending. Automatic browser-download capture, native app launching and extension notifications
are unavailable. See [Safari packaging](#safari-limited-macos-target).

## Installing

- **Chrome, Edge, Brave and other Chromium browsers**: install Ketch from the
  [Chrome Web Store](https://chromewebstore.google.com/detail/flnjeochbgpaipiofdjmoijaeooemhka).

Each [GitHub release](https://github.com/linroid/Ketch/releases) also includes
`ketch-extension-<version>-chrome.zip` (the Chrome Web Store package),
`ketch-extension-<version>-firefox.zip` and `ketch-extension-<version>-safari.zip`. The Safari
archive contains extension sources, not an installable app. Until the other stores list it:

- **Firefox**: release versions of Firefox only install add-ons signed by Mozilla, and the
  Firefox package is what gets submitted to addons.mozilla.org for signing. Until it is signed,
  load it for the current session from `about:debugging#/runtime/this-firefox` with **Load
  Temporary Add-on**, or install it in Firefox Developer Edition or Nightly with
  `xpinstall.signatures.required` set to `false` in `about:config`.

- **Safari**: package the Safari source archive with Xcode, sign the generated host app and
  enable the extension in Safari. See [Safari packaging](#safari-limited-macos-target) for
  the converter command and setup instructions.

### Store submission

Run `npm run build` and upload `build/ketch-extension-<version>-chrome.zip` to the
Chrome Web Store or Edge Add-ons. Release CI also attaches this package to each GitHub release.
Its manifest omits the development `key` of `src/`, which the Chrome Web Store rejects on upload.

The Chrome Web Store item is
[`flnjeochbgpaipiofdjmoijaeooemhka`](https://chromewebstore.google.com/detail/flnjeochbgpaipiofdjmoijaeooemhka),
listed in `NativeHostRegistration.CHROMIUM_EXTENSION_IDS` in `app/desktop` so the store extension
can open the Ketch app. Add the Edge Add-ons id there once it is published.

## Setting up Ketch

- **Ketch app on this computer (Chromium/Firefox)**: install the desktop app and open it once. It registers itself
  with the browsers you have used (Chrome, Chromium, Edge, Brave, Vivaldi, Arc and Firefox), and
  the extension is set up for it out of the box. From then on the extension opens Ketch when a
  download needs it; there is no server to turn on or token to copy. For a browser installed
  after Ketch, open Ketch once more.
- **Safari, CLI, or a browser installed as a Flatpak or Snap** (which can't start other apps): run
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
- the observed request uses a method other than GET, or saves a web document;
- another extension owns the download;
- their type is excluded by the file-type rules, including multipart suffixes such as `tar.gz`;
- they are smaller than the minimum size, if one is set. The unknown-size switch controls files
  without a known size; `.torrent` files bypass the size checks.

Site exclusions check the original URL, final URL, referring page and observed page origin, so
files served through a CDN still respect the originating site's exclusion. Request metadata is
kept in memory for at most 30 seconds and 512 requests; it contains no request bodies or headers.
If the browser did not expose a request method, capture cannot establish whether it was a POST.

Turn off **Capture browser downloads** in the popup to leave downloads to the browser.
Magnet capture has its own switch in settings.

The file name the browser chose (from the `download` attribute or `Content-Disposition`) becomes
the file name in Ketch's download folder; Ketch picks a free name if it is taken.

## Page resources

Choose **Page resources** in the popup or **Ketch → Page resources** in the right-click menu
to scan that website on demand. The picker lists
images, ordinary audio/video URLs and direct file links from accessible frames and resources
already loaded by the page. It does not fetch candidates, start playback, or transmit page data
to Ketch while scanning. Private and browser-internal pages cannot be scanned.

Filter by type, select visible resources and choose an instance to download the selection.
Results are deduplicated and capped at 500. Each submission uses the current cookie permissions;
changing the target's connection settings prevents the batch from silently switching servers.
Successes and uncertain submissions are disabled to avoid resending them within the picker.

Scroll or play media in the original tab, then **Scan again**, to find lazy-loaded content.
`blob:` URLs, HLS/DASH playlists, DRM and resources hidden in custom player internals are not
included. Finding a URL does not guarantee it is still valid or independently downloadable.

## Submission recovery and diagnostics

Each submission to a supporting Ketch instance has a UUID. Ketch returns the same retained task
when that UUID is submitted again with the same options, including after loading its task store.
Removing the task also removes that receipt. Older instances still accept downloads, but the
extension never automatically repeats a write when its result is unknown.

If the response is lost, the extension checks the task list. When it still cannot confirm the
result, it retains a pending receipt for the browser session and blocks another send of that URL
to the same endpoint. An intercepted browser download is paused where possible; check Ketch
before resuming it manually. In extension settings, **Check pending downloads** looks up receipts
without launching the app or submitting downloads. Confirmed handoffs cancel the matching browser
download. A missing receipt, removed task, or older server needs manual inspection; restarting the
browser clears pending receipts. Changing a server's address or token does not move its receipts
to the new connection.

**Export diagnostics** saves the last 100 outcome codes and timestamps with the extension version.
It contains no URLs, filenames, instance names, cookies, access tokens or raw error messages.
**Clear diagnostics** clears that history without forgetting pending submissions.

## Privacy and permissions

Nothing is sent anywhere except to the Ketch instances you add.

| Permission | Why |
|---|---|
| `downloads` | Capture downloads and cancel them in the browser once Ketch has them |
| `nativeMessaging` | Reach and open the Ketch app on this computer |
| `scripting`, `activeTab` | Scan page resources from the popup or context menu |
| `webRequest` | Observe request methods and source pages for capture rules; no bodies or headers |
| `cookies` | Send a site's cookies to Ketch so downloads that need a session work |
| `contextMenus` | Download actions and the **Page resources** entry |
| `notifications` | Tell you when a download was sent, or why it wasn't |
| `storage` | Settings, including instance addresses and access tokens |
| Access to all sites | Read cookies for any download, see magnet link clicks, and reach Ketch at any address |

If the browser hasn't granted access to all sites, the settings page shows an **Allow access**
button.

Things to know:

- **Send cookies and referrer** is a global switch. Remote servers additionally need **Allow
  cookies and referrer for this server**, off by default, including when upgrading existing
  settings without an explicit grant. Changing a server address clears its grant. The native
  app and existing loopback servers retain local forwarding. With forwarding allowed, Ketch stores the cookies with the task so
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
  and select `src/`. Not `build/chrome`: without the `key`, Chrome gives it an id the Ketch app
  does not accept.
- **Firefox**: run `npm run build`, open `about:debugging#/runtime/this-firefox`, choose **Load
  Temporary Add-on** and select `build/firefox/manifest.json`.

```shell
npm test        # unit tests, with Node's built-in test runner (Node 22.3+)
npm run build   # build/chrome, build/firefox, build/safari and their zips
```

The `key` in `src/manifest.json` pins the Chromium extension id to
`kddcjkhnjcjhekohejnehplbnjclbdbl` when `src/` is loaded unpacked, because the Ketch app only lets
the ids it lists start its host (`NativeHostRegistration` in `app/desktop`). Extension stores
assign their own id, so the packaged builds leave the `key` out; add each store's id there when
publishing. Firefox identifies the add-on by its gecko id instead.

The build copies `src/`, removes the `key` from the Chromium build, rewrites the manifest
for Firefox (an event page instead of a service worker, its add-on id, and no `key`), checks that
every file the manifest names exists, and zips each build. Keep `version` in `package.json` and
`src/manifest.json` the same; the build fails
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

Streaming playlists in the resource picker require `hls.finite` for HLS or `dash.finite` for
DASH. The combined `media.finite` capability is also accepted for older servers.
Finite, unencrypted, single-stream HLS and static MP4 DASH are supported within the
[media engine's documented limits](../../docs/media.md). Live or encrypted streams and
separate tracks that require merging are rejected by the engine.

Turn on **Review downloads before sending** to choose the receiving device, an optional file
name, and a folder before each captured download, context-menu link, pasted link or magnet is
sent. The folder belongs to the receiving device and remains subject to its server directory
policy. Torrents retain their file layout. The page resource picker already reviews a batch
and also has an optional destination folder.

A captured browser download must pause successfully before the review opens. Canceling or
closing an unsent review resumes it in the browser. Acceptance removes the browser original;
an uncertain send keeps it paused until you check pending downloads in settings. Reviews and
receipts last only for the browser session. After restarting the browser, inspect Ketch and
the browser's downloads before manually resuming a paused original.

### Safari (limited macOS target)

`npm run build` also writes `build/safari` and a Safari source zip. This target requires Safari
16.4 or later for module background workers and session storage. It supports configured Ketch
servers, reviewed context-menu links and magnets, pasted links, page resources, task controls,
and diagnostics. It does not intercept browser downloads, launch the desktop app through a
native host, or issue extension notifications. Every individual send opens the review page;
batch sends are reviewed in the resource picker.

Start sharing in Ketch or start `ketch server`, then enter its address and access token in the
extension settings. Safari must allow website access both to source pages and to the configured
server. Cookie forwarding still requires the global switch and a grant for that server.

On macOS with Xcode installed:

```sh
npm run safari
open "build/safari-xcode/Ketch for Safari/Ketch for Safari.xcodeproj"
```

The script uses Apple's converter to generate a host app and extension under `build/`; it
replaces only that generated project. Choose a signing team in Xcode, run the host and enable
the extension in Safari settings. The source zip is not an installable Safari app. App Store
signing, notarization, publishing, and iOS packaging are outside this target.

To check compilation without signing:

```sh
xcodebuild -project "build/safari-xcode/Ketch for Safari/Ketch for Safari.xcodeproj" \
  -scheme "Ketch for Safari" -configuration Debug -destination 'platform=macOS' \
  -derivedDataPath build/safari-derived CODE_SIGNING_ALLOWED=NO build
```

Some converter versions warn about the background `type` key despite module workers being
supported in Safari 16.4+. See the [background compatibility data](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/manifest.json/background)
and [Apple's packaging guidance](https://developer.apple.com/documentation/safariservices/safari-web-extensions).
Compilation and API-contract tests do not replace testing site permissions and downloads in
a signed, enabled Safari extension.
