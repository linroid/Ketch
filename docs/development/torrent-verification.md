# Torrent verification

The v1 implementation landed as the [14-layer stack](../plans/pure-kotlin-torrent-progress.md).
The merged v2 foundation and public download/restart workflow are tracked in
[v2 progress](../plans/pure-kotlin-torrent-v2-progress.md). Historical measurements below are
revision-specific; they do not establish production v2 release qualification.
Fixtures use deterministic local payloads and isolated temporary directories. Interoperability clients
are test-only dependencies; normal builds neither start an external downloader nor load libtorrent.

## Reproduction

```sh
./gradlew jvmTest testDebugUnitTest iosSimulatorArm64Test jsNodeTest \
  :library:torrent:testAndroidHostTest :library:core:testAndroidHostTest \
  -PenableIosSimulatorTests=true
./gradlew :library:server:test :library:mcp:test
ANDROID_SERIAL=emulator-5554 ./gradlew :library:torrent:connectedAndroidDeviceTest
TRANSMISSION_DAEMON=/path/to/transmission-daemon ./gradlew :library:torrent:jvmTest
KETCH_TORRENT_BENCHMARK=1 ./gradlew :library:torrent:jvmTest --tests '*TorrentBenchmarkTest*'
```

`test-fixtures/torrent/clients.properties` pins the independent clients: Transmission 4.1.3, built
from its SHA-256-verified source archive by `tools/torrent/build_transmission.py`, and libtorrent4j
2.1.0-39. A Transmission binary of any other version fails the test. `-PtorrentConformance=true`
makes the Transmission scenario required and never restores torrent results from the test cache;
the [fixture README](../../test-fixtures/torrent/README.md) covers the build and the evidence
verifier. Set `KETCH_NATIVE_CLI` and `KETCH_JVM_CLI` alongside `TRANSMISSION_DAEMON` to exercise
the built executables against the same independent seeder. The Gradle test cache tracks these
opt-in environment values.

`PublicSwarmTest` downloads the current Debian netinst image (about 750 MiB) from the public
swarm through a magnet with Debian's HTTP tracker and a UDP tracker, pauses once payload arrives,
resumes, and checks the result against Debian's published `SHA256SUMS`. It is excluded unless
`-PpublicTorrentTests=true` is set:

```sh
./gradlew :library:torrent:jvmTest -PpublicTorrentTests=true --tests '*PublicSwarmTest'
```

Network restrictions on trackers, DHT bootstrap or peers fail the test rather than skipping it.

CI runs JVM/Android host, iOS simulator and JS regression suites; extra macOS/Windows jobs exercise
the OS filesystem adapters. The JVM job builds the pinned Transmission, runs with
`-PtorrentConformance=true` and `:library:torrent:verifyNoNativeTorrentRuntime`, then requires
every scenario in `test-fixtures/torrent/scenarios.json` to have executed. The Android emulator
job requires a nonzero executed test count, since an AGP connected-test task can otherwise succeed
without running instrumentation.

## Public v2/hybrid workflow

### Recorded evidence for merged PR #240 (2026-09-20)

