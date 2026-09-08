# Pure Kotlin torrent implementation status

Approved scope: [roadmap](pure-kotlin-torrent-roadmap.md). Implementation checkout:
`/private/tmp/ketch-torrent-stack`; the original checkout is preserved.

| Layer | PR | Status |
| --- | --- | --- |
| 01 Contracts and CI | https://github.com/linroid/Ketch/pull/147 | Draft; local JVM/Android/iOS gates passed; CI platform jobs passed |
| 02 Metainfo and hashing | https://github.com/linroid/Ketch/pull/148 | Draft; local JVM/Android/iOS gates passed |
| 03 Portable I/O | https://github.com/linroid/Ketch/pull/149 | Draft; real IPv4/IPv6 TCP/UDP and filesystem tests passed on JVM/Android/iOS |
| 04 Peer wire/state | https://github.com/linroid/Ketch/pull/150 | Draft; local JVM/Android/iOS gates passed |
| 05 Verified storage | https://github.com/linroid/Ketch/pull/152 | Draft; local JVM/Android/iOS gates passed |
| 06 First verified transfer | https://github.com/linroid/Ketch/pull/153 | JVM independent libtorrent seeder passed; JVM/Android/iOS loopback transfer and corruption gates passed |
| 07 Tracker discovery | https://github.com/linroid/Ketch/pull/154 | Local HTTP parsing, IPv4/IPv6 UDP, tier and lifecycle validation |
| 08 Swarm and upload | https://github.com/linroid/Ketch/pull/155 | Rarity/pipelines, complementary peers, recovery, upload policies and seeding lifecycle on JVM/Android/iOS |
| 09 Magnet metadata | https://github.com/linroid/Ketch/pull/156 | Independent seeder metadata-to-payload transfer; common negotiation, hash/size/private validation and shared cache tests |
| 10 DHT foundations | https://github.com/linroid/Ketch/pull/157 | Published BEP 42/CRC vectors, routing/token expiry, packet quotas and IPv4/IPv6 RPC correlation |
| 11 Trackerless discovery and PEX | https://github.com/linroid/Ketch/pull/158 | IPv4/IPv6 multi-hop/warm DHT, PEX payload discovery, public identity quorum, private-source policy; independent DHT-to-metadata-to-payload JVM transfer |
| 12 Durable resume | https://github.com/linroid/Ketch/pull/159 | Journal/checkpoint/identity recovery, concurrent session pause/resume and live limits pass on JVM/Android/iOS; JVM process-crash fixtures pass at write/checkpoint boundaries |
| 13 Kotlin runtime and product integration | Pending PR | Kotlin default on JVM/Android/iOS; CLI download/daemon registration; public-source selected boundary transfer and offline recovery, incoming seeding, connection reduction, and final checkpoint handoff |
| 14 Hardening and native removal | Pending | Final interoperability, security/performance/artifact gates and removal remain |

No PR has been merged. The default transfer engine is now Kotlin; native code remains temporarily for comparison until layer 14.

Implementation notes:

- Existing GitHub HTTPS credentials lack workflow scope; authorized pushes use the existing SSH
  credentials. PR creation uses `gh`. No account permission changes were made.
- Installed Ktor 3.5.2 omits `sin6_addr` in its native IPv6 sockaddr packing. The iOS I/O adapter
  uses nonblocking OS sockets and guards descriptor lifetime. Its polling CPU/throughput costs
  must be measured at the performance gate. JVM/Android use Ktor sockets.
- Bencode and SHA-1 use common Kotlin. Platform Unicode normalization is used only to reject
  filesystem name collisions; torrent identity hashes the original metainfo bytes.
- The storage layer refuses symlink child paths and tracks files created by the task. Durable
  ownership recovery and crash-safe resume are implemented in layer 12.
- Common tests use hand-written fakes. Protocol interoperability with an independent seeder
  starts at layer 06; production source/runtime integration is implemented in layer 13.

- Ownership records include OS file identity and are appended independently of TaskStore snapshots.
  Cleanup preserves paths whose identity cannot be proven. A process exit in the tiny interval
  between creating a file/directory and recording its identity can leave an unclaimed path;
  recovery preserves it conservatively. Unknown temporary metadata files are also preserved.
  Stronger descriptor-rooted protection against concurrent symlink replacement remains a final
  storage hardening audit; current checks reject static symlink paths.
- Native/iOS socket disconnects now use IOException consistently, and shared parent-directory
  creation tolerates another torrent creating the same directory without claiming its ownership.
