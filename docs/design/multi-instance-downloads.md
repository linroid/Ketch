# Multi-instance cooperative downloads

Status: **Proposal** — not implemented. Prepared 2026-10-03 against `2f274a45`, rechecked at
`d76c7460`.

Related plans: the [transfer plan](../plans/task-transfer.md) (#355, #361; Send to / Move to
carrying downloaded data) uses this proposal's identity (§7.1) and shares its prerequisite fixes
(§15), read path and `Sha256` (§9.6, §14.2) and `HttpResumeState` fields (§9.8). §5.1 says which
plan owns each shared piece; the transfer plan's §7.1 lists the shared prerequisites and its §14
orders both plans.

One Ketch instance, the task's **owner**, stays the only scheduler, writer and persister of a
download. Other paired instances, **helpers**, are stateless range relays: the owner asks a helper
for a byte range of a pinned representation; the helper fetches it from the origin through its own
network and IP and streams it back sealed; the owner verifies, throttles, writes and counts it
exactly like bytes from its own connections. Local and relayed connections become interchangeable
**lanes** that pull work from one interval ledger. A joining lane takes over the tail of the lane
projected to finish last by lowering one limit: no other lane is paused or cancelled, and the
victim at most ends its current request early. The same scheduler replaces
`SegmentedDownloadHelper` for every HTTP and FTP download, removing stop-the-world
resegmentation, sibling cancellation and whole-download retry for users who never pair a second
device. Cooperation only makes a download faster when a helper brings a different egress (another
WAN, public IP or cellular path); for the common laptop-plus-NAS-on-one-LAN case the answer stays
"Move to device", which the transfer plan makes carry the downloaded data. This document
authorizes no implementation by itself. Its claims about existing code were checked at
`d76c7460`, which includes #352 (approving a device found on the network), #353 (pause reasons,
queue positions, finish times, Auto connections), #354 (`c81f35dc`: HTTP content of unknown size,
§6.11) and #359 (`d76c7460`: partial files kept unless discarded, empty segments restarted,
§6.10, §15).

## 1 Summary and decision

