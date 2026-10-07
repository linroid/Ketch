# Unit Testing Rules

## Test Stack

- **Framework**: `kotlin.test` (`@Test`, `assertEquals`, `assertFailsWith`, `assertTrue`, etc.)
- **Coroutines**: `kotlinx-coroutines-test` (`runTest` for suspend functions)
- **Server**: `ktor-server-test-host` (`testApplication` for REST API tests)
- **Source sets**: write tests in `commonTest` by default; use `jvmTest`, `iosTest` or
  `androidDeviceTest` only for platform-specific behavior. JVM-only modules (`library:server`,
  `library:mcp`, `ai:discover`, `app:desktop`, `cli`) use `src/test`, run by their `test` task
- **No external assertion libraries** — use `kotlin.test` assertions only
- **No mocking libraries** — write hand-crafted fakes (e.g., `FakeHttpEngine`)

## What to Test

Only write tests that verify **meaningful behavior**:

- **Business logic** — computed properties, state machines, algorithms, conditional branching
- **Validation** — `require`/`check` guards, input constraints, error paths
- **Edge cases** — zero, one, negative, empty, boundary values, overflow
- **Backward compatibility** — deserializing old JSON formats without new fields
- **Non-obvious behavior** — semantics that could surprise a reader (e.g.,
  `Immediate != AfterDelay(0.seconds)`, `distinctBy` keeping first occurrence)
- **Custom serializers** — types with hand-written serialization logic (e.g., `SpeedLimit`)
- **Integration points** — verifying component wiring, delegation, fallback chains

## What NOT to Test

Do **not** write tests for things the Kotlin language or frameworks already guarantee:

- **Data class guarantees** — `equals`, `hashCode`, `copy`, `toString`, constructor storage
- **Enum guarantees** — `entries`, `valueOf`, declaration order, exhaustive `when`
- **Sealed class guarantees** — `is` type checks, subclass hierarchy
- **Value class guarantees** — equality, identity
- **Trivial default values** — testing that `val x: Int = 0` is indeed `0`
- **Constructor parameter storage** — testing that passing a value stores that value
- **Basic kotlinx.serialization round-trips** — simple encode/decode for `@Serializable` types
  with no custom serializer (the framework guarantees this)
- **Test doubles** — do not test fakes, mocks, or stubs themselves

## Guidelines

- Prefer one test class per source class, in the same package under `commonTest`
- Use `runTest` for coroutine tests
- Name tests descriptively: `functionName_condition_expectedResult`
- Keep tests focused — one assertion per logical concept
- Use `FakeHttpEngine` and similar test doubles for isolation, but don't test the doubles
- When a formula or algorithm lives in production code, test it by calling that production
  code — never reimplement the formula locally and assert against the reimplementation

## JVM Tests

```shell
./gradlew allJvmTests
```

Runs every module's JVM tests, as the CI JVM job does: `jvmTest` in multiplatform modules and
`test` in the JVM-only ones, which `./gradlew jvmTest` does not select. `:cli:test` first builds
the wasm web app that the CLI bundles; pass `-PprebuiltWebDir=<dir>` to bundle prebuilt assets
instead, or a directory that does not exist to bundle none, as CI does.

`WindowsPortableScriptTest` runs the portable Windows app's PowerShell update script, so it only
runs on Windows; CI runs it in a Windows job of its own:

```shell
./gradlew :app:desktop:test --tests '*WindowsPortableScriptTest*'
```

## Android distributions

```shell
./gradlew :app:android:testDirectDebugUnitTest :app:android:testPlayDebugUnitTest \
  :app:android:assembleDirectDebug :app:android:assemblePlayDebug
```

The direct flavor's tests cover update selection, progress, retries, automatic checks, and APK
package/version validation. Shared checksum and release-feed tests run with `:updater:test`.
Check the merged manifests when changing distribution wiring: only `direct` may contain
`REQUEST_INSTALL_PACKAGES`, `UpdateInstallActivity` and `UpdateFileProvider`.

For an installation smoke test, use two direct release APKs signed with the same key and a higher
`versionCode` in the update. Exercise permission denial and grant, rotation on the permission
screen, canceling and retrying the installer, and a successful update retaining settings/tasks.
Debug signing cannot replace a release-signed installation. See [updates](../updates.md).

## Desktop Startup Memory

