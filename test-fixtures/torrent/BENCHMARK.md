# Direct TCP baseline benchmark

How to measure Ketch's torrent download throughput and resources against the pinned native
Transmission downloader, and how to report the results. It is for contributors who measure
performance changes in `library:torrent`.

## What it measures

`TorrentBenchmarkTest` compares an isolated Kotlin JVM downloader with the pinned native
Transmission downloader, both fed by a separate pinned Transmission seeder:

- A loopback-only tracker advertises a private IPv4 address assigned to this host. Transmission
  rejects loopback tracker peers, so data sockets bind to that host address; all data still
  travels between processes on the same machine.
- Public discovery is off, and the seeder and native downloader run without uTP.
- Payloads are deterministic pseudorandom data from Java `Random` seed 162, generated one 256 KiB
  piece at a time. The independent fixture builder uses the JDK's SHA-1 and SHA-256.
- Output verification streams SHA-256 and checks the file length. Neither generation nor
  verification allocates a payload-sized byte array.

## Run it

Use an otherwise idle Unix host with `ps`, JDK 21 and enough disk space: only one output is kept at
a time, and each size needs twice the payload size plus 2 GiB free. Build Transmission first as
described in the [fixture README](README.md).

```shell
TRANSMISSION_DAEMON=/tmp/ketch-transmission/build/daemon/transmission-daemon \
KETCH_TORRENT_BENCHMARK=1 \
KETCH_BENCHMARK_BYTES=1073741824,10737418240 \
KETCH_BENCHMARK_RUNS=5 \
KETCH_BENCHMARK_REVISION="$(git rev-parse HEAD)" \
KETCH_BENCHMARK_REPORT=/tmp/ketch-torrent-baseline.json \
  ./gradlew :library:torrent:jvmTest --tests '*TorrentBenchmarkTest*'
```

| Variable | Meaning |
| --- | --- |
| `KETCH_TORRENT_BENCHMARK` | `1` includes the benchmark; otherwise it is left out of the test plan |
| `KETCH_BENCHMARK_BYTES` | Payload sizes, comma-separated (default 8 MiB + 37 bytes) |
| `KETCH_BENCHMARK_RUNS` | Runs per size (default 1) |
| `KETCH_BENCHMARK_REVISION` | The Git revision to record in the report |
| `KETCH_BENCHMARK_REPORT` | Where to write the JSON report |

For a harness smoke test, leave out the sizes and runs. Every benchmark invocation executes:
Gradle test caching is off for it.

## Reporting results

- Record the hardware, OS, runtime, file system and competing workloads alongside the JSON
  results.
- Report all raw samples and the median and spread, including failures; do not pick only the
  fast samples.
- Only `complete: true` proves completion. Failures keep the samples completed before them.
- A complete 1 GiB / 10 GiB baseline needs twenty verified child runs, not just a passing smoke
  test.
- Report both end-to-end and post-first-piece rates, without calling either one a complete
  release benchmark.

## Method details

### Order and caching

Kotlin and native runs alternate, which reduces any systematic advantage of going first. File data
is not explicitly purged from the OS cache between runs.

### Deadlines

These bounds are fixed before measurement; no timeout is raised to accommodate a slow run.

| Stage | Deadline |
| --- | --- |
| Seeder readiness | 120 seconds |
| Each child's execution | 600 seconds |
| Parent wall clock, including readiness and sampling | 630 seconds |
| Forced termination of a stuck child | 5 seconds |

### Engine settings

- **Kotlin**: a 256 MiB JVM heap ceiling; one peer connection, uploads and DHT off, 64 MiB transfer
  capacity, 64 MiB session capacity and a 256 MiB combined admission ceiling. Other limits keep
  their defaults.
- **Native**: the Transmission executable. Its small JVM RPC controller is excluded from native
  RSS and CPU measurements. It runs without DHT, LPD, NAT mapping and uTP, uses the same fixture
  tracker, and otherwise keeps Transmission's defaults.
- One local seeder serves either downloader, with no upload recipients.

These are explicit benchmark settings, not a finished production resource profile.

### What the report contains

An initialized controller reports the measured engine's PID and waits for the parent to sample idle
RSS before the transfer. The parent checks that the PID belongs to the controller or its
descendants, and forced timeout cleanup also ends the controller's descendants.

The report includes runtime and platform identity, the fixture's SHA-256 and creation time, seeder
readiness, child initialization, download-to-verified-completion time, CPU time, heap and RSS
samples, descriptor counts, and independent output verification time.

- RSS is sampled every 100 ms, heap and descriptors every 20 ms, so short peaks may be missed.
- Idle heap is not a forced-GC measurement.
- CPU time can exceed wall time, because work runs on several threads.
- Transfer time includes overlapping hashing and disk work; it is not network-only throughput.
- Final descriptor counts follow engine shutdown.
- Unsupported native heap, descriptor and CPU counters are reported as -1, never as zero.

The first verified-progress timestamp separates the wait for a connection and unchoke from the
transfer that follows. Native verified bytes are polled through RPC every 100 ms and may also lag
behind Transmission's own statistics refresh. The 1 GiB and 10 GiB fixtures contain only full
pieces.

## What it does not certify

This baseline does not certify the 80% sustained-throughput release target. Still to do as release
work: matching the native cache and durability policy, separating overlapping hash, disk and
network costs, shaped swarms, many files, partial selection, mobile hardware, energy, lifecycle and
soak measurements. No target is relaxed to accommodate a slow run.

## History

An initial run at `560ba380` used an in-process libtorrent seeder, and its parent JVM crashed with
SIGSEGV on the Java Finalizer thread after one verified Kotlin 1 GiB sample. That run is incomplete
and excluded from aggregate comparisons; the crash does not establish a root cause in the
downloader. The next attempt moved the seeder to an external pinned Transmission process and
isolated native torrent bindings in the reference downloader JVM. No timeout or performance target
was raised.

A second attempt at `d92566d8` isolated the Transmission seeder, but the libtorrent4j downloader JVM
also exited with SIGSEGV on its first 1 GiB sample. That incomplete report is kept separately.

The current native baseline uses the pinned Transmission executable directly, with no torrent JNI
binding in any measurement JVM. The libtorrent4j interoperability tests remain separate; the
large-fixture binding crash is not a successful compatibility or performance result.
