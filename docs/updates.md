# Updates

The desktop app and the native `ketch` command update themselves from the project's
[GitHub releases](https://github.com/linroid/Ketch/releases). Android, iOS, the web app and the
browser extension do not: they update through their stores, the hosted page or a new release
download.

The shared code lives in the `updater` module (`com.linroid.ketch.updater`), including
`extractArchive` (`Archives.kt`), which unpacks the command's archives and the portable Windows
app's `.zip`. The command's installer is in `cli` (`SelfUpdate.kt`, `CliInstallation.kt`) and the
desktop's in `app/desktop` (`DesktopUpdater.kt`, `UpdateInstaller.kt`, `PortableApp.kt`, and the
scripts in `src/main/resources/update/`, `install-update-windows-portable.ps1` among them).

## Finding and checking a release

- `GitHubReleases` asks the GitHub REST API for the latest release
  (`/repos/linroid/Ketch/releases/latest`), which leaves out drafts and pre-releases, or for the
  release of one tag. Unauthenticated requests are limited to 60 an hour from one address.
- Versions come from the tag (`v0.0.1-rc15`) and compare as releases do: a pre-release before
  its release, and `rc9` before `rc15` (`ReleaseVersion`).
- A release can be published before the workflow uploads its files. Until this system's file is
  there, the desktop app treats the release as not out yet and the command says to try again
  later.
