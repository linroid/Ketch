# Updates

This page explains how each Ketch build stays up to date: what updates itself, what you see when
an update arrives, and what to do when an update cannot install. The last section,
[How it works](#how-it-works), is for developers and release maintainers.

The desktop app, the Android APK from GitHub and the native `ketch` command update themselves
from the project's [GitHub releases](https://github.com/linroid/Ketch/releases). Everything else
updates the way it was installed:

| Build | How it updates |
|---|---|
| Desktop app (macOS, Windows, Linux) | [Itself](#the-desktop-app), from GitHub |
| Portable Windows app | [Itself](#the-portable-windows-app), from its own `.zip` |
| Android APK from GitHub (`direct`) | [Itself](#the-android-app), with Android's installer |
| Android from Google Play | Google Play |
| iOS | The App Store |
| `ketch` command (native binary) | [`ketch update`](#the-command) |
| Docker image | Pull the new image; see [Docker](docker.md#updating) |
| Web app | The hosted page, when it reloads |
| Browser extension | The browser's extension store, or a new release download |

## The command

`ketch update` replaces the native `ketch` binary with the latest release:

```bash
ketch update --check
sudo ketch update
```

It downloads the archive for this system to a temporary folder, checks it against the SHA-256
digest GitHub published, extracts it and puts the new binary in place:

- On macOS and Linux the new binary is renamed over the old one. A process that is running, such
  as `ketch server`, keeps the old file open and goes on working until you restart it.
- On Windows the old binary is set aside as `ketch.exe.old`, and the next run removes it.
- The license notices go to the `ketch-licenses` folder the install script made, or the
  `licenses` folder of an unpacked archive.

If the release was just published and its files are still uploading, the command says to try
again later. See the [CLI README](../cli/README.md#update) for the options.

## The desktop app

**Settings → About → Updates** shows where the update stands, with the button for the next step,
and the switch for automatic checks (`[desktop] checkForUpdates`, on by default). On macOS, the
Help menu also has **Check for Updates…**.

1. While the switch is on, the installed app checks 30 seconds after it starts and then once a
   day, and downloads the installer for this system as soon as it finds a release. An app run
   from Gradle or an IDE only checks when asked, and links to the release page.
2. Once the installer is downloaded and checked, a toast offers **Restart** and the tray menu
   **Update and restart** (**Install update…** where the app opens the installer instead). If
   the download fails, the toast offers **Update** and **What's new**, which opens its
   [release notes](#release-notes), and the next day's check tries the download again. With the
   switch off, a check made in Settings shows **Update**, which downloads it.
3. On restart, the app quits and an installer runs in a process of its own. It writes to
   `logs/update.log` in the config folder and opens the app again, updated or not.
4. An update downloaded and not installed installs the next time you open the app, before
   anything else starts. A launch that starts hidden (at login, or for the browser extension) or
   that opens files or links starts as usual and offers the update again; so does an app whose
   update opens an installer. After an install that did not take, it is not tried again.

| System | How it installs | Needs |
|---|---|---|
| macOS | Mounts the `.dmg` while downloading, copies the new `Ketch.app` beside the old one and swaps them by renaming | A folder you can write to. An app run from the disk image, from App Translocation (straight from a download) or from a folder you cannot write opens the `.dmg` instead |
| Windows | `msiexec /i … /passive`, which upgrades the installed app | Windows asks you to allow the change |
| Windows (portable) | Unpacks the `-portable.zip` and swaps the app's files | See [the portable Windows app](#the-portable-windows-app) |
| Linux | `pkexec dpkg -i` | Your password. Without `pkexec` or `dpkg`, the `.deb` opens in the system's installer |

After an update, the app shows "Updated to Ketch …" with **What's new**, listing every release
since the one that ran last, however the new version was installed. If the app finds itself still
on the old version after quitting to install, it reports that the update failed.

## The Android app

The APK published on GitHub updates itself; the Google Play build leaves updates to Google Play
and has no update screen at all.

1. Release builds check 30 seconds after the main screen opens, then daily while the app runs. The
   automatic-check switch in **Settings → About → Updates** turns this off. Debug builds only
   check when asked.
2. For a newer release, a toast offers **Update** and **What's new**.
3. **Update** downloads the release's APK and checks it: its SHA-256 digest, that it is this app,
   that it is the release it claims to be, and that its Android version code is higher. Only then
   does **Open installer** become available.
4. The first time, Android may ask you to allow installs from Ketch (**Allow from this source**);
   come back afterwards to continue. Android checks the signing certificate and asks you to
   confirm the update.

Ketch never downloads or installs an update without you choosing to. Denying the permission or
canceling the installer leaves the update ready to try again. If Android clears the downloaded
file from the cache, About offers to download it again. Rotating the screen keeps a download
going; if Android stops the app, check and download again.

Like the desktop app, an updated app shows "Updated to Ketch …" with **What's new**.

## Release notes

The desktop app and the Android APK show what a release changes in a dialog, opened from
**What's new** on the update toasts and from **Settings → About → Updates**.

- **For an update**, the dialog lists every release after the version you run (or last ran),
  newest first.
- **Changes are grouped** into New, Fixed and Improved. Changes that only matter to developers,
  such as build, test and documentation work or dependency updates, are left out; a release with
  nothing else says it only has maintenance. A change that several releases list, such as a
  release repeating its release candidates, shows only under the newest.
- **Release history** (Settings → About → Updates → Release history) lists every published
  release since 0.1.0, each with its date and number of changes, opening to its notes as
  published. The newest starts open.

The notes come from GitHub, which limits how often one address may ask. When the dialog cannot
load them, it offers **Try again** and a link to the release page.

## The portable Windows app

Each Windows release also ships `ketch-desktop-<version>-windows-<arch>-portable.zip`: the app's
`Ketch` folder, which runs from wherever you unpack it, such as a USB drive, without installing.
There is no portable build for macOS or Linux.

### What makes it portable

A folder named `data` beside `Ketch.exe`, which the `.zip` brings empty. Everything the app
writes, from `config.toml`, the downloads list and `torrent-state` to `logs\` and `updates\`,
goes to `data` instead of `%APPDATA%\ketch`. Downloaded files still go to the folder set in
**Settings → Downloads**.

Without a `data` folder the copy behaves like the installed app, and updates by installing the
`.msi`. When `data` cannot be written, the app keeps its data in `%APPDATA%\ketch`, as an
installed copy does, and logs a warning.

### Updating it

The app downloads the `-portable.zip` instead of the `.msi` and unpacks it into `data\updates\`
before offering **Restart**. Then a script:

1. waits for the app to quit, and up to a minute for other processes running from its folder,
   such as the browser extension's host, because Windows cannot rename open files. If some still
   run, the old version stays.
2. renames the old files and folders (`Ketch.exe`, `app\`, `runtime\` and the rest) into
   `data\updates\previous\`, and the new ones into their place. If a rename fails, it puts the
   old ones back; any that cannot go back are kept in `data\update-backup-<time>\`. `data` itself
   is never touched.
3. opens the app again, updated or not, and writes to `data\logs\update.log`.

The next launch deletes `data\updates\`, and the old files with it.

In a folder Ketch cannot write, such as one under `Program Files` or on a read-only drive, or
with a `data` folder it cannot write or that links to another drive, the update opens the
downloaded `.zip` in Explorer instead: replace the files by hand once Ketch quits.

### Living beside an installed copy

- **Both can run at once**, with separate data, settings and downloads lists.
- **Integrations go to the copy that registered last.** The browser extension, magnet links,
  `.torrent` files and the login item go to whichever copy registered them last: every launch
  registers the browser extension's host, and the others too when they are turned on in that
  copy's Settings (Integration, and Open Ketch at login under General).
- **What it leaves on the computer.** Those registrations live in the Windows user's registry
  (`HKEY_CURRENT_USER`), pointing at the copy's `Ketch.exe` and `data\native-messaging\`. They
  stay when the copy is moved or its drive removed, until another copy registers again; until
  then the browser extension falls back to the browser. Like other programs, it also leaves
  temporary files in `%TEMP%`, such as the SQLite library it unpacks there.
- **The `ketch` command** keeps using `%APPDATA%\ketch`, so it does not see a portable copy's
  settings, AI keys or downloads list.
- **Moving from an installed copy:** with neither copy running, copy the contents of
  `%APPDATA%\ketch` into the portable copy's `data` folder.

### First run

Windows may warn the first time `Ketch.exe` runs: the builds are not signed, and files unpacked
with Explorer carry the mark of a downloaded file, which SmartScreen checks. Choose
**More info → Run anyway**, or select **Unblock** in the `.zip`'s Properties before unpacking it.

## Limitations

- The desktop builds are not signed or notarized. After an update, macOS may ask again for
  permissions it ties to the app's signature, such as notifications, and may refuse to let the
  app replace itself (System Settings → Privacy & Security → App Management). The install then
  leaves the old app in place, and `update.log` says why.
- On Linux, only `.deb` systems install updates in place.
- Updates are whole installers; there are no delta updates.

## How it works

This section is for developers and release maintainers.

### Code layout

- The shared code is the `updater` module (`com.linroid.ketch.updater`): `GitHubReleases`,
  `Release`, `ReleaseVersion`, `ReleaseDownloader`, and `extractArchive` (`Archives.kt`), which
  unpacks the command's archives and the portable app's `.zip`.
- The command's installer is in `cli` (`SelfUpdate.kt`, `CliInstallation.kt`). It refuses inside
  the Docker image (`KETCH_DOCKER` is set) and on a JVM build.
- The desktop's is in `app/desktop` (`DesktopUpdater.kt`, `UpdateInstaller.kt`, `PortableApp.kt`)
  with the scripts in `src/main/resources/update/`, `install-update-windows-portable.ps1` among
  them.
- Android's is in the `direct` flavor of `app/android` (`AndroidUpdater.kt`, `AppUpdates.kt`,
  `UpdateInstallActivity.kt`).
- The release notes dialogs are in `app/shared` (`ReleaseNotesDialog`, `ReleaseHistoryDialog`,
  `releaseChanges`), reading `AppUpdates.releaseNotes` and `AppUpdates.releaseHistory`.

### Finding and checking a release

- `GitHubReleases` asks the GitHub REST API for the latest release
  (`/repos/linroid/Ketch/releases/latest`), which leaves out drafts and pre-releases, or for the
  release of one tag. Unauthenticated requests are limited to 60 an hour from one address.
- Versions come from the tag (`v0.0.1-rc15`) and compare as releases do: a pre-release before its
  release, and `rc9` before `rc15` (`ReleaseVersion`).
- A release can be published before the workflow uploads its files. Until this system's file is
  there, the desktop and Android apps treat the release as not out yet; the command says to try
  again later.
- `Release.asset` picks this system's file by the names the release workflow gives them:
  `ketch-cli-<version>-<os>-<arch>.tar.gz` (`.zip` on Windows),
  `ketch-desktop-<version>-<os>-<arch>.dmg`, `.msi` or `.deb`,
  `ketch-desktop-<version>-windows-<arch>-portable.zip`, and `ketch-android-<version>.apk`
  (`Release.androidAsset`). Renaming those files in `.github/workflows/release.yml` breaks
  updates of every installed copy.
- `ReleaseDownloader` downloads the file with a Ketch engine of its own and checks it against the
  SHA-256 digest GitHub computes for each uploaded asset. A file without a digest is refused. The
  release metadata is trusted through GitHub's TLS; Android also enforces APK signing when
  replacing the installed app.

### Desktop details

- Downloads and unpacked files go to `updates/` in the config folder. Before quitting to install,
  the app writes the version to `updates/pending-install`. The next launch reports a failed
  update when it still runs the old version, and empties `updates/`.
- Each launch records its version as `[ui] lastVersion`, which the "Updated to Ketch …" notice
  compares against. Copies updated from a release that did not record it only show the notice
  after an update the app installed itself.
- The portable app finds its `data` folder from the launcher's path, which jpackage passes as
  `jpackage.app-path` (`PortableApp.kt`).

### Android details

Android has two `distribution` flavors with the same application ID:

- **`direct`** is the APK on GitHub. It supplies `AndroidUpdater` and is the only flavor that
  declares `REQUEST_INSTALL_PACKAGES`.
- **`play`** relies on Google Play. It has no updater dependency, update UI, install activity,
  update file provider or install permission, even when sideloaded.

The automatic-check switch reuses `[desktop] checkForUpdates`, for compatibility with the shared
UI and config schema. The APK is saved as `cache/updates/ketch-update.apk`, outside backups, and
handed to Android's installer through a private FileProvider with a temporary read grant.

Build the GitHub APK with `:app:android:assembleDirectRelease` and the Play bundle with
`:app:android:bundlePlayRelease`. The release workflow ships the direct APK under its existing
asset name, and the Play bundle as `ketch-android-<version>-play.aab`. Keep the application ID,
the signing key and an increasing `-PversionCode`, so installed APKs can upgrade. See
[Google Play signing](app-store-listing.md#google-play) for channel changes.

The release key comes from the `ANDROID_KEYSTORE_*` variables, or from the untracked
`local.properties`, and debug builds are signed with it too. Without `-PversionCode` they take
version code 2,000,000,000, above any release's run number, so they install over a release build
and keep its data; a release then installs only after the debug build is uninstalled. Without
the key, debug builds use Android's debug key and cannot replace a release-signed install:

```properties
ketch.signing.storeFile=/absolute/path/to/ketch-release.jks
ketch.signing.storePassword=...
ketch.signing.keyAlias=release
ketch.signing.keyPassword=...
```

### Release notes details

- For an update, `releasesBetween` reads `/releases` ten at a time, up to five pages, until it
  reaches the running version, and asks for the target release on its own when the pages miss
  it.
- Each release's body is the download list followed by the changes GitHub generates, one line
  per pull request (`* <title> by @<author> in <link>`). `releaseChanges` reads only those lines
  and groups them by their Conventional Commits type: `feat` under New, `fix` under Fixed, the
  rest under Improved, and titles without a type by their first word. `build`, `chore`, `ci`,
  `docs`, `refactor`, `style`, `test` and `deps` changes are left out. The scope shows after the
  summary. `withoutRepeats` keeps a pull request only under the newest release that lists it.
- Release history (`releasesFrom`) lists releases from `FIRST_LISTED_RELEASE` (0.1.0) on. It only
  pages through `/releases`, up to twenty pages, until one reaches an older release, and never
  asks for a release by version, so development builds, whose version GitHub has no release of,
  list them too. Unlike the update dialog, it keeps the changes a newer release repeats, so no
  release drops out.

### Installer versions

jpackage wants a numeric version with a major above 0 on macOS, and Windows Installer only
upgrades to a higher MAJOR.MINOR.BUILD (at most 255.255.65535). So `app/desktop/build.gradle.kts`
gives the installers `(MAJOR + 1).MINOR.(PATCH * 1000 + N)` for `MAJOR.MINOR.PATCH-rcN`, with 999
for the final release and 0 for other builds: `0.0.1-rc15` is `1.0.1015` and `0.0.1` is
`1.0.1999`. The app itself shows the real version. Releases up to `0.0.1` all shipped as `1.0.0`,
which the next release upgrades. The scheme allows release candidates up to `rc998` and patch
versions up to 64; the build fails beyond that.

The Windows package also pins its UpgradeCode (`upgradeUuid`) to the one every earlier release
carried, which jpackage derived from the vendor and the app name. Windows Installer only upgrades
a product with the same UpgradeCode, so changing it would leave installed copies behind.

## See also

- [CLI](../cli/README.md#update): `ketch update` options
- [Docker](docker.md#updating): updating the server image
