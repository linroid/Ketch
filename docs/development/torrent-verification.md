# Torrent verification

The implementation is delivered as the [14-layer review stack](../plans/pure-kotlin-torrent-progress.md).
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

Transmission 4.1.3 (macOS) and 4.0.5 (Ubuntu fixture) are accepted explicitly. The other independent
client is libtorrent4j 2.1.0-39. Set `KETCH_NATIVE_CLI` and `KETCH_JVM_CLI` alongside
`TRANSMISSION_DAEMON` to exercise the built executables against the same independent seeder.
The Gradle test cache tracks these opt-in environment values.

CI runs JVM/Android host, iOS simulator and JS regression suites; extra macOS/Windows jobs exercise
the OS filesystem adapters. The Android emulator job requires a nonzero executed test count, since
an AGP connected-test task can otherwise succeed without running instrumentation.

## Local evidence (2026-09-08)

- Torrent and core JVM, Android host and actual arm64 iOS simulator suites passed.
- Repository `jvmTest`, `testDebugUnitTest`, `iosSimulatorArm64Test`, and `jsNodeTest` passed.
- Actual Android 36 arm64 emulator: metainfo/magnet payload transfer and owned cleanup passed.
- Independent libtorrent: controlled DHT discovery through metadata to exact payload bytes.
  Independent Transmission 4.1.3: magnet metadata and 512 KiB + 37 bytes, exact output.
- Both packaged JVM and Graal CLIs completed Transmission payload downloads and local empty
  `.torrent` downloads. No external torrent process is involved in the downloading product.
- Public-runtime DHT-only discovery and private tracker failover tests pass. The private fixture
  requires old peers to close before the new tracker peer connects and observes zero UDP binds.
- A real local HTTPS fixture verifies metainfo and tracker exchanges, plus rejection of an
  untrusted certificate. This TLS fixture runs on JVM.
- Mixed HTTP/torrent tests verify shared bandwidth and removal of live task/global limits.
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