[PR #240](https://github.com/linroid/Ketch/pull/240) landed at
`e822e8bd0383e008486ca5a3384181eaaba9a1a7`, following consolidated #231–#238.
Its recorded local suites passed with zero failures:

| Suite | JVM | Executed iOS simulator | Android host |
| --- | ---: | ---: | ---: |
| Torrent | 635 | 613 | 612 |
| Core | 203 | 200 | 200 |

The no-native-runtime guard also passed. The
[final-head update](https://github.com/linroid/Ketch/issues/162#issuecomment-5748957160)
records green required CI at `de5623c5`, including JVM, iOS, Android host/device, JavaScript,
macOS/Windows filesystem coverage and the aggregate gate. These are results recorded for
that reviewed head, not newly executed tests or evidence for uncommitted follow-up changes.

### Coverage and open gates

`TorrentPublicV2WorkflowTest` drives the public Ketch/source APIs against a local wire fixture.
It covers metainfo and tracker-only magnets, external piece-layer authentication, hybrid IDs across
padding, selective output, live limits, pause, source recreation from serialized task records,
rechecking modified data, ownership-journal recovery before a source checkpoint, safe removal,
corrupt proofs, and source shutdown during metadata resolution. The tests execute on JVM, Android
host and iOS.

`PublicV2IndependentSeederTest` resolves pure v2 and hybrid magnets, fetches their external hash
layers, and downloads exact payload through Ketch from the pinned test-only libtorrent peer. The
upload direction, inbound routing and hybrid v1-only swarms are covered by the
[v2 swarm completeness evidence](#v2-swarm-completeness-torrent-v2-swarm) below; none of it is
evidence for a second independent v2 implementation or the remaining production release gates.

The full [roadmap #162](https://github.com/linroid/Ketch/issues/162) still requires bidirectional
two-engine format interoperability, shaped-network/NAT/proxy fixtures, production resource and
performance acceptance, physical mobile devices, migration/package qualification and a 72-hour
soak. Passing the download/restart suites does not complete these gates. The older v1 performance
and package measurements below remain historical evidence only.

### V2 swarm completeness (`torrent-v2-swarm`)

The `torrent-v2-swarm` stack makes v2 and hybrid owners full swarm members: incoming routing by
wire tag, upload and seeding with a shared 4-slot choker, BEP 52 proof serving, BEP 10
`ut_metadata`/`ut_pex`/`p` on v2 connections, and hybrid participation in v1 swarms. Recorded at
`f6940ba0a` on 2026-10-10 on macOS (Apple silicon) with the Homebrew build of Transmission 4.1.3,
whose `--version` matches the pin. That revision is the branch rebased on `main` at `1957d4a56`
with every review fix, including the last round's proof-read pacing, dropping peers queued before
a tracker reset, taking over a finished task's seeder and refusing peers at the limit; every suite
and scenario below ran there:

```sh
TRANSMISSION_DAEMON=/opt/homebrew/bin/transmission-daemon \
  ./gradlew allJvmTests :library:torrent:verifyNoNativeTorrentRuntime \
  -PtorrentConformance=true -PprebuiltWebDir=/nonexistent
python3 -m unittest discover -s tools/torrent -p 'test_*.py'
python3 tools/torrent/verify_conformance.py --reports library/torrent/build/test-results/jvmTest \
  --revision "$(git rev-parse HEAD)" --output library/torrent/build/reports/conformance/executed.json
./gradlew :library:torrent:testAndroidHostTest
./gradlew :library:torrent:iosSimulatorArm64Test -PenableIosSimulatorTests=true
```

| Torrent suite | Before (`8c933c143`) | After (`f6940ba0a`) | Failures |
| --- | ---: | ---: | ---: |
| JVM, with Transmission | 699 | 896 | 0 |
| Android host | 675 | 866 | 0 |
| Executed iOS simulator | 676 | 867 | 0 |

On the iOS simulator the suite's tests took 87.1 s, of which 35.2 s went to the 192 tests added
since `8c933c143`: about 68% more than the earlier tests' 51.8 s. Most of it is the loopback
two- and three-engine tests, which iOS still polls every 5 ms, and proof tests that hash whole
pieces, up to 16 MiB.

Earlier runs failed `TorrentV2MetadataServingTest.disabledUploadAdvertisesNoUtMetadata` and
`TorrentV2IncomingTest.duplicatePeerIdKeepsTheSameConnectionOnBothEnds` now and then: the engine
seemed to reset the raw client's connection before answering its handshake. The engines listened
on the wildcard address, and on macOS another socket bound to `127.0.0.1` on the same port takes
the loopback connections, so the client reached someone else. Tests that dial loopback now bind
their engine to `127.0.0.1`. At `f6940ba0a`, ten JVM runs and three Android host runs of the v2
incoming, metadata serving, peer exchange and swarm tests (18 tests each) all passed.

The verifier then required all nine scenarios of `test-fixtures/torrent/scenarios.json`. The
seven new ones are:

| Scenario | Test | What it shows |
| --- | --- | --- |
| `v2.libtorrent.btmh-magnet-download` | `PublicV2IndependentSeederTest.pureV2MagnetDownloadsFromLibtorrent` | Ketch downloads a pure v2 magnet from libtorrent |
| `hybrid.libtorrent.dual-magnet-download` | `PublicV2IndependentSeederTest.hybridMagnetDownloadsFromLibtorrent` | The same for a dual-topic hybrid magnet |
| `v2.libtorrent.btmh-magnet-upload` | `PublicV2IndependentLeecherTest.libtorrentDownloadsPureV2FromKetchSeeder` | libtorrent fetches a pure v2 torrent's info dictionary, piece layers and payload from a seeding Ketch engine |
| `hybrid.libtorrent.dual-magnet-upload` | `PublicV2IndependentLeecherTest.libtorrentDownloadsHybridFromKetchSeeder` | The same for a hybrid, over the upgraded v2 route |
| `hybrid.libtorrent.v1-topic-upload` | `PublicV2IndependentLeecherTest.libtorrentJoinsHybridThroughTheV1Topic` | A `btih`-only magnet of a hybrid: libtorrent dials the v1 tag without the upgrade bit and downloads over the v1 route |
| `hybrid.transmission.v1-peer-upload` | `HybridTransmissionInteropTest.transmissionDownloadsHybridFromKetchSeeder` | Transmission, which knows only v1, downloads a hybrid from Ketch: canonical v1 blocks, padding as zeros |
| `hybrid.transmission.v1-peer-download` | `HybridTransmissionInteropTest.hybridTaskDownloadsFromTransmissionV1Seeder` | A Ketch hybrid task downloads from Transmission seeding it as v1, stripping the padding and checking both hashes |

The libtorrent fixture's first file has 521 pieces of 32 KiB, so its piece layer takes two hash
requests and the proofs carry uncles from the cached upper tree; its other files end mid-piece.
Findings while recording it:

- libtorrent leechers run with `out_enc_policy = pe_disabled` and without uTP, so the first
  attempt is plaintext TCP until Ketch has protocol encryption and uTP. They use libtorrent's
  POSIX disk back end: the memory-mapped one installs SIGSEGV and SIGBUS handlers around its
  writes that turn the JVM's implicit null checks into a crash of the test process.
- libtorrent reads a magnet's `x.pe` without percent-decoding it, so the tests append the peer
  as written rather than through `MagnetUri`, which encodes the colon.
- libtorrent pads the last file of a pure v2 torrent to a whole piece, like every other file, and
  asks for whole blocks of that last piece however few bytes it holds. Ketch used to ban a peer
  asking past the last file; a pure v2 piece's requests may now reach its full length, answered
  with zeros past the file (`TorrentContentLayout.protocolPieceLength`). Hybrids keep their v1
  geometry, whose last piece is short.
- Over a `btih`-only magnet, libtorrent fetches the hybrid info dictionary over `ut_metadata` and
  downloads on the v1 route, verifying SHA-1 pieces without asking for hashes, so the v1-topic
  scenario needed no fallback.
- Transmission 4.1.3 loads hybrid metainfo through `torrent-add` as a v1 torrent with BEP 47
  padding files; it runs with `--no-utp`, so a build with uTP behaves the same. For it to seed,
  the padding files are on disk as zeros.

These are interoperability results for the formats and directions above. A second independent
v2 implementation, the complete Fast Extension and the production release gates remain open.

## Review regression evidence (2026-09-08)

The first review pass added coverage for Unicode path aliases and encoded filename limits,
DNS fallback after a stalled address, two-minute peer keepalives, canceled rechecks, request
chatter and duplicate responses, upload caching and waiting peers, minimum-buffer completion,
tracker-independent metadata caching, malformed DHT UTF-8, and PEX from an exiting introducer.
Resume tests include a real 21-second limiter wait, a checkpoint larger than 4 MiB with the
16 MiB ceiling enforced, and a 1,000-file ownership restore that bounds path reconstruction.
RemoteKetch tests delay snapshots while creating/removing tasks and delivering newer SSE state.

Validation of the reviewed stack passed:

- Torrent: 290 JVM tests (including enabled Transmission interoperability), 280 Android host
  tests, and 281 iOS simulator tests.
- RemoteKetch: 3 JVM and 3 iOS simulator tests.
- Android instrumentation: 1 test on the Android 36 `medium_phone` emulator.
- Android app assembly, iOS device compilation, and CLI compilation.
- Transitional native-engine configuration and each affected earlier layer passed JVM tests
  before being propagated through the stack.

BEP 41 URLData remains on UDP announce requests, beginning at offset 98. Moving it to the
connect packet would contradict the [extension format](https://www.bittorrent.org/beps/bep_0041.html).

## Local evidence (2026-09-08)

- Torrent and core JVM, Android host and actual arm64 iOS simulator suites passed.
- Repository `jvmTest`, `testDebugUnitTest`, `iosSimulatorArm64Test`, and `jsNodeTest` passed.
- Actual Android 36 arm64 emulator: metainfo/magnet payload transfer and owned cleanup passed.
  The packaged Android app launched and reached its empty Downloads screen.
- Independent libtorrent: controlled DHT discovery through metadata to exact payload bytes.
  Independent Transmission 4.1.3: magnet metadata and 512 KiB + 37 bytes, exact output.
- Both packaged JVM and Graal CLIs completed Transmission payload downloads and local empty
  `.torrent` downloads. No external torrent process is involved in the downloading product.
- Public-runtime DHT-only discovery and private tracker failover tests pass. The private fixture
  requires old peers to close before the new tracker peer connects and observes zero UDP binds.
- A real local HTTPS fixture verifies metainfo and tracker exchanges, plus rejection of an
  untrusted certificate. This TLS fixture runs on JVM.
- Mixed HTTP/torrent tests verify shared bandwidth and removal of live task/global limits.
- A real daemon/RemoteKetch fixture resolves a magnet, selects file 1, observes verified progress,
  pauses and resumes to exact output. Remote task identity is preserved across SSE add events.
- Desktop distributable startup initialized Ketch with FTP/torrent sources and an isolated SQLite
  profile. Desktop visual inspection was unavailable while the host Mac was locked.
- Runtime graph audit and 404 packaged JVM/desktop/APK archives contained no libtorrent binaries,
  classes or torrent loader. JNA remains solely for JVM OS filesystem calls.
- Sparse output above 2 GiB, selected boundary pieces, corrupt data, hostile protocol input,
  symlink replacement, ownership isolation and kill/restart checkpoints have dedicated tests.
- Twelve repeated 64 KiB transfers and source shutdowns: open descriptors remained
  `[93, 93, 93, 93, 93, 93, 93, 93, 93, 93, 93, 93]` in the JVM test process.

## Measurements

One cold isolated downloader process per implementation, the same local libtorrent seeder,
8 MiB + 37 bytes, 256 KiB pieces, JVM heap capped at 256 MiB. Wall time includes runtime setup and
finalization. RSS is sampled by the parent every 50 ms; heap/descriptors by the child every 20 ms.
These samples can miss shorter peaks. This is a fixture comparison, not Internet swarm parity.

| Downloader | Wall time | Peak RSS | Peak JVM heap | Peak / final open descriptors |
| --- | ---: | ---: | ---: | ---: |
| Kotlin | 513 ms | 133,968 KiB | 34,847,240 bytes | 53 / 51 |
| libtorrent4j 2.1.0-39 | 2,049 ms | 92,928 KiB | 17,858,344 bytes | 46 / 40 |

The Kotlin run was faster and used more memory in this single measurement. Final descriptor
counts include JVM/client initialization caches; the repeated-source test checks accumulation.

The iOS arm64 simulator debug test transferred the same size in 6,136 ms, with 6,118,431 µs of
process CPU across **both** Kotlin seeder and downloader. A listener idle for one second used
13,164 µs CPU. The iOS adapter polls nonblocking sockets every 5 ms to work around the cached
Ktor native IPv6 sockaddr issue. Debug hashing, storage verification, simulator scheduling and
both peers contribute to the transfer CPU; this is not a device release-build benchmark.

Security boundaries, migration behavior and explicitly deferred protocols are documented in
[the support guide](../torrent.md). Treat the throughput/memory numbers as a baseline to improve,
not a performance guarantee.

The final local torrent counts were 275 JVM tests, 265 Android host tests, 266 iOS simulator tests,
and one Android device test, with zero failures. Core counts were 203 JVM and 200 each on Android
host, iOS simulator and JS. The server suite passed 42 tests. Counts include opt-in JVM fixture
methods, which return early when their required environment variables are absent; the recorded
independent-client runs explicitly set those variables.

Desktop packaging can select a non-Homebrew JDK with `-PdesktopJavaHome=/path/to/jdk`.
The Graal CLI build is `:cli:nativeCompile`; JVM distribution is `:cli:installDist`.
Local package checks reused the original checkout's built web assets through `-PprebuiltWebDir`;
web source was not changed by this work.

Windows filesystem CI passed after replacing Java's unavailable fileKey with OS handle identity.
The deterministic core handoff fixture holds the old source checkpoint open while requesting
resume, and requires both pause and resume to wait for checkpoint completion. The full local
regression/package command passed again after that correction (418 Gradle tasks).

### Session state memory

`TorrentSessionMemoryTest` checks that session admission (`maxSessionStateBytes`) bounds the heap
a session really retains, and is the basis for that default. Run it with
`KETCH_TORRENT_MEMORY=1 ./gradlew :library:torrent:jvmTest --tests '*TorrentSessionMemoryTest'`;
set `KETCH_TORRENT_MEMORY_REPORT` to a path to save the table.

Each row holds five distinct torrents with 256 KiB pieces in DOWNLOADING with no peers, measured
as used JVM heap after repeated GC against a baseline taken before any metadata is parsed. This
covers session state only: parsed metadata and v2 piece layers, storage and scheduler indexes,
tracker and discovery state. Piece and wire buffers belong to the transfer budget
(`maxBufferedBytes`) and are excluded. Two runs on JDK 21 (macOS arm64) agreed within 6%:

| Format | Pieces | Files | Charged / session | Retained / session | Ratio |
| --- | ---: | ---: | ---: | ---: | ---: |
| v1 | 1,000 | 1 | 501 KiB | 238 KiB | 2.10 |
| v1 | 30,000 | 1 | 6,392 KiB | 3,719 KiB | 1.72 |
| v1 | 30,000 | 1,000 | 7,295 KiB | 4,029 KiB | 1.81 |
| v1 | 100,000 | 1 | 20,610 KiB | 6,957 KiB | 2.96 |
| v1 | 100,000 | 10,000 | 29,937 KiB | 16,057 KiB | 1.86 |
| v2 | 1,000 | 1 | 1,185 KiB | 85 KiB | 13.78 |
| v2 | 30,000 | 1 | 6,796 KiB | 1,263 KiB | 5.38 |
| v2 | 30,000 | 1,000 | 9,702 KiB | 2,716 KiB | 3.57 |
| v2 | 100,000 | 1 | 20,339 KiB | 4,807 KiB | 4.23 |
| v2 | 100,000 | 10,000 | 49,616 KiB | 19,484 KiB | 2.55 |

Admission is conservative in every row, so the 64 MiB default holds at most about 37 MiB of real
session heap (64 MiB / 1.72). It admits five 30k-piece, 1,000-file torrents of either format, or
the 100k-piece, 10k-file v2 torrent. v2 charges about 1.1 MiB of fixed per-session state sized
for 500 peers, which dominates small torrents. These are JVM figures; ART object layouts differ,
and transient peaks while checking or decoding a checkpoint are not sampled.
