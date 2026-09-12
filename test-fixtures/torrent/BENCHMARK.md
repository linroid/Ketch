# Direct TCP baseline

The benchmark compares an isolated Kotlin JVM with the pinned native Transmission downloader,
using a separate pinned Transmission seeder. A loopback-only tracker advertises a private IPv4
address assigned to this host. Transmission rejects loopback tracker peers, so data sockets bind
to that host address; all data still travels between processes on the same machine. uTP and public
discovery are disabled. Payloads are deterministic
pseudorandom data from Java Random seed 162, generated one 256 KiB piece at a time. The independent
fixture builder uses JDK SHA-1 and SHA-256. Output verification streams SHA-256 and checks file length.
Neither generation nor verification allocates a payload-sized byte array.

Run on an otherwise idle Unix host with `ps`, JDK 21, and sufficient disk space. Only one output is
retained at a time. Each size needs twice the payload size plus 2 GiB free space. Reference hardware,
OS, runtime, filesystem, and competing workloads must be recorded alongside the JSON results.

```shell
TRANSMISSION_DAEMON=/tmp/ketch-transmission/build/daemon/transmission-daemon \
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
before measurement. Only `complete: true` proves completion. Failures preserve previously completed samples.
A complete 1/10 GiB baseline requires twenty verified child runs, not just a successful smoke test.

The Kotlin JVM has a 256 MiB heap ceiling. The native engine is the Transmission executable; its
small JVM RPC controller is excluded from native RSS/CPU measurements. Unsupported native heap/FD
and CPU counters are reported as -1, never as zero. The Kotlin engine uses one peer, disabled uploads and
DHT, 64 MiB transfer capacity, 64 MiB session capacity, and a 256 MiB combined admission ceiling;
other limits retain their defaults. The native reference disables DHT, LPD, NAT mapping and uTP, uses the same fixture tracker, and
otherwise retains Transmission defaults. One local seeder is available to either downloader, with no upload recipients.
These are explicit benchmark settings, not a completed production resource profile.

An initialized controller reports the measured engine PID and waits for the parent to sample idle
RSS before transfer. The parent verifies that the PID belongs to the controller or its descendants.
Forced timeout cleanup also terminates controller descendants.
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

The first verified-progress timestamp separates connection/unchoke wait from subsequent transfer.
Native verified bytes are polled through RPC every 100 ms and may also be delayed by Transmission's
own statistics refresh. The 1/10 GiB fixtures contain only full pieces. Report both end-to-end and post-first-piece rates, without calling either
one a complete release benchmark.

An initial run at `560ba380` used an in-process libtorrent seeder and its parent JVM crashed with
SIGSEGV on the Java Finalizer thread after one verified Kotlin 1 GiB sample. It is incomplete and
excluded from aggregate comparisons. The crash does not establish a root cause in the downloader.
The harness now uses an external pinned Transmission seeder and keeps native torrent bindings
inside the isolated reference downloader process. No timeout or performance target was raised.

A second attempt at `d92566d8` isolated the Transmission seeder but the libtorrent4j downloader JVM
also exited with SIGSEGV on its first 1 GiB sample. That incomplete report is retained separately.
The current native baseline uses the pinned Transmission executable directly, with no torrent JNI
binding in any measurement JVM. Existing libtorrent4j interoperability tests remain separate; the
large-fixture binding crash is not a successful compatibility or performance result.