- `Release.asset` picks the file of this system by the names the release workflow gives them:
  `ketch-cli-<version>-<os>-<arch>.tar.gz` (`.zip` on Windows),
  `ketch-desktop-<version>-<os>-<arch>.dmg`, `.msi` or `.deb`, and
  `ketch-desktop-<version>-windows-<arch>-portable.zip`, which
  [portable copies](#the-portable-windows-app) update from. Renaming those files in
  `.github/workflows/release.yml` breaks updates of every installed copy.
- `ReleaseDownloader` downloads the file with a Ketch engine of its own and checks it against the
  SHA-256 digest GitHub computes for each uploaded asset. A file without a digest is refused.
  Nothing is signed beyond that: the trust comes from GitHub's TLS, as for the install script.

## The command

`ketch update` checks, downloads and verifies the archive in a temporary folder, extracts it, and
renames the new binary over the old one, which the running process keeps open (on Windows the old
one is set aside as `ketch.exe.old` and removed by the next run). The license notices go to the
`ketch-licenses` folder the install script made, or the `licenses` folder of an unpacked archive.
See the [CLI README](../cli/README.md#update) for the options.

## The desktop app

- Settings → About → Updates shows where the update stands and the button for the next step, and
  the switch `[desktop] checkForUpdates` (on by default). The macOS Help menu has
  "Check for Updates…".
- While the switch is on, the packaged app checks 30 seconds after it starts and once a day. A
  check that finds a release shows a toast with Update and What's new; once the download is
  checked, another toast offers Restart. An app run from Gradle or an IDE only checks when asked
  and links to the release page.
- Downloads and unpacked files go to `updates/` in the config folder. Before quitting to install,
  the app writes the version to `updates/pending-install`; the next launch reports
  "Updated to Ketch …" or, when it still runs the old version, that the update failed, and
  empties `updates/`.
- The installer runs in a process of its own once the app has quit, writes to `logs/update.log`,
  and opens the app again, updated or not:

| System | Installs with | Notes |
|---|---|---|
| macOS | `hdiutil` mounts the `.dmg` while downloading; the script copies the new `Ketch.app` beside the old one and swaps them by renaming | Needs a writable folder. An app run from the disk image, from App Translocation or from a folder the user cannot write opens the `.dmg` instead |
| Windows | `msiexec /i … /passive`, which upgrades the installed app | Windows asks to allow the change |
| Windows (portable) | The `-portable.zip` is unpacked while downloading; the script renames the old files aside and the new ones into the app's folder | Needs a writable folder, otherwise the `.zip` opens in Explorer. See [the portable Windows app](#the-portable-windows-app) |
| Linux | `pkexec dpkg -i` | Asks for the user's password. Without `pkexec` or `dpkg` the `.deb` opens in the system's installer |

## The portable Windows app

Each Windows release also ships `ketch-desktop-<version>-windows-<arch>-portable.zip`: the app's
`Ketch` folder, which runs from wherever it is unpacked, such as a USB drive, without installing.
There is no portable build for macOS or Linux.

- **What makes it portable**: a folder named `data` beside `Ketch.exe`, which the `.zip` brings
  empty (`PortableApp.kt`; jpackage gives the app the launcher's path as `jpackage.app-path`).
  Without it the copy behaves like the installed app, and updates by installing the `.msi`.
- **Where its data goes**: everything the app writes, from `config.toml`, the downloads list and
  `torrent-state` to `logs\` and `updates\`, goes to `data` instead of `%APPDATA%\ketch`. The
  downloaded files still go to the folder set in Settings → Downloads. When `data` cannot be
  written, the app keeps its data in `%APPDATA%\ketch`, as an installed copy does, and logs a
  warning.
- **Updates**: the app downloads the `-portable.zip` instead of the `.msi` and unpacks it into
  `data\updates\` before offering Restart. `install-update-windows-portable.ps1` waits for the
  app to quit and up to a minute for other processes running from its folder, such as the browser
  extension's host (`Ketch.exe --native-messaging-host`), because Windows cannot rename open
  files; if some still run, the old version stays. It then renames the old files and folders
  (`Ketch.exe`, `app\`, `runtime\` and the rest) into `data\updates\previous\` and the new ones
  into their place, all renames on one drive, and puts the old ones back if a rename fails; any
  that cannot go back are kept in `data\update-backup-<time>\`.
  `data` itself is never touched. The app opens again, updated or not, and the script writes to
  `data\logs\update.log`. The next launch deletes `data\updates\`, the old files with it.
- **A folder Ketch cannot write**, such as one under `Program Files` or on a read-only drive, or
  a `data` folder it cannot write or that links to another drive: the update opens the downloaded
  `.zip` in Explorer, to replace the files by hand once Ketch quits.
- **Next to an installed copy**: both can run at once, with separate data, settings and downloads
  lists. The browser extension, magnet links, `.torrent` files and the login item go to whichever
  copy registered them last: every launch registers the browser extension's host, and the
  others too when they are turned on in that copy's Settings (Integration, and Open Ketch at
  login under General).
- **What it leaves on the computer**: the registrations above live in the Windows user's
  registry (`HKEY_CURRENT_USER`), pointing at the copy's `Ketch.exe` and `data\native-messaging\`.
  They stay when the copy is moved or its drive removed, until another copy registers again;
  until then the browser extension falls back to the browser. Like other programs, it also leaves
  temporary files in `%TEMP%`, such as the SQLite library it unpacks there.
- **The `ketch` command** keeps using `%APPDATA%\ketch`, so it does not see a portable copy's
  settings, AI keys or downloads list.
- **Moving from an installed copy**: with neither copy running, copy the contents of
  `%APPDATA%\ketch` into the portable copy's `data` folder.
- **Windows may warn the first time** `Ketch.exe` runs: the builds are not signed, and files
  unpacked with Explorer carry the mark of a downloaded file, which SmartScreen checks. Choose
  More info → Run anyway, or select Unblock in the `.zip`'s Properties before unpacking it.

## Installer versions

jpackage wants a numeric version with a major above 0 on macOS, and Windows Installer only
upgrades to a higher MAJOR.MINOR.BUILD (at most 255.255.65535). So `app/desktop/build.gradle.kts`
gives the installers `(MAJOR + 1).MINOR.(PATCH * 1000 + N)` for `MAJOR.MINOR.PATCH-rcN`, with 999
for the final release and 0 for other builds: `0.0.1-rc15` is `1.0.1015` and `0.0.1` is
`1.0.1999`. The app itself shows the real version. Releases up to `0.0.1` all shipped as `1.0.0`,
which the next release upgrades. The scheme allows release candidates up to `rc998` and patch
versions up to 64; the build fails beyond that.

The Windows package also pins its UpgradeCode (`upgradeUuid`) to the one every earlier release
carried, which jpackage derived from the vendor and the app name: Windows Installer only upgrades a
product with the same UpgradeCode, so changing it would leave installed copies behind.

## Limitations

- The builds are not signed or notarized. macOS may ask again for permissions it ties to the
  app's signature, such as notifications, after an update, and may refuse to let the app replace
  itself (System Settings → Privacy & Security → App Management); the install then leaves the old
  app in place and `update.log` says why.
- Only `.deb` systems install in place on Linux.
- Updates are whole installers; there are no delta updates.
