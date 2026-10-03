# Cross-device task transfer

**Status:** Proposed, revision 4 · 2026-10-03 · line numbers checked against `main` @ `d76c7460`

This plan makes **Send to** and **Move to** carry a task's downloaded data between Ketch instances
("devices"): partial bytes, per-segment progress, source resume state and finished files, so the
destination continues without fetching those bytes again. HTTP/FTP ship first (M1); torrents of
every format follow (M2). It evolves the existing request-only feature (`AppState.sendTo`/`send`,
`app/state/AppState.kt:1306-1425`), which today re-adds the request on the target and starts over.

It shares identity, prerequisites, read-back, `Sha256` and HTTP resume state with the
[helper-devices proposal](../design/multi-instance-downloads.md) ("H", cited as H§n): §7.1 lists
the shared prerequisites, §14 the order of both plans.

Paths: `api/` = `library/api/src/commonMain/kotlin/com/linroid/ketch/api/`, `core/` =
`library/core/src/commonMain/.../core/`, `torrent/` = `library/torrent/src/commonMain/.../torrent/`,
`server/` = `library/server/src/main/kotlin/.../server/`, `remote/` =
`library/remote/src/commonMain/.../remote/`, `sqlite/` = `library/sqlite/src/`, `app/` =
`app/shared/src/commonMain/.../app/`, `cli/` = `cli/src/main/kotlin/com/linroid/ketch/cli/`.

The body describes M1; torrents (M2) are §8.3, everything later §13, and §15 records how
conflicting review findings and the overlap with H were decided.

---

## 1. Summary and key decisions

A transfer is a small durable two-phase protocol between a **source** (S) and a **destination**
(D), run by a **driver** in the orchestrating app. Bytes move as bounded, digest-checked chunk RPCs
in any order. D stages invisibly and commits with one transaction. For a MOVE, S deletes only when
shown a key that D reveals after committing, and only while S is still prepared. Ambiguity resolves
to a visible duplicate, never to loss.

