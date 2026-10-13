# Ketch browser extension

Hands downloads from your browser to Ketch: the Ketch app on this computer, which the extension
opens when it needs it, or a Ketch server elsewhere, such as a NAS. This README covers installing
and using the extension, what it does with your data, and how to build, test and release it.

Chromium browsers and Firefox get automatic capture and native app launching. The limited Safari
target sends links and page resources to a configured server.

## Features

- **Captures downloads**: files you download in the browser go to Ketch instead. When Ketch can't
  be reached, the browser downloads them as usual.
- **Context menu**: right-click and open **Ketch → Page resources** to scan the page. On a link,
  image, video or audio, choose **Download link with Ketch** (or image, video, audio) in that
  menu. With several Ketch instances, a submenu lets you pick one.
- **Download directly**: choose **Ketch → Download directly** on an HTTP(S) link, image, video or
  audio to save it in the browser for this download only, without changing capture settings. On a
  linked image, this downloads the link. In Chromium private windows, use the browser's built-in
  save command instead.
- **Magnet links**: clicking a magnet link adds it to Ketch. Alt+click opens it in another app.
- **Torrent files**: a `.torrent` download becomes a torrent task, even when the site serves it
  from a script such as `download.php?id=7`.
- **Popup**: shows each instance's connection and recent downloads, pauses and resumes them, and
  adds a pasted link or magnet link.
- **Signed-in downloads**: the site's cookies, the referring page and the browser's user agent go
  along, so downloads that need a session work in Ketch too. They stay with that site: when a
  download redirects to another host, Ketch sends it the user agent and only the origin of the
  referring page, not the cookies.
- **Download dialog** (on by default): before anything is sent, a small dialog lets you rename the
  file, pick the receiving device and folder, or download it in the browser instead.

## Supported browsers

| Browser | Minimum version | Build |
| --- | --- | --- |
| Chrome, Edge, Brave, Opera, Vivaldi and other Chromium browsers | Chromium 116 | `build/chrome-store`, `build/chrome-unpacked` |
| Firefox (desktop) | 128 | `build/firefox` |
| Safari (macOS, limited features) | 16.4 | `build/safari` |

