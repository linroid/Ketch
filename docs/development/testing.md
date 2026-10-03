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
servers without range support (including resuming from zero), invalid range responses,
interrupted transfers, HTTP retries, pause/resume, cancellation cleanup, live connection
changes, changed server identity, SQLite restart/resume, truncated local files, and UTF-8
server filenames. Each case has a bounded timeout and closes its clients, server, database, and
temporary files.

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

The README showcase (`art/showcase-light.png`, `art/showcase-dark.png`) is the
`ShowcaseSnapshots` scenario: the desktop window, two phones and a terminal over brand-free
`ShowcaseData`. `art/render-showcase.sh` renders it
(`./gradlew :app:shared:jvmTest -Psnapshots --tests '*ShowcaseSnapshots*'`, about 2.5 minutes),
copies `showcase-{light,dark}.png` from `app/shared/build/snapshots/` into `art/` and shrinks
them losslessly with `oxipng` when it is installed. Do not palette-quantize them: that bands the
background. The run also writes `-desktop`, `-android`, `-ios` and `-2x` files to review; only
the two images are committed. Regenerate them when a release changes the UI, not on every UI
commit.

## Other Suites

- iOS simulator tests of `library:core`, `library:ftp` and `library:torrent` are skipped unless
  `-PenableIosSimulatorTests=true` is passed to `iosSimulatorArm64Test`; JS tests run on Node.js
  with `jsNodeTest`
- The browser extension has its own unit tests: `npm test` (`node --test`, Node 22.3+) in
  `app/browser-extension`
- Torrent interoperability (Transmission, libtorrent), the opt-in public swarm test
  (`-PpublicTorrentTests=true`), benchmarks and memory measurements are described in
  [torrent verification](torrent-verification.md)