| # | Decision | Why |
|---|---|---|
| K1 | `KetchApi.transfers: TransferController?` (default null), non-null in `Ketch` and `RemoteKetch`; `KetchStatus.transfer` alone says whether and how an instance takes part. | Mirrors `torrents` (`api/KetchApi.kt:19`); the planner reads `instanceId` in the same `status()` call. |
| K2 | Protocol types behind `@RequiresOptIn` `@KetchTransferProtocol`; callers use the `TaskTransfers` facade in a new `library:transfer`, which declares only M1 surface. | Small stable surface; the library is pre-1.0, so later roles arrive when built. |
| K3 | `library:transfer` depends only on `library:api` and coroutines, never on core-`internal` types. | `internal` does not cross modules; the build has no friend paths. |
| K4 | S is **frozen** for the whole export: a gate checked **before any state write** (queue `enqueue`/`startTask`, scheduler, top of coordinator `start`/`resume`), a persisted row and a `PAUSED` record. | One runnable owner despite preemption, `setPriority`, restarts or other clients. |
| K5 | Imports are **invisible** until commit (a row plus reserved files, no `TaskRecord`) and every later import call needs the per-import key. | Queue, scheduler, `remove()`, failure cleanup and LAN strangers cannot touch staged bytes. |
| K6 | D's commit point is **one SQLite transaction** strict-inserting a freshly minted task id. | One durable decision; no id collisions. |
| K7 | MOVE release needs a **hash-lock**: D mints the commit key at `beginImport` and returns its hash to the orchestrator; S stores it at `prepare`; `release` must show the key and is valid **only from `PREPARED`**. D's imported task cannot be removed, canceled or exported until D **settles**, which needs a key S reveals only once it has left `PREPARED`. | A revealed key cannot be revoked, so D's copy must outlive every possible release; no confused driver, late redelivery or "Keep both" can delete the last copy. |
| K8 | S **prepares** only if D's content root (hash of chunk digests) equals its own. | The source confirms equality before it can delete. |
| K9 | Per-chunk SHA-256 before writing; chunks in any order into a persisted received set; write → fsync → ledger. | Simple drivers, no reorder livelock; the manifest stays core-internal. |
| K10 | `PREPARED` never expires by clock: release, the user's "Keep here", or D's confirmed abort ends it. | No lease-expiry duplicates, no clock-jump hazard. |
| K11 | D chooses paths: sanitized, under its roots, `mustCreate`, never overwritten. Remote export needs the output's recorded identity. | Peer paths mean nothing elsewhere; export must not read arbitrary files. |
| K12 | No new `DownloadState`, `KetchError`, `TaskEvent` subtypes or snapshot fields in M1; a frozen source that was active or paused is `Paused(PauseReason.Transferring)`, a user pause to older clients; a completed or failed one keeps its state. Both answer `409 task_locked`. | Old clients decode whole lists (`remote/RemoteKetch.kt:297-319`) and unknown reasons as `User` (`api/PauseReason.kt:67-82`); M1 drivers run in the app. |
| K13 | `CredentialPolicy.AUTO` carries what D needs for the origin and strips the rest; a D reached **without a token is public**, so AUTO strips all there. | Token-less servers broadcast request headers (`server/TaskMapper.kt:8-17`). |
| K14 | One `instanceId` per task database (H§7.1's `device.json` beside it; one per device in the usual setup), passed as `Ketch(instanceId)` (F7 = H PR2); transfers add only the JVM store lock and `same_instance`. | App, `ketch server` and `ketch mcp` share `ketch.db`; one identity for both plans; no heartbeat writes. |
| K15 | Token-less servers import but never export. | Any LAN host could otherwise read their downloads. |
| K16 | Engine bugs a transfer would hit are fixed first (§7.1; #359 did F1, F2). | A failed resume on D or an unfreeze must never delete or zero-fill the only copy. |
| K17 | M1 is one vertical slice: HTTP/FTP in every state, driven from the desktop, Android and iOS apps; embedded ⇄ daemon directly, daemon ⇄ daemon by relay. A default `ketch server` (no token) only receives data. | The user's case end to end in one merge; the token limit is stated. |
| K18 | Freezing stops a task's helper lanes as a pause does (H§8.6); `DownloadRequest.helpers` is reset, never carried; a task is never transferred and helped at once. | Pairings belong to S's device; helpers hold no task state. |

---

## 2. Goals, non-goals, terminology

**Goals.** (G1) MOVE or COPY a task in any state between two transfer-capable instances an app
reaches, carrying valid prefixes, validators and completed files. (G2) No loss under a crash or
disconnect of any party; one runnable owner after a MOVE unless the user keeps both. (G3) Resume
from D's durable progress. (G4) D trusts no byte it has not hashed. (G5) Old clients and daemons
keep working; new clients fall back to today's send. (G6) Keep the Send to / Move to contract:
credential confirmation, Undo for move, multi-select, drag to a device.

**Non-goals.** Copying a running download without pausing it; the browser as a data endpoint or
orchestrator in M1 (wasmJs has no core; §13.2); NAT traversal or third-party relays; seeding
continuity; `DownloadCondition`s, which are not persisted (`api/DownloadCondition.kt:32-38`; such
tasks land paused).

| Term | Meaning |
|---|---|
| S / D | Instance holding the task before / receiving it. |
| Orchestrator, driver | The app that plans, drives and recovers a transfer (holds admin tokens; id = its embedded `instanceId`) / its code pumping chunks between two handles. |
| MOVE / COPY | D becomes the only owner and S deletes after a proven commit / both keep independent tasks. |
| Freeze, seal, prepare, commit, release, settle | S's export lock; D's fsync + root; S's vote; D's atomic insert; S's gated deletion; D's unlock once S has left `PREPARED` (§9). |
| Hold | The Undo window after commit, during which S refuses `release` from other orchestrators. |
| Import key | Capability from `beginImport`, required by later import calls unless the caller holds D's admin token. |
| Fallback / metadata-only | Today's request-only send (§12.2) / a protocol export without payload. |
| Admin token / RELAY | A server's `apiToken` / the route through the app (§3.1), not H's relay wire. "Owner" means only a task's runnable owner, as in H. |

---

## 3. Topology (M1)

### 3.1 Routes

| Route | Driver | Bytes | Needs |
|---|---|---|---|
| LOCAL_PUSH | App hosting **embedded S** | S disk → app → `PUT` → D | app reaches D |
| LOCAL_PULL | App hosting **embedded D** | S → `GET` → app → D disk | app reaches S |
| RELAY | Native app hosting neither side | S → app (bounded window) → D | app reaches both |

The driver is identical everywhere; only the handles differ (in-process `Ketch.transfers` or
`RemoteKetch.transfers`). Delegated PULL/PUSH, same-host handoff and the web are §13.

### 3.2 Reachability (M1)

The app hosting an engine drives LOCAL_* with outbound calls only, so it works on iOS and with its
own server off; only that app writes into its engine (Android may import into a SAF tree). Two
remotes use RELAY (two hops, shown, warned on metered links); an embedded engine elsewhere is a
daemon while its Sharing server runs (iOS has none,
`app/shared/src/iosMain/.../MainViewController.kt:200-216`).

| Situation | M1 |
|---|---|
| iOS embedded ⇄ daemon | LOCAL_* from the iOS app; suspension pauses the pump; ATS blocks cleartext to qualified names (no `NSAppTransportSecurity` in `Ketch-Info.plist`), as for remotes today |
| Android/desktop embedded | LOCAL_* from that app; with Sharing on (always a token) other apps can RELAY through it |
| Token-less daemon as D / as S | data (import key, credentials stripped) / fallback, captioned "<S> needs an access code to send downloads", also hinted by `ketch server` at startup |
| Same `ketch.db`: desktop app (`app/desktop/.../main.kt:494`), `ketch server`, `ketch mcp` (`cli/Main.kt:366-368, 630-632`) | one identity (the `device.json` beside it), refused (`same_instance`), as is the app's own server re-added as a remote; separate stores on one host relay over loopback |
| Web app, CLI, MCP | fallback / — / — (§13) |
| Desktop browser-extension server | never (no `TRANSFERS` route group, §4.5) |

### 3.3 Planner (`TransferPlanner`, library:transfer)

1. `status()` from both. Equal `instanceId` → `same_instance`. `transfer == null` (also an engine
   built without an `instanceId`), a missing capability or the source type missing from D's
   `sources` → fallback plan ("starts over").
2. MOVE needs `durable = true` on both (not `InMemoryTaskStore`).
3. One side in-process → LOCAL_*; otherwise RELAY if `allowRelay`.
4. Credentials per §5.5; D reached without a token is public.
5. Free space and the origin probe run in `beginImport`, before any bytes move.

### 3.4 Sequences

§9.3 shows a MOVE driven by the app hosting S (LOCAL_PUSH). LOCAL_PULL only swaps which side is
in-process, and RELAY makes both remote. A COPY ends at `prepare`: S compares roots, unfreezes and
restores its task (`DONE`), then D commits; there is no hold and no release.

---

## 4. Public API (M1)

### 4.1 Everyday additions (`library:api`)

```kotlin
interface KetchApi {
  /** Null only for implementations that do not speak the protocol; see [KetchStatus.transfer]. */
  @KetchTransferProtocol val transfers: TransferController? get() = null
}

@Serializable data class KetchStatus(
  /* existing fields, including features (#353) */
  val instanceId: String? = null,              // H§7.1 (H PR2); null from old servers
  val transfer: TransferCapabilities? = null,      // null: unsupported, no instanceId, old server
)

/** Unknown major versions fail closed, like TorrentCapabilities. */
@Serializable data class TransferCapabilities(
  val protocolMajor: Int = 1,
  val names: Set<String> = emptySet(),      // "export", "import"; open strings
  val sources: Set<String> = emptySet(),    // source types whose data can move: "http", "ftp"
  val durable: Boolean = false,             // rows survive restarts; MOVE needs both
  val maxChunkBytes: Int = 1 shl 20,
)
```

`Ketch` and `RemoteKetch` always return a controller; fakes (`FakeKetchApi`, `DisconnectedApi`)
compile unchanged. Unlike `TorrentController.capabilities()`, support lives in `KetchStatus`
because the planner reads `instanceId` in the same call. `KetchStatus.features` (#353,
`api/KetchStatus.kt:26`) lists boolean behaviours; transfers need structured capabilities, so they
add no `KetchFeatures` string. `TorrentCapability.IMPORT/EXPORT`
(`api/torrent/TorrentCapabilities.kt:16-17`) keep their torrent-controller meaning.

### 4.2 Protocol (`com.linroid.ketch.api.transfer`, opt-in)

```kotlin
@RequiresOptIn("Low-level task transfer protocol; use TaskTransfers", RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY) annotation class KetchTransferProtocol

/** Source role; every call is idempotent per transferId. */
@KetchTransferProtocol interface TransferSource {
  suspend fun openExport(request: ExportRequest): ExportOpened          // freezes, stores manifest
  suspend fun manifest(transferId: String): ManifestEnvelope            // exact bytes as issued
  suspend fun readChunk(transferId: String, index: Int): TransferChunk
  /** Compares D's root and binds D's commit lock; MOVE enters PREPARED, COPY unfreezes. */
  suspend fun prepare(transferId: String, request: PrepareRequest): Prepared
  /** MOVE: deletes task and data; only in PREPARED, with the key whose sha256 is the lock. */
  suspend fun release(transferId: String, request: ReleaseRequest): Released
  /** Unfreezes and restores the prior state; in PREPARED only with [force]. */
  suspend fun abortExport(
    transferId: String, force: Boolean = false, undo: Boolean = false,
  ): TransferInfo
}

/** Destination role; [key] is ImportOpened.importKey, optional with D's admin token. */
@KetchTransferProtocol interface TransferSink {
  /** Validates, probes the origin, reserves paths; mints the task id and keys. */
  suspend fun beginImport(request: ImportRequest): ImportOpened
  suspend fun ledger(transferId: String, key: String? = null): Ledger
  /** Verifies the digest, writes at the chunk's offset; replays are no-ops. */
  suspend fun writeChunk(transferId: String, chunk: TransferChunk, key: String? = null): ChunkAck
  suspend fun seal(transferId: String, key: String? = null): Sealed
  /** One-transaction insert, adoption, then the commit key. */
  suspend fun commit(transferId: String, prepared: Prepared, key: String? = null): Committed
  /** Unlocks the committed task once S proves it left PREPARED; [settleKey] comes from S. */
  suspend fun settle(transferId: String, settleKey: String, key: String? = null): TransferInfo
  /** Discards staging (only files this import created); refused after commit. */
  suspend fun abortImport(transferId: String, key: String? = null): TransferInfo
}

@KetchTransferProtocol interface TransferController : TransferSource, TransferSink {
  suspend fun info(transferId: String): TransferInfo?
  suspend fun list(): List<TransferInfo>
}
```

DTOs (`@Serializable`; chunks travel as raw bodies). Requests decode enums strictly (unknown →
400, never coerced to another mode): request enums have no defaults and transfer routes decode
with a route-local `Json`, untouched by the server-wide `coerceInputValues` (F10b, H§9.8);
responses carry phase and role as strings. Refusals are exceptions, never booleans inside a
success.

| Type | Fields |
|---|---|
| `ExportRequest` / `ExportOpened` | `transferId` (driver-minted UUID), `taskId`, `mode`, `destination` and `orchestrator: TransferPeer(instanceId, name)`, `credentials` (`AUTO`/`INCLUDE`/`STRIP`), `chunkBytes` / `manifestSha256`, `displayName`, `kind` (`data`/`metadata-only`), `chunkCount`, `payloadBytes`, `totalBytes`, `disclosures` |
| `ManifestEnvelope` | `json` (exact bytes, opaque to orchestrators), `sha256` |
| `ImportRequest` / `ImportOpened` | `manifest`, `folder?` (relative for remote callers), `credentials?` (a sign-in for D only, §7.5) / `taskId`, `outputName`, `importKey`, `commitLock` (sha256 of the commit key), `ledger`, `origin` (`match`/`weak`/`skipped`), `warnings` |
| `Ledger`, `Sealed` | `phase`, `chunkCount`, `missing` (index runs), `bytes`; `Ready(contentRoot, taskId)` or `Missing(runs)` |
| `TransferChunk` / `ChunkAck` | `index`, `bytes`, `sha256` / `index`, `status` (`accepted`/`duplicate`), `durable` |
| `PrepareRequest` / `Prepared` | `manifestSha256`, `contentRoot`, `commitLock`, `hold` (≤ 60 s) / `sourceInstanceId`, `contentRoot`, `settleLock` (sha256 of S's settle key) |
| `ReleaseRequest` / `Released` / `Committed` | `commitKey`, `orchestratorId` / `kept` (files S could not prove it owned, left in place), `settleKey` / `taskId`, `commitKey` |
| `TransferInfo` | `transferId`, `role`, `mode`, `phase`, `taskId?`, `displayName`, `peer?`, `orchestrator?`, `holdUntil?`, `settleKey?` (S, once it left `PREPARED`), `bytesDone`, `bytesTotal`, `reason?`, `error?`, `updatedAt` |

### 4.3 Errors

`TransferException(TransferError(code: String, message, retryable, detail))`, never a `KetchError`.
The wire body is the existing `ErrorResponse(error = code, message)`
(`library/endpoints/.../model/ErrorResponse.kt:9`) plus a defaulted `detail: Map<String, String>`.

| Codes | HTTP | Retry | Meaning |
|---|---|---|---|
| `starting` / `busy` | 503 / 429, `Retry-After` | yes | before recovery and task loading finish (§7.9) / concurrency caps |
| `insufficient_space` / `io` | 507 / 500 | yes (10 min) / yes | D's disk / a read or write failed |
| `digest_mismatch` | 412 | 3× | body ≠ `Content-Digest`, or a replay ≠ the first receipt |
| `chunk_too_large` / `length_required` | 413 / 411 | no | proxy body limit; the message names `client_max_body_size` / a chunk `PUT` without `Content-Length` |
| `manifest_mismatch` | 412 | no | a chunk read whose `If-Match` ≠ the manifest hash |
| `same_instance`, `not_durable`, `store_busy` | 409 | no | self; `InMemoryTaskStore`; another process holds the transfer lock |
| `task_locked` | 409 | no | a frozen source: resume, reschedule, cancel, remove, another export; an unsettled import on D: remove, cancel, export (`detail.phase`); existing task routes included |
| `needs_credentials` | 409 | no | D's origin probe needs a sign-in the manifest does not carry; nothing created (§7.5) |
| `transfer_conflict`, `phase` | 409 | no | same `tid`, other parameters; call invalid now (`detail.phase`) |
| `source_data_changed`, `content_mismatch`, `origin_changed` | 409 | no | frozen file changed; roots differ; origin no longer matches S's validators |
| `held`, `expired`, `committed` | 409 | `held` | inside another holder's hold; S left `PREPARED`; abort after commit |
| `bad_commit_key`, `bad_settle_key`, `bad_import_key`, `path_rejected`, `not_exportable` | 403 | no | wrong key; unsafe folder; output identity unproven for a remote caller |
| `not_transferable`, `source_data_missing`, `manifest_invalid`, `version_unsupported`, `unsupported_source` | 422 | no | S cannot describe the task; D rejects the manifest |
| `unsupported`, `closed`, `peer_unreachable`, `insecure_route` | client | — | 404/405/501 from old servers; closed client; no route; credentials over public plaintext |

### 4.4 `library:transfer` (`com.linroid.ketch.transfer`)

Targets android, iosArm64, iosSimulatorArm64, jvm, js, wasmJs, wasmWasi; depends on `library:api`
and coroutines. Setup: Android namespace, `consumer-rules.pro`, publishing, `settings.gradle.kts`,
AGENTS.md module, package and log tags (`Transfer`, `TransferDriver`, `TransferExport`,
`TransferImport`).

```kotlin
/** One side as the orchestrator sees it. [api] is resolved again on every call and retry. */
class TransferParty(
  val deviceId: String, val name: String, val inProcess: Boolean,
  val publicAccess: Boolean,               // reached without a token
  val api: suspend () -> KetchApi,
)

data class TransferOptions(
  val mode: TransferMode, val folder: String? = null,
  val credentials: CredentialPolicy = CredentialPolicy.AUTO,
  val allowRelay: Boolean = true,
  /** MOVE: keep S's release for this long after commit, for Undo (≤ 60 s). */
  val hold: Duration = Duration.ZERO,
)

/** Each holds from, to, taskId and options. Fallback: the app runs today's send. */
sealed interface TransferPlan { class Data /* route, sizes, disclosures, warnings */;
  class Fallback(val reason: String); class Refused(val error: TransferError) }

sealed interface TransferOutcome {
  /** COPY finished, or MOVE committed and S released. */
  data class Completed(val taskId: String, val kept: List<KeptFile>) : TransferOutcome
  /** MOVE committed but S kept its copy ("Keep here", interrupted Undo). */
  data class Duplicated(val taskId: String, val reason: String) : TransferOutcome
  data class Undone(val reason: String) : TransferOutcome
  data class Aborted(val error: TransferError) : TransferOutcome
}

interface TransferHandle {
  val transferId: String
  val progress: StateFlow<TransferProgress>   // phase, bytesDone, bytesTotal, bytesPerSecond
  suspend fun await(): TransferOutcome
  suspend fun cancel()                        // before commit only; after: `committed`
  suspend fun release()                       // end the hold now
  suspend fun undo()                          // during the hold: keep S, settle, remove D's copy
}

/** Plans, runs and recovers transfers for one orchestrator in a long-lived scope. */
class TaskTransfers(scope: CoroutineScope, orchestrator: TransferPeer) {
  val active: StateFlow<List<TransferHandle>>
  suspend fun plan(from: TransferParty, taskId: String, to: TransferParty,
                   options: TransferOptions): TransferPlan
  fun start(plan: TransferPlan.Data): TransferHandle
  /** Lists transfers on [parties], resumes this orchestrator's, reports what needs the user. */
  suspend fun recover(parties: List<TransferParty>): List<TransferAttention>
}
```

`TransferDriver` runs `ledger` → pump `missing` (≤ 4 in flight, AIMD on 429) → `seal` (re-pump
`Missing`) → `prepare` → `commit` → hold → `release` → `settle`. The driver never parses the
manifest; it forwards the envelope and works from indices. Cancellation order is §9.6.

### 4.5 Implementations

- **`Ketch`**: an internal `CoreTransferController`, always non-null, over `SqliteTaskStore` as a
  `TransferStore` (§7.2); any other `TaskStore` gets an in-memory one with `durable = false` (COPY
  only). It reads `Ketch(instanceId)` (H§7.1); without one, `status().transfer` is null. New
  parameter `transferPolicy` (roots, caps, `enabled`; `ketch mcp` and one-shot CLI engines pass
  `enabled = false`, so they never take the store lock, §7.9).
- **`RemoteKetch`**: `RemoteTransferController` calls `Api.Transfers.*` Resources on the existing
  client (`install(Resources)`, `remote/RemoteKetch.kt:105`; library:remote already depends on
  endpoints). It works unstarted; after `close()` (`remote/RemoteKetch.kt:240-247`) calls fail
  `closed`. 404, 405 and 501 map to `unsupported` (an old server answers a non-GET method on a
  path its GET-only `{path...}` catch-all matches with 405, `server/KetchServer.kt:394-412`);
  other statuses decode `ErrorResponse`, never `checkSuccess`, which drops bodies
  (`remote/RemoteKetch.kt:414-422`). It sends `Ketch-Transfer-Key` and per-request timeouts (§6.6).
- **`KetchServer`**: new parameter `routes: Set<RouteGroup> = setOf(RouteGroup.CORE)`, the
  route-set parameter H§15 also needs: M1 defines it with `TRANSFERS`, H PR8 adds `HELPERS`.
  `ketch server` (`cli/Main.kt:378`) and the apps' Sharing servers add `TRANSFERS`;
  `BrowserExtensionServer` keeps the default (`app/desktop/.../BrowserExtensionServer.kt:83-89`),
  since it serves the full embedded API with a token and would otherwise export. `transferRoutes`
  joins `apiRoutes` inside `authenticate` (`server/KetchServer.kt:348-358`; #352's `/api/pairing`
  routes stay outside it). `StatusPages` gains `exception<TransferException>` mapping
  codes per §4.3 to `ErrorResponse(error = code, …)`, which also covers `resume`/`cancel`/`remove`
  of a frozen task on the existing routes (`server/api/DownloadRoutes.kt:83-140`); today any such
  exception becomes the generic 500 `internal_error` echoing `cause.message`
  (`server/KetchServer.kt:323-331`).

### 4.6 Consumers

**app/shared**: a `TransferCoordinator` owned by `InstanceManager` (in `KetchService` on Android)
wraps `TaskTransfers`. Parties resolve `entryOf(deviceId).instance` per call: `reconcile` swaps a
disconnected device to a fresh unstarted client, never a closed one
(`app/instance/InstanceManager.kt:424-445`), and REST works unstarted, so there are no dedicated
clients, no `wanted()` pinning and no `InstanceFactory` change; a call caught by a swap fails
`closed` and retries through the resolver. Tasks are addressed by `(deviceId, taskId)`; no
`DownloadTask` is held across suspension. Web: fallback; CLI and MCP: none in M1 (§13.1).

---

## 5. Transfer manifest

Internal to core (`core/transfer/`), but a versioned wire format that S issues and D validates.
Orchestrators carry it opaque in `ManifestEnvelope`.

### 5.1 Shape

```kotlin
@Serializable internal data class TransferManifest(
  val format: Int = 1,                       // major; unknown -> 422
  val transferId: String, val mode: TransferMode,
  val source: TransferPeer, val sourceTaskId: String, val issuedAt: Instant,
  val task: TransferTask,
  val sourceType: String? = null, val portableState: PortableState? = null,
  val totalBytes: Long = -1,
  val displayName: String,                   // one component; D sanitizes again
  val segments: List<Segment>? = null,       // layout of a partial task with progress, else null
  val payload: TransferPayload? = null,      // null: metadata-only
  val credentials: CredentialDisclosure,     // carried and stripped item names
)
@Serializable internal data class TransferTask(
  val request: DownloadRequest,              // §5.3 and §5.5 applied
  val createdAt: Instant, val downloadTime: Duration? = null,
  val completedAt: Instant? = null,          // #353; whole milliseconds, Completed only
  val intent: String,                        // scheduled|queued|downloading|paused|failed|completed
  val hadConditions: Boolean = false,        // -> lands PAUSED
  val error: JsonElement? = null,            // KetchError JSON, decoded leniently
)
@Serializable internal data class PortableState(
  val sourceType: String, val schema: Int, val data: String,
)
@Serializable internal data class TransferPayload(
  val chunkBytes: Int, val ranges: List<LongRange>, val chunkCount: Int, val bytes: Long,
)
```

The chunk plan is deterministic: ranges `[a, b)` are each segment's valid prefix `[start, start +
downloadedBytes)` (a completed file is `[0, totalBytes)`); chunk *k* of a range covers
`[a + k·chunkBytes, min(b, a + (k+1)·chunkBytes))`; indices run over ranges in order.
The payload comes from the record's segments whenever any segment has progress, whatever the
state, so a task rescheduled after it started (`core/engine/DownloadScheduler.kt:44-46`) keeps
its bytes. All-zero progress (#359 resets it when the final flush fails,
`core/engine/DownloadExecution.kt:391-396`) and partial tasks of unknown size export
metadata-only. After H PR9, each prefix ends at the first span of S's `relayed` provenance that
no passed audit covers, whether its audit is pending or its sampled window never committed
(H§9.6); until H records passed audits per span, that is the first relayed byte. D refetches
those bytes, so H's fail-closed rule survives an export.

### 5.2 Portable state (schema 1)

| sourceType | `data` | Notes |
|---|---|---|
| `http` | `{etag, lastModified, totalBytes}`; after H PR3 also `pinMode`, `aliases` (defaulted) | Origin identity from `HttpResumeState` (`core/engine/HttpDownloadSource.kt:439-444`); never `relayed`, `pendingAudits` or `pinVersion` (H§9.8). `totalBytes` comes from the record: after an unknown-size download completes, its resume state still says -1. |
| `ftp` | `{totalBytes, mdtm}` | Same as `FtpResumeState`. |
| other | — | Sources without `SourceTransfer` use the fallback (§12.2). Torrent schema: §8.3. |

D decodes leniently (F6, shared with H§9.8) and re-encodes in its own format.

### 5.3 Carried and not carried

| Carried | Not carried |
|---|---|
| `url`, `connections` (0 is Auto, resolved with D's `maxConnectionsPerDownload`, `core/engine/DownloadContext.kt:75-79`), `speedLimit`, `priority`, `selectedFileIds`, `properties` (such as `ketch.origin`) | `outputPath`, `content://` URIs: machine-bound. |
| `schedule` verbatim; `AfterDelay` is re-armed in full on D, as every restart already does on S (`DownloadScheduler.kt:124-127`, `core/Ketch.kt:388`) | `DownloadCondition`s (`hadConditions` lands paused). |
| `headers`, URL userinfo per §5.5 | `resolvedSource` (rebuilt from portable state); `SourceResumeState` verbatim. |
| Segments, `totalBytes`, `sourceType`, portable state, `downloadTime`, `completedAt`, error; `createdAt`, so D's queue (priority, then age, `DownloadQueue.kt:393-404`) keeps the task's age | Tokens, keys, file identities; `queuePosition` (derived on D). |
| `destination` mapped as today's `forDevice` (`AppState.kt:2128-2142`): a name is kept, a file path is reduced to its file name, a folder or `content://` value is dropped | `DownloadRequest.helpers` (H§14.1), reset as `forDevice()` resets it (K18). |

### 5.4 Where it lives, limits, validation, versioning

- **Where**: S stores the exact JSON and hash at `openExport`; orchestrators forward it verbatim and
  `prepare` compares hashes. D compacts its row at commit, S when its row turns terminal: headers,
  userinfo and metainfo go, hash and size stay. Rows are purged after 30 days.
- **Limits**, checked against `Content-Length` before parsing: manifest ≤ 8 MiB; segments ≤ 1,024;
  ranges ≤ 1,024; chunks ≤ 2²²; headers ≤ 64 entries and 64 KiB; `displayName` ≤ 255 UTF-8
  bytes. (Torrent metainfo, M2: ≤ 4 MiB as `TorrentConfig.maxMetadataBytes`,
  `torrent/TorrentConfig.kt:42,82`.)
- **D validates before touching disk**: known format; `source.instanceId ≠ self`; `sourceType` has
  a `SourceTransfer` and a supported schema; segments sorted, `index == position`, a contiguous
  cover of `[0, totalBytes-1]`, `0 ≤ downloadedBytes ≤ size`, non-empty when `totalBytes > 0`;
  ranges equal the prefixes inside `totalBytes`; `chunkCount` recomputed. Completed: `segments`
  null, payload `[0, totalBytes)`. `totalBytes < 0` (unknown size, #354): not completed, `segments`
  null or `[]`, no payload.
- **Versioning**: `format` is a major; minors add defaulted fields (`ignoreUnknownKeys`). Portable
  schemas are versioned per source; an unknown one fails at `beginImport`, not as
  `CorruptResumeState` at resume.

### 5.5 Secrets policy

One predicate, `isSensitiveHeader(name)` in `api/log/LogFormat.kt`, covers `Cookie`,
`Authorization`, `Proxy-Authorization` and names matching `redactUrl`'s keys
(`api/log/LogFormat.kt:132-135`); F11 and `KtorHttpEngine`'s log masking use it too. It is H's
shared sign-in classifier (H§15, plus its signed-URL keys and fragment masking); only the policies
differ.

| Item | AUTO (default) | INCLUDE | STRIP |
|---|---|---|---|
| `Proxy-Authorization`, S's own proxy credential (as H§11) | stripped, in the fallback too | carried | stripped |
| Other sensitive headers | carried for partial and metadata-only tasks; stripped for completed ones | carried | stripped |
| URL userinfo (FTP `user:pass@`) | as headers | carried | stripped; a partial task asks for a sign-in for D before bytes move (§7.5) |
| Signed query values | carried (the URL is the identity) and disclosed | | |

- **Public destinations**: when D is reached without a token (`TransferParty.publicAccess`, i.e.
  `RemoteConfig.apiToken == null`), AUTO behaves as STRIP and the confirmation says why ("anyone
  on <network> can read sign-ins sent to <D>"); INCLUDE needs an explicit choice. A partial task
  whose origin needs a sign-in then offers INCLUDE, a sign-in typed for D (the same exposure) or
  the fallback. The fallback follows the same rule.
- Credentials never cross plaintext HTTP to a non-private address (private: loopback, RFC 1918,
  ULA, link-local, `.local`, 100.64/10) without explicit consent.
- Carried items are listed in `disclosures` and confirmed in the UI. Manifests and keys are never
  logged; transfer logs name `transferId` and `taskId`.

---

## 6. Data plane and wire protocol

### 6.1 Resources and models

New `@Resource` classes in `library/endpoints/.../KetchEndpoints.kt`: `Api.Transfers`
(`/api/transfers`) with `ById(tid)`, which has `Export` (`manifest`, `chunks/{index}`, `prepare`,
`release`, `abort`) and `Import` (`chunks/{index}`, `seal`, `commit`, `settle`, `abort`); server
and `RemoteKetch` share them, and core never sees them (endpoints has no js or wasmWasi target).
The only endpoints model change is `ErrorResponse.detail`. Rule for later, shared with H§9.8: an
enum in a type that reaches task snapshots or events must be a string or defaulted, since
`coerceInputValues` (`remote/RemoteKetch.kt:93`) only rescues defaulted properties; a sealed type
needs a tolerant serializer like `PauseReason`'s (`api/PauseReason.kt:47-92`), as H's
`HelperPolicy` does (H§9.8).

### 6.2 Routes (under `/api/transfers`)

Auth: the admin token where the server has one (`authenticate(AUTH_API)`, tree unchanged; the
import key is then optional). On token-less servers, import calls after `beginImport` need
`Ketch-Transfer-Key`. Errors per §4.3.

| Method, path | Role | Success |
|---|---|---|
| `GET /`, `GET /{tid}` | any | `List<TransferInfo>` / `TransferInfo` |
| `PUT /{tid}/export`, `GET /{tid}/export/manifest` | S | `ExportOpened` / `ManifestEnvelope` |
| `GET /{tid}/export/chunks/{i}` | S | octets, exact `Content-Length`, `Content-Digest: sha-256=:<b64>:`; 412 when `If-Match` ≠ manifest hash |
| `POST /{tid}/export/prepare`, `/release`, `/abort?force&undo` | S | `Prepared`, `Released`, `TransferInfo` |
| `PUT /{tid}/import`, `GET /{tid}/import` | D | `ImportOpened` / `Ledger` |
| `PUT /{tid}/import/chunks/{i}` | D | `ChunkAck`; 411 without `Content-Length` |
| `POST /{tid}/import/seal`, `/commit`, `/settle`, `/abort` | D | `Sealed`, `Committed`, `TransferInfo`, `TransferInfo` |

### 6.3 Chunks

- **Size**: `min(S.maxChunkBytes, D.maxChunkBytes)`, 1 MiB by default, fixed per manifest; that
  passes nginx's default `client_max_body_size`. A 413 aborts with `chunk_too_large` naming the
  proxy setting; there is no halving loop. Larger chunks later come from a larger advertised
  `maxChunkBytes`.
- **S reads** through `FileReader` (§7.4), hashes with core `Sha256` (§7.4), answers
  `respondBytes` with `Content-Digest`, and pins each digest in memory. A repeat read with another
  digest, or a size/mtime/identity change of the frozen file (re-stat every 64 chunks), aborts
  with `source_data_changed`. Reads send `If-Match: "<manifest sha256>"`.
- **D writes in any order**: it needs `Content-Length` equal to the planned length and
  `Content-Digest`; reads exactly that many bytes with a bounded `readAvailable` loop (Ktor's
  `readRemaining(max)` can overshoot); checks the digest; writes at the chunk's offset; marks the
  index received and keeps its digest in memory. A replay of a received index is a no-op
  `duplicate` when the digest equals the first receipt and `digest_mismatch` otherwise.
- **Durability**: every 64 MiB or 2 s per import, fsync the file, then persist the received set
  (index runs) in the row. A crash loses only chunks after the last persisted set; they are resent.
- **No long streams**: memory stays `window × chunk`; buffering clients, proxies and CIO idle
  timeouts are fine.

### 6.4 Resumability and idempotency

Drivers start with `ledger()` and send `missing`. `openExport`, `beginImport`, `seal`, `prepare`,
`commit` and `release` are idempotent per `transferId`; the same id with other parameters is 409
`transfer_conflict`. Because the ledger is on D, a restarted orchestrator continues where the
last persisted set left off.

### 6.5 Authentication and server guards

- **Admin token**: LOCAL and RELAY drivers already hold it, so M1 adds no new trust. The compare
  becomes constant-time and a blank token counts as none (F10b, H PR1 #8; today
  `credential.token == expectedToken`, `server/KetchServer.kt:338`).
- **Token-less servers** install no Authentication (`server/KetchServer.kt:283-289, 334-355`).
  They serve import routes, later calls guarded by the import key (32 random bytes, stored
  hashed), so a LAN host that learns a `tid` from `GET /api/transfers` cannot write, seal, commit
  or abort someone else's import. **Export routes are not registered** and `export` is dropped
  from `status().transfer`.
- **HostValidation and CrossOriginGuard** (token-less only, `server/HostValidation.kt:80-96`,
  `server/CrossOriginGuard.kt:21-37`) also guard transfer routes; native drivers send no `Origin`.
  CORS is unchanged in M1, because the web app only falls back.

### 6.6 Timeouts and backpressure

- Driver timeouts (the client default is infinite, `remote/RemoteKetch.kt:97-100`): connect 10 s,
  metadata 30 s, chunks `30 s + bytes / 256 KiB/s`, `seal` 10 min then `ledger` polling. Retries
  back off 1 s → 60 s, 8 per call; 2 min without durable progress shows "stalled".
- D accepts ≤ 4 concurrent writes per import and ≤ 2 active imports; S serves ≤ 4 reads per export
  (429 + `Retry-After`). Transfers take no queue slots; no speed limit applies in M1.
- ENOSPC → 507, retried for 10 min so the user can free space, then abort.

### 6.7 Progress

`TransferHandle.progress` feeds the driving app. Other clients see a frozen source that was
active or paused as `Paused(Transferring)` ("Being moved to NAS"; clients before this change show
a user pause); a completed or failed one keeps its state. Either way its actions answer
`task_locked` with `detail.transferId` and `detail.peer`, which is how other clients learn that a
finished file is being moved. Staged imports appear only in `list()`. A badge or event stream
comes with delegated routes (§13.2).

---

## 7. Core engine changes

### 7.1 Shared prerequisites

**Done.** #353 stopped `close()` from deleting partial files (it pauses for `Shutdown`); #359
generalized that and fixed most of H PR1 #1. F1: only `cancel()` and `remove(deleteFiles = true)`
delete a partial file (`discardPartialFile()`, `core/engine/DownloadCoordinator.kt:199-212`,
`core/engine/DownloadExecution.kt:445-465`); a failure, `remove(false)` or `close()` keeps file
and segments (the deletion half of H PR1 #3). F2: a known-size file the engine writes never saves
`[]` (`DownloadExecution.kt:293-297`); an older `[]` record starts fresh
(`DownloadCoordinator.kt:158-163`), but resolves its path again (F2b).

**Open**, one list for both plans, each shippable alone; "Needs" names the first milestone of each
plan that waits for it. H PR1 #5 and #6 are H's alone.

| # | Fix | Where | Needs | Why |
|---|---|---|---|---|
| F2b | A fresh restart of a record with an `outputPath` reuses it instead of resolving and deduplicating the path again (rest of H PR1 #1) | `DownloadExecution.kt:189-200, 627-649` | H PR1 | A file F1 now keeps makes the restart write `name (1).ext` and orphans the original. |
| F3 | Snapshot → sync → persist for periodic, final and pause saves; `F_FULLFSYNC` on iOS (H§9.7, H PR1 #2) | `DownloadExecution.kt:305-333`; pause flushes before its join (`DownloadCoordinator.kt:118-133`); iOS flush is `fflush` (`core/file/PathFileAccessor.kt:46-50`) | M1, H PR3 | A "valid prefix" over unsynced zeros would move to D. |
| F4 | `AtomicSaver` tombstone after `remove` | `core/task/AtomicSaver.kt:6-20` | M1 | `INSERT OR IGNORE + UPDATE` (`sqlite/.../SqliteTaskStore.kt:53-80`) lets a late setter (`core/task/RealDownloadTask.kt:81-106`) resurrect a released task. |
| F5 | `synchronous = FULL` set explicitly everywhere; `fullfsync`, `checkpoint_fullfsync` on Apple (iOS, macOS JVM) | `sqlite/{jvm,android,ios}Main/.../DriverFactory.*.kt` | M1 | Android may run compatibility WAL at `NORMAL`, iOS opens WAL with no level set; Apple's `fsync` leaves the drive cache. |
| F6 | Lenient resume decoding for HTTP, FTP, torrent (H PR1 #7) | `HttpDownloadSource.kt:150`, `ftp/.../FtpDownloadSource.kt:175`, `TorrentDownloadSource.kt:287, 596` | M1, H PR3 | Portable states and H's fields meet strict published builds. |
| F7 | H PR2 (H§7.1): `instanceId` in `device.json` beside the task database, `Ketch(instanceId)`, `KetchStatus.instanceId`, `RemoteConfig.instanceId` stored on connect (`app/ui/connect/DeviceConnector.kt:56, 141-166`); the app refuses its own server by id; `device.json` stays out of Android and iOS backups, so a restored phone gets a new id before H PR8 wraps keys | `api/KetchStatus.kt:18-27`; H's `boot` nonce replaces the port-and-name filter (`AddDeviceSheet.kt:132`) | M1, H PR8 | Self-transfer refusal; one identity for both plans. |
| F8 | `close()` waits: `Ketch.shutdown(grace)` (H§14.1) joins executions and transfer critical sections first (rest of H PR1 #3) | `Ketch.kt:508-515` | M1, H PR3 | `NonCancellable` freeze, commit and release (§9.6) would lose their dispatcher. |
| F10a | `ErrorResponse.detail`; the generic 500 stops echoing `cause.message` (M1 adds the `TransferException` mapping, §4.5) | `server/KetchServer.kt:305-332` | M1 | `detail` carries §4.3's phase, transfer and peer; the echo leaks internals. |
| F10b | Server `Json` gains `coerceInputValues` (§4.2); constant-time bearer compare; a blank token is none (H PR1 #8) | `KetchServer.kt:272-275, 338`; `:176` vs `:283` | M1, H PR5 | H's defaulted enums (H§9.8); timing-safe auth; a blank token is half-applied today (pairing checks `isNullOrBlank` at `:176`, auth `== null` at `:283`). |
| F11 | Credential detection covers userinfo, `isSensitiveHeader` and query keys (§5.5); `sendConfirmation` becomes a queue | `AppState.kt:1976-1988`; `:366, 1322-1332` | M1, H PR9 | FTP passwords go without asking; a second send overwrites the first. |
| F13 | Writes after `close()` throw instead of recreating the file (H PR1 #4) | `PathFileAccessor.kt:28-38` | M1, H PR3 | A late write would recreate a released file, or an import file `abortImport` discarded (I4). |
| F14 | Core `Sha256`, one positional-read path, core `fileIdentity`, `@KetchInternalApi` (§7.4) | `torrent/Sha256.kt`, `torrent/TorrentCheckpoint.kt:122` | M1, H PR3 | One implementation for both plans. |

**Hardening**, any time: F5b skip undecodable rows, insert `created_at`
(`SqliteTaskStore.kt:95-117`, `sqlite/.../TaskRecords.sq:17-22`); F9 `forDevice()` keeps
`resolvedSource` for `torrent:` and content-resolved tasks, which fail on the target today
(`AppState.kt:2128-2142`, `TorrentDownloadSource.kt:193-195`), F11 then counting passkeys in
carried metainfo and magnet `tr=`; F12 `KtorHttpEngine` masks every `isSensitiveHeader`, not only
`set-cookie`, `cookie`, `authorization` (`library/ktor/.../KtorHttpEngine.kt:212-221`).

### 7.2 `TransferStore` and schema

```kotlin
/** Durable transfer bookkeeping; SqliteTaskStore implements it next to TaskStore. */
interface TransferStore {
  val durable: Boolean
  suspend fun save(row: TransferRow)
  suspend fun load(transferId: String): TransferRow?
  suspend fun active(): List<TransferRow>
  /** Commit point: strict-inserts [record] and saves [row] in one transaction. */
  suspend fun commitImport(record: TaskRecord, row: TransferRow)
}
```

```sql
-- 5.sqm (schema 5 -> 6; databases/6.db); #353's 4.sqm added completed_at
ALTER TABLE task_records ADD COLUMN output_identity TEXT;   -- §7.6
CREATE TABLE transfers(
  transfer_id TEXT NOT NULL PRIMARY KEY,           -- one role per store (self-transfer refused)
  role TEXT NOT NULL, task_id TEXT NOT NULL, mode TEXT NOT NULL, phase TEXT NOT NULL,
  state_json TEXT NOT NULL,   -- S: prior state, stat, lock, hold, orchestrator, settle key;
                              -- D: received runs, output, identity, commitKey,
                              --    importKey hash, settleLock
  manifest_json TEXT, manifest_sha256 TEXT,        -- write-once; compacted when terminal
  expires_at INTEGER,                              -- OPEN lease / staging TTL; null for PREPARED
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX transfers_frozen ON transfers(task_id)
  WHERE role = 'SOURCE' AND phase IN ('FREEZING','OPEN','PREPARED','RELEASING');
-- TaskRecords.sq gains insertNew: INSERT (no OR IGNORE) of every column, completed_at
-- included, used only by commitImport.
```

`TaskRecord` gains `outputIdentity: String? = null`; "Moved from MacBook" comes from D's row. The
instance identity (`instanceId`) is not stored in these tables: `CoreTransferController` reads
`Ketch.instanceId` (F7).

### 7.3 Freeze, unfreeze, release (source)

**Gate.** An internal `TransferGate` holds frozen ids, checked before any state write at:
- `DownloadQueue.enqueue`, under its mutex before `markQueued`
  (`core/engine/DownloadQueue.kt:69-98`).
- `DownloadQueue.startTask`, before its Paused → Queued flip and the `activeEntries` put
  (`:294-316`). Every start passes through it: `promoteNext` (from `enqueue`, `updateLimits`,
  `dequeue`, completions), `setPriority` (`:221-252`, which calls `startTask` and
  `tryPreemptAndStart` directly) and URGENT preemption.
- The URGENT victim filter (`:114-118`): preemption writes `QUEUED` twice
  (`DownloadCoordinator.kt:111-115`, `markPreempted` at `DownloadQueue.kt:363-373`), so it must
  never pick a task being frozen.
- `DownloadScheduler.schedule` and `reschedule`.
- The top of `DownloadCoordinator.start`/`resume`, before their `QUEUED`/`DOWNLOADING` writes
  (`DownloadCoordinator.kt:67-75, 141-190`); `launchExecution` (`:248`) only asserts.

`TaskController.resume`, `reschedule`, `cancel` and `remove` throw `task_locked`; speed, priority,
connection and helper changes (H's `setHelpers`, `stopHelp`) persist as today but cannot start
the task.

**Freeze** (`openExport`), under the per-transfer mutex; steps 2–7 run `NonCancellable`:
1. Task exists and is not frozen (same `tid` → stored result); `destination ≠ self`; store lock
   held (§7.9); MOVE needs `durable`.
2. Persist the S row `FREEZING` with `prior = {record state, schedule}` (a preempted task is
   `Paused(Preempted)` over a `QUEUED` record and must re-queue) and the orchestrator.
3. `gate.freeze(taskId)`.
4. `scheduler.cancel`; `coordinator.pause(taskId, Transferring(…))`
   (`DownloadCoordinator.kt:82-134`; under H it also stops every helper lane, H§8.6); then
   `queue.dequeue` (preempted tasks too, `DownloadQueue.kt:254-272`), so no other task takes the
   slot while this one still runs, the order of `TaskController.pause` (`Ketch.kt:230-233`);
   `awaitCompletion`.
5. Prior SCHEDULED, QUEUED or DOWNLOADING → `record.update { state = PAUSED }` unconditionally
   (pause skips it when segments are empty, `:104`); publish
   `Paused(progress, Transferring(transferId, destinationName))` for those and paused tasks.
6. Read `handle.record.value` (exact after pause) and call `source.transfer.openExport`: the file
   must exist with `size ≥` the payload's end (partial) or `== totalBytes` (completed); remote
   callers need a provable output (§7.6); capture `stat = (size, mtime, identity)`; build the
   plan and portable state, or a metadata-only export (§8.2).
7. Persist `OPEN` (manifest, stat, 24 h idle lease renewed by chunk reads and `prepare`, not by
   `info`). Failure in 6–7 unfreezes and marks the row `ABORTED`.

**Pause reason.** `PauseReason.Transferring` is wire type `"transferring"`, its fields added to
`PauseReasonWire` and both directions of `PauseReasonSerializer`, with a constant
(`api/PauseReason.kt:51, 57-92`); clients from #353 on decode it as `User`, older ones ignore
`reason`. Exhaustive `when`s gain a branch (`core/Ketch.kt:462-467`,
`library/mcp/.../KetchToolSet.kt:328-333`, `app/util/RowContent.kt:213-218`). Reasons are not
persisted (`Ketch.kt:411`), so recovery arms the gate before `loadTasks` (§7.9) and
`mapRecordState` asks it.

**Unfreeze** (COPY prepare, abort, OPEN expiry, force): `gate.thaw`; SCHEDULED →
`record.update { state = SCHEDULED }`, then `scheduler.schedule`, which publishes `Scheduled` but
never persists it (`DownloadScheduler.kt:27-52`), so without the update a restart would restore
the PAUSED record as a user pause and lose the schedule; QUEUED/DOWNLOADING →
`queue.enqueue(preferResume = true)`, which writes `QUEUED` and resumes the saved segments or,
with none, starts fresh (`DownloadQueue.kt:307-312`; the path is resolved again,
`DownloadExecution.kt:189-200`, until F2b); PAUSED shows `Paused(User)` again.
Row → `DONE`, `ABORTED`, `EXPIRED` or `FORCE_ABORTED` (with `undo` when Undo asked).

**Settle key** (MOVE): `prepare` mints a random settle key, stores it in the row and returns
`sha256(key)` as `Prepared.settleLock`. S reveals the key (`Released.settleKey`,
`TransferInfo.settleKey`) only once it has left `PREPARED` for good, as `RELEASED` or
`FORCE_ABORTED`; after that no `release` can succeed, so the key carries no further power.

**Release** (MOVE): only from `PREPARED` (or a redo from `RELEASING`, an answer from `RELEASED`);
any other phase answers `expired` or `phase`. `sha256(key) == lock`, else `bad_commit_key`. Before
`holdUntil`, only the holding orchestrator may release (`held` otherwise). Persist `RELEASING`,
then an internal `releaseTransferred` (not `cancel`, which would flash `Canceled` over SSE):
tombstone the saver; `coordinator.release`; `coordinator.cleanup`, where single-file sources first
compare the output's stat and identity with the frozen ones and keep a replaced or changed file
rather than delete by path; `taskStore.remove`; drop from `_tasks`; thaw; `RELEASED`, listing kept
files in `Released.kept`. Every step is idempotent and redone from `RELEASING` on restart.

### 7.4 Export reading and hashing (F14, shared with H)

```kotlin
internal interface FileReader : AutoCloseable {
  suspend fun stat(): FileStat                                  // size, mtime, identity
  suspend fun readFully(offset: Long, into: ByteArray, length: Int)
}
internal expect fun openFileReader(path: String, io: CoroutineDispatcher): FileReader
@KetchInternalApi expect fun fileIdentity(path: String): String?   // moved from torrent
```

One positional-read implementation serves both plans: okio `openReadOnly` + `FileHandle.read`,
and for Android `content://` `Os.pread` on `openFileDescriptor(uri, "r")`, identified by document
id. `FileReader` wraps it for export, H's `FileAccessor.readAt` (H§14.2) for a running execution.
Export cannot use an accessor: a frozen task has none (`DownloadExecution.kt:280-286`), opening
one creates a missing file (`PathFileAccessor.kt:28-38`) and needs a rw SAF grant, and its
`limitedParallelism(1)` queues reads behind the writer. `fileIdentity` replaces torrent's
`torrentFileIdentity` (`torrent/TorrentCheckpoint.kt:122`); js and wasmWasi return null.

Every transfer hash uses `core.hash.Sha256`, moved from `torrent/Sha256.kt` (H§9.6); if it limits
LAN throughput in the end-to-end test, JVM and Android get a `MessageDigest` actual. F14, among
the shared prerequisites, adds `Sha256`, `fileIdentity`, the read code and `@KetchInternalApi`
(beside `@KetchTransferProtocol` in `library:api`, both at ERROR level) before either plan.

### 7.5 Import staging and commit (destination)

`beginImport`:
1. Validate (§5.4); same `tid` and manifest hash → stored result, otherwise 409.
2. Origin preflight for partial HTTP/FTP (`SourceTransfer.probeOrigin`: HEAD/MDTM today, H's ranged
   probe under `LaneMode.LANES`, H§9.4). It runs the resume's validator check
   (`HttpDownloadSource.kt:163-186`) plus two the resume lacks: the length, which resume never
   compares, and range support, without which resume restarts at byte zero (`:204-208`). Validators
   equal, `Content-Length == totalBytes`, ranges supported → `match`; no validators, or under LANES
   another strong validator at the same length → `weak`; unknown size → `skipped`; a changed
   validator, another length or no ranges → 409 `origin_changed` (`detail.reason`) with nothing
   created (the app offers the fallback). An authentication failure (stripped cookies or FTP login)
   → 409 `needs_credentials`, also with nothing created: the app asks for a sign-in before any bytes
   move (§12.1) and calls `beginImport` again with `ImportRequest.credentials`, which only D
   receives and which go into D's copy of the request. Partial data never lands without working
   credentials, because today's way to add them, Enter credentials, reopens the add sheet, and a
   changed header or URL starts the task over (`IntakeState.startsOver`,
   `app/state/IntakeState.kt:849-854`), removing it with its file (`:1517`).
3. Mint a fresh task id (MOVE and COPY alike), `importKey` and `commitKey`.
4. Folder and name (§7.6), space (§7.7).
5. Persist row `RECEIVING` (task id, output, keys, empty received set) **before** any file.
6. `openImport`: create with `mustCreate`, record the identity, `preallocate(totalBytes)` (sparse,
   so D's size-only `validateLocalFile` passes). A metadata-only import reserves no file and keeps
   the mapped `destination`, so D resolves the path when the task starts.

There is no `TaskRecord` or task; only `abortImport` or the TTL deletes staged files, and only
identity-matched files this import created.

`seal`: all chunks received → sync the file (§7.7) and `syncDirectory` its parent (best effort)
→ `contentRoot = sha256(digest₀ ‖ … ‖ digestₙ)` over receipt digests, re-reading only chunks whose
digests a restart lost → `SEALED` → `Ready(root, taskId)`.

`commit(prepared)`: require `SEALED`, `prepared.contentRoot == root` and `sourceInstanceId` equal
to the manifest's source. `finish()` yields path, segments, `SourceResumeState` and completeness;
the `TaskRecord` takes the manifest's request (with data, `destination = Destination(outputPath)`;
`helpers` reset, §5.3), the §8.2 state, `createdAt`, `downloadTime`, `completedAt` and
`outputIdentity`. `transferStore.commitImport(record, row → COMMITTED)` is **the decision point**
(strict insert). Then `Ketch.adoptRecord` (`createTaskFromRecord` + append under `tasksMutex`, as
`download()` does, `core/Ketch.kt:184-188`), then the row is marked adopted, and only then is the
key returned. If adoption throws, the call fails retryably with the key still hidden; the next
`commit()` or `start()` adopts idempotently (`loadTasks` restores the record anyway). A repeated
commit returns the same key while the row is `COMMITTED`.

**Settle lock.** A revealed commit key cannot be taken back, so D keeps its copy until S can no
longer release. `commit` stores `prepared.settleLock`; until the row is `SETTLED`, D refuses to
remove, cancel or export the task (`task_locked`, `detail.phase = settling`), in process and on the
existing routes alike (§4.5), while it runs, pauses and resumes normally. `settle(settleKey)` with
`sha256(settleKey) == settleLock` moves the row to `SETTLED`. A COPY commits with no lock. If S is
gone for good, D offers [Stop waiting for MacBook], which settles after warning that MacBook may
still delete its copy.

### 7.6 Destination path policy and export confinement

`internal class DestinationPathPolicy` extracts `resolveDestPath`/`deduplicatePath`
(`core/engine/DownloadExecution.kt:590-649`) for fresh downloads and imports alike.

> **Status:** part of it exists. `DestinationPathPolicy` (public, `core/file`) holds the roots and
> the containment check (`contains`, symlinks followed, dangling links refused), `confine` for a
> new download's destination (relative paths rebased, the name through `sanitizeFileName()`, a
> path that exists or `OutputPathReservations` holds replaced by a free `name (n).ext`) and
> `confineFile` for a resume destination. The server applies it through `DestinationGuard` with
> roots = `ServerConfig.allowedDirectories` + the live download directory: always without a
> token, with one when the list is set (open question 5). Imports should reuse it with the same
> roots, rather than a separate `transferRoots`. Still to do: NFC, the `.ketch-` escape,
> reserving the path at check time, and rejecting (rather than rebasing) relative or `..` paths
> for imports.

- **Rebasing**: roots are the live `defaultDirectory` (else `defaultDownloadDirectory()`) plus a
  new `ServerConfig.transferRoots`. Remote callers may name only a relative sub-folder (`..`,
  absolute paths, drive letters, schemes → 403 `path_rejected`); in-process callers may pass any
  folder or a `content://` tree.
- **Sanitizing**: NFC; `/ \ : * ? " < > |`, NUL and controls → `_`; `.`/`..` mapped; trailing
  dots and spaces trimmed; Windows reserved names suffixed; a `.ketch-` prefix escaped; ≤ 255
  UTF-8 bytes keeping the extension; empty → `download`.
- **Dedup**: `name`, `name (1).ext`, … reserved with `mustCreate` (or SAF `createDocument`); an
  in-process `PathReservations` set also serializes fresh downloads, closing today's
  check-then-create race. Never overwrite.
- **SAF**: only an in-process Android D imports into `content://`.
- **Export confinement by identity**: Ketch records `outputIdentity` when it creates the output (a
  fresh start where the file did not exist, an import's `mustCreate`, or the document id from SAF
  `createDocument`) and clears it when `resume(destination)` re-points the task
  (`DownloadCoordinator.kt:173-184`) or a `Destination.isFile()` names an existing file
  (`resolveDestPath` returns it raw, `DownloadExecution.kt:596-598`). A remote caller exports only
  when the stored identity equals the current one; records from before the upgrade (null) export
  only when the output's real path (symlinks resolved; SAF by tree and document id) lies under
  the roots. In-process callers are unrestricted. This keeps `resume?destination=` from turning
  export into an arbitrary-file read, without refusing tasks saved to other folders.

### 7.7 Free space and durability

`usableSpace(directory)` is extracted from the existing `currentSystemInfo` actuals
(`core/PlatformInfo.kt:9`), so Android measures `content://` trees through `treeVolume`
(`PlatformInfo.android.kt`); js and wasmWasi report 0, read as unknown. Hard check
`payload + 64 MiB` (507); warning when `totalBytes + 64 MiB` exceeds it (sparse files may not stay
sparse on FAT/NTFS); unknown → warning.

Ordering: row before files; write → fsync → received set; file and directory synced before
`SEALED`; commit under F5's pragmas. Imports write through a `FileAccessor` and sync with its
`flush()`, durable after F3 (`fcntl(F_FULLFSYNC)` on iOS, `fsync` elsewhere, the macOS JVM
included); only `syncDirectory` is new. On macOS JVM the commit's `F_FULLFSYNC` (SQLite
`fullfsync`) flushes the whole drive cache, covering a payload on `ketch.db`'s volume; a payload
on another volume keeps power-loss exposure until release (§15 risks).

### 7.8 Source SPI

```kotlin
interface DownloadSource {
  val transfer: SourceTransfer? get() = null              // null: the fallback
}
interface SourceTransfer {
  val portableSchema: Int
  suspend fun openExport(snapshot: ExportSnapshot): SourceExport   // describe + reader, no writes
  suspend fun probeOrigin(request: DownloadRequest, state: PortableState, totalBytes: Long):
    OriginVerdict = OriginVerdict.Skipped                  // from D's network
  suspend fun openImport(context: ImportContext): SourceImport      // create or reopen storage
}
interface SourceImport {
  suspend fun write(index: Int, bytes: ByteArray)
  suspend fun sync()
  suspend fun readBack(index: Int, into: ByteArray): Int   // digests lost to a restart
  suspend fun finish(): ImportResult                       // path, segments, resume state, complete
  suspend fun discard()                                    // identity-checked, idempotent
}
```

`SingleFileTransfer(type, codec)` in core serves single-file sources; HTTP and FTP opt in with one
line each. It is opt-in rather than the default for `managesOwnFileIo = false`, because a
third-party source may keep machine-bound resume state. The SPI is `@KetchInternalApi` (H§14.2):
FTP and torrent implement it, third-party sources keep the fallback.

### 7.9 Readiness, store lock and recovery

- **Readiness**: `CoreTransferController` answers `starting` (503, `Retry-After: 1`) until
  `Ketch.start()` has run recovery and then `loadTasks()` (`core/Ketch.kt:209-212, 349-373`).
  `ketch server` deliberately listens before `start()` (`serveDaemon`, `cli/Main.kt:426-435`), so
  a daemon that cannot bind never resumes tasks; the gate makes that order harmless. Recovery and
  route handlers take the same per-transfer mutex.
- **Store lock (JVM)**: before recovery and any transfer, the controller takes an exclusive OS lock
  on `<db>.transfer-lock` (`FileChannel.tryLock`; the OS releases it on exit or crash). Without it,
  calls answer `store_busy` and rows stay untouched; it is retried on the next call, and recovery
  runs when it is finally taken (OPEN and PREPARED tasks have loaded as PAUSED meanwhile). Android
  and iOS stores belong to one process. Engines with `transferPolicy.enabled = false` (`ketch
  mcp`, one-shot CLI downloads) never take it, so they cannot block the desktop app. H's
  `device.json` lock (H§14.5) stays a separate file with other holders (the daemon and one-shot
  `--helpers` runs): it keeps two processes from running relay sessions under one `instanceId`;
  this one decides who recovers, freezes and releases transfers.
- **Recovery** (lock holder only), before `loadTasks`, so frozen tasks restore as
  `Paused(Transferring)` and are never re-enqueued:
  - S: `FREEZING` → restore prior, `ABORTED`. `OPEN` → `gate.freeze`, or past the lease → unfreeze,
    `EXPIRED`. `PREPARED` → `gate.freeze`. `RELEASING` → redo the release.
  - D: `RECEIVING` → reopen lazily (identity-checked; a missing or foreign file resets the set and
    takes a new name); `RECEIVING`/`SEALED` past the TTL → discard (safe: the key was never
    revealed). `COMMITTED` → ensure adopted. `ABORTING` → redo discard.
  - Terminal rows are compacted, then purged after 30 days.
- Leases and TTLs use the local wall clock and only abort before a vote, so a clock jump can end a
  transfer early but never creates a duplicate or a loss.

---

## 8. Per-source and per-state handling

### 8.1 HTTP and FTP (M1)

| Source | Export (S) | Import (D) | D resumes with |
|---|---|---|---|
| HTTP | Valid prefixes (completed: whole file; unknown size: none); origin fields of `HttpResumeState` (§5.2) | Reserve, preallocate, any-order writes, seal; segments = manifest segments | `resume()`: the probe of §7.5 with carried headers, size check passes, resegments to D's connections |
| FTP | Same; `{totalBytes, mdtm}` | Same | MDTM re-probe; stripped credentials are asked for before bytes move |
| Source without `SourceTransfer` (torrents until M2, third-party) | — | — | fallback (§12.2) |

### 8.2 By state

| State on S | Freeze | Payload | Lands on D | S after COPY or aborted MOVE |
|---|---|---|---|---|
| Scheduled, no progress | `scheduler.cancel`, PAUSED | none | SCHEDULED | re-scheduled |
| Scheduled after it started (has segments) | same | prefixes | SCHEDULED with segments | re-scheduled |
| Queued, no progress (`segments == null`) | `dequeue`, PAUSED | none | QUEUED, fresh | re-enqueued |
| Downloading, or Paused for preemption (QUEUED record, `DownloadCoordinator.kt:111`) | pause (join), PAUSED | prefixes | QUEUED + segments → resumes | `enqueue(preferResume)` |
| Paused | — | prefixes | PAUSED | PAUSED |
| Failed, retryable, file intact | `awaitCompletion` | prefixes | FAILED (user resumes) | FAILED |
| Failed with `FileChanged`/`CorruptResumeState`, or file gone | — | none | QUEUED, fresh | unchanged |
| Partial of unknown size (`totalBytes < 0`, #354), any state | as the row for its state | none: every attempt empties the file and streams from zero (`HttpDownloadSource.kt:157-161, 311-320`) | as the row for its state, without payload and with `segments = null`, so it starts fresh | as the row for its state |
| Completed, file intact (also of unknown size: the record holds the measured size, `DownloadExecution.kt:347-374`) | — | whole file | COMPLETED(outputPath, totalBytes, downloadTime, completedAt) | unchanged |
| Completed with missing file, Canceled | — | none | QUEUED, fresh ("Download again on D") | unchanged |
| Zero-byte completed | — | one empty range | COMPLETED, empty file created | unchanged |

`hadConditions` lands PAUSED; `needs_credentials` is resolved before bytes move (§7.5).
Metadata-only exports happen only when S holds no usable data, so their release deletes nothing
of value.

### 8.3 Torrents (M2)

Portable state `{infoHash, format: v1|v2|hybrid, metainfo (base64), selectedFileIds, privacy,
received}`; no checkpoint, `savePath`, taskId or identities. Selection is effective:
`request.selectedFileIds`, else the state's only once `savePath` is set (before that it is
`buildResumeState`'s all-files default, `TorrentDownloadSource.kt:257-261`). Chunks are pieces; a
piece above the chunk size travels in parts that D buffers until whole (≤ 64 MiB, one at a time).

- **Export**: a read-only store view from the checkpoint, whose `verified` bits are untrusted
  (`TorrentPieceStore.kt:267-274`), so S hash-checks each piece before pinning it. A piece that
  fails, or that `TorrentV2PieceStore.read` refuses (`TorrentV2PieceStore.kt:200`), answers
  `unavailable`, leaves both roots, and the task lands partial for the swarm to repair.
- **Import**: reserve the final root (`mustCreate`) first, then build the store with that root and
  the fresh task id. Today the v1 sidecar (`TorrentPieceStore.kt:87`, bound to the output at `:56`)
  and the v2 creation log (`TorrentSourceMetainfo.kt:55-56`, bound at `TorrentV2PieceStore.kt:441`)
  exist before the payload (`TorrentV2PieceStore.kt:81-83`), so an `AlreadyExists` retry under the
  next name fails its binding and orphans the log; D deletes the record-free sidecar or log it just
  made. v1 needs an `initialize(fresh = true)` that owns every path via `mustCreate` (today's
  adopts existing paths unowned, `TorrentPieceStore.kt:113-126`).
- **Rejected pieces** (the store's `commit` hash-checks first) are recorded in the ledger and
  folded into the root as an accepted/rejected bit, so S sees D's real coverage. Any rejection
  lands the task partial, never COMPLETED (`finish()` requires every selected piece,
  `TorrentPieceStore.kt:210`); a MOVE prepares only with zero rejections unless the user consents.
- **Hash reservation** is new: `TorrentSessionRegistry` holds only running sessions
  (`TorrentSessionRegistry.kt:16-41`, via `withActiveSlot`, `TorrentDownloadSource.kt:531`); D
  reserves the hash for every non-terminal task and import.
- **Release**: files whose ownership cleanup cannot prove are kept
  (`TorrentDownloadSource.kt:619-622`) and listed in `Released.kept`; the UI says "Moved; 3 files
  left on MacBook" with Reveal.
- D lists `torrent` in `sources` only if its root has file identities and no symlinked parents;
  torrents need paths (`TorrentDownloadSource.kt:339, 444`), not SAF. D's first resume rechecks
  everything (`KotlinTorrentSession.kt:234`).

---

## 9. Consistency protocol

### 9.1 Invariants

- **I1 No loss**: the data always exists with a durable record on an instance where it is runnable
  or frozen. S deletes only from `PREPARED`, after D's commit transaction over fsynced bytes whose
  root S matched; D cannot remove its task until S has left `PREPARED`, so D's copy outlives every
  possible release.
- **I2 One runnable owner**: before commit D has no task; S is frozen from freeze to release;
  after commit only D runs. Two runnable copies arise only from an explicit "Keep here" or an
  interrupted Undo, and both are shown.
- **I3 Equality**: D's committed bytes equal S's frozen bytes chunk by chunk.
- **I4 No orphans**: an abort leaves no file D created; identity checks protect user files.
- **I5 Nothing silent**: stuck transfers show "stalled", "waiting for <D>" or "duplicated" with an
  action.

### 9.2 State machines

```
SOURCE row
  FREEZING --crash or openExport failure---------> ABORTED (prior state restored)
     | frozen
     v
  OPEN --abort, or frozen file changed-----------> ABORTED (unfrozen)
     |--idle 24 h--------------------------------> EXPIRED (unfrozen)
     |--prepare, COPY----------------------------> DONE    (unfrozen)
     | prepare, MOVE
     v
  PREPARED (frozen; no clock expiry; the only phase that accepts release)
     |--force: "Keep here", Undo, D aborted------> FORCE_ABORTED (unfrozen)
     | release(key), after the hold
     v
  RELEASING --redone after a restart-------------> RELEASED

DESTINATION row (no task until COMMITTED)
  RECEIVING --abort, or idle 24 h--+
     | all chunks received          |
     v                              +--> ABORTING --discard--> ABORTED
  SEALED ----abort, or idle 24 h---+
     | commit (one transaction)
     v
  COMMITTED (key revealable; COPY: terminal, purged after 30 days;
     |        MOVE: D's task cannot be removed, canceled or exported)
     | MOVE: settle(key S reveals once it leaves PREPARED), or the user's "Stop waiting"
     v
  SETTLED (purged after 30 days)
```

Orchestrator phases (`planning → exporting → copying → sealing → preparing → committing → holding
→ releasing → settling → done | aborted | duplicated`) are derived from S and D and never
authoritative.

### 9.3 MOVE commit (embedded S → NAS, LOCAL_PUSH)

```
Driver (app)                Source S (embedded)                Destination D (NAS)
  | openExport, manifest -->| freeze ... OPEN                     |
  | beginImport ---------------------------------------------------->| row, file; mints key
  |<------------------------------------ ImportOpened(importKey, lock=sha256(key))
  | chunks: readChunk -> writeChunk, any order ...                  |
  | seal ----------------------------------------------------------->| fsync, root, SEALED (c1)
  | prepare{manifest sha, root, lock, hold 6 s} ->|                   |
  |                             | root == pins? sha == own?         |
  |                             | PREPARED{lock, holdUntil}, mints settle key, frozen (c2)
  |<----- Prepared(S.id, root, settleLock) --|                      |
  | commit{prepared} ----------------------------------------------->| check root, S.id; one tx:
  |                             |                                   | TaskRecord + COMMITTED;
  |<-------------------------------------- Committed(taskId, key) --| adopt, then reveal   (c3)
  | [Undo window: hold]         |                                   |
  | release{key, orch} -------->| PREPARED? sha256(key)==lock? -> RELEASING (c4)
  |                             | stop, identity-checked delete, remove -> RELEASED (c5)
  |<---- Released(kept, settleKey)                                  |
  | settle{settleKey} ---------------------------------------------->| SETTLED; task removable (c6)

c1  seal is idempotent; S is OPEN and frozen.   c2  PREPARED survives restarts; retries get
the stored answer.   c3  SEALED (retry) or COMMITTED with the task (retry returns the key);
from here D refuses to remove the task.   c4/c5  RELEASING is redone on restart; later calls get
Released.   c6  settle is idempotent; recovery settles with the key from S's row.
Driver dies after c3: S stays PREPARED; recovery (§9.7) re-obtains the key or the user keeps S.
```

### 9.4 Failure matrix

| Step | S crashes | D crashes | Orchestrator dies |
|---|---|---|---|
| Freeze | `FREEZING` → prior restored; driver retries | — | S OPEN until 24 h idle, or the orchestrator resumes |
| beginImport | — | Row precedes files; retry returns the same result | D discards after 24 h; S unfreezes |
| Copying | Reads fail, driver backs off; gate re-armed by recovery, pins rebuilt on demand; a changed file aborts | Chunks after the persisted set are resent | Pump stops; recovery resumes from D's ledger |
| Prepare | Re-evaluated before `PREPARED`, same answer after | Re-seal, re-prepare | S PREPARED, D SEALED: recovery commits or, if D discarded, forces S |
| Commit | — (frozen) | Atomic; retry idempotent | D committed or not, nothing between |
| Release | `RELEASING` redone | — | Recovery re-obtains the key, releases, then settles D |
| Settle | — (key kept in S's row) | Lock survives restarts | D stays unremovable until recovery settles it, or the user stops waiting |

Also: ENOSPC on D → 507, retried 10 min; digest mismatch → chunk retried 3×, then
`digest_mismatch`; root mismatch → `content_mismatch`, the driver retries once with a new id,
then fails; version or capability mismatch → refused with no side effects.

### 9.5 Idempotency

`transferId` is the key on both sides. Replays of received chunks are no-ops; `commit` after
`COMMITTED` returns the same key; `release` after `RELEASED` returns the stored `Released`.
Concurrent drivers of one transfer are harmless; the hold protects Undo.

### 9.6 Cancellation and Undo

- **Before prepare**: `abortExport` first (S is OPEN; no vote is outstanding), then `abortImport`.
- **After prepare, before commit**: `abortImport` first. If D answers `ABORTED`, force S; if D
  answers `committed`, the transfer can only finish.
- **After commit**: `cancel()` fails with `committed`.
- **Undo** (during the hold): `abortExport(force = true, undo = true)` on S; only once S answers
  `FORCE_ABORTED` does the app settle D with the revealed key and remove D's task and files.
  Already released →
  "Already moved". If removing D's copy fails, `MessageCenter` shows "Undo didn't finish: X is on
  both MacBook and NAS" [Remove from NAS] [Keep both]; `PendingOps` alone only logs
  (`app/state/PendingOps.kt:109`).
- **User actions on a frozen source** get `task_locked`; the app cancels its own transfer first.
  In `PREPARED` only "Keep here" (force) is offered.
- Freeze steps 2–7, the commit transaction and the release run `NonCancellable`; whatever a
  cancelled caller leaves is resolved by the next call or recovery.

### 9.7 Recovery by the orchestrator

No journal: on launch and whenever a device reconnects, `TaskTransfers.recover` lists transfers
on every reachable transfer-capable device, pairs rows by `transferId` and acts on its own rows
(orchestrator id = its instance):

| S | D | Action |
|---|---|---|
| OPEN | RECEIVING/SEALED | resume the pump, seal, prepare |
| PREPARED | COMMITTED | after `holdUntil`: `commit()` re-obtains the key, `release`, `settle` |
| RELEASED, or FORCE_ABORTED | COMMITTED | `settle` with S's key; after an Undo, ask: [Remove from NAS] [Keep both] |
| PREPARED | ABORTED, or D reachable without the row | force S |
| any | unreachable | "Waiting for NAS" on the task, with [Keep here] |

Other orchestrators' rows show on S's device as "Being moved to NAS by Pixel"; after 10 minutes
without progress they offer [Keep here] and [Finish here] (the same table). On a token-less D the
import key lives only in the driver's memory, so after a restart recovery cannot act on D: the
import expires and S's "Keep here" leaves, at worst, a visible duplicate.

### 9.8 Integrity checklist

1. Per chunk: S hashes what it read; D re-hashes before writing (412).
2. Pinning: a different repeat read or a stat change of the frozen file aborts.
3. Durability before trust: D's received set advances after fsync; committed segments come from
   fully received ranges, so size-only validation never meets a hole. S's prefixes are durable
   bytes only once F3 lands, which gates M1.
4. Content root over chunk digests in plan order; S prepares only on equality and an equal
   manifest hash.
5. Origin preflight at `beginImport`. S never verified HTTP/FTP data against the origin; the
   transfer preserves exactly what S had. `Content-Digest` does not stop an active attacker on
   plaintext HTTP; use TLS (a proxy with `RemoteConfig.secure`,
   `app/instance/InstanceFactory.kt:79`) or a VPN.

---

## 10. Security and privacy

- **Early deletion** by a confused or replaying driver: hash-lock from `ImportOpened` on D's
  admin channel, release only from `PREPARED`, and D's copy locked until S proves it left
  `PREPARED` (§7.3, §7.5).
- **LAN host vs token-less D**: import key on every later call; S checks the root before deleting.
  **vs token-less S**: no export routes.
- **Token holder reading arbitrary files** via `resume?destination=`: output identity (§7.6).
- **Credentials**: AUTO strips for public D, itemized confirmation, plaintext-public rule (§5.5),
  S rows compacted when terminal, never logged (`redactUrl`, `core/Ketch.kt:166,195`; F12).
- **Hostile pages, DNS rebinding**: HostValidation and CrossOriginGuard on token-less servers; no
  CORS change in M1.
- **Malicious manifests, exhaustion**: caps before parsing, strict validation, sanitized names,
  `mustCreate`, concurrency caps, TTLs, space checks.
- **SSRF**: an imported task fetches its URL from D's network, as if added there; the
  confirmation names it. **Same store**: lock and `same_instance` (§7.9).

---

## 11. Compatibility and feature detection

| Pairing | Behaviour |
|---|---|
| New app, old daemon | `status().transfer == null` → fallback, labelled "starts over on <D>". |
| Old `RemoteKetch`, new daemon | Ignores new fields (`remote/RemoteKetch.kt:90`); a frozen active or paused task looks paused by the user (`Transferring` decodes as `User` from #353 on, `api/PauseReason.kt:77-82`), its resume gets 409 (`checkSuccess` says "HTTP 409: Conflict"); released tasks disappear, imported ones appear. |
| Different transfer versions | `protocolMajor` must match; manifest `format` major; per-source schemas; phases and roles are strings; strict enums in requests. |
| D lacks a source type | Not in `sources` → fallback. |
| Native image | New DTOs and `$Companion`s in `cli/src/main/resources/META-INF/native-image/com.linroid.ketch.cli/reflect-config.json`, checked by `NativeImageConfigTest`. |
| Stores | `5.sqm` is additive; downgrades unsupported as for any bump. `device.json` is H's (F7). |
| `KetchApi.VERSION` | Not used; `KetchStatus.transfer` decides, not `features` (§4.1). |
| mDNS | Unchanged: identity comes from authenticated `status()`; H's per-process `boot` nonce is no stable id. |

---

## 12. UX

### 12.1 Evolving Send to / Move to

`AppState.sendTo` takes keys: `sendTo(keys: List<TaskKey>, target, move = false, confirmed =
false)` (`TaskKey`: `app/state/TaskKey.kt:18`). Today it takes `List<DownloadTask>`
(`AppState.kt:1315-1320`) and every caller drops the key first: `RowActionRunner.sendTo` passes
`rows.map { it.task }` (`app/ui/downloads/actions/RowActionRunner.kt:318`), `DeviceDrops` maps
`dropped.keys` back to tasks (`app/ui/shell/DeviceDrops.kt:186`), `SendConfirmation` stores tasks
(`AppState.kt:1969-1974`) and Try again re-calls with them (`:1378`). All pass keys now, which also
ends `deviceOf(task)`'s fallback to the active device for a stale handle (`:1517-1519`);
`AllDevicesTest`, `AppStateCommandsTest` and `AllDevicesSnapshots` change mechanically. `sendTo`:

1. plans each key: Data, Fallback or Refused with a reason (same device, older version, not
   reachable from here, no access code to send, torrents not yet);
2. confirms once per batch, queued (F11), extending `SendConfirmationDialog`
   (`app/ui/downloads/actions/RowActionDialogs.kt:237`) with the data size ("1.9 GB of downloaded
   data will be copied to NAS"), itemized credentials with a "Don't send sign-ins" toggle (forced
   on for a public D), a sign-in field for partial tasks whose origin needs one on D, "starts
   over" items, relay and metered warnings, and free space from
   `DevicePresence.status.system.usableSpace`;
3. starts transfers (≤ 2 per destination) and `claimAdds` D's keys so arrivals are not reported
   twice.

**Move**: sources stay visible with "Moving to NAS · 43 %" and Cancel. After commit,
`PendingOps.register` (6 s, `app/state/PendingOps.kt:170`) holds the release (`commit =
handle.release()`, `undo = handle.undo()`); "Moved X to NAS" offers Undo and Show. `flush()` on
quit and iOS backgrounding releases early, safe because D committed. If S kept its copy: "Moved to
NAS; MacBook kept a copy" with Remove here. **Copy**: "Sent X to NAS" with Show and Remove here.

**Progress** without a new `DownloadState`: an overlay keyed by `TaskKey` from
`TaskTransfers.active` feeds the row pill and bar, the inspector, the Pulse, tray and Dock badge,
the Android notification and iOS continued-processing progress. D shows the task at commit.
`MessageCenter` errors: `origin_changed` → [Start over on NAS] (the fallback, §12.2) [Cancel];
`Duplicated` → [Remove here] [Keep both];
"Waiting for NAS" → [Retry] [Keep here]; `task_locked` → "Being moved to NAS by Pixel".
Rows read `Paused(Transferring)` as "Being moved to NAS" and keep it on the Paused tab
(`isPausedUntilResumed`, `app/state/StatusFilter.kt:40`). A new `isResumable` beside it
(`app/state/TaskStates.kt:15-16`) leaves it out of Resume all, the header and palette counts,
Retry and Start now (`AppState.kt:1035, 1215, 1680`, `app/ui/downloads/DownloadsHeader.kt:401`,
`app/ui/palette/PaletteProviders.kt:298`), and the extension stops offering Resume for it
(`app/browser-extension/src/lib/format.js:117`).

**Lifecycle**: Android's `ForegroundPolicy` (`app/state/ForegroundPolicy.kt:26-75`) counts
transfers, which stop at the 6 h data-sync `onTimeout` (`app/android/.../KetchService.kt:269-275`)
and recover next launch; iOS adds them to `ContinuedDownloads`; desktop counts them in the close
prompt (`app/desktop/.../CloseBehavior.kt:113-126`). Over 100 MB through this device on a metered
network asks first.

### 12.2 Fallback

For old daemons, sources without `SourceTransfer`, token-less sources and the web app. It keeps
today's semantics exactly, as `AllDevicesTest.kt:267-280` asserts: the request is re-added on D
via `forDevice()`; a Move removes the source task when the Undo window ends, deleting an
unfinished partial file and keeping a finished one (`AppState.kt:1387-1392, 2121-2126`). The
confirmation says "Starts over on NAS; progress here is discarded" or "Downloads again on NAS;
the file stays here".

### 12.3 Deferred

Copy to / Move to groups for touch (Move needs ⌥ in Compact density and is absent in Comfortable,
`app/ui/downloads/actions/RowMenu.kt:252-264`), plan captions in `sendTargets` (`RowMenu.kt:542`),
a Transfers activity entry and "Start paused".

---

## 13. Later milestones

### 13.1 M2: torrents, CLI, MCP

- **Torrents**: §8.3, plus the passkey policy (announce URLs carried for partial private torrents,
  dropped for completed ones, whose info hash covers only the info dict; STRIP warns that D finds
  no private peers).
- **CLI** (adds `library:remote` and `library:transfer`, never opens `ketch.db` for transfers):
  `ketch transfer <task-id> --from <name|host:port> --to <name|host:port> [--move] [--dir <sub>]
  [--credentials auto|include|strip] [--yes] [--json]`, `ketch transfers [--on <device>] |
  recover`. **Copy is the default everywhere**; `--move` asks unless `--yes`. Exit codes: 0
  completed, 2 aborted, 3 duplicated or waiting.
- **MCP**: `KetchMcpServer(api, parties: Map<String, () -> KetchApi>)` with `RemoteKetch` parties;
  `listDevices`, `transferDownload(taskId, from, to, mode = "copy", includeCredentials = false,
  confirmSourceDeletion = false)`, `listTransfers`, `cancelTransfer`; stdio only; no tokens or keys
  in results. Its own engine stays transfer-disabled.

### 13.2 M3: delegated daemon ⇄ daemon routes and observation (after H PR8)

PULL (a job on D) or PUSH (a job on S) lets daemons continue after the orchestrator exits and lets
the web app move data. It runs on H's helper pairing and sealed relay sessions (H§7.3-7.4,
H PR8); unpaired daemons keep M1's relay through the app.

- **Trust**: the daemons are paired (H§7.3). M3 adds to H's pairing a per-pairing flag beside
  `allowHelpFor` (such as `allowTransfers`) that admits delegated jobs, and refuses a device
  pairing with itself (equal `instanceId`s), as `same_instance` does. An admin caller starts a job
  with the token of the job's daemon.
- **Sessions**: protocol calls and chunks travel over the sealed session on H's relay listener
  (H§10.1), so a serving daemon runs it even with `[helping] enabled = false`, H's default; it
  binds LAN addresses only, unless `[helping] host` is set. Transfer requests have caps of their
  own there: `maxChunkBytes` bodies on the chunk routes, §6.6's concurrency and no daily byte
  budget, instead of H's helper caps (64 KiB bodies, 20 requests/s, per-owner daily bytes, H§11).
- **Lock binding first**: S serves a delegated export only after an admin call bound D's
  `commitLock`; `prepare` then refuses any other lock. Otherwise a confused or compromised daemon
  could pick its own key, prepare and release with no commit on D.
- **Abort lock**: D returns `sha256(abortKey)` at seal, S stores it at prepare, D reveals it once
  `ABORTED`, so a delegated job can unfreeze S without an admin force.
- **Delegated hold and Undo**: `DelegateRequest.hold` plus admin `/import/release` and
  `/import/undo` on the job's daemon; its outbox releases only while D's row is `COMMITTED`.
- **Observation**: a defaulted task badge with string `mode`/`phase`, or `/api/transfers/events`,
  not both, sent only to clients that ask (`?transfers=1`, like H's `?lanes=1`).
- **Web**: both daemons need tokens and allow the origin; CORS adds `Content-Digest`, `If-Match`,
  `Ketch-Transfer-Key` and exposes `Content-Digest`, `Retry-After` (`server/KetchServer.kt:296-299`;
  H's server change adds `PATCH`). No Undo hold and no `pagehide` flush (an authenticated
  cross-origin fetch during unload is usually dropped); recovery re-attaches on load.

### 13.3 M4: same host, different stores

Proof of a shared filesystem: S writes `.ketch-probe-<nonce>` beside the output and lists paths,
identities and the nonce in an admin-requested manifest; D adopts only if it reads the nonce and
the identities match under its roots. HANDOFF keeps or `atomicMove`s the files; torrents keep
taskId, output and `SourceResumeState`, so their checkpoints stay valid. LOCAL_COPY clones files.

### 13.4 M5: as needed

Hot copy; opt-in verify-at-rest that drops the page cache first (`posix_fadvise(DONTNEED)`,
`F_NOCACHE`, `O_DIRECT`), since a plain re-read after fsync only catches write-path bugs; a
seal-time origin re-probe and `ORIGIN_SAMPLE` for HTTP tasks without validators; skipping the
torrent recheck for trusted imports; partial-commit salvage; built-in TLS for the
controller-to-device admin plane (H's sessions already seal device-to-device traffic) with
fingerprint and `instanceId` in the pairing link (mDNS is spoofable), trust on first use only for
devices added by address; `F_FULLFSYNC` from the JVM via FFM on JDK 22+.

---

## 14. Phased delivery and tests

Order across both plans: shared prerequisites (§7.1: identity, `Sha256`, read-back among them) →
M1 → H PR3–PR7 (lanes) → M2 → H PR8+ (relay, pairing, sessions) → M3 → M4, M5.

| M | Content | Ships | Size |
|---|---|---|---|
| **P** | Shared prerequisites (§7.1): H PR1 (except #5, #6), PR2 and the `Sha256`/read-path part of PR3; hardening any time | Engine safety for both plans; FTP passwords confirmed; self-refusal by id | 2 wk |
| **M1** | api types; library:transfer; core gate, store, lock, `FileReader`, path policy, protocol, readiness, recovery; `PauseReason.Transferring` (every exhaustive consumer, the extension's `format.js` included); `SingleFileTransfer`; endpoints, routes, `StatusPages`; `RemoteTransferController`; app coordinator, keyed `sendTo`, confirmation, overlay, held release, lifecycle. One merge behind a flag. | HTTP/FTP move/copy in every state: laptop/phone ⇄ NAS on desktop, Android, iOS; NAS ⇄ NAS by relay; data leaves a NAS only if it has a token | 4–5 wk |
| **M2** | Torrents (§8.3), CLI, MCP | Torrents of every format; scripted and agent transfers | 3 wk |
| **M3** | Delegated jobs over H's sessions, observation, web (§13.2) | Daemon ⇄ daemon direct; web; orchestrators may exit | 2–3 wk |
| **M4** | Same-host handoff (§13.3) | Zero-copy moves on one host | 1.5 wk |
| **M5** | §13.4 | As needed | — |

**Tests**
- **Unit**: chunk planner (0/1-byte files, uneven splits, chunk-aligned range ends); property-based
  manifest validation (overlaps, gaps, wrong indices, `[]` with `totalBytes > 0`; `[]` or null
  with `totalBytes < 0` and no payload; all-zero progress → metadata-only); sanitizer; received set
  and replays; content root; transition tables; credential detection; error mapping.
- **Core integration**: two `Ketch` instances with separate stores and folders, in-process;
  partial HTTP via `FakeHttpEngine`, resumed on D until the SHA-256 equals the origin's; every §8.2
  row × {MOVE, COPY} for HTTP, Paused/Completed × {MOVE, COPY} for FTP; restarts over the same
  store (`KetchQueueIntegrationTest.kt:101-133`), a frozen task restoring as
  `Paused(Transferring)`, an unfrozen scheduled task restoring as `Scheduled`; an injectable okio
  `FileSystem` for ENOSPC and torn writes. Once H PR5 exists the suite runs in both lane modes;
  H PR9 adds an export of a task whose relayed bytes are partly unaudited (pending and unsampled).
- **Crash-point harness (gate for M1)**: a `FaultInjector` at each durable step kills S, D or the
  driver, rebuilds, recovers and runs to quiescence asserting I1–I5, while dropping, duplicating
  and reordering messages. Named cases: release after "Keep here"; removing D's task during the
  hold (refused until settled); release re-delivered after settle; Undo racing another
  orchestrator; adoption failing after the commit transaction; calls while `starting`; a second
  JVM process on the store.
- **Races**: freeze vs URGENT preemption, `promoteNext`, `setPriority`, `updateLimits`, scheduler
  triggers and remote resume; release vs `setPriority` (F4).
- **SQLite**: `5.sqm` under `verifyMigrations`; commit atomicity and strict insert; pragmas; the
  store lock across two processes.
- **Server/remote**: token-less servers (no export, import key enforced), the extension server
  without routes, `task_locked` → 409 on the existing resume route, `starting`, body caps,
  `MockEngine` mapping incl. 404/405/501, closed clients. **End to end (JVM)**: two
  `KetchServer`s and a Range origin; a server killed mid-copy; cutting and 1 MiB-body proxies.
- **App**: `FakeInstanceFactory` as is; `AllDevicesTest`, `AppStateCommandsTest` (keyed `sendTo`,
  held release, Undo ordering, fallback labels, confirmation queue, public-D stripping); UI
  snapshots. **Compatibility**: golden `KetchStatus` JSON through the previous `RemoteKetch`;
  `Transferring` decoding as `User` through #353's serializer; unknown manifest major → 422;
  native-image metadata.

---

## 15. Review decisions, alternatives rejected, risks, open questions

**Decisions where review findings conflicted** (least mechanism that keeps I1–I5):
1. *Shared store*: heartbeat vs a holder per row → one lazily taken JVM lock file; only its holder
   recovers, freezes and releases; transfer-disabled engines (`ketch mcp`) never take it.
2. *Forged votes on D*: a second hash-lock (`prepareKey`) vs a per-import key → the import key,
   which also blocks foreign aborts.
3. *Stuck `PREPARED`*: monotonic leases vs an abort lock vs no expiry → no expiry; orchestrators
   force S once D reports `ABORTED`; the abort lock waits for M3.
4. *Undo*: a persisted undo journal vs a hold on S's row → the hold plus strict ordering (S
   force-aborted before D's copy goes); an interrupted Undo is surfaced, never auto-completed.
5. *Recovery*: journal + `list()` → `list()` only.
6. *Clients*: dedicated clients, pinning and rebuild → a resolver per call.
7. *Observation*: badge + stream + handle → handle in M1.
8. *Write order*: prefix rule vs any order → any order; the manifest is internal.
9. *Verify at rest*: default for MOVE vs off → off (a page-cache re-read proves little); M5 opt-in.
10. *Export confinement*: roots vs identity → identity, roots for legacy records.
11. *Token-less daemons*: auto-token vs loopback export vs stated limit → stated limit with hints
    (§3.2); fresh-install tokens are open question 6.
12. *Fallback Move*: keep, refuse or never delete → today's semantics exactly, labelled.
13. *Task ids on D*: keep vs mint → always mint (HANDOFF in M4 keeps them).
14. *M1 scope*: every state stays (one freeze path); `LandingPolicy`, CLI, MCP, badge, touch Move
    groups and verify-at-rest leave M1.
15. *Release vs removal on D* (PR review): revoke D's row vs lock D's task until settled → the
    lock, unlocked by a key S mints at `prepare`; a revealed commit key cannot be revoked.
16. *Missing credentials on D* (PR review): land paused and prompt vs ask before bytes move → ask
    first, because Enter credentials restarts a task today; see open question 8.

**Reconciled with H** (revision 4, accepted in principle):
1. *Identity*: `instance_meta` with fingerprint rotation vs `device.json` → H§7.1 alone (F7 = H PR2,
   K14); this plan keeps the JVM store lock and `same_instance`.
2. *Prerequisites*: M0 and H PR1 → one list (§7.1). #359 did F1, F2 (F2b remains); F3 grows into
   H's snapshot → sync → persist; `close()` pauses (#353) but does not wait, so F8 is added.
3. *Read-back*: `FileReader` vs `readAt` → one read path, core `Sha256` and `fileIdentity`;
   `FileReader` stays for export (§7.4). Not applied: one `FileReader` for H's audits too, which
   is H PR3's call.
4. *HTTP resume state*: origin identity only, never provenance or pending audits; prefixes stop at
   the first relayed span no passed audit covers; F6 shared. Refined: `probeOrigin` runs the
   resume's validator check plus length and range checks (§7.5).
5. *Helpers*: K18.
6. *Visibility*: `PauseReason.Transferring` (K12), restored by the gate.
7. *Unknown size* (#354): partial → metadata-only, completed → whole file (§8.2).
8. *#353 fields*: `completedAt` carried, `queuePosition` derived, `createdAt` kept (the task keeps
   its age in D's queue).
9. *Migration*: `5.sqm`, without `instance_meta`.
10. *Order*: §14; M3 runs on H's pairing and sessions, keeping lock binding and the abort lock.
11. *Server Json*: H's server-wide `coerceInputValues` and strict transfer enums coexist (§4.2).

**Rejected**: (1) serving files as HTTP ranges for D's `HttpDownloadSource`: single files only,
no sparse ranges or integrity, and the peer credential in persisted request headers; (2) shipping
`TaskRecord`/`SourceResumeState`: machine-bound torrent state, strict decoding, leaked
credentials; (3) a visible `Importing` state: breaks old clients' list decoding and ~16 exhaustive
`when`s; (4) "add there, delete here" as the data path: it discards progress, so it stays only as
the labelled fallback (§12.2), which never deletes finished files; (5) presumed commit without a
vote; (6) 3PC or consensus: needs synchrony we lack; (7) HMAC-signed grants: clock skew, and M3
gets keys from H's pairing instead; (8) temp-folder staging + rename: breaks torrent
output binding and SAF; (9) hidden staging rows in `task_records`: every loader, older builds
included, would filter; (10) a hard lock on `ketch.db`: breaks co-running engines (the transfer
lock only gates transfers); (11) the driver in library:remote or app/shared: no js/wasmWasi for M3
jobs in core, no CLI or MCP reuse; (12) tar streams or one PUT per file: not resumable per chunk,
not sparse-aware, hostile to proxies; (13) `kt1_` tickets and an unauthenticated `hello` for M3: a
second device-trust system and handshake beside H's pairing.

**Risks**: a `PREPARED` source stays frozen until its orchestrator, the user or D's answer resolves
it (shown); relay doubles traffic until M3; plaintext HTTP until TLS; on macOS JVM a payload off
`ketch.db`'s volume has power-loss exposure until release; iOS suspension and Android's 6 h cap
stretch long transfers; IP-, cookie- or agent-bound and expiring URLs may fail on D after a MOVE
(F1 keeps D's file when that resume fails); sparse files reserve nothing; a JVM process without
the store lock can let a user resume a frozen task, which pins and identity checks turn into an
abort or a kept file, never a deletion of D's copy; a JVM config folder copied to another machine
keeps its `instanceId` (only Android and iOS keep `device.json` out of backups, F7, H§7.1), so
transfers between the two are refused as `same_instance` until one deletes its `device.json`.

**Open questions** (recommended default first)
1. Menu default: **Copy** (as today); Move only when chosen.
2. Leases: **24 h idle** for OPEN and D staging; none for PREPARED; 30 days retention.
3. Release only after D's first successful resume? **No**; F1 (#359) keeps D's file if it fails.
4. Incoming placeholder rows on D before commit: **no** in M1.
5. Roots: **`transferRoots`** for imports now; confining existing destination parameters for
   remote callers behind a server flag.
6. `ketch server` creating and saving a token on a fresh install that binds a non-loopback address
   (printing a pairing link; `--no-token` opts out; existing configs untouched): **done**, for
   any run without a token rather than fresh installs only, as configs made from the template
   were as exposed. It keeps the token in an owner-only `api-token` file, leaving `config.toml`
   untouched, and prints the token rather than a pairing link.
7. Transfers from JS/WASI core engines: **not advertised** (no `HttpEngine` ships there).
8. An in-place credential update on `DownloadTask`, which would also keep progress when cookies
   expire today and let partial imports land paused without a sign-in: **yes**, as a separate
   change.
