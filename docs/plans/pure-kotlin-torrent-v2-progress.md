# Pure Kotlin torrent v2 implementation progress

The approved scope and acceptance gates are tracked in
[roadmap #162](https://github.com/linroid/Ketch/issues/162).
Implementation uses focused stacked PRs, each based on the preceding branch. A roadmap row may
span multiple PRs; it is complete only when all of its acceptance gates have evidence.

## Foundation stack

| Slice | Branch | Scope |
| --- | --- | --- |
| 01a | `torrent-v2-01-foundation` | Pinned reference clients, executed-scenario evidence, CI gate |
| 01b | `torrent-v2-01-contracts` | Control/state/capability and compatibility contracts |
| 01c | `torrent-v2-01-budgets` | Independent metadata/transfer partitions and aggregate ceiling |
| 01d | `torrent-v2-01-admission` | Retained metadata cache admission and lifecycle cleanup |
| 01e | Planned | Session state admission, profiles, deterministic harness, baseline |

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
