# Pure Kotlin torrent v2 implementation progress

The approved scope and acceptance gates are tracked in
[roadmap #162](https://github.com/linroid/Ketch/issues/162).
A roadmap row may span multiple PRs; it is complete only when all of its acceptance gates
have evidence.

## Current status — 2026-09-20

The consolidated implementation stack #231–#238 and public workflow #240 have landed.
The integrated baseline is `e822e8bd0383e008486ca5a3384181eaaba9a1a7`.
**Public v2/hybrid download and restart integration is complete. The production v2 release
roadmap remains open.** Original phases below retain their acceptance gates; a merged slice
does not complete a broader phase.

| Capability | Merged delivery | Status and remaining boundary |
| --- | --- | --- |
| Contracts and resource admission | #231 | Inspection models, conformance harness and bounded admission; runtime controller adapters and production resource gates remain |
| Identity and integrity | #232 | Full v2 identity, metainfo, SHA-256/Merkle verification and hybrid layout validation |
| Verified storage and recovery | #233–#234 | Owned storage, catalog/checkpoints, creation journals and crash fixtures; full migration/power-loss/mobile gates remain |
| Wire and scheduling | #235–#236 | V2 negotiation, authenticated hash exchange, bounded transport and download scheduling; incoming routing, uploads and complete Fast Extension/dual-swarm integration remain |
| Trackers and privacy | #237 | Lifecycle, internal edits/revisions/scrape and tracker-only privacy; public controller/remote commands and full network-policy gates remain |
| Engine lifecycle | #238 | Shared ownership/admission, joined shutdown, request rate controls and checkpoint restoration |
| Public download/restart | #240 | Metainfo and btih/btmh/dual-topic inputs, authenticated layers, selection, verified progress, tracker/DHT discovery, shared/live limits, pause/resume, TaskStore restart and owned removal |

### Completed delivery checklist

- [x] **Step 04 — Identity and metainfo model:** #232, #238 and #240; identity/magnet,
  metainfo/layout and engine ownership tests cover full hashes, malformed/conflicting inputs
  and hybrid alias exclusion.
- [x] **Step 06 — Versioned content storage:** #233 and #240; layout, verifier and store tests
  cover virtual padding, empty/selected files, large offsets, dual-hash integrity, committed
  availability and owned-file isolation.
- [x] **Foundation slice:** inspection contracts, pinned-client conformance harness and resource
  admission (#231). Step 01 still needs its complete production profiles/performance/harness gates.
- [x] **Integrity slice:** incremental SHA-256, Merkle verification, bounded layers and authenticated
  hash-response validation (#232/#235). Step 05 remains open for proof-serving primitives.
- [x] **Recovery slice:** catalog/checkpoints, owned storage, creation journal and public TaskStore
  restart with rechecking (#234/#238/#240). Step 07 still needs its complete migration/GC gates.
- [x] **Download slice:** outgoing v2 wire/scheduling, metainfo/magnet acquisition, selected payload,
  progress, live limits and pause/resume (#235/#236/#238/#240). Steps 08–10 remain open for full
  wire/dual-swarm participation and bidirectional two-engine interoperability.
- [x] **Tracker slice:** internal lifecycle, edits/revisions, scrape and tracker-only privacy (#237).
  Step 11 retains its complete private-network evidence gate and remaining integration work.
- [x] **Public integration milestone:** v2/hybrid download and restart through Ketch/source (#240).
  Steps 27–28 still require the complete torrent controller and equivalent product controls.

Checks describe the merged baseline and recorded tests, not uncommitted fixes or production
release qualification. All other original phase checkboxes remain open.

### Evidence and its limits

[PR #240](https://github.com/linroid/Ketch/pull/240) records 635 JVM, 613 executed iOS simulator
and 612 Android host torrent tests, plus core suites and the no-native-runtime guard.
Its final reviewed head `de5623c5` passed all required CI checks, as recorded in the
[workflow update](https://github.com/linroid/Ketch/issues/162#issuecomment-5748957160).
These are recorded results from that revision, not a fresh validation of subsequent local edits.

Pure v2 and hybrid magnet downloads passed against the pinned test-only libtorrent peer.
This does not satisfy the two-independent-engine, bidirectional format gate: v2 incoming
routing, upload/seeding, PEX, hybrid v1-only peers and a second independent v2 implementation
remain outstanding. No production qualification or release is claimed.

### Remaining delivery priorities

1. Complete v2 incoming routing, upload/seeding, PEX and hybrid dual-swarm participation;
   then satisfy the independent-client download/upload format gate.
2. Complete runtime readiness and network policy. iOS still uses 5 ms polling; production
   profiles, aggregate memory/RSS and responsiveness acceptance remain open.
3. Deliver uTP, MSE/PE, PCP/NAT-PMP/UPnP, hole punching, proxy routing, local discovery and web seeds.
4. Deliver live selection/verified streaming, durable seed goals/queue, storage management,
   mobile destinations/lifecycle, creation/export and equivalent SDK/daemon/CLI/app/remote controls.
   `TorrentController` currently supplies inspection contracts; runtime adapters and mutations remain.
5. Complete shaped-network, adversarial/performance, physical-device, migration/package and
   72-hour soak evidence. Preserve all original release requirements and exclusions.

Steps 04 and 06 are checked based on their implementation and focused regression evidence;
other partial phases retain unchecked parent rows.
Uncommitted working-tree fixes are outside this merged baseline and need their own evidence.

### Delivery history

Original PRs #163–#228 were superseded by #231–#238. Their branches and review discussions
remain preserved. See the
[consolidation record](https://github.com/linroid/Ketch/issues/162#issuecomment-5748504439),
[early progress archive](https://github.com/linroid/Ketch/issues/162#issuecomment-5647431431)
and [public workflow record](https://github.com/linroid/Ketch/issues/162#issuecomment-5748957160).
Their statements about pending merges describe the time of posting.

Current repository references:
[support](https://github.com/linroid/Ketch/blob/main/docs/torrent.md),
[verification](https://github.com/linroid/Ketch/blob/main/docs/development/torrent-verification.md),
[implementation progress](https://github.com/linroid/Ketch/blob/main/docs/plans/pure-kotlin-torrent-v2-progress.md).

## Historical foundation notes

The following notes describe the initial foundation slices, before the consolidated stack and
public workflow landed. Pending-work statements below apply to those historical revisions;
the current status above takes precedence.

## Foundation stack

| Slice | Branch | Scope |
| --- | --- | --- |
| 01a | `torrent-v2-01-foundation` | Pinned reference clients, executed-scenario evidence, CI gate |
| 01b | `torrent-v2-01-contracts` | Control/state/capability and compatibility contracts |
| 01c | `torrent-v2-01-budgets` | Independent metadata/transfer partitions and aggregate ceiling |
| 01d | `torrent-v2-01-admission` | Retained metadata cache admission and lifecycle cleanup |
| 01e | `torrent-v2-01-session-admission` | Session admission before storage/index construction |
| Remaining 01 | Planned | Production profiles, deterministic harness, performance baseline |

Slice 01a covers two existing v1 interoperability scenarios. Missing Transmission fails required
conformance mode; ordinary local runs omit unconfigured optional fixtures from the test plan.
The JVM CI lane verifies actual executions and publishes evidence tied to its tested revision.
`Required Checks` aggregates the existing platform lanes; repository branch protection must select
that check separately. Adding a job does not configure GitHub branch protection.

Roadmap step 01 remains incomplete. This harness is not evidence of v2/hybrid compatibility,
production performance, physical-device behavior, or completion of any later roadmap step.
Steps 02–30 remain pending. Runtime behavior and the legacy upload default are unchanged by 01a.

## Validation environment for 01a

- Baseline: merged v1 tree at `06e8e2cb`.
- Local host: macOS 26.6.2 (25G83), arm64; OpenJDK 21.0.11; Gradle 9.7.1.
- Reference clients: source-built Transmission 4.1.3 and JVM test-only libtorrent4j 2.1.0-39.
- Fixture source digest and scenario identities: `test-fixtures/torrent/`.
- The existing v1 JVM suite and native runtime dependency guard pass locally.
- Six verifier tests cover missing, skipped, failed, duplicate, and executed report behavior.

CI artifacts provide revision-specific results. Local fixture timings are correctness test durations,
not throughput or memory benchmarks; the production performance baseline is still pending.

## Slice 01b

The [control protocol contract](../design/torrent-control-contract.md) specifies capabilities,
revision/reconnect ordering, completion generations, mutation semantics, and compatibility gates.
The API module supplies bounded inspection models, capability negotiation, command preconditions,
and revision merge decisions. `KetchApi.torrents` defaults to null; existing local/remote backends
continue their legacy behavior until the runtime adapters are implemented.

Validation: API tests pass on JVM and JavaScript; core and remote JVM implementations compile.
Mutation implementations, paginated detail endpoints, operation ledgers, and runtime adapters
remain pending; their declarations and tests ship with the respective implementation slices.

## Slice 01c

Metadata exchange now has an independent scratch reservation instead of competing with active
piece buffers. Both partitions account against a shared exchange ceiling. Configuration rejects
ceilings that cannot hold both partitions, so payload saturation cannot prevent one metadata
exchange from progressing. The existing metadata wire fixture exercises successful and failed
exchanges while the transfer partition remains fully reserved.

This is an exchange-buffer ceiling, not a total engine memory or process RSS claim. Retained
metadata/cache entries, session indexes, proof layers, disk handles, and platform allocations still
need their admission rules. Mobile/desktop production profiles and performance gates remain pending.

## Slice 01d

Cached metadata now holds a budget lease until eviction, replacement, or shutdown. Conservative
weights include retained arrays, file records, strings, and a container allowance. Results that
cannot be admitted are returned to the caller without being retained. Cache capacity is a separate
partition within the same ceiling, preserving scratch headroom for metadata exchange even when
cache and transfer partitions are full.

Explicit shutdown rejects new cache work, cancels shared fetches, and awaits their finalizers
outside the cache mutex. Owner cancellation also clears retained entries. Tests cover replacement,
least-recently-used eviction, parent pressure, oversized entries, repeated close, pending fetch
cancellation, and owner teardown. Caller-owned metadata, session state, and process overhead remain
outside this cache-retention accounting; production profile and total-memory gates remain pending.

## Slice 01e

Session admission applies file count, piece count, per-session estimated size, and aggregate state
capacity before constructing storage or decoding a checkpoint. The allowance covers retained
metadata, file/path indexes, scheduler arrays/candidate lists, checkpoint parsing, and checking
scratch buffers. Existing peer/wire reservations remain in the transfer partition.

Reservations survive pause and failed deletion, return on construction failure, and return after
successful detachment on removal or after the entire runtime's jobs finish during shutdown. Tests verify rejection before filesystem creation,
aggregate exhaustion, failed-construction rollback, pause retention, removal/readmission, and
non-suspending shutdown. The combined ceiling includes session admission while preserving metadata
headroom even if every other partition is full.

These are conservative admission allowances, not measured total heap or RSS bounds. New format
limits, runtime profiles, platform overhead, deterministic transport fault coverage, and baseline
performance evidence still require the remaining roadmap work. No production release gate is
checked off from these admission tests alone.

Review follow-up for 01e: closing a session's network jobs does not end runtime ownership when
file cleanup fails. A runtime ledger now retains admission until successful map detachment.
Failed deletion remains retryable instead of being treated as already completed on the next call.
The regression test proves two failed cleanup attempts remain charged and block new admission,
while explicit keep-data removal returns credit without touching storage.
