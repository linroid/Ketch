# Torrent conformance fixtures

These clients are test fixtures. They must never enter a product runtime dependency graph.
`clients.properties` pins their versions; Transmission's release archive is authenticated by SHA-256.
The libtorrent fixture remains a version-pinned JVM test dependency and validates its loaded version.

Build Transmission with Python 3.12+, CMake, a C/C++ compiler, and platform curl/TLS development
libraries. On Ubuntu 24.04, install `cmake`, `build-essential`, `libcurl4-openssl-dev`, and
`libssl-dev`. Third-party torrent libraries are built from the verified Transmission source archive.

```shell
python3 tools/torrent/build_transmission.py --output /tmp/ketch-transmission
TRANSMISSION_DAEMON=/tmp/ketch-transmission/build/daemon/transmission-daemon \
  ./gradlew :library:torrent:jvmTest -PtorrentConformance=true
python3 tools/torrent/verify_conformance.py \
  --reports library/torrent/build/test-results/jvmTest \
  --revision "$(git rev-parse HEAD)" \
  --output library/torrent/build/reports/conformance/executed.json
```

`torrentConformance=true` always executes the torrent JVM suite; its results cannot be restored
from Gradle's test cache. A missing Transmission binary or a version different from the pin fails
the required suite. Ordinary JVM runs exclude Transmission when it is not configured, rather than
counting a no-op test as a successful interoperability scenario. Supplying the binary opts in.
`KETCH_TORRENT_BENCHMARK=1` similarly includes the expensive process comparison; otherwise it is
excluded from the test plan. Packaging fixtures remain opt-in until their release lane is added.

`scenarios.json` lists actual required behavioral tests. The evidence verifier rejects missing,
skipped, failed, and duplicate results and records the Git revision, report digests, client versions,
and durations. It removes an older evidence file before validating, so failure cannot leave a stale
green report available for upload. Run the gate's negative cases with:

```shell
python3 -m unittest discover -s tools/torrent -p 'test_*.py'
```

The first manifest covers **v1 download** through libtorrent DHT/metadata and a Transmission magnet
with an explicit peer. It does not certify v2, hybrid, uploads, or public trackerless discovery.
Extend the manifest with each implemented capability and preserve independent expected payloads.
Never add an unimplemented scenario as passing, or satisfy two capability rows with one no-op test.
