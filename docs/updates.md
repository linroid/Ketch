# Updates

The desktop app and the native `ketch` command update themselves from the project's
[GitHub releases](https://github.com/linroid/Ketch/releases). Android, iOS, the web app and the
browser extension do not: they update through their stores, the hosted page or a new release
download.

The shared code lives in the `updater` module (`com.linroid.ketch.updater`); the command's
installer is in `cli` (`SelfUpdate.kt`, `CliInstallation.kt`, `Archives.kt`) and the desktop's in
`app/desktop` (`DesktopUpdater.kt`, `UpdateInstaller.kt`, the scripts in
`src/main/resources/update/`).

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
  `ketch-cli-<version>-<os>-<arch>.tar.gz` (`.zip` on Windows) and
  `ketch-desktop-<version>-<os>-<arch>.dmg`, `.msi` or `.deb`. Renaming those files in
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
| Linux | `pkexec dpkg -i` | Asks for the user's password. Without `pkexec` or `dpkg` the `.deb` opens in the system's installer |

## Installer versions

jpackage wants a numeric version with a major above 0 on macOS, and Windows Installer only
upgrades to a higher MAJOR.MINOR.BUILD (at most 255.255.65535). So `app/desktop/build.gradle.kts`
gives the installers `(MAJOR + 1).MINOR.(PATCH * 1000 + N)` for `MAJOR.MINOR.PATCH-rcN`, with 999
for the final release and 0 for other builds: `0.0.1-rc15` is `1.0.1015` and `0.0.1` is
`1.0.1999`. The app itself shows the real version. Releases up to `0.0.1` all shipped as `1.0.0`,
which the next release upgrades. The scheme allows release candidates up to `rc998` and patch
versions up to 64; the build fails beyond that.

## Limitations

- The builds are not signed or notarized. macOS may ask again for permissions it ties to the
  app's signature, such as notifications, after an update, and may refuse to let the app replace
  itself (System Settings → Privacy & Security → App Management); the install then leaves the old
  app in place and `update.log` says why.
- Only `.deb` systems install in place on Linux.
- Updates are whole installers; there are no delta updates.
