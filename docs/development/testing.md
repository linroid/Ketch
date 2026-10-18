# Testing

How Ketch is tested: which tools to use, what deserves a test, and how to run each suite, from the
everyday JVM run to opt-in network tests, UI snapshots and memory measurements. These are rules
for everyone who changes the code, people and coding agents alike.

## Common commands

| What | Command |
| --- | --- |
| Every module's JVM tests, as CI runs them | `./gradlew allJvmTests` |
| One module's tests (multiplatform) | `./gradlew :library:core:jvmTest` |
| One module's tests (JVM-only module) | `./gradlew :library:server:test` |
| One test class | `./gradlew :library:core:jvmTest --tests '*SegmentCalculatorTest*'` |
| Android host tests of the libraries | `./gradlew testAndroidHostTest` |
| iOS simulator tests | `./gradlew iosSimulatorArm64Test -PenableIosSimulatorTests=true` |
| JS (Node.js) tests | `./gradlew jsNodeTest` |
| Public API check | `./gradlew :library:api:checkKotlinAbi` |
| UI snapshots | `./gradlew :app:shared:jvmTest -Psnapshots --tests '*Snapshots*'` |
| Browser extension | `npm test` in `app/browser-extension` |

The sections below explain each of them.

## Test stack

- **Framework**: `kotlin.test` (`@Test`, `assertEquals`, `assertFailsWith`, `assertTrue` and so
  on).
- **Coroutines**: `kotlinx-coroutines-test`; use `runTest` for suspend functions.
- **Server**: `ktor-server-test-host`; use `testApplication` for REST API tests.
- **No external assertion libraries**: use `kotlin.test` assertions only.
- **No mocking libraries**: write hand-crafted fakes, such as `FakeHttpEngine` in
  `library/core/src/commonTest`.

### Where tests go

Write tests in `commonTest` by default, so they run on every platform. Use `jvmTest`, `iosTest` or
`androidDeviceTest` only for platform-specific behavior. The JVM-only modules (`library:server`,
`library:mcp`, `ai:discover`, `app:desktop` and `cli`) keep their tests in `src/test`, run by
their `test` task.

## What to test

Only write tests that verify **meaningful behavior**:

- **Business logic**: computed properties, state machines, algorithms, conditional branches.
- **Validation**: `require`/`check` guards, input constraints, error paths.
- **Edge cases**: zero, one, negative, empty, boundary values, overflow. For downloads that means
  0-byte and 1-byte files and segment counts that do not divide the size evenly.
- **Backward compatibility**: decoding old JSON that lacks newer fields.
- **Non-obvious behavior**: semantics that could surprise a reader, such as
  `Immediate != AfterDelay(0.seconds)` or `distinctBy` keeping the first occurrence.
- **Custom serializers**: types with hand-written serialization, such as `SpeedLimit`.
- **Integration points**: component wiring, delegation and fallback chains.

## What not to test

Do **not** test what the Kotlin language or a framework already guarantees:

- **Data classes**: `equals`, `hashCode`, `copy`, `toString`, constructor storage.
- **Enums**: `entries`, `valueOf`, declaration order, exhaustive `when`.
- **Sealed classes**: `is` checks, the subclass hierarchy.
- **Value classes**: equality, identity.
- **Trivial defaults**: that `val x: Int = 0` is indeed `0`.
- **Constructor parameter storage**: that passing a value stores that value.
- **Plain kotlinx.serialization round trips**: encoding and decoding a `@Serializable` type with
  no custom serializer.
- **Test doubles**: never test fakes, mocks or stubs themselves.

These tests cost build time and maintenance and can never fail for a real bug.

## Writing tests

- Prefer one test class per source class, in the same package under `commonTest`.
- Use `runTest` for coroutine code.
- Name tests `functionName_condition_expectedResult`.
- Keep each test focused: one assertion per logical concept.
- Use `FakeHttpEngine` and similar doubles for isolation, but don't test the doubles.
- When a formula or algorithm lives in production code, test it by calling that production code.
  Never reimplement the formula in the test and assert against your copy: both would share the
  same bug.