Safari supports manual links, magnets, page resources and task controls, through the same
download dialog. Automatic browser-download capture, native app launching and extension notifications are
unavailable. See [Safari](#safari-limited-macos-target).

## Installing

- **Chrome, Edge, Brave and other Chromium browsers**: install Ketch from the
  [Chrome Web Store](https://chromewebstore.google.com/detail/flnjeochbgpaipiofdjmoijaeooemhka).
  To install a release by hand, such as one the store is still reviewing, unzip its
  `chrome-unpacked` package, open `chrome://extensions`, turn on **Developer mode**, choose
  **Load unpacked** and select the folder. It has the store version's id, so it is the same
  extension to Chrome and to the Ketch app: remove the store version first, and reinstall it from
  the store once the review is done to get updates again. Not the `chrome-store` package: loaded
  by hand, it gets an id from its folder that the Ketch app refuses.
- **Firefox**: release versions of Firefox only install add-ons signed by Mozilla, and the Firefox
  package is what gets submitted to addons.mozilla.org for signing. Until it is signed, load it
  for the current session from `about:debugging#/runtime/this-firefox` with **Load Temporary
  Add-on**, or install it in Firefox Developer Edition or Nightly with
  `xpinstall.signatures.required` set to `false` in `about:config`.
- **Safari**: package the Safari source archive with Xcode, sign the generated host app and
  enable the extension in Safari. See [Safari](#safari-limited-macos-target).

The extension has its own releases on the
[releases page](https://github.com/linroid/Ketch/releases), tagged
`extension-v<app version>.<number>` and named like "Browser extension 0.4.0.1"; each app release
links the latest one under *Add Ketch to your browser*. Each includes:

- `ketch-extension-<version>-chrome-store.zip`, the Chrome Web Store upload (no `key`; loaded by
  hand, it gets an id the Ketch app refuses);
- `ketch-extension-<version>-chrome-unpacked.zip`, the Chromium package with the store item's
  public key as the manifest `key`, for **Load unpacked**: it gets the store's id, which the Ketch
  app accepts;
- `ketch-extension-<version>-firefox.zip`;
- `ketch-extension-<version>-safari.zip`, which contains extension sources, not an installable
  app.

## Setting up Ketch

- **The Ketch app on this computer (Chromium and Firefox)**: install the desktop app and open it
  once. It registers itself with the browsers you have used (Chrome, Chromium, Edge, Brave,
  Vivaldi, Arc and Firefox), and the extension is set up for it out of the box. From then on the
  extension opens Ketch when a download needs it; there is no server to turn on or token to copy.
  For a browser installed after Ketch, open Ketch once more.
- **Safari, the CLI, or a browser installed as a Flatpak or Snap** (which can't start other apps):
  run `ketch server`, or in the app open **Settings → Sharing** and choose **Allow another
  device**. Then choose **Add server** in the extension's settings and enter
  `http://127.0.0.1:8642`.
  - Sharing from the app always has an access code: enter the one under **Advanced** as the access
    token.
  - For `ketch server`, enter its `apiToken` or the token it printed when it created one (see
    [its access token](../../cli/README.md#access-token)); on `--host 127.0.0.1` it needs none
    unless you give it one.
- **Another device**: on that device, open **Settings → Sharing** and choose **Allow another
  device**; the access code is under **Advanced** (for `ketch server`, the token it uses, as
  above). In the extension's settings, choose **Add server** and enter the address Ketch shows,
  such as `http://192.168.1.20:8642`, and the code as the access token. A bare host such as
  `nas.local` gets port 8642.

## Using the extension

### Instances

The extension can send downloads to several Ketch instances. One of them is the default:

- the download dialog starts on the default instance for captured downloads and clicked magnet
  links, and on the one you picked for context-menu and pasted links;
- the context menu lists every instance, default first;
- the popup has a picker to watch any instance, and adds pasted links to the one shown.

Each instance card in the settings page shows whether it can be reached; the download dialog shows
it too, and lets you switch instance for that download. With the dialog off, captured downloads fall back to the
browser only when the default instance fails; they never switch to another instance.

### Capturing downloads

Turn off **Capture browser downloads** in the popup to leave downloads to the browser. Magnet
capture has its own switch in settings.

In Chromium browsers the extension holds each download while its file name is decided, before any
"Save as" dialog or download bubble. With the [download dialog](#download-dialog) on, it cancels
the browser's download there and then, so the browser shows nothing, and opens the dialog;
**Download in browser** starts it again. With the dialog off, it asks Ketch to take the download
straight away; if Ketch refuses or doesn't answer within 6 seconds, the browser continues as if
nothing happened.

Firefox has no such hook, so the extension acts once the download has started: its downloads
panel may show it for a moment, and if Firefox is set to ask where to save each file, that dialog
appears first.

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
If the browser did not expose a request method, capture cannot tell whether it was a POST.

The file name the browser chose (from the `download` attribute or `Content-Disposition`) becomes
the file name in Ketch's download folder, or in the category folder there that the receiving
device sorts it into by type or site (Settings → Downloads in the app). Ketch picks a free name if
it is taken.

### Page resources

Choose **Page resources** in the popup, or **Ketch → Page resources** in the right-click menu, to
scan the page on demand. The picker lists images, ordinary audio and video URLs, and direct file
links from accessible frames and resources the page has already loaded. It does not fetch
candidates, start playback or send page data to Ketch while scanning. Private and
browser-internal pages cannot be scanned.

- Filter by type, select visible resources and choose an instance to download the selection. The
  picker also has an optional destination folder.
- Results are deduplicated and capped at 500.
- Each submission uses the current cookie permissions; changing the target's connection settings
  prevents the batch from silently switching servers.
- Successful and uncertain submissions are disabled, so they are not sent again from the picker.
- Scroll or play media in the original tab, then **Scan again**, to find lazy-loaded content.

`blob:` URLs, DRM and resources hidden in custom player internals are not included. Finding a URL
does not guarantee it is still valid or downloadable on its own.

Streaming playlists in the picker need the receiving instance to support them: `hls.finite` for
HLS and `dash.finite` for DASH (older servers' combined `media.finite` also counts). Finite,
unencrypted, single-stream HLS and static MP4 DASH work within the
[media engine's limits](../../docs/media.md); live or encrypted streams and separate tracks that
would need merging are rejected by the engine.

### Download dialog

With **Review downloads before sending** on, as it is by default, every captured download,
context-menu link, pasted link and clicked magnet link opens a dialog before anything is sent.
Turn it off in settings to send them straight to the default instance, or the one picked in the
menu or popup; Safari always shows the dialog.

It opens over the page the download came from, in a frame of the extension's own page that the
site can't read or style; clicking outside it cancels. On pages extensions can't script (browser
pages, the extension stores, a frame that doesn't load within 5 seconds), it opens as a small
window over the browser instead, or a tab in Safari. It shows:

- the file's type, name, size when the browser knows it, and site. Edit the name to save it under
  another one; torrents keep the names and layout inside them;
- every Ketch instance with whether it can be reached and the free space in its download folder;
  checking never opens the Ketch app, sending does;
- the folder, on the selected device, with quick options under it:
  - **Auto** (the default) leaves the folder empty, so the device chooses: its download folder,
    or the category folder it sorts this file into by type or site (Settings → Downloads in the
    app), named on the chip as in "Auto · Videos" and shown in the field. Clicking the field fills
    in that path to edit; left unchanged, it stays Auto;
  - the device's category folders, to pick one yourself;
  - the last five folders you sent to on that device, kept in the extension's local storage.

  A device that can't be asked, such as the Ketch app while it is closed, shows the download and
  category folders it reported last, in the dialog or the popup, which the extension keeps in its
  local storage.

  Folders stay subject to the server directory policy, and a warning shows when the file is
  larger than the device's free space;
- **Download in browser**, which starts an HTTP(S) link, a captured one included, as a browser
  download with the name in the dialog (not from a Chromium private window);

Enter sends, Esc cancels. With notifications on, a notification confirms where it was sent. The
page resource picker sends a batch without it.

A captured download that finished before it could be canceled stays in the browser and opens no
dialog. Canceling the dialog, closing it or leaving its page downloads nothing; an uncertain send
can be checked in settings under pending downloads. Dialogs and receipts last only for the
browser session.

### When a send is uncertain

Each submission to a supporting Ketch instance carries a UUID. Ketch returns the same task when
that UUID is submitted again with the same options, even after it reloads its task store. Removing
the task also removes that receipt. Older instances still accept downloads, but the extension
never automatically repeats a write whose result is unknown.

If the response is lost, the extension checks the task list. When it still cannot confirm the
result:

- it keeps a pending receipt for the browser session and blocks another send of that URL to the
  same endpoint;
- an intercepted browser download is paused where possible; check Ketch before resuming it by
  hand.

In the extension settings, **Check pending downloads** looks up receipts without launching the app
or submitting downloads. Confirmed handoffs cancel the matching browser download. A missing
receipt, a removed task or an older server needs checking by hand. Restarting the browser clears
pending receipts, and changing a server's address or token does not move its receipts to the new
connection.

### Diagnostics

**Export diagnostics** saves the last 100 outcome codes and timestamps with the extension version.
It contains no URLs, file names, instance names, cookies, access tokens or raw error messages.
**Clear diagnostics** clears that history without forgetting pending submissions.

## Privacy and permissions

Nothing is sent anywhere except to the Ketch instances you add.

| Permission | Why |
| --- | --- |
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
button. The Safari build leaves out `downloads`, `nativeMessaging`, `notifications` and
`webRequest`.

Things to know:

- **Cookies**: **Send cookies and referrer** is a global switch. Remote servers additionally need
  **Allow cookies and referrer for this server**, off by default, including when upgrading
  existing settings without an explicit grant. Changing a server address clears its grant. The
  native app and existing loopback servers keep local forwarding. With forwarding allowed, Ketch
  stores the cookies with the task, in its task database on the device that downloads, so it can
  resume the task later. Turn the option off for downloads that don't need a session.
- **Tokens**: access tokens of servers are stored in the browser's extension storage, as the apps
  store them in `config.toml`. The Ketch app's token only lives in memory, for the browser
  session.
- **Plain HTTP**: over `http://` to another device, the token and cookies travel unencrypted. The
  settings page warns about such addresses; prefer `https://` behind a reverse proxy, or use a
  trusted network.
- **Magnet clicks**: the magnet link script only reacts to real clicks, so a page can't add
  downloads by scripting clicks, and it never sees the access tokens.

## Limitations

- Downloads started by submitting a form (POST) can't be repeated by Ketch; exclude such sites or
  turn capturing off in the popup.
- Cookies are read from the tab's cookie store (including Firefox containers), but cookies a
  browser keeps partitioned for third-party frames aren't sent.
- Streaming media (`blob:` videos, live or encrypted HLS and DASH) has no single file to download.
- Firefox for Android isn't supported: it has no downloads API.
- The Ketch app registers with the browsers listed under [Setting up Ketch](#setting-up-ketch). In
  Opera, and in browsers installed as a Flatpak or Snap, add Ketch as a server instead.
- Uninstalling the app leaves its registration behind: files in the browsers'
  `NativeMessagingHosts` directories, or registry values on Windows.
- Native messaging was tested with Chrome on macOS. The Windows and Linux registration and launch
  code has unit tests but hasn't run against real browsers yet.

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
   like any Ketch server. When the app restarts, the extension asks the host again.

Looking at the popup or the settings page never opens Ketch; sending a download, **Open Ketch** in
the popup and **Test connection** do; sending waits up to 45 seconds for Ketch to start.

## Safari (limited macOS target)

`npm run build` also writes `build/safari` and a Safari source zip. This target needs Safari 16.4
or later, for module background workers and session storage.

- **Supported**: configured Ketch servers, context-menu links and magnets, pasted links,
  page resources, task controls and diagnostics. Every individual send opens the download
  dialog; batch sends are chosen in the resource picker.
- **Not supported**: intercepting browser downloads, launching the desktop app through a native
  host, and extension notifications.

To use it, start sharing in Ketch or start `ketch server`, then enter its address and access token
in the extension settings. Safari must allow website access both to source pages and to the
configured server. Cookie forwarding still needs the global switch and a grant for that server.

### Package it

On macOS with Xcode installed:

```sh
npm run safari
```

```sh
open "build/safari-xcode/Ketch for Safari/Ketch for Safari.xcodeproj"
```

The script uses Apple's converter to generate a host app and extension under `build/`; it replaces
only that generated project. Choose a signing team in Xcode, run the host and enable the extension
in Safari's settings. The source zip is not an installable Safari app. App Store signing,
notarization, publishing and iOS packaging are outside this target.

To check compilation without signing:

```sh
xcodebuild -project "build/safari-xcode/Ketch for Safari/Ketch for Safari.xcodeproj" \
  -scheme "Ketch for Safari" -configuration Debug -destination 'platform=macOS' \
  -derivedDataPath build/safari-derived CODE_SIGNING_ALLOWED=NO build
```

Some converter versions warn about the background `type` key even though Safari 16.4+ supports
module workers. See the
[background compatibility data](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/manifest.json/background)
and [Apple's packaging guidance](https://developer.apple.com/documentation/safariservices/safari-web-extensions).
Compilation and API-contract tests do not replace testing site permissions and downloads in a
signed, enabled Safari extension.

## Development

The extension is plain JavaScript modules with no dependencies or bundler. `src/` is the Chromium
build, so it can be loaded as is while developing:

- **Chromium**: open `chrome://extensions`, turn on **Developer mode**, choose **Load unpacked**
  and select `src/` (or `build/chrome-unpacked`). Not `build/chrome-store`: without the `key`,
  Chrome gives it an id the Ketch app does not accept.
- **Firefox**: run `npm run build`, open `about:debugging#/runtime/this-firefox`, choose **Load
  Temporary Add-on** and select `build/firefox/manifest.json`.

```shell
npm test
```

runs the unit tests with Node's built-in test runner (Node 22.3+), and

```shell
npm run build
```

writes `build/chrome-store`, `build/chrome-unpacked`, `build/firefox`, `build/safari` and their
zips.

### Extension ids

The `key` in `src/manifest.json` pins the Chromium extension id to
`kddcjkhnjcjhekohejnehplbnjclbdbl` when `src/` is loaded unpacked, because the Ketch app only lets
the ids it lists start its host (`NativeHostRegistration.CHROMIUM_EXTENSION_IDS` in
`app/desktop`). Extension stores assign their own id, so the packaged builds leave the `key` out.
The Chrome Web Store id, `flnjeochbgpaipiofdjmoijaeooemhka`, is in that list too; add the Edge
Add-ons id there once it is published. The `chrome-unpacked` package has the store item's public key
(`STORE_KEY` in `build.mjs`) as its `key`, so a copy installed by hand gets the store's id. Firefox identifies the add-on by its gecko id,
`ketch@linroid.github.io`, instead.

### What the build does

`build.mjs` copies `src/` for each browser and:

- removes the `key` from the Chromium build, which the Chrome Web Store rejects on upload;
- rewrites the manifest for Firefox: an event page instead of a service worker, its add-on id and
  no `key`;
- rewrites it for Safari, without the permissions Safari does not get;
- checks that every file the manifest names exists, and the locales;
- zips each build.

Keep `version` in `package.json` and `src/manifest.json` the same; the build fails otherwise. The
icons are rendered from the repository's `art/icon-app.svg` by `art/render-icons.sh`.

### Source layout

| Path | Contents |
| --- | --- |
| `src/background.js` | Download capture, context menus, magnet link hand-off |
| `src/content/magnet.js` | Sends clicked magnet links to the background script |
| `src/popup/` | Toolbar popup |
| `src/options/` | Settings page |
| `src/resources/` | Page resource picker |
| `src/review/` | Download dialog shown before sending |
| `src/ui/common.css` | Styles shared by the pages |
| `src/lib/settings.js` | Settings, instances and their validation |
| `src/lib/ketch-client.js` | Client for the Ketch REST API |
| `src/lib/connection.js` | Reaches the Ketch app through native messaging |
| `src/lib/handoff.js` | Creates the task with cookies, or resolves a fetched `.torrent` file first |
| `src/lib/request.js` | Builds the download request and headers, tagged `ketch.origin: browser`; recognizes torrents |
| `src/lib/request-context.js` | Remembers request methods and source pages for capture rules |
| `src/lib/intercept.js` | Which browser downloads are captured |
| `src/lib/page-resources.js` | Finds resources on a page |
| `src/lib/review.js` | Download dialog state: open, send, cancel, hand back to the browser |
| `src/lib/submissions.js` | Submission receipts, pending sends and diagnostics |
| `src/lib/format.js` | Text for tasks, sizes and connection problems |
| `src/lib/i18n.js` | Messages in the browser's language, for scripts and pages |
| `src/lib/ext.js` | The `browser` or `chrome` API namespace |
| `src/_locales/` | Messages of each language |
| `build.mjs` | Builds each browser's package |
| `safari.mjs` | Generates the Safari Xcode project |
| `test/` | Unit tests |

### Releasing

The extension is released apart from the apps, when it has changed, so a store review only follows
changes to the extension. Run the `Extension Release` workflow (`.github/workflows/extension-release.yml`) from the Actions
tab. It takes the version of the latest app release, runs the tests and runs
`node build.mjs --version <app version> --build <run number>`, and publishes the packages in a
GitHub release tagged `extension-v<app version>.<run number>`. That release is never marked
latest, since the desktop and CLI updaters read the latest release. Then upload its
`ketch-extension-<version>-chrome-store.zip` to the Chrome Web Store (or Edge Add-ons). You can
also build the packages locally with `npm run build`.

Browsers only accept versions made of numbers, so the run number becomes a fourth part (`0.3.1.7`)
and every release is newer than the one before, as the stores require; Chromium shows the app
version (`0.3.1`) as the version name. A pre-release keeps its numeric part: `0.4.0-rc2` becomes
`0.4.0.<run number>`. The `version` in `package.json` and `src/manifest.json` only names local
builds.

## Translations

The extension shows its text in the browser's language, in the
[languages of the apps](../../docs/development/localization.md#languages). It falls back to
English for other languages and for any message a translation lacks. The terms follow the apps'
[translation glossary](../../docs/development/translation-glossary.md).

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
placeholders. Write a whole sentence per message, with placeholders for what changes, rather than
joining pieces, and keep counts out of sentences: the `i18n` format has no plural forms.

To add a language, copy a translation's folder under the language's
[locale code](https://developer.chrome.com/docs/extensions/reference/api/i18n#locales), translate
it and set `language_tag` to the language's tag, such as `pt-BR`.
