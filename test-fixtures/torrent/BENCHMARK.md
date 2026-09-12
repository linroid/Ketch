# Direct TCP baseline

The benchmark compares isolated Kotlin and libtorrent4j downloader JVMs against the same pinned
libtorrent seeder, on loopback TCP. uTP and public discovery are disabled. Payloads are deterministic
pseudorandom data from Java Random seed 162, generated one 256 KiB piece at a time. The independent
fixture builder uses JDK SHA-1 and SHA-256. Output verification streams SHA-256 and checks file length.
Neither generation nor verification allocates a payload-sized byte array.

Run on an otherwise idle Unix host with `ps`, JDK 21, and sufficient disk space. Only one output is
retained at a time. Each size needs twice the payload size plus 2 GiB free space. Reference hardware,
OS, runtime, filesystem, and competing workloads must be recorded alongside the JSON results.

```shell
KETCH_TORRENT_BENCHMARK=1 \
KETCH_BENCHMARK_BYTES=1073741824,10737418240 \
KETCH_BENCHMARK_RUNS=5 \
KETCH_BENCHMARK_REVISION="$(git rev-parse HEAD)" \
KETCH_BENCHMARK_REPORT=/tmp/ketch-torrent-baseline.json \
  ./gradlew :library:torrent:jvmTest --tests '*TorrentBenchmarkTest*'
```

For a harness smoke test, omit sizes/runs: defaults are 8 MiB + 37 bytes and one run. Every benchmark
invocation executes; Gradle test caching is disabled. Alternating Kotlin/native order reduces a
systematic order advantage. File data is not explicitly purged from the OS cache between runs.
Report all raw samples and the median/spread, including failures; do not select only fast samples.

The seeder has a 120-second readiness deadline. Each child has a 600-second execution deadline and
the parent enforces a 630-second wall-clock deadline including readiness and sampling. A stuck
child is forcibly terminated, with a five-second termination deadline. These bounds are fixed
before measurement. A failure leaves `complete: false` and previously completed samples in the report.
A complete 1/10 GiB baseline requires twenty verified child runs, not just a successful smoke test.

Both child JVMs have a 256 MiB heap ceiling. The Kotlin engine uses one peer, disabled uploads and
DHT, 64 MiB transfer capacity, 64 MiB session capacity, and a 256 MiB combined admission ceiling;
other limits retain their defaults. The native reference uses `referenceSettings()` and remaining
libtorrent defaults. One local seeder is available to either downloader, with no upload recipients.
These are explicit benchmark settings, not a completed production resource profile.

An initialized child prints readiness and waits for the parent to sample idle RSS before transfer.
The report includes runtime/platform identity, fixture SHA-256, fixture creation time, seeder readiness,
child initialization, download-to-verified-completion time, CPU time, heap/RSS samples, descriptor
counts, and independent output verification time. RSS is sampled every 100 ms; heap/FDs every 20 ms,
so short peaks may be missed. Idle heap is not a forced-GC measurement. CPU can exceed wall time
because work uses multiple threads. Transfer time includes overlapping hashing and disk work and
is not a measurement of network-only throughput. Final descriptor counts follow engine shutdown.

This baseline does not certify the 80% sustained-throughput release target. Matching native cache
and durability policy, separating overlapping hash/disk/network costs, shaped swarms, many files,
partial selection, mobile hardware, energy, lifecycle, and soak measurements remain release work.
No target is relaxed to accommodate a slow run.
