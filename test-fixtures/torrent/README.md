# Torrent conformance fixtures

The independent BitTorrent clients that Ketch's torrent tests run against, and the gate that
proves the required interoperability scenarios ran. It is for contributors who run or extend those
tests. The broader test and evidence guide is
[torrent verification](../../docs/development/torrent-verification.md).

These clients are test fixtures. They must never enter a product runtime dependency graph;
`:library:torrent:verifyNoNativeTorrentRuntime` checks that.

| File | Contents |
| --- | --- |
| `clients.properties` | Pinned client versions; Transmission's release archive URL and SHA-256 |
| `scenarios.json` | The behavioral tests the evidence verifier requires |
| `BENCHMARK.md` | The throughput benchmark against Transmission |

- **Transmission** is built from its release archive, authenticated by SHA-256, by
  `tools/torrent/build_transmission.py`. Its third-party libraries are built from that verified
  source archive too.
- **libtorrent** (libtorrent4j) is a version-pinned JVM test dependency that checks the version
  it loads.

## Run the conformance suite

You need Python 3.12+, CMake, a C/C++ compiler and the platform's curl and TLS development
libraries. On Ubuntu 24.04:

```shell
sudo apt-get install cmake build-essential libcurl4-openssl-dev libssl-dev
```

Build Transmission, run the torrent tests with it, then verify the evidence:

```shell
python3 tools/torrent/build_transmission.py --output /tmp/ketch-transmission
```

```shell
TRANSMISSION_DAEMON=/tmp/ketch-transmission/build/daemon/transmission-daemon \
  ./gradlew :library:torrent:jvmTest -PtorrentConformance=true
```

```shell
python3 tools/torrent/verify_conformance.py \
  --reports library/torrent/build/test-results/jvmTest \
  --revision "$(git rev-parse HEAD)" \
  --output library/torrent/build/reports/conformance/executed.json
```

Any official Transmission 4.1.3 build works in place of the one `build_transmission.py` makes, as
long as `--version` reports the pinned version.

## How the opt-ins work

- `-PtorrentConformance=true` always runs the torrent JVM suite; its results are never restored
  from Gradle's test cache. A missing Transmission binary, or one whose version differs from the
  pin, fails the required suite.
- Ordinary JVM runs leave the Transmission tests out when `TRANSMISSION_DAEMON` is not set,
  rather than counting a no-op test as a passed interoperability scenario. Setting it opts in.
- `KETCH_TORRENT_BENCHMARK=1` likewise includes the expensive process comparison; otherwise it is
  left out of the test plan. See [BENCHMARK.md](BENCHMARK.md).
- Packaging fixtures stay opt-in until their release lane is added.

## The evidence verifier

`scenarios.json` lists real, required behavioral tests. `verify_conformance.py`:

- rejects missing, skipped, failed and duplicate results;
- records the Git revision, report digests, client versions and durations;
- removes an older evidence file before validating, so a failure cannot leave a stale green
  report behind for upload.

Run the gate's own negative cases with:

```shell
python3 -m unittest discover -s tools/torrent -p 'test_*.py'
```

## What the scenarios cover

- **v1 download**: through libtorrent DHT and metadata, and a Transmission magnet with an explicit
  peer.
- **v2 and hybrid download and upload** with libtorrent, which fetches metadata, piece layers and
  payload from a seeding Ketch engine through `btmh`, dual-topic and `btih`-only magnets (the last
  over the v1 route, without hashes).
- **Hybrid v1-peer upload and download** with Transmission, which knows only v1 and its padding.
- **Live file selection**: a running task gains files while it downloads from a libtorrent seeder
  (v1 and pure v2) and from a Transmission seeder (v1), byte-exact, without dialing the peer
  again.

The scenarios do not certify public trackerless discovery, a second independent v2
implementation, uTP or protocol encryption. Tests outside the manifest do run libtorrent peers
that require RC4 or speak only uTP (`IndependentSeederTest`, `PublicV2IndependentLeecherTest`).

Extend the manifest with each implemented capability and keep independent expected payloads.
Never add an unimplemented scenario as passing, or satisfy two capability rows with one no-op
test.

## Client settings

- Transmission runs with `--no-utp` (and without DHT, local peer discovery or port mapping), so a
  build with uTP keeps the same TCP behavior.
- libtorrent leechers in the scenarios run plaintext over TCP (`pe_disabled`, uTP off) unless a
  test asks for RC4 or uTP.
- libtorrent leechers use the POSIX disk back end: the memory-mapped one installs SIGSEGV and
  SIGBUS handlers around its writes that crash the test JVM.