The desktop launcher uses `-Xms32m -Xmx512m`. The limit covers the Java heap, not native
Skia/Metal allocations, thread stacks, class metadata or compiled code. Compare the app process
itself, excluding Gradle and Kotlin daemons, and use a packaged release for release measurements.

Measure at launch and after a fixed idle interval with the same window size and data. Include a
configured Discover provider, saved history, and `--background`: clients should initialize only
on a search or connection check, history only when first needed, and a background launch with a
tray should create its window only when shown. Closing an unused app must leave its saved history
intact. After first use, hide and reopen the window and verify its UI state is retained.

For heap diagnostics use `jcmd <pid> GC.heap_info`. A separate diagnostic launch with
`-XX:NativeMemoryTracking=summary` enables `jcmd <pid> VM.native_memory summary`; this tracks JVM
memory, not every native allocation. Do not force GC when comparing ordinary idle footprints.
Exercise concurrent HTTP and torrent downloads as well as Discover before lowering the heap cap.

Packaged app images carry a class data sharing archive, `ketch.jsa` beside the jars (`CdsArchive` in
`app/desktop/build.gradle.kts`), dumped by the image's own launcher from
`app/desktop/cds/app.classlist`, with ProGuard's names for release images. The JVM maps the classes
from it instead of loading them, and its read-only part stays clean, shared memory. On Apple Silicon
it took the release app from 167 to 117 MB in the background and from 575-583 to 512-513 MB with the
window shown, and opened the window in 1.0 to 1.3 s instead of 1.5; it adds about 70 MB to the
installed app and 15 MB to the DMG. To check that a launch uses it, add `-Xlog:class+load` (classes
from `shared objects file`) and `-Xlog:cds` to `JAVA_TOOL_OPTIONS`: the archive should map at the
address it was dumped for, not an alternative one, which writes to all of it. When what the first
window loads changes much, such as after a Compose update, record the list again with
`./gradlew :app:desktop:recordCdsClassList`, which opens the app with an empty profile until it is
quit; classes the list misses still load, only from the jars.

## Public HTTP Download Smoke Tests

Run the desktop app with `./gradlew :app:desktop:run`.

The Ktor module has opt-in end-to-end tests using real HTTPS connections and temporary files:

```shell
./gradlew :library:ktor:jvmTest -PpublicDownloadTests=true --tests '*PublicDownloadTest'
```

These verify a Git v2.46.0 README from GitHub against its SHA-256 checksum, plus HTTPBingo's
256 KiB deterministic range resource through a redirect and segmented pause/resume. They check
file contents and completed segment progress. Each test has a 90-second timeout and cleans up
its temporary files. Public service outages, rate limits, or network restrictions fail the tests;
they are not silently treated as success.

Ordinary test runs exclude these tests. Opted-in runs bypass test-result caching and up-to-date
checks so they always exercise the network. Use `:library:core:jvmTest` for the offline regression
coverage of completed segment progress.

## Offline HTTP Integration Tests

```shell
./gradlew :library:core:jvmTest :library:ktor:jvmTest :library:ftp:jvmTest
```

`HttpDownloadIntegrationTest` uses a loopback HTTP server, real Ktor connections, temporary
output files, and SQLite task storage. It covers empty and small files, uneven segments,
servers without range support (including resuming from zero), chunked responses of unknown
size, invalid range responses, interrupted transfers, HTTP retries, pause/resume, cancellation
cleanup, live connection changes, changed server identity, SQLite restart/resume, truncated
local files, and UTF-8 server filenames. Each case has a bounded timeout and closes its clients,
server, database, and temporary files.

Common tests also check response validation without sockets and regression cases for segment
progress snapshots, cancellation, and resegmentation. Keep fault-injection tests local so they
remain reproducible without relying on a public server to misbehave.

## UI Snapshots

```shell
./gradlew :app:shared:jvmTest -Psnapshots --tests '*Snapshots*'
```

