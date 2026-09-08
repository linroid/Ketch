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
| 11 Trackerless discovery and PEX | Pending PR | IPv4/IPv6 multi-hop/warm DHT, PEX payload discovery, public identity quorum, private-source policy; independent DHT-to-metadata-to-payload JVM transfer |
| 12–14 | Pending | Not implemented yet |

No PR has been merged. The default transfer engine is still libtorrent4j.

Implementation notes:

- Existing GitHub HTTPS credentials lack workflow scope; authorized pushes use the existing SSH
  credentials. PR creation uses `gh`. No account permission changes were made.
- Installed Ktor 3.5.2 omits `sin6_addr` in its native IPv6 sockaddr packing. The iOS I/O adapter
  uses nonblocking OS sockets and guards descriptor lifetime. Its polling CPU/throughput costs
  must be measured at the performance gate. JVM/Android use Ktor sockets.
- Bencode and SHA-1 use common Kotlin. Platform Unicode normalization is used only to reject
  filesystem name collisions; torrent identity hashes the original metainfo bytes.
- The storage layer refuses symlink child paths and tracks files created by the task. Durable
  ownership recovery and crash-safe resume remain layer 12 work.
- Common tests use hand-written fakes. Protocol interoperability with an independent seeder
  starts at layer 06; production source/runtime integration remains gated until layer 13.