```kotlin
class SegmentCalculatorTest {
  @Test
  fun calculateSegments_unevenSize_lastSegmentTakesRemainder() {
    val segments = SegmentCalculator.calculateSegments(totalBytes = 10, connections = 3)
    assertEquals(3, segments.size)
    assertEquals(9L, segments.last().end)
  }
}
```

## JVM tests

```shell
./gradlew allJvmTests
```

This runs every module's JVM tests, as the CI JVM job does: `jvmTest` in the multiplatform modules
and `test` in the JVM-only ones. A plain `./gradlew jvmTest` skips the JVM-only modules.

`:cli:test` first builds the wasm web app that the CLI bundles. Pass `-PprebuiltWebDir=<dir>` to
bundle prebuilt assets instead, or a directory that does not exist to bundle none, as CI does:

```shell
./gradlew allJvmTests -PprebuiltWebDir=build/no-web-app
```

`WindowsPortableScriptTest` runs the portable Windows app's PowerShell update script, so it only
runs on Windows. CI runs it in a Windows job of its own:

```shell
./gradlew :app:desktop:test --tests '*WindowsPortableScriptTest*'
```

## Public API dump

CI fails when the public API of `library:api` differs from the checked-in dump in
`library/api/api/`. After an intended API change, update the dump and commit it with the change:

```shell
./gradlew :library:api:updateKotlinAbi
```

## Design and text guards

Two tests in `app:shared` keep the UI consistent:

- `DesignTokenUsageTest` fails when code outside `theme/` and `components/` adds literal radii,
  colors or text sizes, `MaterialTheme.` or Material icons. Keep its allowlist
  (`design-token-allowlist.txt`) empty.