The snapshot harness in `app/shared/src/jvmTest/.../app/snapshot/` renders the shared Compose UI
headlessly and writes PNGs to `app/shared/build/snapshots/`, named
`<scenario>-<theme>-<width>x<height>.png`, to look at while working on the UI. Scenarios are
skipped without `-Psnapshots`. `appSnapshot` and `appSnapshots` render the real `App` root over
`SampleData` (downloads in every state, a remote NAS, the clock fixed at 2026-10-01 14:30 UTC) at
desktop, medium and phone sizes, in light and dark; `snapshot` renders any composable in
`KetchTheme`. Add scenarios in a file of your own, such as `InspectorSnapshots.kt`, opening
surfaces through `AppScenario` (`inspect`, `select`, `openAddSheet`, `openSettings`, `showTab`) and
pressing keys or hovering through its `scene`. Tests run in English; add
`-PsnapshotLocale=de-DE` (any BCP 47 tag) to render the snapshots in another language and check
that its text fits.

Snapshots and the render tests in `app/shared/src/jvmTest` (`withScene`) run on `SnapshotClock`:
the AWT event thread on virtual time. Each frame a scene renders lets 16 ms pass, a `delay` in a
test lets that much time pass instead of sleeping, and the delays, debounces and Undo windows of
the app and the scene wait for it, so no test waits for the wall clock. Environments hand the
clock to `InstanceManager` (`context`) and `AppController` (`context`, `listDispatcher`,
`timeSource`), and every string is read once per test JVM before the first scene, since a
first read loads its file on another thread. Code that runs on another dispatcher, reads
`TimeSource.Monotonic` or uses `withTimeout` in a composable (Compose times those out on the wall
clock) still follows the wall clock.

The README showcase (`art/showcase-light.png`, `art/showcase-dark.png`) is the
`ShowcaseSnapshots` scenario: the desktop window, two phones and a terminal over brand-free
`ShowcaseData`. `art/render-showcase.sh` renders it
(`./gradlew :app:shared:jvmTest -Psnapshots --tests '*ShowcaseSnapshots*'`, about 2.5 minutes),
copies `showcase-{light,dark}.png` from `app/shared/build/snapshots/` into `art/` and shrinks
them losslessly with `oxipng` when it is installed. Do not palette-quantize them: that bands the
background. The run also writes `-desktop`, `-android`, `-ios` and `-2x` files to review; only
the two images are committed. Regenerate them when a release changes the UI, not on every UI
commit.

The app store screenshots and the Play feature graphic are the `StoreScreenshots` scenario: the
real app on a framed iPhone, iPad, Android phone and Android tablet under English captions, at the
exact size each store takes and without an alpha channel. `art/render-store-assets.sh` renders
them (about 8 minutes) and copies them into the fastlane folders of `app/android` and `app/ios`;
the scenario makes the app name the device as a phone or tablet would (`localDeviceKindOverride`)
and write its shortcuts with that platform's keys (`KeyboardPlatform.override`). See
[app store listings](../app-store-listing.md).

## Other Suites

- iOS simulator tests of `library:core`, `library:ftp`, `library:hls`, `library:dash` and
  `library:torrent` are skipped unless
  `-PenableIosSimulatorTests=true` is passed to `iosSimulatorArm64Test`; JS tests run on Node.js
  with `jsNodeTest`
- Test CI sets `testBuildRevision=ci-test` in every job (`ORG_GRADLE_PROJECT_testBuildRevision`).
  This keeps the generated API revision stable between commits, so unchanged modules reuse
  their compilations and test results from the Gradle build cache. The override is only for
  test builds: omit it when packaging or publishing so `KetchApi.REVISION` identifies the real
  Git revision. The iOS job also preserves `~/.konan` across compatible Kotlin and Xcode
  toolchains, and with a `GRADLE_ENCRYPTION_KEY` secret every job keeps its configuration
  cache between runs. Build scripts must not read values that change on every run, such as
  `GITHUB_RUN_NUMBER`, while configuring: the release workflow passes the Android
  `versionCode` as `-PversionCode` instead.
- `./gradlew compilePublishedMetadata` compiles the common metadata of the published libraries,
  as publishing does; CI runs it in a job of its own, since it fetches Kotlin/Native
- `app:shared:jvmTest` runs its test classes in parallel JVMs. A string read for the first time
  lets a test's virtual time run, so tests that read strings during an Undo window call
  `warmStrings()` first
- The browser extension has its own unit tests: `npm test` (`node --test`, Node 22.3+) in
  `app/browser-extension`
- Torrent interoperability (Transmission, libtorrent), the opt-in public swarm test
  (`-PpublicTorrentTests=true`), benchmarks and memory measurements are described in
  [torrent verification](torrent-verification.md)