| Question | Decision | Main reason |
| --- | --- | --- |
| Topology | Owner-pulled relay lanes; helpers hold no task, file or record | Keeps every single-writer invariant; no distributed state |
| Lease | The owner-opened stream plus the owner's 5 s heartbeat. Revoke = lower an in-memory limit or cancel a coroutine | Late bytes cannot arrive; no TTLs or epochs on forward lanes |
| Scheduler | `LaneScheduler` + `RangeLedger` in `library:core`, default for HTTP and FTP after a soak and a benchmark gate | Needed for instant join anyway; fixes today's batch defects for everyone |
| Integrity | Every ranged GET pinned (strong validator in `If-Range` and `If-Match`, checked on every 206, identity encoding); backends that disagree are aliased by comparing committed bytes; `FileChanged` only on positive evidence that the pin is gone from every path; audits; origin digests | Combining bytes from several egresses is only safe on one representation |
| Persistence | `TaskRecord`, `Segment` and the SQLite schema (version 5 since #353) unchanged; canonical segment export; snapshot → sync → persist, with a real `fsync` on Apple targets | No migration, no wire break, resume path unchanged |
| Trust | Explicit pairing by single-use helper link; P-256 ECDH plus the link's key, with key confirmation; ECDH per session (forward secrecy); per-message keys; AES-256-CTR + HMAC-SHA256 from platform crypto | No new dependency; access tokens never become key material |
| Exposure | Relay plane on its own listener (`[helping] port`, default 8643, LAN interfaces only); admin helper routes need the API token, or a loopback caller without forwarding headers | A phone can help without exposing its admin API |
| Default | Auto over helpers the user set to Auto; eligible tasks ≥ 32 MiB; same-LAN helpers default to "shares this connection" and are skipped; executables need an origin digest; a helper adding < 10% is retired | Most same-LAN helpers add nothing; measure instead of guessing |
| Names | "Helper" in every public API, config key, route and string; "relay" for the wire | "Peer" already means BitTorrent peers in `library:api` |
| Out of scope | Owner failover, multi-consumer dedup, BitTorrent relay, origins the owner itself cannot reach | §2, §17 |
| Task transfer | Moving a task with its data is task-transfer.md's; it uses this design's identity, and both share prerequisite fixes, sign-in classification, `Sha256` and one positional-read path, each owned per §5.1 | One definition of each |

## 2 Goals and non-goals

Goals:

1. Add or remove a helper for a running download without pausing or cancelling any other lane.
   A split's victim at most ends its current request early (waste ≤ its in-flight window); most
   splits land on a request boundary. First relayed byte within 2 LAN round trips plus origin
   connect and TTFB, hard bound 10 s, after which only the new lane fails and the split is undone.
2. Byte-exact output under join, leave, crash, partition, owner restart, origin change and
   backends that disagree, for tasks pinned by a strong validator. `LENIENT` tasks (no strong
   validator) keep today's checks and never use helpers. Residual: a rolling deploy whose two
   versions differ only outside the compared windows (§9.4).
3. Truthful progress: counted implies written; persisted implies synced to stable storage.
4. Task and global speed limits exact across instances; origin politeness per client IP.
5. No open relay. A helper receives only `User-Agent`, `Accept-Language` and an origin-only
   `Referer` unless the user trusts it with sign-ins. Relay traffic is confidential against passive
   observers with forward secrecy, and authenticated against active ones while the helper link
   stays secret.
6. Single-instance downloads become more robust and no slower (benchmark gate, §16).
7. Owners need no inbound port for forward relays, so an iPhone, or an Android device with its
   server off, can own a helped task.

Non-goals: automatic ownership migration or failover (a task waits for its owner; the user hands
it off with "Move to device", which restarts it on the target until the
[transfer plan](../plans/task-transfer.md) carries the partial file: its M1 for HTTP and FTP, M2
for torrents); several devices consuming one file; relaying BitTorrent, which cooperates natively;
a browser-local engine; NAT traversal beyond the reverse lane; origins the owner cannot reach
itself (resolve and pin always come from the owner's egress; use Send to); unknown-length or
range-less sources, since one un-ranged GET cannot be shared.

## 3 When cooperation helps and when it does not

For a task with egress groups `g` (the owner's own connection and each helper):

```
rate           = min( owner_ingress, task_limit, sum over g of goodput_g )
goodput_local  = min( wan_owner, origin(lanes_local) )
goodput_helper = min( downlink_h, uplink_h->owner, origin(lanes_h) )
```

`owner_ingress` is the owner's own link. Bytes from a remote helper also arrive through the owner's
WAN, so a remote helper helps only against per-IP or per-connection origin limits, never against a
saturated owner WAN; only a helper on the owner's LAN with its own WAN (a phone on cellular) adds
WAN capacity. `uplink_h->owner` is the helper's upload toward the owner: free on one LAN, the
binding term for a home daemon helping an office owner. `origin(n)` is what the origin grants one
client IP with `n` connections. Each relayed byte crosses the helper's link twice; over one Wi-Fi
access point the air carries it three times.

| Topology | Origin | Owner alone | With helper | Verdict |
| --- | --- | --- | --- | --- |
| Laptop + NAS, same LAN and 100 Mbit/s WAN | no per-IP cap | 12 MB/s | ≤ 12 MB/s | No gain; NAS defaults to "shares this connection". Move to the NAS if the laptop will sleep |
| Same | 2 MB/s per connection | 8 MB/s (4 connections) | 12 MB/s (6) | Same as raising local connections; Auto skips a helper that shares the connection |
| Same | 4 MB/s per IP | 4 MB/s | 4 MB/s | Same public IPv4: no gain |
| Home owner 100 Mbit/s + VPS | 4 MB/s per IP | 4 MB/s | 8 MB/s | 2×; VPS egress is billed per GB |
| Same | uncapped | 12 MB/s | 12 MB/s | The owner's WAN is the ceiling |
| Home owner 100 Mbit/s fiber + phone with "Help over mobile data" on | uncapped | 12 MB/s | 25–35 MB/s for the first 2 GiB a day (adjustable) | 2–3×, metered; with defaults no gain, since the phone relays over the same Wi-Fi WAN |
| Office owner 1 Gbit/s + home daemon on 500/50 Mbit/s | 10 MB/s per IP | 10 MB/s | ≈ 16 MB/s | The home uplink caps the helper at 6 MB/s; symmetric fiber reaches 20 MB/s |

Gain therefore needs a different egress (another public IP against per-IP limits, another WAN
against a saturated one). Same-NAT helpers only add load. One device with Wi-Fi and cellular is
already covered by `MultiNetworkHttpEngine`. IPv6 is unpredictable: devices behind one router often
hold distinct global addresses, but most limiters key on the /64. Users rarely know which case
they are in, so the owner measures: the gain controller (§6.9) retires a helper that does not
raise throughput and says why.

**Move to device** (`AppState.sendTo` with move, which today re-adds the request on the target and
starts over; with the [transfer plan](../plans/task-transfer.md)'s M1 it carries the partial file,
segments and validators, its §12.1) stays the answer when the owner will sleep or leave, when the
other device's connection is as good and the origin has no per-IP cap, or when the link carries
sign-ins the user will not share. When an always-on helper (CLI daemon, or a desktop with
`[server] autoStart`) is retired as "no gain", the inspector offers "Move to <device>" beside the
reason, but only when that helper is also a fleet device (its `RemoteConfig.instanceId` equals the
helper's `instanceId`): a helper pairing gives the app no access to the device, and Move to needs
it.

## 4 Terminology

Aligned with `docs/design/ux-redesign.md` ("Lanes & Fleet") and
`docs/development/translation-glossary.md`. User-facing text says "device"; code says "instance".
Public API, config, routes and copy say "helper"; "relay" names the wire. "Peer" stays reserved for
BitTorrent (`DownloadTask.setConnections` already means "peer connection limit" for torrents).
task-transfer.md calls its route through the app RELAY, which is not this wire, and a
destination's fsync and content root "seal"; here "sealed" means encrypted under session keys
(§10.2). Its admin token is this document's access token (`ServerConfig.apiToken`), and "owner"
means a task's owner in both (§18 lists the rename left).

| Term | Meaning | User-facing |
| --- | --- | --- |
| Fleet | Devices the app controls through controller pairing (`RemoteConfig`): a typed access code, a `ketch://pair` link or, since #352, a request the device's user allows (`RemotePairing`, `/api/pairing`, `PairingApprover`); each hands over the access token. Unchanged | Devices |
| Owner | Instance whose `Ketch.download` created the task. Only scheduler, writer and persister | the task's device |
| Helper | Paired instance that relays byte ranges for an owner | helper; "Help from NAS" |
| Helper pairing | Instance-to-instance trust with a per-pair key; separate from controller pairing | "Add helper" |
| Helper link | `ketch://helper` single-use invite, shown as a code and a link | helper link (never "pairing link") |
| Lane | One connection with a write head (ux-redesign §1.2). Local: owner → origin. Relayed: owner → helper → origin. Reverse: the helper dials the owner | connection; relayed lanes take the helper device's hue |
| Request | One HTTP request inside a lane's claim, at most max(16 MiB, 8 s of the lane's rate) | — |
| Egress group | Lanes the origin sees as one client: `local` (`local:<interfaceId>` per bound network), `helper:<instanceId>`; a helper that shares the connection joins `local` | — |
| Claim | Contiguous span a lane owns: `[start, limit)`, committed up to `cursor` | — |
| Ledger | The owner's interval map of done, claimed and free bytes (`RangeLedger`) | — |
| Pin | Representation identity every range must match: length, strong validator, aliases (`RepresentationPin`) | — |
| Alias | Another strong validator proved to name the same bytes by comparing committed windows | — |
| Drain | Graceful lane exit; the unfetched tail returns to the ledger | "Stopping" |
| Audit | Owner re-fetch of a random window of relayed bytes through another egress | — |
| Segment | Persisted and wire progress: a file region with a committed prefix. Type unchanged; it no longer means "one connection" | map block |

## 5 Architecture overview

```
             +------------------------ origin (HTTP / FTP) ------------------------+
             ^ GET Range, If-Range, If-Match (owner)     same, helper's egress     ^
             |                                                                     |
 +-----------+-------------------------+              +----------------------------+----+
 | OWNER  Ketch                        |              | HELPER  Ketch                   |
 |  LaneScheduler + RangeLedger        |              |  RelayServer :8643 (sealed)     |
 |   LocalLane   HttpRangeFetcher      | POST /relay  |   RelayService                  |
 |   RelayLane   RemoteRelayFetcher ---+------------->|    guarded engine, own network  |
 |                                  <--+-- frames ----|    helper limiter, caps         |
 |   ReverseLane ReverseRelayFetcher <-+- POST frames-|  ReverseLaneClient (dials out)  |
 |  admit > throttle > writeAt > commit|   (sealed)   +---------------------------------+
 |  FileAccessor: the only writer      |
 |  TaskRecord: the only persister     |
 +-------------------------------------+
```

- **Owner**: holds the `TaskRecord` (through `AtomicSaver`, as today), the only `FileAccessor`
  (so Android SAF `content://` output keeps working), the ledger and scheduler, all user-visible
  state, and the task's speed limit and helper policy.
- **Helper**: runs `RelayService`; has no `TaskRecord`, file, `DownloadQueue` slot or entry in
  `tasks`. Relays never appear as tasks; device presence reads `HelpingStatus` instead (§14.4), and
  speed totals are counted once, at the owner. Its only persistent state is its pairings. One
  helper serves several owners while running its own downloads.
- **Controller** (apps, web, `RemoteKetch`): manages the helpers of the instance it controls and
  watches lanes.

Bytes land only in the owner's output file. Helpers stage nothing on disk and hold at most one
64 KiB frame per stream.

| Lane kind | Owner ↔ helper | Helper → origin | Owner listens | Helper listens |
| --- | --- | --- | --- | --- |
| Local | — | — (owner → origin) | no | — |
| Relayed (forward) | owner dials | helper dials | no | yes |
| Reverse | helper dials | helper dials | yes | no |

Module direction: `library:core` ← `library:relay` (android, ios, jvm) ← `library:server` (JVM);
apps and the CLI wire them. Core never depends on relay, remote or server, and relay's crypto and
Ktor client stay out of core's `js(nodejs)` and `wasmWasi` targets.

### 5.1 Shared with task transfer

The [transfer plan](../plans/task-transfer.md) moves or copies a whole task with its data between
devices, driven by an app that holds both devices' access tokens; helpers fetch ranges for a task
that never leaves its owner. Each shared piece has one owner, and the other plan uses it instead
of defining its own; only the JVM locks stay separate. The transfer plan's §7.1 lists the shared
prerequisites and its §14 orders both plans:

| Piece | Owner | Use in the other plan |
| --- | --- | --- |
| `instanceId` in `device.json` beside the task database, `Ketch(instanceId)`, `KetchStatus.instanceId`, `RemoteConfig.instanceId` | This design, §7.1 (PR2 is transfer's F7) | Transfers refuse an equal id (`same_instance`) |
| One process per role over a shared `ketch.db` (JVM) | Each plan its own lock: `device.json` for relay sessions (§14.5), `<db>.transfer-lock` for transfer recovery, freeze and release (its §7.9) | Neither lock implies the other |
| Reading a task's output | Shared F14: core `Sha256`, core `fileIdentity`, one positional-read path, before PR3 | `FileAccessor.readAt` for a running execution here (§9.4, §9.6); transfer's `FileReader` for export (its §7.4) |
| Sign-in classification | `isSensitiveHeader` (its §5.5, F11), plus §11's signed-URL keys and fragment masking | Forwarding allowlist and `SIGN_INS` here (§11) |
| Snapshot → sync → persist, durable `flush()` | PR1 item 2, transfer's F3 | Imports sync through the same `FileAccessor.flush()` (its §7.7) |
| Segment export, `HttpResumeState` | This design (§6.10, §9.8); #359 shipped empty segments as no progress and kept partial files (transfer's F2, F1) | Manifests carry the segments, `pinMode` and `aliases`, never provenance or pending audits (its §5.1-5.2) |
| `KetchServer` route sets | Transfer's M1 adds `routes: Set<RouteGroup>` (its §4.5); PR8 adds `HELPERS` | `BrowserExtensionServer` keeps the default and serves neither |
| Constant-time compare, blank token as none, server `coerceInputValues` | PR1 item 8, transfer's F10b | — |
| `PauseReason.Transferring` | Transfer (its K12, §7.3) | A frozen task's lanes stop as on any pause (§8.6) |

A task frozen for a transfer is paused for `Transferring`: its lanes stop as in §8.6, and
`setHelpers` and `stopHelp` persist like speed and priority changes. `DownloadRequest.helpers` names
this device's pairings, so neither a transfer nor `forDevice()` carries it as is: both pass it
through `HelperPolicy.forOtherDevice()`, which never widens it. `Off` stays `Off`; `Auto` keeps
`signIns` and drops `added` and `excluded`; `Only` becomes `Off`, since its helpers are pairings of
the source device. A task the user kept from helpers, or whose sign-ins it withheld, keeps that
choice on the destination; task-transfer.md resets the field instead (§18). Relayed bytes travel
only per §9.6. In M1 transfer chunks run on the admin plane under access tokens; its M3 runs
delegated jobs over this design's pairings and sealed sessions (§7.3).

## 6 Range scheduler and lanes

### 6.1 Ledger

`RangeLedger` (internal, `core.lane`, pure, no I/O) is guarded by one `Mutex` owned by
`LaneScheduler`. It holds `done` (sorted disjoint committed intervals, each tagged with the egress
group that wrote it), `claims` (`claimId → Claim(laneId, start, cursor, admittedEnd, limit)`;
`cursor` is the committed prefix, `admittedEnd ≤ cursor + one chunk`, `limit` is exclusive) and
`free` (everything neither done nor in any `[cursor, limit)`). A limit changes only through
`setLimit(claim, x) = max(admittedEnd, alignUp64K(min(x, limit)))`, used by split, drain, revoke
and leave, and through the un-split of §6.3. Invariants, asserted by the simulator (§16): no byte
is in two claims; a limit never drops below its `admittedEnd`; `commit` requires `offset ==
cursor` and `offset + len ≤ limit`; `done` only grows except on audit invalidation; claim ids are
never reused within an execution.

### 6.2 Sizes

| Unit | Value | Why |
| --- | --- | --- |
| Granule (split alignment) | 64 KiB | Equals the relay frame and the `TokenBucket` burst |
| Claim | variable, uncapped | Lowering a limit ends at most the current request |
| Request | max(16 MiB, 8 s × lane rate) | Keep-alive reuses the connection, a TTFB costs ≤ ~1% of a request, and a split at a request end wastes nothing |
| Minimum split tail | max(4 MiB, r_thief × s_thief) | A split must move at least the in-flight window it may waste (socket buffers ≈ 1–4 MiB) plus the thief's setup time worth of bytes |
| Endgame minimum split | max(512 KiB, r_thief × s_thief); waived for a slow victim (§6.5) | At the tail finishing sooner beats overhead; still ≥ 8 frames |
| Relay frame | 64 KiB | One sealing unit; bounds helper memory per stream |
| Local read | 8 KiB (`KtorHttpEngine` buffer) | Unchanged |
| Rate estimate | EWMA, 5 s half-life; task median for the first 1 s | Smooths TCP slow start without hiding a stall |
| Setup time s | measured TTFB; default 0.3 s local, 1.0 s relayed | Weighs a fresh request against continuing the victim |

All sizes and timers live in an internal `LaneTuning` passed through the `@KetchInternalApi`
constructor, so tests can shrink them.

### 6.3 Claiming, splitting, stealing

Seeding: a fresh download splits `free` evenly into `effectiveConnections()` spans with today's
`SegmentCalculator.calculateSegments` arithmetic. A resume restores `done` from the persisted
segment prefixes and `free` from the gaps, and reseeds evenly when there are fewer gaps than lanes.

A lane that needs work calls `claim()`:

1. If `free` is non-empty, take the lowest-offset free interval whole, so gaps close front to back
   and the export stays short.
2. Otherwise pick the victim `v` with the largest projected finish `T_v = R_v / r_v`, where
   `R_v = limit − admittedEnd`. With thief rate `r_t` (its EWMA, else its group's EWMA for this
   origin host, else the task median) and setup time `s_t`, split where both finish together:

   ```
   p = alignUp64K( admittedEnd_v + r_v × (R_v + r_t × s_t) / (r_v + r_t) )
   ```

   If the victim's current request ends at `e` with `p < e` and `e − p ≤ 0.25 × (limit_v − p)`,
   use `p := e`, so the split costs nothing on the wire. Accept only if the tail `limit_v − p` is
   at least the minimum split, the split helps (`T_v > tail / r_t + s_t`), `v` was not a victim
   in the last 2 s, and the thief is not a helper whose lane failed its first frame in the last
   60 s. Then `setLimit(v, p)` and the thief claims `[p, oldLimit)`.
3. Otherwise park, holding no connection. Parked lanes wake on every ledger mutation, every 1 s,
   and when a victim cooldown ends, since slowing victims and diverging rates mutate nothing.

A victim whose new limit falls inside its current request keeps reading until its cursor reaches
the limit and then ends that request: the sink returns false, the engine closes the response
without an error (the connection is not reused) and the lane claims again with no backoff
(`Drained`). If the thief's claim is released with nothing committed while the victim still holds
the adjacent limit and its open request covers the range, the victim's limit is restored under the
mutex (un-split), so a failed join costs nothing. Lowering the local connection count drains the
slowest local lane (§8.3) instead of hitting the gap-count floor of `SegmentCalculator.resegment`
(`targetActive = newConnections.coerceAtLeast(merged.size)`).

### 6.4 Admit and commit

Writes run outside the mutex: `PathFileAccessor` serializes every write through
`limitedParallelism(1)`, so writing under the ledger lock would stall every claim behind queued
writes.

```kotlin
// RangeSink.accept, per received chunk; false ends the response. `data` is valid only until return.
val allowed = ledger.admit(claim, offset, len) // mutex: 0 if revoked, else ≤ limit - offset
if (allowed == 0) return false                  // engine closes the response, no error
context.throttle(allowed)                       // task limiter, then global limiter
fileAccessor.writeAt(offset, if (allowed == len) data else data.copyOf(allowed))
ledger.commit(claim, offset, allowed)           // mutex: offset == cursor, end ≤ limit
return allowed == len
```

`release(claim)` runs only in the lane's own `finally`, after any in-flight `writeAt` returned,
and moves `[cursor, limit)` back to `free`. The scheduler never releases on a lane's behalf: a
stall revoke calls `setLimit(claim, admittedEnd)` and cancels the lane, which releases as it
unwinds. Two writers therefore never overlap an offset.

### 6.5 Endgame and stragglers

No duplicate or hedged requests: every byte has at most one lane, and the origin sees each byte
requested once plus bounded truncation waste. Stall detection is per lane kind:

| Lane | Rule | Result |
| --- | --- | --- |
| Local | No DATA for 15 s (today's default client never times out) | Revoke, Retryable |
| Relayed, helper liveness | No frame of any kind, KEEPALIVE included, for 15 s | Retryable (`helper_unresponsive`), helper Suspect |
| Relayed, origin stall | KEEPALIVEs but no DATA for 30 s | Released (`origin_stalled`), helper stays Healthy |
| Endgame (every claim has < 4 MiB left) | DATA gap above clamp(4 × the lane's p95 gap, 3 s local / 6 s relayed, 15 s local / 30 s relayed), counted only after the lane's first DATA; before it the setup bounds of §10.4 apply | Revoke, Retryable |

Time spent waiting in the throttle never counts. A lane revoked twice in 60 s is quarantined for
30 s.

A trickling lane is not stalled, so the endgame also takes over slow victims: when a thief would
park, the minimum split is waived down to one granule, and if
`T_v > 3 × (R_v / r_t + s_t)` the victim is cut at its admitted end (`setLimit(v, admittedEnd)`)
and the thief takes the whole remainder. Waste is one chunk plus the victim's in-flight window.
Example: a 2 KB/s lane holding the last 400 KiB, with an idle 5 MB/s thief, is cut at once instead
of finishing in 200 s.

### 6.6 Lane counts

- Local lanes: `DownloadContext.effectiveConnections()` as #353 defines it: `maxConnections` when
  positive, else (Auto, 0) the `maxConnectionsPerDownload` of the run's `config` snapshot.
  `maxConnections` starts at the persisted `DownloadRequest.connections` and only `setConnections`
  writes it. The local group's throttle cap (§6.7) bounds the target; with several bound networks
  the target is dealt round-robin over their groups. Auto is this device's default for its own
  lanes: helper lanes neither count toward it nor change it, and a helper that shares the
  connection joins the local group's cap, which the owner's own lanes take first.
- Per helper: `min(lanesPerHelper = 2, helper freeSlots, maxStreamsPerOwner)`; FTP helpers 1.
- Per task: helper lanes fill up to `maxTotalLanes = 16` minus the local lanes; the local target is
  never cut for helpers. More parallel requests from one download start to look like abuse to
  origins, and the Connections tab's densest mode covers 17–32, so 16 leaves room.
- Faster lanes steal more by construction; nothing else allocates bandwidth.

### 6.7 Egress groups and lane errors

Every lane belongs to one `EgressGroup`; 429, 503 and 403 are about the group, not the task. With
`MultiNetworkHttpEngine` each bound network is its own group (`local:<interfaceId>`), because
networks can reach different origin edges.

| Class | Triggers | Action |
| --- | --- | --- |
| Retryable | network error, 5xx except 503, short read, helper EOF, helper unresponsive | Release, per-lane backoff 1 s × 2^n (cap 30 s, ±20% jitter), claim again; n resets on commit |
| Throttled | 429; 503 with `Retry-After`; FTP 421 | Group paused until `Retry-After` (≤ 120 s) or 5 s × 2^n; group cap halves (min 1); +1 lane per 60 s clean. The local group's cap starts at the lower of the local target and `RateLimit-Remaining` (`HttpDownloadSource.applyRateLimit`) and never writes `DownloadContext.maxConnections`, so an Auto task stays Auto and `setConnections` cannot lift a back-off. Replaces today's task-wide halving in `DownloadExecution.reduceConnections` (§6.11) |
| Excluded | origin 401/403/407/410/451, policy denied, different content, integrity, no gain, busy | The group leaves this task, permanently or until `until` |
| Pin evidence | 200 or 412 to a conditional request, 206 with another strong validator, another complete length, 416 | `PinAdjudicator` (§9.4) |
| Drained | DRAIN frame, owner retirement, rebalance, request ended at a lowered limit | Exit with no backoff and no health penalty |
| Fatal | `Disk`, confirmed `FileChanged`, local "server ignored Range" (`Unsupported`) | Task fails, as today |

A helper's error is never the task's error. It shows on its `LaneInfo` and in the exclusion list;
the task error is always the owner's own. The local group being Excluded is not fatal while
another group progresses and an audit path remains (§9.6).

### 6.8 Task-level failure

The task fails on:

1. A Fatal error.
2. No eligible group: every group Excluded with no `until`, or with `until` more than 10 min away.
   The error is the owner's own last error (for example the local 403).
3. A local failure streak: local-group lane failures with no committed byte from any group in
   between reach `(retryCount + 1) × max(1, local lane target)` (defaults 4 × 4 = 16, which on a
   dead origin fails after about the same 7 s of backoff as today's 1 + 2 + 4 s). A group-wide
   Throttled pause counts as one round of `local lane target` failures, so a persistent 429 fails
   after `retryCount + 1` pauses, as today's retries do. Helper failures never count; they feed
   exclusion and health.
4. A watchdog: no committed byte for `max(60 s, retryCount × 30 s)` of active time. The clock
   stops while a speed limit throttles the task and while every eligible group waits on a
   server-requested pause (`Retry-After`, `helper_busy`). A solo download told to wait 120 s
   therefore waits, as `downloadWithRetry` does today. A group parked on `helper_busy` 3 times in
   a row without a commit is Excluded (`BUSY`) for 10 min.
5. No audit path left for relayed bytes (§9.6).

`DownloadConfig.retryCount` keeps its meaning, "retries before giving up". For scheduler-backed
executions (HTTP, FTP) `downloadWithRetry` wraps only the pre-scheduler work (resolve, resume
probe, local file check). Whole-source retry stays for self-managed sources, which BitTorrent relies
on for its transient `KetchError.Network` failures, and for unknown-size streams
(`HttpDownloadSource.downloadUnknownSize`, #354), whose every attempt empties the file and starts
again at byte zero.

### 6.9 Gain controller

Auto must not make downloads slower, so the owner measures each helper group it chose:

- Baseline `B`: task goodput over the 10 s before the group's first lane (the check waits until
  the task has 10 s of history). After 3 s of warm-up, measure goodput `G` over 15 s.
- If `G − B < max(0.1 × B, 256 KiB/s)`, drain the group (`NO_GAIN`) and do not offer that helper
  for the same origin host for 30 min: "Not faster with NAS: it likely shares this device's
  internet connection."
- A group delivering under 5% of task goodput for 30 s is drained (`SLOW`).
- While a speed limit binds (throttle waits > 20% of wall time over 10 s), no helper is added and
  relayed groups drain (`LIMITED`: "Helpers aren't used while a speed limit caps this
  download"). A capped download gains nothing from helpers, and relayed lanes trickling under a
  limit only refill the helper's socket buffers from the origin.
- A local Throttled or Excluded event, or a 30% drop in task rate, lifts the cooldown.
- Helpers the user picked for the task (`HelperPolicy.Only`, or `Auto.added`) are measured and
  annotated, never retired by this controller.

Total throughput cannot see the costliest pointless case, a same-LAN helper against an origin that
caps each connection: its lanes add throughput that more local connections would add too. So a
helper reached at a private address inside one of the owner's interface subnets, or reporting the
owner's own public address, is paired as "Shares this connection" (it joins `local`), and Auto
skips it unless the user picks it: "NAS shares this Mac's internet connection. Add connections
instead."

### 6.10 Progress, speed and the segment export

- The scheduler publishes `onProgress(committed, total)` and the export into `context.segments`
  every `progressIntervalMs`, and `TaskLanes` at most once per second.
- Speed is the sum of lane EWMAs passed through the existing `DownloadContext.reportedSpeed`
  override, which also removes today's post-resume spike. Time left needs no new field: the apps
  divide the remaining bytes by `DownloadProgress.bytesPerSecond` (`RowContent.timeLeft`), relayed
  bytes included, so the estimate jumps only when the gain controller adds or retires a group.
  `Completed.completedAt` and `downloadTime` (#353) are stamped by the owner's
  `DownloadExecution`; helpers report no times.
- Export: one `Segment` per claim (`start = claim.start`, `end = limit − 1`, `downloadedBytes` =
  the length of `done` contiguous from `claim.start`) plus one per maximal "done prefix + free
  gap" run outside claims, re-indexed so `index == position`. Typically at most `2L + 2` entries;
  audit invalidation and fragmented legacy lists can raise it, so the export is capped at 64
  entries by merging adjacent runs and dropping the later run's done bytes from the persisted
  prefix (refetched on resume, never over-counted). A claim keeps its `start` for life.
- "Unplanned" needs no flag: since #359 a file of known size that Ketch writes keeps no saved
  segments until its source publishes some. `DownloadExecution.runDownload`'s `savedSegments` saves
  a `[]` snapshot with `total > 0` and no `managesOwnFileIo` as `segments = null`, in the periodic
  and the final save; `DownloadCoordinator.resume` returns `false` for `null` and for a legacy `[]`
  of known size, so `DownloadQueue.startTask` starts the task again; and `HttpDownloadSource.resume`
  and `FtpDownloadSource.resume` split a `[]` resume of known size into fresh segments.
  `DownloadContext.segments`, `TaskHandle.mutableSegments` and `DownloadTask.segments` stay non-null
  lists. The scheduler runs inside `runDownload`, so `savedSegments` applies to its export
  unchanged, and the export keeps #359's guarantees: the seed is published into `context.segments`
  before `preallocate` and the first `writeAt`, as both sources publish theirs today, so every save
  after a write persists a layout covering it; from the seed until completion the export covers
  `[0, totalBytes)` and is never `[]`; restoring `[]` with `totalBytes > 0` seeds like a fresh
  download, never as done; a failed final sync zeroes the export's prefixes and keeps its layout
  (`discardProgress`, §9.7); and the scheduler never deletes the file (§8.6). Content of unknown
  size keeps `[]`, saved as is and restarted in place by `HttpDownloadSource.resume`
  (`downloadUnknownSize`, #354), and a torrent's `[]` stays `[]`; neither enters the scheduler.
- Segments are progress, not connections: from PR5 the apps draw lanes from `LaneInfo` (§14.4),
  so free runs never show as stalled connections in `ConnectionsTab` or `LaneStrip`.

### 6.11 Relation to today's code

| Today (`d76c7460`) | With `LaneScheduler` |
| --- | --- |
| `SegmentedDownloadHelper.downloadAll` launches every incomplete segment at once under `coroutineScope` + `awaitAll`; one failure cancels all | Lanes are `supervisorScope` children; failures are values interpreted per §6.7 |
| A `maxConnections` value whose effective count differs from the baseline the source sized from (`requestedConnections`, #353) cancels the whole batch and resegments (`SegmentedDownloadHelper.downloadBatch`, `pendingResegment`) | Adding a lane splits one victim; removing one drains it, until the local count matches `effectiveConnections()`; with no batch there is no baseline |
| `SegmentCalculator.resegment` grows the list and floors connections at the gap count | Bounded export; any lane count is honoured |
| `DownloadExecution.downloadWithRetry` re-runs the whole source with one budget | Per-lane retry; §6.8 decides task failure |
| 429 writes half of `effectiveConnections()` (or `RateLimit-Remaining`) into `ctx.maxConnections` (`DownloadExecution.reduceConnections`), not persisted: an Auto task runs an explicit count for the rest of the run, and any `setConnections` lifts the back-off | Per-group AIMD cap; `maxConnections` stays the user's request |
| `SegmentDownloader`: one unconditional request per segment | `HttpRangeFetcher`: conditional requests of bounded span per claim |
| FTP cuts segments with `SegmentCompleteException` (`FtpDownloadSource.downloadSegment`) | `FtpRangeFetcher` re-reads `admit()` per chunk and cuts at the live limit |
| `DownloadContext.maxConnections` (0 = Auto since #353) | Unchanged: the requested local lane count, resolved by `effectiveConnections()`; `TorrentDownloadSource` keeps collecting it as its peer limit |
| `DownloadContext.pendingResegment` | Deleted with the legacy path |
| `DownloadExecution.cleanupAfterExecution` deletes the file only when the coordinator called `discardPartialFile` (#359: `cancel()`, `remove(deleteFiles = true)`) | Unchanged; it runs after every lane has released, and no lane, helper exit or task failure deletes |

Rollout: `Ketch(laneMode = LaneMode.LEGACY | LANES)`, a constructor parameter behind
`@KetchInternalApi`, never a `@Serializable DownloadConfig` field. `LANES` enables pinning from PR3
and the scheduler from PR5; the integration suite runs in both modes until the default flips and
the legacy path is deleted (PR7). BitTorrent is untouched: it manages its own file I/O
(`TorrentDownloadSource.managesOwnFileIo`) and its segments are synthetic per-file progress. Tasks
with `totalBytes < 0` never enter `LaneScheduler`: `HttpDownloadSource.downloadUnknownSize` (#354,
`c81f35dc`) streams them from byte zero over one un-ranged `HttpEngine.download` with
`segments = []`, emptying the file first, so every retry or resume restarts it (resume without
probing the server), and `DownloadExecution` then records the file's size as `totalBytes`.
`DownloadExecution.executeFresh` still fails an unknown size with `SourceError` before a
`managesOwnFileIo` source runs, and FTP rejects one itself. The size comes from the resolve HEAD
alone (`RangeSupportDetector`). That path stays as it is: whole-source retry (§6.8), `[]` saved
as is (#359's `savedSegments` leaves it alone, §6.10), no pin and no helpers (`UNKNOWN_SIZE`,
§14.1).

## 7 Membership

### 7.1 Identity

`instanceId` is a UUIDv4 generated on first start and kept in `device.json` (mode 0600, written
like `SingleInstance`'s files) next to the task database: `defaultDbPath()`'s directory on the CLI,
the app data directory elsewhere. It does not go in `config.toml`, which the CLI only ever loads
and users may keep read-only. It is passed as `Ketch(instanceId = …)` and exposed as
`KetchStatus.instanceId` (additive, default `null`) on the admin API, never in mDNS. Helpers need
it, so an engine built without one keeps `Ketch.helpers` null, as it reports no `transfer`
(task-transfer.md §4.5). The desktop app, `ketch server` and `ketch mcp` share `ketch.db` in
`defaultConfigDir()`, and so one `device.json` and one id; only the holder of its lock runs relay
sessions (§14.5). The apps persist `RemoteConfig.instanceId` learned on connect (the pattern
`RemoteConfig.os` follows since #345), refuse to add their own server as a device by it, and key
`KetchColors.deviceHue` by it from then on, falling back to `InstanceEntry.deviceId` until it is
known, so one machine keeps one hue across DHCP changes. The Add device sheet hides its own
advertisement by a per-process random `boot` TXT value instead of the port-and-name heuristic in
`ui/connect/AddDeviceSheet.kt`.

`device.json` also holds the pairings (§7.3). On Android it is excluded from backup and
device-to-device transfer (`dataExtractionRules`, `fullBackupContent`; the manifest sets
`allowBackup="true"` today), on iOS from backups (`isExcludedFromBackup`), and on Android and iOS
pairing keys are wrapped by an Android Keystore or Keychain (`ThisDeviceOnly`) key. A copy
restored elsewhere fails to unwrap; the device then generates a new `instanceId` and drops its
pairings instead of cloning another device's identity. On the JVM nothing marks a copy: a config
folder copied to another machine keeps its id and pairings until one side deletes `device.json`.
The [transfer plan](../plans/task-transfer.md) uses this identity as it is (its F7 is PR2) and
adds only a JVM lock for transfers over a shared `ketch.db` and refusal of transfers to itself.

### 7.2 Discovery

Discovery never admits a helper and is never on the join path. Since #352 a device found with
`pairing=1` on `_ketch._tcp` can ask for controller pairing (`/api/pairing`), which hands over the
access token; helper pairing has no such route (§17, open question 11). `RelayServer` registers
`_ketch-relay._tcp` with TXT `pv` and `name` (no identifier; `instanceId` appears only in admin API
and relay-session responses). `MdnsDiscoverer` gains `browse(): Flow` over dns-sd-kt's Discovered,
Resolved and Removed events. Apps browse only while an Add helper sheet is open, or while a paired
helper is Down and an eligible task is active (a re-announce then triggers an immediate `hello`).
Daemons do not browse; pairings carry endpoints.

### 7.3 Admission and trust

Admission is always explicit, through a **helper link**:

```
ketch://helper?host=192.168.1.20&port=8643&name=NAS&role=helper#id=<inviteId>&key=<128-bit>
```

Single use, valid 15 min, at most 4 pending, burned after 3 failed attempts, shown as a code and a
link. The secrets sit in the fragment, as `ketch://pair` keeps its token (`util/PairingLink.kt`),
and `host` comes from the same `pairingAddresses`, which leaves out VM bridges, VPN tunnels and
mobile data unless no other interface has an address (a Hyper-V external switch moves the LAN
address onto a virtual adapter).
Either side can create one, provided its relay listener runs (helping on for the helper role,
`acceptReverse` for the owner role):

| Created on | `role` | Opened on | Who dials whom | Typical use |
| --- | --- | --- | --- | --- |
| The helper (Sharing page, "Help other devices") | `helper` | The owner (paste, scan) | Owner → helper's listener | Desktop or daemon helping a phone or laptop |
| The owner (Devices page, "Add helper") | `owner` | The helper's camera | Helper → owner's listener | A phone helping a desktop: the desktop shows the code, the phone scans it. The only route for an iOS helper, which cannot listen |
| Either, by a controller holding that device's access token | either | The other device | as above | "Let NAS help This Mac", "Help This Mac with this phone": one tap |

In the controller route, the app calls the creating device's admin `POST /api/helpers/invites`
with the bearer it already holds, then gives the link to the other device in-process or through
that device's admin `POST /api/helpers/pair`. The bearer may have been typed, read from a
`ketch://pair` link or, since #352, handed over by `GET /api/pairing/{id}` after that device's user
allowed a request. Helper pairing never moves a token: tokens are used only against their own
admin API, never become key material, and `pair()` refuses `ketch://pair` links. The link crosses
an admin plane that already carries the bearer in cleartext, in every request and in #352's
answer, so this adds no exposure.

The OS-level intake that handles `ketch://pair` also accepts `ketch://helper`: the Android intent
filter, today limited to `android:host="pair"`, gains `helper`, and `isPairingLink`
(`state/IncomingDownloads.kt`), through which `IncomingDownloads` and the desktop's `OpenedFiles`
route links from Android, iOS `onOpenURL` and launch arguments, gains a `ketch://helper` sibling.
A link that arrives that way opens a confirmation sheet naming the device and endpoint, warning
when the endpoint is not on a local network, and stating "<Helper> will see the links it helps
with and could change their content."

Pairing runs on the creator's (C) relay listener; the dialer is D. `LP(…)` prefixes every field
with its u32 length; `e` are ephemeral P-256 keys as uncompressed points, rejected unless on the
curve:

```
m1  D -> POST /api/relay/v1/pair          {v:1, inviteId, nameD, eD, nD}
m2  C -> {nameC, eC, nC, sealC(idC, endpointsC), confC}
m3  D -> POST /api/relay/v1/pair/confirm  {inviteId, sealD(idD, endpointsD), confD}  -> 204
th     = SHA-256( LP("ketch-pair-v1", m1 fields, nameC, eC, nC) )
kC‖kD‖pairKey = HKDF-SHA256( ikm = ECDH(eD, eC) ‖ inviteKey, salt = th, info = "ketch-pair-v1" )
confC  = HMAC(kC, th)    confD = HMAC(kD, th)    sas = 6 digits of HMAC(pairKey, "sas")
sealX  = AES-256-CTR + HMAC-SHA256 tag under HKDF(kX, info = "ketch-pair-seal"), tag checked first
```

A passive observer learns nothing, since the keys depend on the ECDH secret and identifiers and
endpoints travel sealed; an active attacker needs the invite key. C commits the pair only after
verifying m3. When the creator's user copied or shared the link instead of only showing the code,
both devices show the 6-digit `sas` and C commits only after its user confirms that they match.
`/pair` limits: 3 failures burn the invite, 5 failures per minute per source address, and 20
failures per minute in total lock the route for 10 min.

Each pairing has flags on both sides. Owner side: `helpsThisDevice` = `PICKED` (only tasks the user
picks for it) until the user sets `AUTO` on the Devices page; `shareSignIns` off, asked once with
the copy rules of the Send to warning (`credentialWarningText`); `sharesConnection` per §6.9.
Helper side: `allowHelpFor` on. Copy on the helper: "MacBook can download through this device's
internet connection and IP address. This device sees the links it helps with, and links can
themselves grant access." On the owner: "NAS will see the links it helps with and could change
their content; Ketch checks samples of its bytes against the site."

Revocation is per pairing: **Unpair** (drain, delete the key) or **Block** (immediate, delete the
key, refuse that key's fingerprint and endpoint for 24 h). The Block copy adds: "Devices that were
given this device's access code, or that you allowed to connect, can invite it again; choose New
code in Settings › Sharing to stop that." A failed audit Blocks the helper (§9.6).

The [transfer plan](../plans/task-transfer.md)'s M3 (its §13.2) runs delegated transfers over these
pairings and sessions: it adds an `allowTransfers` flag beside `allowHelpFor`, refuses a pairing
between equal `instanceId`s, keeps the relay listener (§10.1) running for them with helping off,
and gives transfer requests caps of their own instead of §11's.

### 7.4 Session and capability handshake

```
D -> POST /api/relay/v1/hello   Ketch-Relay: v1 hint=<HMAC(pairKey, LP("hint", ts))[0..8]> ts=<s>
     {eD, nD, protocols:[1], wants:["relay"], mac = HMAC(pairKey, T1)}
     T1 = LP("ketch-hello-v1", hint, ts, eD, nD, protocols, wants)
C -> {eC, nC, sessionId, instanceId, name, platform, protocols, features, sourceTypes, endpoints,
      relay:{enabled, draining, maxStreams, freeSlots, maxStreamsPerOwner, maxBytesPerSecond,
      metered}, mac = HMAC(kconf, T2)}                T2 = SHA-256(T1 ‖ LP(response fields))
kconf‖kD2C‖kC2D = HKDF-SHA256( ECDH(eD, eC) ‖ pairKey, salt = T2, info = "ketch-session-v1" )
```

C finds the pairing by checking the hint against each pair key (at most 64), so no identifier
crosses the network in cleartext. `|ts − now| ≤ 300 s` with a 128-entry nonce cache per pairing.
Per-pairing limits (6 `hello` per minute) count only after a valid MAC; failures are limited per
source address. Either side may dial `hello` (reverse lanes).

Later requests carry `Ketch-Relay: v1 s=<sessionId> c=<counter> mac=HMAC(kD2C.mac, LP("req",
method, path, query, c, fence headers, SHA-256(body)))`, checked against a 1024-entry sliding
replay window per session (as in RFC 4303). Every non-streaming response carries
`Ketch-Relay-Mac: HMAC(kC2D.mac, LP("res", c, status, SHA-256(body)))`, so `status`, `goodbye` and
errors cannot be forged. Sessions expire after 10 min idle. Session keys come from ephemeral
ECDH: a later leak of a pair key does not decrypt recorded sessions. Clock skew affects only
`hello` freshness, never leases.

### 7.5 Health

`Healthy → Suspect` on the first unresponsive lane or connect failure; `Suspect → Down` after 3
failed status polls 10 s apart or 2 unresponsive lanes within 60 s, which retires that helper's
lanes in every task. Recovery runs `hello` with backoff 1 s → 30 s, or at once on an mDNS
re-announce. A drained or politely closed lane never changes health.

## 8 Join and leave flows

### 8.1 Instant join (helper already paired)

```
owner HelperManager            owner LaneScheduler                 helper             origin
  hello ok / user adds helper -> LaneSpecs re-emitted
                                 claim(): setLimit(victim, p)      (µs)
                                 victim ends its request at p
                                 only if p is inside it
                                 POST /relay {sealed req} -------> check MAC, caps
                                                                   GET Range, If-Range,
                                                                   If-Match ------------>
                                                          <------- HEAD frame <------ 206
                                 verify, admit, throttle, <------- DATA frames
                                 writeAt, commit
```

Bound: no lane is paused or cancelled; the victim's current request ends early only when `p`
falls inside it (waste ≤ its in-flight window, then one new request for its next claim). First
committed byte after 2 LAN RTTs + origin connect + TTFB, typically 100–600 ms; after a 10 s
first-frame timeout only the new lane fails and the split is undone (§6.3). If no split passes the
floor and gain tests (a nearly finished file), the new lane parks.

### 8.2 Graceful leave (helper-initiated)

Triggers: "Help other devices" off, daemon SIGTERM, Android `KetchService.onTimeout`, iOS
resign-active, or Stop on the helper's card for one owner.

```
helper: draining = true; new relays -> 503 draining; DRAIN{graceMs: 5000} on every stream
owner : setLimit(lane, cursor + rate × 5 s)
        tail [newLimit, oldLimit) -> free at once; parked lanes claim or split it
lane  : finishes its short remainder, exits Drained (no backoff, no Suspect)
helper: force-closes leftovers at graceMs + 2 s
```

Lost: nothing committed. Wasted: frames in flight past the new limit.

### 8.3 Owner-initiated removal

| Scope | Mode | Action | Bound |
| --- | --- | --- | --- |
| One task (`DownloadTask.stopHelp`) | Drain (default; also rebalancing and lower connection counts) | `setLimit(lane, cursor + 2 s of rate)`; the lane finishes | ≤ 2 s; nothing wasted beyond in-flight |
| One task | Now | `setLimit(lane, admittedEnd)`, cancel the lane: the call closes the socket and the helper cancels its origin GET | Owner instant; helper stops within one frame; ≤ 64 KiB refetched |
| Every task (`unpair`) | Drain, Now or Block | As above in each task; Block also deletes the key, sends a courtesy `goodbye`, later requests get 401 | As above |

In `Auto`, stopping a helper for one task adds it to `excluded`; in `Only`, it removes it. The
gain controller and errors use the same drain.

### 8.4 Abrupt loss

```
helper crash / reboot:    RST or EOF -> lane Retryable(helper_eof) at once -> release
silent partition:         no frame for 15 s -> Retryable(helper_unresponsive), helper Suspect
origin stall at helper:   KEEPALIVEs, no DATA for 30 s -> release (origin_stalled), helper Healthy
owner gone (helper side): stream missing from two 5 s heartbeats, or none for 20 s -> abort GET
```

Redispatch is always "remainder to free", claimed whole or split by the fastest idle lane. A
partitioned helper that heals has nothing to deliver into: the socket's only reader is gone.

### 8.5 Restarts

```
owner restart:  loadTasks -> DOWNLOADING record -> Queued, preferResume (Ketch.createTaskFromRecord)
                resume: conditional pin probe (§9.4) -> ledger.restore(segments) -> new exec nonce
                HelperManager: hello -> LaneSpecs -> relayed lanes claim
                helpers: a new hello aborts that owner's older sessions' streams; a relay request
                whose fence.exec differs from open streams of the same (owner, taskKey) aborts them
                first; old reverse pushes -> 409 fence_stale
helper restart: nothing to restore; owners see EOF and back off; a session-unknown 401 forces
                hello; lanes return when Healthy
```

Aborted streams never count against `maxStreamsPerOwner` or the per-origin-host cap, so a 2-slot
phone helper takes a restarted or resumed owner's lanes at once instead of answering 429. An owner
crash refetches committed bytes after the last checkpoint (≤ `saveIntervalMs`, 5 s) as idempotent
overwrites of the same pinned representation; a graceful quit loses nothing (`Ketch.shutdown`,
§15). A helper restart costs in-flight bytes only.

### 8.6 Pause, resume, cancel and remove with helpers active

- **Pause, preemption, close**: every stop that keeps progress cancels the execution:
  `DownloadCoordinator.pause` for `PauseReason.User` and for `Preempted(byTaskId)` when an URGENT
  task takes the slot (`DownloadQueue.tryPreemptAndStart`; the record stays `QUEUED`), and
  `DownloadCoordinator.close` (from `Ketch.close()`, with `closing` set) for `Shutdown`. The
  scheduler cancels every lane, relayed sockets close, and helpers stop their origin GET within one
  frame (≤ 1 RTT); pending reverse orders are withdrawn and open reverse streams get 409
  `task_paused`. Lanes release in their `finally`; the final checkpoint runs snapshot → sync →
  persist (§9.7), which `Ketch.shutdown` waits for and plain `close()` does not (§15). The file and
  its saved export stay: only a stop the coordinator marks as a discard deletes (#359). Pause before
  the first byte already works at HEAD (`RealDownloadTask.pause` accepts `Queued`). A preempted task
  keeps its `queuePosition` and resumes on its own through `DownloadQueue.startTask` →
  `DownloadCoordinator.resume`, as a Resume below; meanwhile its helper slots go to other tasks
  (§12). Helpers add no `PauseReason`: one leaving, draining, failing or being excluded only removes
  lanes, and a task left with no eligible group fails (§6.8).
- **Resume**: a new execution and nonce; helpers rejoin without a new `hello` while the session
  lives, and abort the paused execution's streams on the first new relay request.
- **Cancel, remove**: only `cancel()` and `remove(deleteFiles = true)` discard the file (#359):
  `DownloadCoordinator.cancel(handle, deletePartialFile)` calls `discardPartialFile()` on the
  running execution before cancelling it. Lanes stop as for pause and the task becomes
  `CANCELED`; `cleanupAfterExecution` deletes the file after every lane has released and
  `FileAccessor.close` ran, and `PathFileAccessor` rejects writes after `close()` (§15 item 4),
  so nothing can recreate it. `remove(deleteFiles = false)` stops the lanes the same way and keeps
  the file. No helper event and no task failure (§6.8) discards: the file and the saved export
  stay for a resume. A task canceled while not running has no execution to mark and keeps its
  file, as at HEAD; `remove(deleteFiles = true)` deletes it through `DownloadSource.cleanup`.
- Pause and cancel never trigger failover, adoption or staging; there is none.
- A task frozen for a transfer is paused through `DownloadCoordinator.pause` too, for
  `PauseReason.Transferring` (a user pause to older clients), so its lanes stop as above and its
  helper slots go to other tasks; it is never transferred and helped at once
  ([transfer plan](../plans/task-transfer.md) K12, K18, §7.3).

| Event | Other lanes disturbed | Committed bytes lost | Origin bytes wasted |
| --- | --- | --- | --- |
| Join | none paused or cancelled; the victim may end its current request early | 0 | 0 on a request boundary, else ≤ the victim's in-flight window |
| Failed join | none; the split is undone | 0 | ≤ the victim's in-flight window if it had already ended its request |
| Graceful leave | none (tail freed at once) | 0 | in-flight past the new limit |
| Removal (Now) | none | 0 | ≤ one frame + socket buffers (≤ 4 MiB) |
| Helper crash | none | 0 | in-flight |
| Silent partition | none; tail freed after 15 s | 0 | in-flight |
| Owner crash | — | ≤ 5 s of progress refetched | — |
| Pause (user, preemption, close, transfer) | all stop by design | 0 (≤ 5 s refetched after `close()` without `shutdown`) | in-flight |
| Task failure (§6.8) | all stop | 0: the file and export stay for a resume (#359) | in-flight |
| `remove(deleteFiles = false)` | all stop | 0: the task goes, its partial file stays (#359) | in-flight |
| `cancel()`, `remove(deleteFiles = true)` | all stop by design | all, as asked: the file is deleted once every lane has released | in-flight |

## 9 Correctness and integrity

### 9.1 Representation pin and validator policy

`RepresentationPin(totalBytes, etag?, lastModified?, aliases, mode)` is built by the owner from the
resolve response and confirmed by the first ranged response: while nothing is committed, a ranged
response with another strong validator or length re-pins (§9.4). `RangeSupportDetector` sends
`Accept-Encoding: identity`; `ServerInfo` gains `date` and `contentEncoding`.

| Mode | Condition | Ranged GETs send | Helpers |
| --- | --- | --- | --- |
| `STRONG_ETAG` | ETag without `W/` | `If-Range: <etag>` and `If-Match: <etag>`; with aliases, `If-Match` lists all and `If-Range` is omitted | yes |
| `STRONG_LM` | no strong ETag; `Date − Last-Modified ≥ 1 s` (RFC 9110 §8.8.2.2) | `If-Range` and `If-Unmodified-Since` with the `Last-Modified`; with aliases, only the response check | yes |
| `LENIENT` | weak ETag only, or no validator | nothing | no (`UNPINNED`) |

Weak ETags are never used for preconditions or for combining ranges (RFC 9110 §13.1.5). RFC 9110
§13.2.2 evaluates `If-Match` before `If-Range`, so an origin that ignores `If-Range` but evaluates
`If-Match` answers 412 to a changed file. An origin that answers 412 while an unconditional probe
returns the pinned validator mishandles `If-Match`; the task stops sending it. A pinned task is
never demoted; a `LENIENT` task upgrades to a strong pin from the first ranged response carrying
one while nothing is committed. Pinning applies to every ranged HTTP download under
`LaneMode.LANES` and to all of them after the PR7 flip, closing two gaps at HEAD: a fresh download
never revalidates (`HttpDownloadSource.download` trusts `preResolved` metadata and
`SegmentDownloader` sends no precondition), and `HttpDownloadSource.resume` fails with
`FileChanged` on one HEAD whose ETag differs, even when a load balancer merely picked another
backend.

### 9.2 Response validation and encoding

`RangeResponseValidator` lives in core and runs for every fetcher; the helper runs it on the
origin response and the owner again on the HEAD frame. It requires status 206; `Content-Range:
bytes a-b/T` with `a`, `b` as requested; `T == pin.totalBytes`, or `*` on local lanes when the
pin's length is known (today `KtorHttpEngine.matchesRange` accepts `*` or any total beyond the
end), while relayed lanes need a numeric `T`; `Content-Encoding` absent or `identity`; and the
response validator, when present, equal to the pin or an alias (ETag by strong comparison for
`STRONG_ETAG`, `Last-Modified` for `STRONG_LM`). A relayed lane's HEAD frame must carry that
validator; without it the helper is Excluded (`UNPINNED`) for the task.

A 206 with another strong validator, a 200 or 412 to a conditional request, another numeric
complete length, or 416 is pin evidence (§9.4). A 200 to `bytes=0-(T−1)` is accepted only with
the pinned validator present and equal and `Content-Length == T`; a 200 at offset > 0 in
`LENIENT` stays `Unsupported` locally, as today. One range per request, never multipart. Engines
that report no response metadata (§14.2) keep today's checks and make the task ineligible for
helpers.

Every lane sends `Accept-Encoding: identity`, so byte offsets are comparable across CIO, OkHttp
and Darwin, OkHttp's transparent gzip (added only when a request names no encoding) cannot apply,
and servers that weaken ETags when compressing do not. A compressed 206 is rejected.

### 9.3 Redirects and IP-bound URLs

The owner always sends the original `DownloadRequest.url`, and every lane follows redirects
itself: local lanes through their client as today, helpers manually (at most 5 hops, URL policy
re-checked per hop, §11). On a hop that changes scheme, host or port the helper drops every
forwarded sign-in header, and it refuses an `https` → `http` hop while any are attached. Each
helper thus gets its own short-lived or IP-bound signature, and an origin 401/403/410 at a helper
excludes only that helper ("NAS can't open this link (403)"). The pin is checked on the final
response, so a mirror with other bytes is caught by validator or length; lanes report the final
host for politeness accounting. Probes are always ranged GETs (`bytes=0-0`), never HEAD, because
some servers send other validators on HEAD than on GET.

### 9.4 Pin evidence and adjudication

Authority over the representation belongs to the owner's own egress. Each egress group records
when it last saw a response matching the pin. `PinAdjudicator` resolves every piece of evidence
(another validator `v'` or length `T'`) in this order:

1. **Nothing committed yet** (`done` empty) and the evidence came from a local group, at most twice
   per execution: re-pin to the evidence's validator and length, re-preallocate and reseed, keeping
   `record.outputPath` and the file name and updating the record's `totalBytes` and resume state. A
   task scheduled or queued behind the add sheet's pre-resolve (`IntakeState` passes
   `resolvedSource` together with `schedule`) whose origin changed before it started downloads the
   new content instead of failing.
2. **Same length, strong `v'`, seen by a local group: alias check.** Through that group, with
   `Connection: close`, fetch four 64 KiB windows of committed bytes in responses that carry `v'`
   (`If-Match: v'` for an ETag; up to 8 attempts per window, since a balancer may route to a pin
   backend): the committed windows nearest the start and the end of the file and two random ones.
   Compare them with the committed bytes, read with `FileAccessor.readAt` (§14.2). All equal: `v'`
   joins `pin.aliases` (persisted) and the group continues. The windows near the ends are always
   compared because container formats keep version fields and indexes there (a zip's central
   directory holds every entry's CRC-32).
3. **Otherwise** (windows differ, another length, no or weak `v'`, or evidence from a helper): the
   reporting group's lanes park, and the task fails with `FileChanged` only when all of these hold:
   - no response matching the pin or an alias on any group since the first evidence;
   - three local probes (conditional on the pin, identity, `Connection: close`) at 1 s, 5 s and
     20 s all return the same new validator or length;
   - when the local group is in a `Retry-After` pause the probes wait for it; when no local group
     is left, every remaining group reports the same new validator or length.

   Otherwise the reporting group is handled alone: a helper is Excluded (`DIFFERENT_CONTENT`) for
   this task; one network among several (`local:<id>`) is Excluded for this task; the single
   local group's lanes retry with backoff and the task keeps progressing on matching responses.

Helpers never create aliases: alias windows are fetched through local groups only.

| Situation | Outcome |
| --- | --- |
| Load-balanced backends with different ETags for identical bytes | Aliased within seconds; nothing fails |
| `MultiNetworkHttpEngine`, networks reaching different edges | Per-network groups; alias, or exclude that network |
| Genuine change on a single backend | `FileChanged` about 20 s after the first evidence |
| Rolling deploy serving both versions | Retries on the old version until it disappears, then `FileChanged` |
| Origin ignores `If-Range` and serves new same-length content | 412 to `If-Match`, or a 206 with a new validator: evidence, never a mixed file |
| Stale pre-resolve, nothing committed | Re-pin |
| A helper's edge serves other bytes | That helper is excluded |

Residual: an alias check can pass for two versions that differ only outside the compared windows
while both are served. Today's solo path has no check at all. The resume check in
`HttpDownloadSource.resume` becomes a conditional probe through the same adjudicator, and so does
task transfer's origin preflight (`SourceTransfer.probeOrigin`) under `LaneMode.LANES`, where
another strong validator at the same length answers `weak` (task-transfer.md §7.5).

### 9.5 Commit rule and fencing

A byte counts only after its frame's tag verified (relayed), `admit()` allowed it,
`FileAccessor.writeAt` returned, and `commit()` advanced the cursor with `offset == cursor`.
Replays and duplicates cannot double-count. Forward lanes need no fence: the only reader of a
relay stream is the lane coroutine that opened it, and cancelling it closes the socket; `admit()`
still rejects claim ids no longer in `claims`, and every DATA frame carries its offset.

Reverse lanes (helper pushes) get four guarantees:

1. A fence `(executionNonce: 64-bit random per DownloadExecution, claimId: monotonic per
   execution, orderId)` in the stream request headers, covered by the request MAC, checked before
   any body byte is read and again, under the ledger mutex, when the body is handed to the waiting
   lane. A restarted owner has a new nonce, so every pre-restart push gets 409 `fence_stale`.
2. Verify-then-write: every frame's tag is checked before `admit()`.
3. Inbound handlers never write. The route hands its body channel to the `ReverseRelayFetcher`
   suspended inside the owner's lane coroutine, so every write runs in a child of the execution
   job and is joined by pause, cancel and remove.
4. `PathFileAccessor` rejects writes after `close()`; today `getOrCreateHandle` reopens the file
   and even recreates parent directories.

### 9.6 Audits and digests

Frame tags prove a byte came from the paired helper, not that the origin sent it, so the owner
audits relayed bytes. When a relayed claim is granted, `AuditSampler` picks one random 64
KiB-aligned window per 32 MiB of claim (at least one), unknown to the helper. As its bytes commit
they feed an incremental SHA-256 (the pure-Kotlin `Sha256` moved from `library:torrent` to core);
the finished window hash is persisted in `pendingAudits`, so a restarted owner finishes the audit
with one fetch. The owner fetches the window through a local lane with the pin's preconditions,
one request at a time, about 0.2% extra origin bytes. When the local group is Excluded, a second
helper with another egress cross-audits (never the supplier); audits wait out a local
`Retry-After` pause.

- **Fail closed.** A task never completes with unaudited relayed bytes unless an origin digest
  verified the whole file. With no audit path left, relayed lanes stop and the task fails with the
  owner's own error, as a solo download does today.
- **Mismatch.** The helper is Blocked (persisted, with a notification: "NAS sent bytes that don't
  match the site. It was blocked."). In one mutex section its live claims are revoked (cancelled;
  their release frees `[cursor, limit)`), every interval it supplied to this task returns to
  `free`, and provenance is updated; progress steps back.
- **Digests.** A helped task verifies the whole file at completion with `FileAccessor.readAt`
  when the origin states a digest of the full representation. Explicit digests: `Repr-Digest`
  (RFC 9530; `sha-256`, `sha-512`), legacy `Digest` (`SHA-256`, `MD5`), `x-goog-hash` (`md5`),
  `x-amz-checksum-sha256` unless `x-amz-checksum-type` is `COMPOSITE`, and `Content-Digest` or
  `Content-MD5` only from a HEAD or a full 200, since on a 206 they cover just that range. On a
  mismatch the owner refetches every relayed span locally and verifies again; a second mismatch is
  `FileChanged`. One inferred digest: a 32-hex-digit ETag on a response that identifies S3
  (`x-amz-request-id`) without SSE-KMS or customer-key encryption headers, read as the MD5 of a
  single-part upload. It never fails a task: a mismatch drops it (logged at debug) and the task
  falls back to audits, because many servers emit 32-hex ETags that are not content hashes.
- **Executables.** Auto leaves out executable and package types (`.exe`, `.msi`, `.dmg`, `.pkg`,
  `.apk`, `.deb`, `.rpm`, `.AppImage`, `.jar`, `.sh`, `.iso`) unless the origin offers an explicit
  digest (`NEEDS_DIGEST`); the user can still add a helper to such a task, and the copy states the
  trust.
- **Task transfer.** A task moved or copied with its data carries only bytes its owner vouches
  for: each exported prefix ends at the first relayed span no passed audit covers, whether its
  audit is pending or its window never committed, so the destination fetches the rest from the
  origin. `RelaySpan` records no passed audits yet, so until it does an export ends at the first
  relayed byte (task-transfer.md §5.1; §9.8 lists what travels).

Sampling catches broken or wholesale-lying helpers, not a targeted patch of a few bytes: one 64 KiB
window per 32 MiB finds a single patched window with about 0.2% probability. Only a digest gives
that guarantee, which is why executables need one and why pairing copy says a helper is trusted
with content.

### 9.7 Durability order

Every checkpoint is **snapshot → sync → persist**: export segments, provenance and pending audits
in one mutex section, call `fileAccessor.flush()`, then `record.update(…)`. Every byte in the
snapshot was written before the flush began, so persisted progress never exceeds durable bytes.
This covers the periodic save (`DownloadExecution.runDownload`'s `saveJob` never flushes today),
the `NonCancellable` final save (no flush today) and shutdown. `DownloadCoordinator.pause` flushes
right after `job.cancel()`, before the execution's final save, so writes still in flight can land
after that flush yet be counted; fixing the final save fixes pause.

#359 covers one case: when the flush after a finished transfer throws, `discardProgress` keeps the
file and the segment layout, zeroes every prefix in memory and in the record, and the task fails
with `Disk`, so a resume refetches every byte into the same file instead of completing over bytes
that may not be on disk. It cannot do more, since no earlier checkpoint was synced either, and it
leaves the rest open: the final save still persists before that flush, so a crash between the two,
or after any periodic save, keeps progress beyond durable bytes (§15 item 2). Under this rule a
failed sync, periodic or final, persists nothing from its snapshot and fails the task with `Disk`
as #359 does, and #359's reset stays: it runs on the export (§6.10), keeping the layout. Falling
back to the previous, synced checkpoint instead would save that refetch, but a disk that failed a
sync is not worth the extra path.

`flush()` becomes durable on Android, JVM and Apple targets, the engines that run helped tasks. On
the JVM okio's `JvmFileHandle.protectedFlush` already calls `fd.sync()` and Android SAF's
`ContentUriFileAccessor.flush` calls `fileDescriptor.sync()`. On Apple targets okio 3.18.2's
`UnixFileHandle.protectedFlush` is `fflush` on a `FILE*` whose data went through `pwrite`, which
forces nothing to storage, so `PathFileAccessor` there adds `fcntl(F_FULLFSYNC)` (falling back to
`fsync`) on a descriptor for the same path. Residual: on macOS the JVM's `fsync` does not flush the
drive cache. After an OS crash at most `saveIntervalMs` of committed bytes are refetched.

### 9.8 Persisted format and migration

- `TaskRecord` and `TaskRecords.sq` stay as #353 left them (schema 5: `4.sqm` added
  `completed_at`); this proposal adds no migration (`instanceId` lives in `device.json`, §7.1;
  task transfer's `5.sqm` adds only its own table and column). `TaskLanes.bytesByDevice` is
  runtime-only, so a finished task shows no per-device split after a restart.
  `TaskRecord.segments` holds the export (§6.10) with prefix semantics and `index == position`:
  `null` until a source or the scheduler publishes segments (#359's `savedSegments`), `[]` only
  for an unknown-size stream (#354) or a `managesOwnFileIo` source, and a legacy `[]` of known size
  restarts from zero (#359). Legacy fragmented lists restore exactly.
- `Segment` unchanged; its KDoc changes from "one connection" to "a file region with a committed
  prefix". Per-connection data moves to `LaneInfo`, which supersedes the
  `Segment.bytesPerSecond`/`retryCount`/`networkInterfaceId` plan of ux-redesign
  W5-API-ERRORS-SEGMENTS.
- `HttpResumeState` gains `pinMode`, `aliases`, `pinVersion = 1`, `relayed: List<RelaySpan(helper,
  start, end)>` (at most 256, merged across gaps beyond that, which over-invalidates safely) and
  `pendingAudits: List<PendingAudit(start, sha256)>`, all defaulted, and decodes with
  `ignoreUnknownKeys` (today strict `Json.decodeFromString`). Old JSON decodes; a downgrade to a
  strict build fails resume with `CorruptResumeState`, acceptable before the first release.
  The transfer plan (its §5.1-5.2) carries `pinMode` and `aliases` to another device, never
  `relayed`, `pendingAudits` or `pinVersion`, and ends exported prefixes at the first relayed span
  no passed audit covers (pending, or its window never committed).
- `DownloadRequest.helpers` is additive in the request JSON column, which `SqliteTaskStore`
  already decodes with `ignoreUnknownKeys`. That flag does not cover an unknown subtype of a sealed
  class, and `SqliteTaskStore.toTaskRecord` decodes the request uncaught, so one newer policy would
  fail `loadAll` for every task after a downgrade. `HelperPolicy`, the one new sealed type on the
  wire, therefore follows `PauseReason` (#353): a hand-written serializer that reads `type` and its
  own fields under any `Json` and decodes an unknown `type` as `Off`, which never shares more than
  the user chose. Claims, lanes, sessions and fences are runtime-only.
- No new `DownloadState` or `KetchError` subtypes (`KetchError.isRetryable` is an exhaustive
  `when`, and old `RemoteKetch` clients could not decode them), and no new `PauseReason` from
  helpers (§8.6; task transfer adds `Transferring`, which clients from #353 on decode as `User`).
  Every new wire property has a default, enums included, so `coerceInputValues` (set in
  `RemoteKetch`'s `Json`; `KetchServer`'s gains it) maps an unknown enum value to its default
  instead of failing a whole task list; sealed types need a serializer like `HelperPolicy`'s
  above. Lanes go only to clients that ask (`?lanes=1`).

## 10 Protocol

### 10.1 Planes and endpoints

| Plane | Where | Auth | Callers |
| --- | --- | --- | --- |
| Admin: `/api/helpers*`, task helper routes, lane data | Existing `KetchServer` | Bearer `apiToken`; without a token, loopback callers whose request carries no `Origin`, `Forwarded`, `X-Forwarded-For` or `X-Real-IP` | Apps, web, CLI |
| Relay: `/api/relay/v1/*` | New `RelayServer` on `[helping] port` (default 8643), CIO; binds the LAN addresses `pairingAddresses` picks, without its fallback to virtual and mobile-data interfaces when nothing else has an address, unless `[helping] host` is set (a VPS) | Sealed session; pairing by invite proof; any `Origin` refused; ≤ 64 unauthenticated sockets, 10 s idle | Owner and helper instances |

Keeping the relay plane off the admin port lets a phone help without running its admin API, and a
tokenless admin server never gains a remotely reachable pairing route. Since #352 the apps'
token-holding servers answer `/api/pairing` without the token and hand that token to a device
their user allows; it exists only with a token and a `PairingApprover`, adds a device to the
fleet, and never pairs a helper. `KetchServer` takes the route-set parameter task transfer's M1
adds (`routes: Set<RouteGroup> = setOf(RouteGroup.CORE)`, its §4.5); PR8 adds `HELPERS`, off by
default as `pairingApprover = null` already leaves `/api/pairing` off, so `BrowserExtensionServer`
(a full `KetchServer` with a per-run token and no approver) serves no helper, task helper or
transfer route. On a tokenless server a non-loopback `POST /api/tasks` has its `helpers` field
reset to the default, so a LAN caller cannot pick helpers or spend them.

| Method | Path | Body | Response |
| --- | --- | --- | --- |
| GET | `/api/helpers` | — | `HelpersSnapshot{devices, helping, settings}` |
| PUT | `/api/helpers/settings` | `HelperSettings` | `HelperSettings` |
| POST | `/api/helpers/invites` | `{role}` | `HelperInvite{link, expiresAt}` |
| POST | `/api/helpers/pair` | `{link}` (`ketch://helper` only) | `HelperInfo`; 400 for `ketch://pair` |
| PATCH | `/api/helpers/{id}` | `HelperChange` | `HelperInfo` |
| DELETE | `/api/helpers/{id}?mode=drain\|now\|block` | — | 204 |
| POST | `/api/helpers/helping/{ownerId}/stop?now=` | — | 204 |
| PUT | `/api/tasks/{id}/helpers` | `HelperPolicy` | `TaskSnapshot` |
| POST | `/api/tasks/{id}/helpers/{instanceId}/stop?now=` | — | `TaskSnapshot` |
| GET | `/api/tasks?lanes=1`, `/api/events?lanes=1` | — | `TaskSnapshot.lanes` filled; SSE `lanes_changed`, ≤ 1/s per downloading task |
| GET | `/api/helpers/events` | — | SSE `helpers_changed`, `helping_changed` |
| POST | `/api/relay/v1/pair`, `/api/relay/v1/pair/confirm` | §7.3 m1, m3 | m2; 204; 403 `invite_invalid`; 429 |
| POST | `/api/relay/v1/hello` (either way) | §7.4 | hello response |
| GET | `/api/relay/v1/status?streams=<ids>` (owner → helper) | — | `{draining, freeSlots, bytesPerSecond, live}` |
| POST | `/api/relay/v1/relay` (owner → helper) | sealed `RelayRequest` | 200 frame stream; 401, 403, 409, 429, 503 |
| GET | `/api/relay/v1/orders?wait=25000` (helper → owner) | — | `[RelayOrder{orderId, sealed RelayRequest}]`, each sealed under its own index |
| POST | `/api/relay/v1/orders/{orderId}/stream` (helper → owner) | frame stream, fence headers | 200 `{acceptedBytes}`; 409 `fence_stale`; 410 `order_gone` |
| POST | `/api/relay/v1/goodbye` (either) | `{reason}` | 204 |

Resources and models live in `library:endpoints` (`Api.Helpers`, `Api.Relay`). `KetchServer`
implements the admin routes over `ketch.helpers`. `lanes_changed` is a new `TaskEvent` subtype sent
only to clients that ask with `?lanes=1`, because today's `RemoteKetch` logs an error for every
event it cannot decode; `TaskSnapshot` gains `lanes: TaskLanes = TaskLanes()` beside #353's
`queuePosition`. A command response (`PUT /api/tasks/{id}/helpers`, the stop route) carries the
lanes the server read when it answered, so `RemoteDownloadTask` applies them only when no SSE event
arrived while the request was in flight, through the `appliedEvents` count #353 added for
`queuePosition`; `lanes_changed` events count too. Lanes are a flow of their own, sampled at 1/s,
outside `EventRoutes.taskEvents`' `combine` of state, request, segments and queue position, so
other clients' `progress` and `state_changed` traffic does not grow.

### 10.2 Relay request and frames

`RelayRequest{v: 1, fence{exec, claim}, taskKey = trunc16(HMAC(pairKey, taskId)), source{type:
"http", url, headers}, pin{totalBytes, etag?, lastModified?, aliases, mode}, range{start,
endInclusive}}`. `headers` holds only what §11 allows. The helper never learns task ids, paths or
audit windows.

```
frame     = type:u8 | len:u32 BE | payload | tag:16      (len ≤ 1 MiB; 0x80-0xFF skippable)
0x01 HEAD      sealed {status, contentRange, etag, lastModified, contentEncoding, finalHost}
0x02 DATA      offset:u64 | ciphertext (≤ 64 KiB)
0x03 KEEPALIVE empty, after 5 s without DATA
0x04 END       sealed {bytes}
0x05 ERROR     sealed {code, http, retryAfterSec}        (codes only, never free text)
0x06 DRAIN     sealed {graceMs}
encKey‖macKey = HKDF(kDir, info = LP("msg-v1", sessionId, c, kind, index))
tag       = HMAC-SHA256(macKey, LP(seq, type, offset, ciphertext)), first 16 bytes
cipher    = AES-256-CTR, initial counter block = seq:u64 ‖ 0:u64
```

`kDir` is the sender's direction key (`kD2C` or `kC2D`), `kind` is `request`, `frames` or
`order`, and `index` is an element's position in a list (orders) or 0. The tuple (direction,
session, `c`, kind, index) never repeats, so no key and counter block are ever reused, including
across the orders in one long-poll response and across the two directions of a reverse lane.
`seq` is implicit and counts frames, so a dropped, reordered or replayed frame fails its tag; the
tag is checked before decryption and before `admit()`. A streamed request body (the reverse lane's
POST) cannot be hashed up front, so its header MAC covers the fixed digest of `"stream"` and every
frame carries its own tag.

Backpressure: the owner reads frames inside `execute {}` through `bodyAsChannel` only as fast as
throttle and `writeAt` allow; the TCP window fills, the helper's `writeFully` suspends, it stops
reading the origin, and the origin sees TCP backpressure. The helper therefore has no write-stall
timer, which would misread a throttled owner as a dead one; it aborts a stream when the owner's
5 s status heartbeat stops listing it or no heartbeat arrives for 20 s, and reverse-lane helpers
read the same from the orders long-poll. The helper seals synchronously inside the origin
`onData`, copying out of `KtorHttpEngine`'s reused 8 KiB buffer before accumulating a frame.

### 10.3 Error mapping

| Helper signal | Owner lane result |
| --- | --- |
| 401 `session_unknown` | Re-`hello` once and retry; a second 401 means the pairing was removed: Excluded, helper `UNPAIRED` |
| 403 `policy_denied` (URL policy, sign-ins, budget) | Excluded for this task |
| 409 `protocol_unsupported` | Excluded until the next `hello` |
| 429 `helper_busy` + `Retry-After` | Group parked until then; no origin AIMD; 3 in a row → Excluded (`BUSY`) 10 min |
| 503 `draining` | Drained |
| ERROR `origin_http` 401/403/407/410/451 | Excluded (`ORIGIN_DENIED`) |
| ERROR `origin_http` 429, or 503 with `Retry-After` | Throttled (group AIMD) |
| ERROR `origin_http` 5xx, `origin_network`, EOF | Retryable |
| ERROR `pin_mismatch` (with HEAD validators) | Pin evidence (§9.4) |
| ERROR `range_ignored` | Excluded |
| Bad tag, frame or response MAC | Excluded (`INTEGRITY`), helper Suspect; nothing was written |

`LaneInfo.error` is built from these codes by the owner and localized by the apps; nothing a
helper sends reaches a log or a UI as text.

### 10.4 Timeouts, heartbeats and versioning

| Timer | Value | Why |
| --- | --- | --- |
| Relay connect | 3 s private address, 10 s public | LAN RTT ≪ 1 s; WAN helpers |
| First frame (HEAD) | 10 s | Origin DNS + TCP + TLS + TTFB with margin |
| KEEPALIVE | every 5 s without DATA | Tells a slow origin from a dead helper |
| Helper liveness | 15 s without any frame | Three missed keepalives |
| Origin stall (keepalives only) | 30 s | Release without blaming the helper |
| Local DATA gap | 15 s | `KtorHttpEngine`'s default client has no timeout at all |
| Owner heartbeat | `status` every 5 s while streams are open; helper aborts after 20 s without | Liveness without a write-stall timer |
| Status poll | 10 s with an active helped task, else 60 s | Idle heartbeat |
| Session idle | 10 min | Bounds helper memory |
| Reverse long-poll | 25 s; at most 2 concurrent per session | Below common proxy and NAT idle timeouts |
| Drain grace | 5 s + 2 s force | Finishes a short tail at LAN speed |

The relay client is a dedicated Ktor `HttpClient` with these finite timeouts, separate from
`RemoteKetch`'s `Long.MAX_VALUE` ones. Versioning: `/v1` in the path; `hello` negotiates the
highest common protocol and lists `features` (`drain`, `reverse`, `ftp`); frame types 0x00–0x7F
are must-understand; JSON uses `ignoreUnknownKeys` and `encodeDefaults` as `KetchServer` already
does; mDNS `pv=1`. `RemoteKetch.helpers` is never null: its `supported` flow follows
`KetchStatus.features` (`helpers`, §14.1), read on connect and reconnect in the `status()` call
that also yields `instanceId`, as the apps already gate Auto connections and queue positions
(`AppState.featuresOf`): `null` until read, `false` for a server that does not list it, so no
probe is needed. A server whose route set leaves out the helper routes drops `helpers` from the
status it serves. Against an older server its calls throw `UnsupportedOperationException`, a 404
or 501 included.

## 11 Security and trust

| Threat | Mitigation |
| --- | --- |
| A LAN host or web page pairs itself | Pairing needs a single-use 128-bit invite created explicitly; the bearer that unlocks admin helper routes reaches a LAN host only when typed, through a `ketch://pair` link, or when the owner allows its `/api/pairing` request (#352; web pages refused); admin helper routes need the bearer, or a loopback caller without forwarding headers; tokenless servers already refuse foreign pages (`CrossOriginGuard`) and rebound hosts (`HostValidator`) |
| Passive observer, now or after a key leak | ECDH in pairing and in every session; access tokens are never key material; no identifier in cleartext |
| Active attacker on the path | Needs the invite during pairing (SAS when the link was shared), the pair key afterwards |
| Replay | 1024-entry window per session; nonce caches for `hello` and `/pair` |
| Bytes injected in transit | Tag checked before `writeAt` |
| A paired helper lies | Audits, fail closed without an audit path, digests, executables need a digest, Block on failure |
| Helper as open relay or SSRF | Paired owners only; GET with one range inside `[0, totalBytes)`; URL policy enforced at connect time; no proxy |
| Helper bandwidth abuse | Caps and budgets below |
| A lost device keeps access | Unpair or Block per pairing; Block refuses the key fingerprint and endpoint; a controller that holds the device's access code (typed, linked or allowed through #352) can invite it again until New code replaces it |
| Credential or key in a log | `redactUrl` masks fragment secrets; ERROR frames carry codes; parsers never echo links |

**Realms.** `HostValidator` and `CrossOriginGuard` are application plugins that
`KetchServer.configureServer` installs before routing when `apiToken == null`, so the admin helper
routes inherit them. `CrossOriginGuard` lets browser extensions through and a local reverse proxy
makes every caller look local, so tokenless helper routes also refuse requests carrying `Origin`,
`Forwarded`, `X-Forwarded-For` or `X-Real-IP`. `RelayServer` installs neither plugin: every
request is sealed, DNS rebinding needs a browser, and the listener refuses any request carrying
`Origin`. Its own authentication runs regardless of `apiToken`; `KetchServer` today installs
`Authentication` only with a token.

**Remote controllers.** On a tokenless remote device the helper routes answer 403 to the apps and
the web app, so its Helpers sections say "Set an access code on NAS to manage helpers" with a link
to its Sharing page. For a remote owner, the controller hands the owner only a single-use helper
link created on the helper (§7.3), never the helper's access token.

**Credential forwarding.** An allowlist: a helper receives `User-Agent`, `Accept-Language` and
`Referer` cut to its origin (the browser extension sends the full page URL with its query). Every
other request header, URL userinfo, and any URL whose query matches `redactUrl`'s
`SENSITIVE_QUERY_KEY` (`pass|token|secret|key|sig|auth|credential|session`) or a known signed-URL
key (`hm`, `hdnts`, `hdnea`, `X-Goog-*`, `Expires` with `Signature`, `md5` with `expires`, `policy`)
needs the pairing's `shareSignIns` and the task's own setting (on by default). Without them the
task is ineligible for that helper (`SIGN_INS`) and the UI says why. `Proxy-Authorization` is the
owner's own proxy credential and is never forwarded. A URL whose host is single-label, `.local`,
or resolves to a non-global address goes only to helpers marked as sharing the owner's LAN, so
intranet URLs are not disclosed to remote helpers. Secrets in a URL path cannot be detected, which
the pairing copy covers ("links can themselves grant access"); the sign-ins copy adds that a site
may treat use from a second address as suspicious. Helpers hold the material in memory for one
request, `RelayRequest.toString` redacts it, and helper logs carry only host and range.

**Crypto and TLS.** Primitives come from the platform through `expect`/`actual` in
`library:relay`: `javax.crypto` and `java.security` (`HmacSHA256`, `AES/CTR/NoPadding`,
`KeyPairGenerator("EC")` on secp256r1, `KeyAgreement("ECDH")`, `MessageDigest`) on JVM and Android
(all present at minSdk 26); CommonCrypto (`CCHmac`, `CCCryptorCreateWithMode` with `kCCModeCTR`,
`CC_SHA256`, `CC_MD5`) and Security.framework (`SecKeyCreateRandomKey` with
`kSecAttrKeyTypeECSECPrimeRandom`, `SecKeyCopyKeyExchangeResult` with
`kSecKeyAlgorithmECDHKeyExchangeStandard`) on iOS; platform secure random; HKDF (RFC 5869) is a
few lines over HMAC. The relay plane therefore needs no TLS listener on CIO: confidentiality
against passive observers with forward secrecy, and authentication against active ones as long as
the invite stayed secret until pairing and the pair key stays secret afterwards. The admin plane
keeps today's bearer-over-cleartext risk; a TLS proxy still works in front of both.

**Open relay and SSRF.** Only sessions of paired owners with `allowHelpFor` may relay: GET, one
range inside the pinned `totalBytes`, `http`/`https` (FTP later), hop-by-hop headers and `Host` set
by the helper. The relay engine is a port of `SafeFetcher.createHttpClient` as a whole: Ktor
`HttpClient(OkHttp)` with `followRedirects = false`, a validating `Dns` (from `ai:discover`'s
`ValidatingDns`), `Proxy.NO_PROXY` (a proxy would resolve hosts out of reach of the check), and no
cookie jar, authenticator or cache. Its `fetch` reports 3xx status and `Location` in
`RangeResponseMeta`, so the helper's hop loop (at most 5) re-checks each hop. `RelayUrlPolicy`
accepts only globally routable unicast addresses per the IANA IPv4 and IPv6 special-purpose
registries, after decoding IPv4-mapped, NAT64 (`64:ff9b::/96`, `64:ff9b:1::/48`) and 6to4
(`2002::/16`) forms; `ai:discover`'s `UrlValidator.isBlockedAddress` checks only loopback,
link-local, site-local, any-local and CGNAT, and Java's `isSiteLocalAddress` misses ULA
`fc00::/7`. Every address of the helper's own interfaces is refused on every port, re-read as
`HostValidator` re-reads them. Private ranges open only per pairing (`allowPrivateCidrs`, empty by
default), never for every owner. A helper bound to an Android network wraps
`network.getAllByName` with the same filter, as `KtorHttpEngine.forNetwork` binds sockets and DNS
today. iOS helpers (reverse lane only) lack a DNS hook in Darwin: they set an empty
`connectionProxyDictionary`, check resolved and literal addresses before connecting and always
refuse private targets; the remaining rebinding window is a documented residual risk behind
authentication.

**Logging.** `redactUrl` keeps fragments verbatim today (`redactQuery` appends
`url.substring(fragmentStart)`); it gains masking of fragment parameters named like
`SENSITIVE_QUERY_KEY` and of the whole fragment of `ketch:` links. `InviteCodec` and the pairing
handlers never put a link in an exception message, because `KetchServer`'s `StatusPages` returns
`IllegalArgumentException.message` to the caller; the new routes never log request bodies.

**Helper caps.**

| Cap | Desktop | CLI daemon | Android | iOS | Why |
| --- | --- | --- | --- | --- | --- |
| Relay streams | 4 | 8 | 2 | 2 | Room for the helper's own downloads |
| Streams per owner | 4 | 4 | 2 | 2 | One owner cannot take every slot |
| Streams per origin host, all owners | 4 | 4 | 2 | 2 | Two owners cannot stack 8 connections from one IP |
| Bytes per owner per day | 50 GiB | 50 GiB | 2 GiB | 1 GiB | Several disk images a day, while capping a billed VPS; metered plans and battery on phones. A warning shows when the helper has a public address |
| Origin bytes per final URL per owner per day | 1.25 × `totalBytes` | same | same | same | Keyed by SHA-256 of the final URL without query as the helper computes it, so an owner cannot mint fresh keys |
| Request rate and size | 20/s per owner; body ≤ 64 KiB, ≤ 64 headers, URL ≤ 8 KiB | same | same | same | Cheap parsing |
| Metered network, low battery | allowed | allowed | refused unless "Help over mobile data"; refused < 20% | refused; refused < 20% | User expectation |

Relayed bytes also pass the helper's own global limiter and an optional relay bucket (from
`helperRuntime`, since core's `TokenBucket` is internal), so helping never exceeds the helper
user's limit, including the Slow lane speed mode.

## 12 Limits and fairness

- **Task limit** (`DownloadRequest.speedLimit`, `setSpeedLimit`): enforced once, at the owner, by
  the existing `context.throttle` before `writeAt`, for every lane kind. Relays are pulled, so
  throttling the read backpressures the helper and the origin; the limit is cluster-wide by
  construction and can never be exceeded N times. While it binds, relayed groups drain (§6.9).
- **Owner global limit**: also charged for relayed bytes, which are downloads for the owner's user
  and usually cross the same home link (open question 2, proposed: yes).
- **Helper**: relayed bytes pass the helper's global limiter plus the relay bucket. With
  `yieldToLocalDownloads` (default on), while the helper has active tasks of its own, relays drop
  to 1 stream per owner and to at most half of a set global limit; excess streams get DRAIN.
- **Origin politeness**: per-group lane caps, 16 lanes per task, AIMD per group, per-helper caps
  per origin host across owners, no duplicate requests, `[helpers] excludeHosts` (matched on the
  request and final host) and per-task Off for origins that forbid multi-IP fetching. Helpers that
  share the owner's connection join the local group.
- **Queue**: a helped task holds one `DownloadQueue` slot, counts once toward the per-host task
  limit (`DownloadQueue.hasHostCapacity`) and reports `queuePosition` (#353) like any task; helper
  lanes are not tasks and never move positions, and relays take no slot on the helper. A task
  waiting for a slot (`Queued`, or `Paused(Preempted)`) runs no lanes.
- **Across the owner's tasks**: `HelperManager` deals each helper's `freeSlots` round-robin over
  active helped tasks in priority order (URGENT first), at most `lanesPerHelper` each, rebalancing
  by drain when tasks start, finish or pause for any `PauseReason`.

## 13 Source and platform support

| Source | Local lanes | Relayed lanes | Pin | Notes |
| --- | --- | --- | --- | --- |
| HTTP(S), ranges, known length, strong validator | yes | yes, ≥ 32 MiB | ETag or strong LM, aliases | The main case |
| HTTP(S), ranges, weak or no validator | yes (`LENIENT`) | no (`UNPINNED`) | exact range + length | Still gains per-lane retry and live connection changes |
| HTTP without ranges, or 200 to Range | one lane, restart from zero | no | — | Today's `rangeLimitedConnections` path |
| Unknown length (resolve HEAD without `Content-Length`) | one un-ranged stream from byte zero (`downloadUnknownSize`, #354); retries and resumes restart it in place | no (`UNKNOWN_SIZE`) | — | Never enters `LaneScheduler`; HTTP only: FTP and `managesOwnFileIo` sources fail |
| FTP/FTPS with REST + SIZE | yes (PR6) | PR14: MDTM required, 1 lane per helper, sign-ins for userinfo | SIZE + MDTM | 421 → Throttled; FTPS relay only on JVM/Android helpers |
| BitTorrent | own scheduler, unchanged | never; greyed with a reason | piece hashes | Native multi-source; synthetic segments |
| HLS, media (roadmap) | per ranged sub-fetch | possible | per sub-fetch | Only fetchers and pin are HTTP-specific |

| Platform | Owner | Forward helper | Reverse helper | Listener (owner invites, reverse lanes) | Controller |
| --- | --- | --- | --- | --- | --- |
| Desktop (JVM) | yes | yes (`RelayServer`) | yes | yes | yes |
| CLI daemon | yes | yes; the best helper (always on, 8 streams) | yes | yes | `ketch helpers` |
| Android | yes (dataSync FGS) | yes (`RelayServer` in `KetchService`) | yes | while it runs | yes |
| iOS | yes, foreground | no (no server) | yes, foreground (PR12) | no | yes |
| Web (wasmJs) | no | no | no | no | yes, through `RemoteKetch` |
| core `js(nodejs)`, `wasmWasi` | no `HttpEngine` | — | — | — | — |

Android: while `RelayServer` listens the service stays in the foreground with "Ready to help
MacBook" (`ForegroundStatus.isRequired` gains `helperPort != null`, as `serverPort` keeps it today)
and counts against the daily dataSync limit; it holds a `WifiLock` while relaying and drains on
`onTimeout`. The listener binds LAN addresses only (the `pairingAddresses` filter without its
fallback, §10.1), so none while only mobile data has an address. The phone-as-second-WAN
case needs "Help over mobile data", one control that sets `[helping] network = cellular` and allows
metered relays together and shows the daily cap left (2 GiB by default, adjustable); radios throttle
when the phone is hot, and helping stops below 20% battery. iOS: the engine closes on view dispose
and the app has no `UIBackgroundModes`, so an iOS owner or helper works in the foreground; iOS 26
continued processing covers user-started downloads only.

Non-server platforms help through the **reverse lane**: the helper long-polls
`GET /api/relay/v1/orders` on the owner, receives sealed `RelayRequest`s, fetches from the origin
and POSTs the same frame stream to `/orders/{orderId}/stream`. `ReverseRelayFetcher` implements
the same `RangeFetcher`; truncation stays owner-local (stop reading, answer `{acceptedBytes}`);
only the fence (§9.5) is added. A spike must confirm Ktor Darwin streams request bodies before
PR12 is scheduled.

## 14 Public API, configuration and UX

### 14.1 `library:api` (additive, default-valued)

```kotlin
/** Which helper devices may fetch parts of this download. An unknown `type` decodes as [Off]. */
@Serializable(with = HelperPolicySerializer::class)   // hand-written, like PauseReasonSerializer
sealed class HelperPolicy {
  /** Never use helpers. */
  @Serializable @SerialName("off") data object Off : HelperPolicy()
  /** Helpers set to Auto, plus [added], minus [excluded]; [signIns] false withholds sign-ins. */
  @Serializable @SerialName("auto")
  data class Auto(val added: Set<String> = emptySet(), val excluded: Set<String> = emptySet(),
                  val signIns: Boolean = true) : HelperPolicy()
  /** Only [instanceIds]; the gain check reports on them but never removes them. */
  @Serializable @SerialName("only")
  data class Only(val instanceIds: Set<String>, val signIns: Boolean = true) : HelperPolicy()
}
// DownloadRequest.helpers: HelperPolicy = HelperPolicy.Auto()
// HelperPolicy.forOtherDevice(): Off stays Off, Auto keeps only signIns, Only becomes Off (§5.1)
// KetchStatus.instanceId: String? = null (§7.1; PR2, task transfer's F7)
// KetchFeatures.TASK_LANES = "task.lanes": DownloadTask.lanes reports and ?lanes=1 is honoured
// KetchFeatures.HELPERS = "helpers": KetchApi.helpers works and /api/helpers* answer

/** One connection of a download on this device ([instanceId] null) or through a helper. */
@Serializable
data class LaneInfo(
  val number: Int = 0, val instanceId: String? = null, val state: LaneState = LaneState.CONNECTING,
  val start: Long = 0, val end: Long = -1 /* inclusive, like Segment.end */,
  val downloadedBytes: Long = 0, val bytesPerSecond: Long = 0, val retries: Int = 0,
  val networkInterfaceId: String? = null, val error: LaneErrorCode? = null,
  val httpStatus: Int? = null,
)
/** What a lane is doing now. */
enum class LaneState { CONNECTING, STREAMING, PARKED, BACKOFF, DRAINING }
/** Lanes of a download, bytes per device ("" is this one) and why helpers are or are not used. */
@Serializable
data class TaskLanes(
  val lanes: List<LaneInfo> = emptyList(), val unavailable: HelpersUnavailableReason? = null,
  val excluded: List<ExcludedHelper> = emptyList(),
  val bytesByDevice: Map<String, Long> = emptyMap(),
)
/** A helper left out of this download, until [until] when time-limited. */
@Serializable
data class ExcludedHelper(val instanceId: String = "",
  val reason: ExclusionReason = ExclusionReason.UNKNOWN, val until: Instant? = null)
// LaneErrorCode, HelpersUnavailableReason (OFF, NO_HELPERS, TOO_SMALL, UNKNOWN_SIZE, NO_RANGES,
// UNPINNED, SIGN_INS, SOURCE_TYPE, EXCLUDED_HOST, LIMITED, NEEDS_DIGEST) and ExclusionReason
// (ORIGIN_DENIED, DIFFERENT_CONTENT, THROTTLED, NO_GAIN, SLOW, AUDIT_FAILED, INTEGRITY, METERED,
// BUSY) each start with UNKNOWN, the value an older client decodes a newer one to.

interface DownloadTask {
  /** Live lanes; empty for backends that do not report them. */
  val lanes: StateFlow<TaskLanes> get() = NoLanes
  /** Persists [policy] and applies it live; old backends throw UnsupportedOperationException. */
  suspend fun setHelpers(policy: HelperPolicy): Unit = throw UnsupportedOperationException()
  /** Stops [instanceId] helping this task, draining its lanes unless [now]. */
  suspend fun stopHelp(instanceId: String, now: Boolean = false): Unit =
    throw UnsupportedOperationException()
}
interface KetchApi {
  /** Helper pairing and helping; `null` when this backend has none. */
  val helpers: HelperController? get() = null
}

/** Helper pairings of this instance and the help it gives others. */
interface HelperController {
  val supported: StateFlow<Boolean?>           // null until known
  val devices: StateFlow<List<HelperInfo>>
  val helping: StateFlow<HelpingStatus>
  val settings: StateFlow<HelperSettings>
  suspend fun updateSettings(settings: HelperSettings)        // applies immediately
  suspend fun createInvite(role: HelperRole): HelperInvite     // single use, 15 minutes
  suspend fun pair(link: String): HelperInfo                   // ketch://helper only
  suspend fun update(instanceId: String, change: HelperChange): HelperInfo
  suspend fun stopHelping(ownerId: String, now: Boolean = false)
  suspend fun unpair(instanceId: String, mode: RemovalMode = RemovalMode.DRAIN)
}
```

`HelperInfo(instanceId, name, endpoints, platform, health, helpsThisDevice: HelpMode {OFF, PICKED,
AUTO}, allowHelpFor, sharesConnection, shareSignIns, allowPrivateCidrs, rttMs, activeLanes,
bytesToday, lastSeen)`, `HelperChange` (nullable fields), `HelperRole { HELPER, OWNER }`,
`RemovalMode { DRAIN, NOW, BLOCK }`, `HelperInvite(link, expiresAt)`, `HelpingStatus(enabled,
draining, owners: List<HelpedOwner>, bytesPerSecond)` and `HelperSettings` (the `[helpers]` and
`[helping]` keys) are `@Serializable` classes in `com.linroid.ketch.api.helper`, each with a
one-line KDoc and defaults on every property. `DownloadConfig` is unchanged. `Ketch.features`
(what `status()` lists) adds `task.lanes` under `LaneMode.LANES` and `helpers` while a
`helperController` is set; both stay out of `KetchFeatures.ALL` until `task.lanes` joins it at the
PR7 flip, so `AppState.featuresOf` reads them from the embedded engine instead of assuming `ALL`.
Since #353 `Ketch.close()` pauses running tasks for `PauseReason.Shutdown` and keeps their files
and records (since #359 because only `cancel()` and `remove(deleteFiles = true)` discard a file,
no longer through a shutdown check), but returns without waiting for their final checkpoints.
`Ketch` (not `KetchApi`) gains `suspend fun shutdown(grace: Duration = 5.seconds)`: the same
pause, then, within `grace`, each execution's snapshot → sync → persist (§9.7) and a `goodbye` to
helpers, then `close()`.

### 14.2 Core SPI (`@KetchInternalApi`, a new `RequiresOptIn` for `library:ftp` and `library:relay`)

```kotlin
interface HttpEngine {
  // head(), download() and close() unchanged
  /**
   * Ranged fetch that reports response metadata and lets [onData] end the response: returning
   * false closes it without an error. Default: [download] without metadata.
   */
  suspend fun fetch(
    request: RangeRequest,                       // url, range, headers, network: String?
    onResponse: suspend (RangeResponseMeta?) -> Unit,
    onData: suspend (ByteArray) -> Boolean,
  ): FetchEnd                                    // COMPLETE or STOPPED
}
fun interface RangeFetcher { suspend fun fetch(claim: RangeClaim, sink: RangeSink) }
fun interface RangeSink { suspend fun accept(offset: Long, data: ByteArray, length: Int): Boolean }
class LaneSpec(val laneId: String, val group: String, val fetcher: RangeFetcher,
               val audited: Boolean)
fun interface LaneProvider { fun lanesFor(task: HelpedTask): Flow<List<LaneSpec>> }
// FileAccessor.readAt(offset, length): ByteArray, default UnsupportedOperationException; path
//   and SAF accessors read through F14's positional-read code (task-transfer.md §7.4)
// Ketch(instanceId: String? = null, laneMode: LaneMode = …, laneTuning: LaneTuning = …),
// var laneProvider, var helperController (returned by Ketch.helpers),
// val helperRuntime: HelperRuntime // acquireGlobal(bytes), newLimiter(bps), localActiveTasks
```

`fetch` is additive. The default stops a plain `download` by throwing a private
`CancellationException` subtype that it catches itself; engines rethrow cancellation untouched by
convention (`KtorHttpEngine.download` does), so stopping is never logged or classified as
`KetchError.Network`. `KtorHttpEngine`, `MultiNetworkHttpEngine` and
`ConfigurableNetworkHttpEngine` override it; the network engines honour and report
`RangeRequest.network` (interface id), which makes per-network egress groups possible.
`TorrentHttp`'s un-ranged `download(url, null, …)` for tracker announce, scrape and `.torrent`
metainfo, and `HttpDownloadSource.downloadUnknownSize`'s (#354) for content of unknown size, keep
today's semantics. Every existing `HttpEngine` compiles unchanged, but Kotlin `by` delegation
forwards default-bodied members to the delegate, so a wrapper that overrides only `download`
silently bypasses its hook once callers use `fetch`. PR3 makes the three such wrappers override
`fetch` as well (`HttpDownloadIntegrationTest`'s pausing engine for the `*PAUSE_FIRST` modes, which
keeps its `download` hook for #354's `STREAMED_PAUSE_FIRST`, `SegmentDownloaderTest` and
`TorrentPublicV2WorkflowTest`) and states the rule in `HttpEngine`'s KDoc.
`DownloadQueueTest.BlockingHeadEngine` overrides only `head`, which resolve keeps calling.
`Ketch.httpEngine` is never wrapped, so `networkInterfaces()` keeps its
`as? ConfigurableNetworkHttpEngine` cast. `core.hash.Sha256` is public under `@KetchInternalApi`
so `library:torrent` can import it. The transfer plan's export reader (its §7.4) shares `Sha256`,
a core `fileIdentity` (torrent's `torrentFileIdentity` moved) and the positional-read code behind
`readAt`; its shared prerequisite F14 adds them, with `@KetchInternalApi`, before PR3. The
helper's relay engine is `library:relay`'s guarded client (§11); the private `globalLimiter` and
the helper's local activity are reached only through `helperRuntime`.

### 14.3 Configuration

`config.toml` holds settings only (`ConfigStore` ignores unknown names); identity and pairings live
in `device.json` (§7.1), which the CLI daemon and the apps write.

| Section | Keys and defaults |
| --- | --- |
| `[helpers]` (owner side) | `mode = "auto"` (`off`, `auto`), `maxTotalLanes = 16`, `lanesPerHelper = 2`, `minBytes = 33554432`, `excludeHosts = []`, `acceptReverse = true` (desktop, CLI) |
| `[helping]` (helper side) | `enabled = false`, `host = ""` (LAN interfaces; `0.0.0.0` for a VPS), `port = 8643`, `network = ""` (`cellular` for "Help over mobile data"), `maxStreams = 4`, `maxStreamsPerOwner = 4`, `maxStreamsPerOriginHost = 4`, `maxBytesPerSecond = 0`, `dailyBytesPerOwner = 53687091200` (mobile defaults per §11), `allowMetered = false`, `minBatteryPercent = 20`, `yieldToLocalDownloads = true` |
| `device.json` | `instanceId`; `pairings[]`: `id`, `name`, `endpoints`, wrapped `key`, `helpsThisDevice`, `allowHelpFor`, `sharesConnection`, `shareSignIns`, `allowPrivateCidrs`, `blocked[]` (key fingerprint, endpoint, until) |

### 14.4 Apps (Lanes & Fleet)

- **Inspector → Connections**: lanes grouped by device ("This Mac · 4 connections · 12 MB/s",
  "NAS · 2 · 8 MB/s"), each group headed by its `DevicePennant`, labelled "#n" from
  `LaneInfo.number`. Exclusions read plainly with an action: "NAS can't open this link (403)",
  "Not faster with NAS" (+ "Move to NAS" when it is always on and a fleet device, §3), "Helpers
  aren't used: this link carries sign-ins · Share with NAS…". "Add help ▸" lists eligible helpers
  and adds one to the task (`Auto.added`, or `Only` when the task already has picked helpers); each
  group has Stop (`stopHelp`). A parked lane reads "Too little left to split"; the live caption
  becomes "+ adds a connection when enough is left to split".
- **Connections control**: the stepper, row menus and selection bar, which offer Auto since #353,
  set this device's lanes only; helper groups change through "Add help" and Stop.
  `autoSegmentsOf` (`InspectorControls.kt`) takes Auto's value from the count of local `LaneInfo`
  (`instanceId == null`) on devices that list `task.lanes`, since the export (§6.10) is no longer
  one segment per connection; other devices keep the segment count.
- **Colour**: an explicit amendment of ux-redesign §3.2.5. Local lanes keep the accent ramp;
  relayed lanes use the helper's `deviceHue` with the same alpha ramp, and group headers carry the
  pennant. `LaneStrip` colours by `LaneInfo.instanceId` only when helpers are present.
- **Activity tab**: stacked bands per device in pennant hue, as the Pulse sparkline already does
  under All devices. **Task row**: a small stack of helper pennants.
- **Devices page** (owner role): a Helpers section with "Add helper" (shows an owner-role code and
  link), each paired helper with "Helps This Mac download: Off · Picked downloads ·
  Automatically", "Share sign-ins with NAS", "Shares this connection", Unpair and Block; other
  devices' cards show "Helps This Mac download" only when the owner is the embedded device.
  Remote owners get the same section through their own controller.
- **Sharing page** (helper role): "Help other devices download", a helper-role code and link
  (15-minute countdown), devices this one helps with Stop, and limits. iOS has no Sharing page, so
  its helper role sits on the Devices page. Android adds "Help over mobile data". The card chip
  reads "Sharing :8642 · Helping :8643".
- **Presence**: `DevicePresence` reads `HelpingStatus`, so a helper's live line reads "Helping ·
  3 MB/s" in the sidebar, switcher and Pulse bar; All devices counts speed once, at the owner.
- `KetchCommands` gains "Get help from ▸ device" and "Stop help". Send to and Move to stay the
  whole-task tools, carrying data once task transfer's keyed `AppState.sendTo` lands
  (task-transfer.md §12.1); `forDevice()` and the transfer manifest map `helpers` through
  `HelperPolicy.forOtherDevice()` (§5.1; §18 for the manifest). While a transfer runs, the row and
  inspector show its progress overlay instead of lanes: a frozen task has none.
- Copy: glossary rows for "helper", "help (with a download)" and "helper link"; strings in
  `strings_helpers.xml` as `UiText`, passing `HardcodedTextTest` and `LocalizationResourcesTest`;
  colours only from `KetchColors.deviceHue`. Android's lane `ProgressStyle` takes device hues.
  "Auto" already names the speed mode and, since #353, the connection count ("Auto (4)"), so
  helper modes read "Picked downloads" and "Automatically" and the Connections tab never shows two
  different Autos.

### 14.5 CLI

```
ketch helpers [--server host:port] [--token t] invite [--role helper|owner] | pair <link>
              | list | unpair <id> [--now|--block] | helping on|off
ketch <url> --helpers auto|off|<id,...>
ketch server [--helping]                  # starts RelayServer per [helping]
```

`ketch helpers` is a client of the running daemon's admin API through `RemoteKetch` (loopback and
the configured token by default). The desktop app, `ketch server` and `ketch mcp` share `ketch.db`,
and so `device.json` and one `instanceId`; two processes running relay sessions under one id would
abort each other's sessions. A process therefore runs helper roles (`RelayServer`,
`HelperManager`) only while it holds an exclusive file lock on `device.json`, released by the OS
on exit or crash; `ketch mcp` never takes it. The one-shot `ketch <url> --helpers` takes it to load
the pairings and, when the desktop app or a daemon holds it, exits with "Helpers are in use by
Ketch here; add the download there". Task transfer keeps its own `<db>.transfer-lock` for
recovery, freeze and release (its §7.9); neither lock implies the other.

## 15 Code changes by module

| Module | Changes |
| --- | --- |
| `library:api` | `HelperPolicy` with `HelperPolicySerializer` and `forOtherDevice()`, `LaneInfo`, `TaskLanes`, `api.helper.*`; `DownloadRequest.helpers`; `KetchFeatures.TASK_LANES`/`HELPERS`; `HelpersUnavailableReason.UNKNOWN_SIZE`; `DownloadTask.lanes`/`setHelpers`/`stopHelp`; `KetchApi.helpers`; `KetchStatus.instanceId` (PR2, task transfer's F7); `@KetchInternalApi` (task transfer's F14); signed-URL keys in the shared sign-in classifier (`isSensitiveHeader`, §5.1); `redactUrl` fragment masking |
| `library:core` | `core.lane`: `RangeLedger`, `LaneScheduler`, `LaneTuning`, `EgressGroup`, `LaneFailure`, SPI, `HttpRangeFetcher`, `RepresentationPin`, `RangeResponseValidator`, `PinAdjudicator`, `AuditSampler`; `core.hash.Sha256` (moved, public opt-in), core `fileIdentity` and the positional-read code (task transfer's F14, before PR3); `HttpEngine.fetch`; `FileAccessor.readAt` over that code; durable Apple flush; `ServerInfo.date`/`contentEncoding`; network engines' `fetch`; `HttpDownloadSource` pin + scheduler; `DownloadExecution` checkpoints and narrowed retry; `DownloadContext.lanes`/`executionNonce`; `Ketch(instanceId, laneMode, laneTuning)`, `Ketch.features`, `laneProvider`, `helperController`, `helperRuntime`, `shutdown` joining executions (the `DownloadCoordinator.closing` flag exists since #353); later delete `SegmentedDownloadHelper`, `SegmentDownloader`, `resegment`, `pendingResegment` |
| `library:ktor` | `KtorHttpEngine.fetch` with metadata, preconditions, identity encoding and stop |
| `library:ftp` | `FtpRangeFetcher`; leaves `SegmentedDownloadHelper`; 421 → Throttled |
| `library:torrent` | Imports core `Sha256` and `fileIdentity` (the transfer plan's F14); nothing else |
| `library:relay` (new; android, ios, jvm) | `RelayCrypto` (expect/actual HMAC, AES-CTR, ECDH, digests, random; HKDF), `InviteCodec`, pairing and session handshakes, `KeyWrapper` actuals, `RelayFrameCodec`, `RelayClient`, `RemoteRelayFetcher`, `ReverseRelayFetcher`, `HelperManager` (`LaneProvider` + `HelperController`; eligibility, gain, slots, audits path, digests), `RelayService`, `RelayUrlPolicy` + guarded engine, `ReverseLaneClient` |
| `library:server` | `RelayServer` (CIO, sealed realm, `Origin` refusal, socket caps, LAN binding through `pairingAddresses`' interface filter without its virtual-interface fallback, moved here from `app/shared/util/PairingLink.kt` so the CLI daemon binds the same addresses); `/api/helpers` admin routes over `ketch.helpers` with the loopback rule; task helper routes; `?lanes=1`; `RouteGroup.HELPERS` in the `routes` parameter task transfer's M1 adds (its §4.5); CORS allows `PATCH` (`/api/helpers/{id}`); `coerceInputValues`, constant-time bearer compare and a blank token treated as none (PR1 item 8, its F10b) |
| `library:endpoints` | `Api.Helpers`, `Api.Relay`, models, `TaskEvent.LanesChanged`, `TaskSnapshot.lanes` |
| `library:remote` | `RemoteHelperController` (non-null, `supported` from `KetchStatus.features`); `RemoteDownloadTask.lanes`/`setHelpers`/`stopHelp`, response lanes guarded by `appliedEvents` (#353); `?lanes=1` |
| `config` | `HelpersSettings`, `HelpingSettings`; `DeviceStore` (`device.json`, 0600, pluggable `KeyWrapper`, the helper-role lock of §14.5) |
| `app/shared`, platforms | Devices and Sharing helper sections, confirmation sheet, inspector lanes by device, `LaneInfo`-driven `ConnectionsTab`/`LaneStrip`, `RemoteConfig.instanceId` and `deviceHue` by instance id (PR2, task transfer's F7), `autoSegmentsOf` from local lanes, `DevicePresence` helping line, Move to <device> from the inspector, `MdnsDiscoverer.browse`, `ketch://helper` intake (manifest host, `isPairingLink` sibling), `ForegroundPolicy`, `device.json` kept out of Android and iOS backups, `InstanceFactory` wiring of `HelperManager` and `RelayServer` |
| `cli` | `helpers` client commands, `--helpers`, `RelayServer` in `server` |
| `docs` | `architecture.md` (lanes), ux-redesign §3.2.5 and W5 amendments, glossary rows, new `docs/helpers.md` |

Prerequisite fixes (PR1), verified at `ec52a306` and rechecked at `d76c7460` (after #353, #354
and #359). With #353, #359 (transfer's F1 and F2) fixed most of item 1 and the deletion half of
item 3 as first proposed; the rows keep the rest. Task transfer's §7.1 keeps one list of the fixes
both plans need: items 1, 2, 3, 4, 7 and 8 are its F2b, F3, F8, F13, F6 and F10b; items 5 and 6
are this design's alone:

| # | Hazard | Evidence at HEAD | Fix |
| --- | --- | --- | --- |
| 1 | A record without segments restarts under a new name beside its own file | `HttpDownloadSource.download` and `FtpDownloadSource.download` publish their segments and then `preallocate` the full size, but `runDownload` first saves after `saveIntervalMs` (5 s). A crash in between, or `Ketch.close()` before the final save runs (item 3), leaves `segments = null` (#359) beside a full-size file. On restart `DownloadCoordinator.resume` returns `false`, and `start` → `DownloadExecution.executeFresh` → `resolveDestPath(deduplicate = true)` picks "name (1).ext" for a folder or default destination, leaving the preallocated file (#359's `pause_unknownSizeStream_resumesIntoSameFile` names the same rename). A legacy `[]` record beside a preallocated file, which #359 restarts, does the same | `executeFresh` reuses `record.outputPath` when the record has one, so a restart overwrites its own file |
| 2 | Checkpoints can exceed durable bytes | `saveJob` and the `NonCancellable` final save never flush; the flush after a finished transfer runs after the final save, and #359's `discardProgress` resets progress only when that flush throws, not when the process dies between the two; `DownloadCoordinator.pause` flushes right after `job.cancel()`, before the final save; on Apple targets okio's flush is `fflush` | Snapshot → sync → persist; `F_FULLFSYNC` on Apple; a failed sync persists nothing and keeps #359's reset (§9.7) |
| 3 | `Ketch.close()` does not wait for final checkpoints | `DownloadCoordinator.close` sets `closing`, so tasks pause for `Shutdown` and records stay `DOWNLOADING` or `QUEUED` (#353), and files stay because nothing marked the execution as a discard (#359). But `Ketch.close` then cancels the coordinator scope and closes the source resolver, engine and dispatchers at once, so nothing joins each execution's `NonCancellable` final save or `FileAccessor.close`, and the record keeps the last periodic save | `Ketch.shutdown(grace)` joins the executions (snapshot → sync → persist, §9.7) before `close()` |
| 4 | Writes after close recreate the file | `PathFileAccessor.getOrCreateHandle` reopens and creates parents | Closed flag; `writeAt` after `close()` throws |
| 5 | One execution's `Error` can cancel every execution | `DownloadCoordinator.scope = CoroutineScope(dispatchers.network)` | Add `SupervisorJob()` |
| 6 | Speed spike after resume | `buildContext` starts `lastBytes` at 0 | Seed it from the first report |
| 7 | Strict resume-state decode | `Json.decodeFromString<HttpResumeState>` | `ignoreUnknownKeys` + old-JSON tests |
| 8 | Bearer compared with `==`; a blank token half-applied | `KetchServer.configureServer` (`apiToken != null`, `credential.token == expectedToken`) vs `startMdnsRegistration` and #352's `pairing` field (`isNullOrBlank`): a blank token installs `Authentication` and skips `HostValidator`/`CrossOriginGuard` while mDNS says `token=none` and pairing is off; the server's `Json` lacks `coerceInputValues` | Constant-time compare; blank → `null`; `coerceInputValues` in the server's `Json` (§9.8) |

Already fixed at HEAD and dropped: pause before the first byte (`RealDownloadTask.pause` accepts
`Queued`); a known-size download stopped before its first segments completing as a zero-filled
file, also after an URGENT preemption in `applyRateLimit`'s delay (#359, transfer's F2:
`runDownload`'s `savedSegments` keeps `null` until the source publishes segments,
`DownloadCoordinator.resume` starts a legacy `[]` of known size from zero, and
`HttpDownloadSource.resume` and `FtpDownloadSource.resume` split such a resume into fresh
segments; unknown-size streams and torrents keep `[]`); partial files deleted after a failure or
`remove(deleteFiles = false)` (#359, transfer's F1: `DownloadCoordinator.cancel` marks the
execution through `discardPartialFile()` for `cancel()` and `remove(deleteFiles = true)` only,
which also keeps them on `Ketch.close()`, where #353's `shuttingDown` check is gone); a failed
final flush completing over bytes that may not be on disk (#359: `discardProgress`);
`setConnections`/`setSpeedLimit` on inactive tasks (persisted under `settingsMutex`); CLI `server`
restoring tasks and advertising mDNS (`Main.kt` listens, then `ketch.start()`; `KetchServer.start`
registers before `awaitStop`); `updateConfig` promoting queued tasks (`DownloadQueue.updateLimits`);
the stale `supervisorScope` claim in `docs/architecture.md`; CORS `*` on tokenless servers
(`HostValidator`, `CrossOriginGuard`). The mDNS self-filter is PR2.

## 16 Delivery plan and testing

Each PR ships on its own. Helpers stay invisible in the apps until PR10; PR8 and PR9 are reachable
only through the CLI and `[helping] enabled`.

1. **PR1 prerequisite fixes** (§15), all but items 5 and 6 on task transfer's shared list. Exit:
   one regression test per item, including `crash_afterPreallocate_restartsIntoSameFile` (a folder
   destination; the restart writes the origin's bytes into the first file and creates no
   "name (1).ext"), `shutdown_duringDownload_joinsFinalCheckpoint` (#353's `KetchCloseTest` and
   #359's `close_midDownload_keepsPartialFileAndRestartResumes` already cover the kept partial
   file), a `FakeFileAccessor` whose `crash()` drops unsynced writes, proving persisted segments
   never exceed synced bytes, and a failed periodic sync that persists nothing and fails the task
   with `Disk`. Two cases of #359's behaviour that it left untested join them:
   `preempt_beforeFirstSegments_downloadsOriginBytes` (an URGENT task preempts a victim in
   `applyRateLimit`'s delay; the victim resumes on its own through `DownloadQueue.startTask`'s
   fallback to `start` and completes with the origin's bytes) and
   `torrent_pausedWhileWaitingForSlot_resumesThroughSource` (`[]` kept for a `managesOwnFileIo`
   source). #359's `DownloadCoordinatorResumeTest`, `DownloadExecutionCleanupTest` and
   `resume_emptySegmentsWithFullSizeFile_downloadsFromZero` (HTTP and FTP), and #354's
   `resume_unknownSize_restartsFromZero`, pass unchanged.
2. **PR2 identity**, task transfer's F7: `DeviceStore` with `instanceId` in `device.json`,
   `Ketch(instanceId)`, `KetchStatus.instanceId`, `RemoteConfig.instanceId` stored on connect,
   refusal of the app's own server by id, mDNS `boot` nonce and `pv`, the Add device self-filter
   by nonce, `deviceHue` by instance id, `device.json` kept out of Android and iOS backups (keys
   are wrapped only from PR8). Exit: old status JSON decodes; Add device hides this device by its
   nonce; a device keeps its hue across an address change; a phone restored from a backup gets a
   new id.
3. **PR3 range correctness** under `LaneMode.LANES`: `HttpEngine.fetch` with stop and network,
   validator, pin with aliases, `If-Range` + `If-Match`/`If-Unmodified-Since`, identity encoding,
   adjudicator, re-pin, resume through the adjudicator, `FileAccessor.readAt` (core `Sha256` and
   the read path come first, as the transfer plan's F14), wrappers overriding `fetch`. Exit:
   `RangeResponseValidatorTest` (exact 206, `*` locally and relayed, wrong total, weak ETag, gzip,
   416, 200 with and without the pinned validator);
   `PinAdjudicatorTest` (alias on equal windows, no alias on a changed first or last window,
   FileChanged only after consistent probes, no FileChanged while any group still sees the pin,
   re-pin before the first commit, probes deferred during `Retry-After`). Integration in both
   modes: a load-balanced two-backend origin with different ETags completes; a
   `MultiNetworkHttpEngine` over two fake networks reaching different edges completes; an origin
   that ignores `If-Range` and swaps same-length content yields `FileChanged`, never a mixed file;
   a scheduled task whose origin changed between resolve and start completes with the new content;
   an origin answering `bytes a-b/*` completes with no helper involved.
4. **PR4 `RangeLedger`** (pure, `commonTest`). Exit: seeding, lowest-offset claim, weighted split
   with setup term, request-end snapping, alignment, floors, endgame waiver and slow-victim
   takeover, victim cooldown, un-split, admit truncation, rejected commit at `offset != cursor` or
   past `limit`, release, legacy restore, `[]` of known size restoring as all free, export cap and
   never `[]` after seeding; a seeded 10k-step simulator (join, leave, commit, stall, audit
   invalidation, provenance overflow) asserting no byte in two claims, export ≤ 64, monotone done
   outside invalidation, and a 2 KB/s lane holding the last 400 KiB being taken over.
5. **PR5 `LaneScheduler` for HTTP, local lanes**, under `LaneMode.LANES`, plus `LaneInfo`,
   `TaskLanes`, `DownloadTask.lanes`, `TaskSnapshot.lanes`, `?lanes=1` and the apps'
   `ConnectionsTab`/`LaneStrip` reading `LaneInfo`. Exit: `HttpDownloadIntegrationTest` in both
   modes, its PAUSE_FIRST engine hooking `fetch`; #354's
   `download_unknownSize_streamsAndRecordsSize` and `resume_unknownSize_restartsFromZero` keep their
   request lists (`none`; `none`, `none`). #359's `DownloadCoordinatorResumeTest` and
   `DownloadExecutionCleanupTest`, with PR1's `preempt_beforeFirstSegments_downloadsOriginBytes`,
   run in both modes: a pause before the seed saves no segments; a failure, `close()` or
   `remove(deleteFiles = false)` keeps the file and the export; `cancel()` deletes the file only
   after every lane has released; and an HTTP twin of `flushFailure_keepsFileButResetsProgress`
   keeps the export's layout with no progress.
   `setConnections_restartsActiveTransferWithoutLosingBytes` keeps 3 requests in legacy mode; in
   lanes mode its 65,539-byte file is below every split floor, so with a short `LaneTuning` stall
   bound the new lane parks and the blocked lane is stall-revoked: 2 requests. A new 16 MiB case
   that throttles the first response instead of blocking it asserts exactly one extra request and
   the first never cancelled. `LaneSchedulerTest` on virtual time with a `Channel`-driven
   `FakeRangeFetcher`: adding a lane never cancels a sibling; one lane's network failure neither
   cancels siblings nor ends the task while others progress; 429 halves only its group; a solo task
   told `Retry-After: 120` completes; fast-failing helper lanes during a local pause do not fail the
   task; streak and watchdog failures; progress never exceeds bytes written; truncation writes only
   the allowed prefix and logs no error; twins of #353's Auto cases in `SegmentedDownloadHelperTest`
   (`setConnections(0)` converges to the run config's `maxConnectionsPerDownload`, an equal
   effective count changes nothing, a change made while a lane drains still applies), except that
   Auto never lifts a cap set by 429 or `RateLimit-Remaining` and a 429 leaves `maxConnections`
   untouched. An old-client decode of a `TaskSnapshot` carrying an unknown `LaneState` falls back to
   the default instead of failing the task list.
6. **PR6 FTP on the scheduler**. Exit: `:library:ftp:jvmTest` green; a live connection change that
   restarts no other lane.
7. **PR7 flip and delete**: default `LANES` once both modes pass and a loopback benchmark (1 GiB
   and 32 MiB, 4 connections, median of 5) is within 5% of `main` in throughput and CPU; delete
   the legacy path and `LaneMode`; update `docs/architecture.md`.
8. **PR8 `library:relay` foundation + `RelayServer`**, CLI only: crypto actuals, ECDH, helper links
   in both roles, the three-message pairing with SAS, sessions and replay window, `hello`/`status`,
   admin `/api/helpers` with the loopback and forwarding-header rules, `RouteGroup.HELPERS`,
   `DeviceStore` pairings and key wrapping (a store restored without its wrapping key gets a new
   `instanceId` and no pairings), the `device.json` lock (§14.5), `redactUrl` fragments, CLI
   `ketch helpers`. Exit: RFC 4231 HMAC, RFC 5869 HKDF, AES-CTR and P-256 ECDH vectors on JVM and
   `iosSimulatorArm64`, plus a JVM↔iOS handshake transcript; off-curve key rejected; replay, stale
   timestamp and wrong-hint rejection; known-answer vectors for both directions and for a
   multi-order `orders` response, each element under its own key; server tests (unsealed 401, any
   `Origin` 403, invite consumed once, burned after 3 failures, `/pair` lockout, an admin bearer
   cannot call `/api/relay/v1`, a tokenless non-loopback or forwarded admin call gets 403,
   `ketch://pair` refused with 400).
9. **PR9 forward relayed lanes** (library, server, CLI, API): `RelayService`, codec, URL policy
   and guarded engine, `RemoteRelayFetcher`, `HelperManager`, `HelperPolicy`, `setHelpers`,
   `stopHelp`, task helper routes, `--helpers`, `--helping`, gain controller, audits, digests,
   `RemoteKetch` lanes and helpers. Exit: codec tests (fragmented reads, oversize, skippable and
   must-understand types, bad tag); `RelayUrlPolicyTest` (loopback, link-local, metadata IP,
   IPv4-mapped, ULA, NAT64, 6to4, the helper's own public address, private CIDR per pairing,
   proxy configured, null-validator 200, redirect hop, rebinding through a fake `Dns`, sign-ins
   dropped on a cross-origin hop, `https` → `http` refused); jvmTest
   `CooperativeDownloadIntegrationTest` with a loopback origin (deterministic bytes, strong ETag,
   per-connection rate cap, behaviour switchable per client) and an owner on SQLite plus two
   helpers on ephemeral ports: a helper joins at 30% and local lanes issue no extra request; a
   helper killed mid-stream; DRAIN; 403 at one helper only; another ETag at one helper → excluded,
   not `FileChanged`; a real origin change → `FileChanged`; owner killed and restarted; pause and
   immediate resume against a 2-slot helper with no 429; a 1 MB/s task limit within 10% across
   three instances; a 10 KB/s task limit with one helper finishing with origin bytes ≤ 1.05 × file
   size; a lying helper caught by audit, Blocked and its spans refetched; a helper-only task with
   no audit path failing closed; an S3-style 32-hex ETag that is not the file's MD5 never failing
   a task; output SHA-256 equal to the origin's every time.
10. **PR10 apps**: Devices and Sharing helper sections, confirmation sheet with SAS, inspector by
    device with per-task Add help and Stop, exclusion copy, colour amendment, presence line,
    remote-device rules, glossary and strings. `HelperPolicy.Auto` becomes reachable in the apps
    here. Exit: `RemoteKetch` MockEngine tests (lanes decode, an unknown enum value decodes to
    `UNKNOWN`, a status without the `helpers` feature gives `supported == false`, a command response
    never overwrites lanes from a newer `lanes_changed`); `HelperPolicyTest` (`forOtherDevice()`
    keeps `Off` and `signIns = false` and turns `Only` into `Off`) and a `forDevice()` test that
    Send to and Move to use it; snapshot scenarios under `-Psnapshots`.
11. **PR11 Android helper (second WAN)**, gated on the PR9 harness and one real VPS measurement:
    `RelayServer` in `KetchService`, cellular-bound relay engine, "Help over mobile data",
    `WifiLock`, `ForegroundPolicy`, metered and battery gates, drain on `onTimeout`. Exit:
    emulator run with cellular binding; unit tests for the gates.
12. **PR12 reverse lanes**, same gate, after the Darwin streaming spike: orders, streamed POST,
    fence, iOS foreground helper. Exit: jvmTest helper without a server; `fence_stale` after owner
    restart; truncation of a reverse stream; manual simulator run.
13. **PR13 polish**: Activity bands, task-row pennants, notification, commands, remaining
    localization.
14. **PR14 optional**: FTP relay.

Order across both plans (task-transfer.md §14): the shared prerequisites (its §7.1: PR1 but items
5 and 6, PR2 as its F7, and F14's `Sha256`, `fileIdentity` and read path from PR3) → its M1 →
PR3–PR7 → its M2 → PR8 onward → its M3 → its M4, M5. From PR3 its origin preflight uses the
conditional probe under `LaneMode.LANES` (§9.4). M1 and PR5 change the same files
(`DownloadCoordinator`, `DownloadExecution`, `HttpDownloadSource`) but not the same behaviour;
once both are in, transfer's core integration tests run in both `LaneMode`s, and after PR9 they
and `CooperativeDownloadIntegrationTest` export a helped task whose relayed bytes are partly
unaudited (§9.6).

Testing follows `docs/development/testing.md`: `kotlin.test` and `kotlinx-coroutines-test` only;
hand-written fakes (`FakeRangeFetcher`, `FakeHttpEngine` with `fetch`, `FakeFileAccessor` with
written and synced interval sets and `crash()`, a fake `TimeSource`, a `FakeRelayTransport` that
drops, delays, duplicates, reorders and partitions); `commonTest` by default, jvmTest only for
sockets, `testApplication`, SQLite and platform crypto; algorithms tested through production code
(the split formula through `RangeLedger`); no data-class or plain serialization tests, but
old-JSON decode tests for `HttpResumeState`, `KetchStatus`, `DownloadRequest` and `TaskSnapshot`
(extending #353's `KetchStatusTest` and `TaskSnapshotCompatibilityTest`), and an unknown
`HelperPolicy` type decoding as `Off`;
every integration case bounded by a timeout and cleaning up servers, clients and temp files.

## 17 Alternatives considered

| Alternative | Why not | Grafted |
| --- | --- | --- |
| **Swarm**: every instance a peer over hash-sealed pieces, an elected soft-state tracker, multiple consumers | Taxes solo downloads (2 MiB runs, SHA-256 per piece, frequent fsync), redefines `Segment` and `maxConnectionsPerHost`, needs a state directory core lacks, tracker epochs collide under partition, a pause can trigger adoption | Signed-query classification; secrets in memory only; owner cross-checks of helper bytes (audits); per-URL origin byte cap; `Sha256` moved to core; `shutdown(grace)`; deterministic churn simulator; constant-time bearer compare |
| **Leased workers**: helpers dial out, take TTL leases on piece runs and push verified 1 MiB slices | The owner must listen (no iPhone owners; Android owners expose a server), stop-and-wait slices cap WAN lanes, content staged on phones, the largest protocol surface | Reverse-lane fencing (verify-then-write, fence re-checked under the write lock, nonce per execution); snapshot → sync → persist; task-level watchdog; session counters with a sliding replay window; DRAIN/NOW/BLOCK; daily budgets, metered and battery gates; benchmark gate; a finite-timeout relay client |
| **Incremental**: same topology, opt-in scheduler, bearer tokens between devices, engine overload | Cleartext bearer tokens on the relay plane; two schedulers and two retry layers without a removal date; writes under the slot lock; no adjudication | Admin-route hardening; additive `HttpEngine` overload; a constructor switch with the suite in both modes; a helper hook (`helperRuntime`); manual redirects in a guarded relay engine; `excludeHosts`; draining low contributors; "create it on the daemon" guidance |
| Keys derived from a device access token | The token is a cleartext bearer that grants full admin (user-chosen on the CLI or in `config.toml`, a random UUID in the apps, which since #352 also hand it to every device their user allows); one sniffed proof allows an offline guess of a chosen one, and the owner would receive another device's admin rights | Controller-minted invites through the admin API |
| Admit helpers through `/api/pairing` (#352): fetch the helper's access token, then use the controller route | Grants control where only help was asked, and an offer of help would give the helper control of its owner; needs a token-holding admin server with an approver on the asked device (none on iOS or the CLI daemon); its four digits are chosen by the requester and sent in cleartext, so they authenticate no key | Open question 11 |
| Static PSK keys without ECDH | No forward secrecy; a leaked key decrypts recorded traffic | — |
| Demote a task to unpinned after validator flaps | Drops validator checks exactly where versions can mix | Aliasing by comparing committed windows |
| Relay `HttpEngine` inside `MultiNetworkHttpEngine` | Global, not per task; round-robin by request count, HEAD included; removal only by drain; a new relay idles until a resegment; a lost one triggers a whole-download retry | Per-network egress groups |
| Keep `SegmentedDownloadHelper`, resegment on join | Stop-the-world, list growth, gap-count floor | — |
| Helpers write part files, merged later | Breaks the single writer, needs reads and transfer, no SAF | — |
| Sub-tasks through `POST /api/tasks` | Take queue slots, create files on helpers, no rebalancing | Send/Move stays the whole-task tool |
| Duplicate or hedged endgame | Extra origin load; weighted takeover, the slow-victim cut and per-kind stall revokes cover the tail | — |
| Helper write-stall timer | Misreads a throttled owner as gone and multiplies origin reads | Owner heartbeat |
| Relay routes on the admin port | A tokenless admin server would expose pairing; phones would need their admin API open to help | — |
| TLS on CIO through another server engine | A heavier engine and certificates for LAN devices when sealing already protects the plane | — |
| `cryptography-kotlin` for AES-GCM | A new dependency with an unverified iOS provider, when platform AES-CTR + HMAC gives the same guarantee | Open question 3 |

## 18 Risks and open questions

Risks:

1. **Ceiling and double traffic.** The owner's link caps every task and relayed bytes cross the
   helper twice; gains need a different egress, and the gain controller plus the
   shares-this-connection default keep Auto honest. PR11 and PR12 wait for the PR9 harness and
   one real VPS measurement.
2. **Core data-path rewrite.** Mitigated by the switch, both-mode suites, the simulator and the
   5% benchmark gate before PR7.
3. **Pinning and sign-ins lower the hit rate.** Weak validators and IP-bound URLs exclude helpers;
   the browser extension forwards cookies by default (`forwardCookies: true`), so most captured
   downloads need "Share sign-ins with <helper>" before any helper can take them.
4. **Hand-built protocol.** ECDH, encrypt-then-MAC and transcript hashing from platform
   primitives. Before PR8, map pairing and sessions onto a published, analyzed pattern (the closest
   is Noise `NNpsk0`: ephemeral-ephemeral DH plus a pre-shared key) and keep every difference
   explicit (P-256, AES-CTR + HMAC instead of an AEAD). Fixed vectors and JVM↔iOS interop tests
   cover the code; an external review gates PR10, when helpers become reachable in the apps.
5. **Audit strength.** Sampling catches broken helpers, not targeted patches; only a digest does,
   and most origins send none.
6. **Alias residual.** A rolling deploy whose versions differ only outside the compared windows
   can be aliased (§9.4).
7. **Truncation waste** of up to about 4 MiB when a split lands inside the victim's request.
8. **Owner is a single point of stall**; no failover by design.
9. **Residual SSRF on iOS helpers**: no DNS hook in Darwin; authenticated callers only.
10. **Mobile limits**: Android's daily dataSync limit and Doze; iOS helps only in the foreground;
    the phone-as-second-WAN gain ends at the daily cap.
11. **Semantic shift**: charging relayed bytes to the owner's global limit may surprise multi-WAN
    users.

Open questions for the owner:

1. Which use case is primary: aggregating different WANs (VPS, cellular) or offloading to an
   always-on daemon? The second is the [transfer plan](../plans/task-transfer.md)'s MOVE, which
   keeps the partial file. Settle before PR8.
2. Should relayed bytes count against the owner's global speed limit? (Proposed: yes, §12.)
3. Sealing from platform primitives (proposed), or `cryptography-kotlin` for AES-GCM?
4. Never relay unpinned origins (proposed), or allow it behind a per-task flag?
5. Auto by default over helpers the user set to Auto (proposed), or per task only?
6. Helper defaults: yield to its own downloads, refuse metered networks, 2 GiB per owner per day
   on phones, 50 GiB on desktops and daemons?
7. Is FTP relay (PR14) worth building?
8. Hand-off with the partial file is the [transfer plan](../plans/task-transfer.md)'s (§9.6
   covers relayed bytes). Should "Move to <device>" also be offered for a helper that is not a
   fleet device, by first adding it as a device through #352's approval when it advertises
   `pairing=1`?
9. Should desktop and CLI owners run the relay listener by default (`acceptReverse`), which
   owner-role helper links and reverse lanes need?
10. Is a second listening port (8643) acceptable on desktop and Android helpers?
11. Should helper pairing also take a request from a device found on the network, as controller
    pairing does since #352, but on the relay plane and authenticated by a 6-digit code both users
    compare, committed before either ECDH key is seen, so no link or camera is needed?
12. Should a fleet device whose access code the app holds become a helper through the controller
    route without asking its user (proposed, as that code already grants control), or should every
    helper pairing ask the helper's user? Since #352 more devices hold codes.

Alignment with [task-transfer.md](../plans/task-transfer.md), which needs these changes to match
this document (not made here; #361 made the rest):

1. **Helpers in a manifest.** Its K18, §5.3 and §7.5 reset `DownloadRequest.helpers` "as
   `forDevice()` resets it", which yields the default `Auto()` and would let the destination's Auto
   helpers take a task the user kept from helpers, or whose sign-ins it withheld. Map it through
   `HelperPolicy.forOtherDevice()` instead (§5.1), as `forDevice()` does from PR10.
2. **Words.** "Peer" is BitTorrent's (§4): `TransferPeer`, `TransferInfo.peer`, `detail.peer` and
   `peer_unreachable` take device wording.