- `HardcodedTextTest` fails when a file gains user-facing literal text; see
  [localization](localization.md#writing-text).

Both allowlists live in `app/shared/src/jvmTest/resources/`. When a count goes down, rewrite them
with:

```shell
./gradlew :app:shared:jvmTest -PupdateTokenAllowlist
```

## Offline HTTP integration tests

```shell
./gradlew :library:core:jvmTest :library:ktor:jvmTest :library:ftp:jvmTest
```

`HttpDownloadIntegrationTest` (in `library:ktor`) uses a loopback HTTP server, real Ktor
connections, temporary output files and SQLite task storage. It covers:

- empty and small files, and uneven segments;
- servers without range support, including resuming from zero;
- chunked responses of unknown size and invalid range responses;
- interrupted transfers, HTTP retries, pause/resume and cancellation cleanup;
- live connection changes and changed server identity;
- SQLite restart/resume, truncated local files and UTF-8 server file names.

Each case has a bounded timeout and closes its clients, server, database and temporary files.

Common tests also check response validation without sockets, plus regression cases for segment
progress snapshots, cancellation and resegmentation. Keep fault-injection tests local, so they stay
reproducible without relying on a public server to misbehave.

## Public HTTP download smoke tests

The Ktor module has opt-in end-to-end tests that use real HTTPS connections and temporary files:

```shell
./gradlew :library:ktor:jvmTest -PpublicDownloadTests=true --tests '*PublicDownloadTest'
```

They download a Git v2.46.0 README from GitHub and check it against its SHA-256 checksum, and
HTTPBingo's 256 KiB deterministic range resource through a redirect with segmented pause/resume.
They check file contents and completed segment progress. Each test has a 90-second timeout and
cleans up its temporary files.

- Public service outages, rate limits or network restrictions fail the tests; they are never
  silently treated as success.
- Ordinary test runs exclude these tests. Opted-in runs bypass the test-result cache and
  up-to-date checks, so they always reach the network.
- For offline coverage of completed segment progress, use `:library:core:jvmTest`.

To try a change by hand, run the desktop app with `./gradlew :app:desktop:run`.

## UI snapshots

```shell
./gradlew :app:shared:jvmTest -Psnapshots --tests '*Snapshots*'
```

The snapshot harness in `app/shared/src/jvmTest/.../app/snapshot/` renders the shared Compose UI
headlessly and writes PNGs to `app/shared/build/snapshots/`, named
`<scenario>-<theme>-<width>x<height>.png`, to look at while you work on the UI. Without
`-Psnapshots` the scenarios are skipped.

- `appSnapshot` and `appSnapshots` render the real `App` root over `SampleData` (downloads in
  every state, a remote NAS, the clock fixed at 2026-10-01 14:30 UTC) at desktop, medium and
  phone sizes, in light and dark.
- `snapshot` renders any composable in `KetchTheme`.
- Add scenarios in a file of your own, such as `InspectorSnapshots.kt`. Open surfaces through
  `AppScenario` (`inspect`, `select`, `openAddSheet`, `openSettings`, `showTab`) and press keys or
  hover through its `scene`.
- Tests run in English. Add `-PsnapshotLocale=de-DE` (any BCP 47 tag) to render the snapshots in
  another language and check that its text fits. Tests that assert English text then fail, so run
  only the snapshots with it.

### Virtual time

Snapshots and the render tests in `app/shared/src/jvmTest` (`withScene`) run on `SnapshotClock`:
the AWT event thread on virtual time. No test waits for the wall clock:

- each frame a scene renders lets 16 ms pass;
- a `delay` in a test lets that much time pass instead of sleeping;
- the delays, debounces and Undo windows of the app and the scene wait for that virtual time.

Environments hand the clock to `InstanceManager` (`context`) and `AppController` (`context`,
`listDispatcher`, `timeSource`, which the shell provides to composables as `LocalTimeSource`).
Every string is read once per test JVM before the first scene,
since a first read loads its file on another thread. Code that runs on another dispatcher, reads
`TimeSource.Monotonic` or uses `withTimeout` in a composable (Compose times those out on the wall
clock) still follows the wall clock.

`app:shared:jvmTest` runs its test classes in parallel JVMs. A string read for the first time lets
a test's virtual time run, so tests that read strings during an Undo window call `warmStrings()`
first.

### Show UI changes in pull requests

Pull requests that change the UI show it in their description: the affected scenarios rendered on
the base commit and on the branch, side by side in a Before | After table. Add a scenario when
none covers the change.

Keep the images out of the repository and put them in a secret gist:

1. `gh gist create` refuses binaries, so create the gist with a text file.
2. `git clone` the gist over HTTPS, commit the PNGs and push.
3. Link each image pinned to that commit:
   `https://gist.githubusercontent.com/<user>/<gist id>/raw/<commit>/<file>.png`.

GitHub has no API for the attachments its editor uploads, which is why a gist is used.

### README showcase

The README showcase (`art/showcase-light.png`, `art/showcase-dark.png`) is the
`ShowcaseSnapshots` scenario: the desktop window, two phones and a terminal over brand-free
`ShowcaseData`. Render it with `art/render-showcase.sh`, which:

1. runs `./gradlew :app:shared:jvmTest -Psnapshots --tests '*ShowcaseSnapshots*'` (about 2.5
   minutes);
2. copies `showcase-{light,dark}.png` from `app/shared/build/snapshots/` into `art/`;
3. shrinks them losslessly with `oxipng` when it is installed.

Do not palette-quantize them: that bands the background. The run also writes `-desktop`,
`-android`, `-ios` and `-2x` files to review; only the two images are committed. Regenerate them
when a release changes the UI, not on every UI commit.

### Store screenshots

The app store screenshots and the Play feature graphic are the `StoreScreenshots` scenario: the
real app on a framed iPhone, iPad, Android phone and Android tablet under English captions, at the
exact size each store takes and without an alpha channel. `art/render-store-assets.sh` renders
them (about 8 minutes) and copies them into the fastlane folders of `app/android` and `app/ios`.
The scenario makes the app name the device as a phone or tablet would
(`localDeviceKindOverride`) and write its shortcuts with that platform's keys
(`KeyboardPlatform.override`). See [app store listings](../app-store-listing.md).

## Android distributions

```shell
./gradlew :app:android:testDirectDebugUnitTest :app:android:testPlayDebugUnitTest \
  :app:android:assembleDirectDebug :app:android:assemblePlayDebug
```

The `direct` flavor's tests cover update selection, progress, retries, automatic checks, and APK
package and version validation. The shared checksum and release-feed tests run with
`:updater:test`. When changing distribution wiring, check the merged manifests: only `direct` may
contain `REQUEST_INSTALL_PACKAGES`, `UpdateInstallActivity` and `UpdateFileProvider`.

For an installation smoke test, use two `direct` release APKs signed with the same key, the update
with a higher `versionCode`. Exercise:

- denying and granting the install permission;
- rotating the device on the permission screen;
- canceling and retrying the installer;
- a successful update that keeps settings and tasks.

Debug signing cannot replace a release-signed installation. See [updates](../updates.md).

## Desktop startup memory

The desktop launcher uses `-Xms32m -Xmx512m`. The limit covers the Java heap, not native Skia or
Metal allocations, thread stacks, class metadata or compiled code. Skia's GPU resource cache is
capped separately, with `-Dskiko.gpu.resourceCacheLimit=64M` (Skia's default is 256 MB).

### How to measure

- Measure the app process itself, not Gradle or Kotlin daemons, and use a packaged release for
  release measurements.
- Measure at launch and after a fixed idle interval, with the same window size and data.
- Measure after using the window too: resize it and open the add sheet, the palette, Discover and
  Settings. An idle window never fills Skia's caches, so it hides what use costs.
- Include a configured Discover provider, saved history and a `--background` launch. Clients
  should initialize only on a search or connection check, history only when first needed, and a
  background launch with a tray should create its window only when shown. Closing an unused app
  must leave its saved history intact.
- Closing the window to the tray disposes it, so the process then owns no window. Reopen it and
  check that the page, filter, search and scroll positions come back.
- On macOS, measure the physical footprint (`footprint -p <pid>`, Activity Monitor's Memory), not
  RSS, which leaves out GPU memory.
- For heap diagnostics use `jcmd <pid> GC.heap_info`. A separate diagnostic launch with
  `-XX:NativeMemoryTracking=summary` enables `jcmd <pid> VM.native_memory summary`; it tracks JVM
  memory, not every native allocation.
- Do not force GC when comparing ordinary idle footprints.
- Exercise concurrent HTTP and torrent downloads as well as Discover before lowering the heap cap.

### What to expect

Most of a shown window's cost is graphics: about 170 MB of GPU memory and five to seven frame
buffers (16 MB each for a 1280 × 800 window on a Retina display). macOS keeps the frame buffers
after the window closes and reuses them when it opens again, so a closed window still costs about
80 to 110 MB more than a `--background` launch that never showed one. The JDK's
`LWWindowPeer.lastCommonMouseEventPeer` also keeps a closed window's UI objects until another
window gets a mouse event; it holds no native resources.

Measured on Apple Silicon (1280 × 800, empty list, idle):

| State | Physical footprint |
| --- | --- |
| Never shown (`--background`) | about 175 MB |
| Shown | 530 to 590 MB |
| Closed to the tray (window disposed) | 365 to 440 MB |
| Only hidden (before windows were disposed) | 405 to 460 MB |

Disposing the window brought GPU memory down to about 25 MB instead of 60 MB, and the window's
other graphics memory to about 1 MB instead of 18 MB.

Using the window used to grow it past 1 GB. Compose's `Modifier.dropShadow` blurs each shadow into
a bitmap the size of its shape on the CPU and builds a new one whenever the size changes, so a
resize, or a sheet or the inspector animating open, left a bitmap of several megabytes and its GPU
texture for each size until a collection freed them, and Skia kept up to 256 MB of textures
cached. Surfaces now draw their shadows with `elevationShadow`, which Skia blurs on the GPU from a
small cached profile whatever the size, and the launcher caps the cache. The same use (a resize
sweep, then the add sheet, palette, shortcuts, Discover and Settings, then a minute idle) measured:

| Build | After use | Peak |
| --- | --- | --- |
| Bitmap shadows, 256 MB cache | 1130 MB | 1360 MB |
| GPU shadows, 64 MB cache | 690 to 720 MB | 810 to 840 MB |

While a window draws, Skiko calls `System.gc()` every 30 seconds so that Skia objects are freed,
so GC logs show `Pause Full (System.gc())` then. The serial collector used about 20 MB less when
idle but paused far longer while downloading at full speed (95th percentile 32 ms against
3.5 ms), so the launcher keeps G1.

### No class data sharing archive

App images carry no class data sharing (AppCDS) archive. One dumped at build time never applied to
an installed app:

- JDK 21 checks each jar the archive names at its absolute path on the build machine
  (`SharedClassPathEntry::validate` in `filemap.cpp`), and rejects the whole archive when that
  path is missing, before its "moved together" prefix match.
- JDK 25 checks the jars where they now are, but still compares their modification times, and
  jpackage resets those when it copies the image into the DMG (checked; the MSI and deb are built
  from a copy too).

A copied image uses an archive only while the build folder it was dumped in still exists, so test
with that folder renamed away. To see whether a launch maps classes from an archive, set
`JAVA_TOOL_OPTIONS=-Xlog:class+load,cds:file=/tmp/ketch-cds.log` and count the classes from
`shared objects file`. The launcher keeps the JVM's own logging off stdout, which the browser
extension's native messaging host answers on, and at warnings on stderr, so other `-Xlog` outputs
show nothing.

## Other suites

- **iOS simulator**: the tests of `library:core`, `library:ftp`, `library:hls`, `library:dash`,
  `library:metalink` and `library:torrent` are skipped unless `-PenableIosSimulatorTests=true`
  is passed to `iosSimulatorArm64Test`.
- **JS**: tests run on Node.js with `jsNodeTest`.
- **Android host**: CI runs `testAndroidHostTest` of `library:core`, `library:torrent`,
  `library:hls`, `library:dash` and `library:metalink` with the app's unit tests.
- **Published metadata**: `./gradlew compilePublishedMetadata` compiles the common metadata of
  the published libraries, as publishing does. CI runs it in a job of its own, since it fetches
  Kotlin/Native.
- **Browser extension**: its unit tests run with `npm test` (`node --test`, Node 22.3+) in
  `app/browser-extension`.
- **Torrent**: interoperability with Transmission and libtorrent, the opt-in public swarm test
  (`-PpublicTorrentTests=true`), benchmarks and memory measurements are described in
  [torrent verification](torrent-verification.md).

## CI and the build cache

Test CI sets `testBuildRevision=ci-test` in every job (`ORG_GRADLE_PROJECT_testBuildRevision`).
This keeps the generated API revision stable between commits, so unchanged modules reuse their
compilations and test results from the Gradle build cache. The override is only for test builds:
omit it when packaging or publishing, so `KetchApi.REVISION` names the real Git revision.

- Simulator test results are cacheable too (the root build script turns it on), so only modules
  whose test binary changed run on the simulator again.
- The iOS job preserves `~/.konan`, saved once per dependency set rather than per commit.
- With a `GRADLE_ENCRYPTION_KEY` secret, every job keeps its configuration cache between runs.
  Build scripts must therefore not read values that change on every run, such as
  `GITHUB_RUN_NUMBER`, while configuring: the release workflow passes the Android `versionCode` as
  `-PversionCode` instead.

## See also

- [Code style](code-style.md)
- [Localization](localization.md)
- [Torrent verification](torrent-verification.md)
