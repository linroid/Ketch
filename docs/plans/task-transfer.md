# Cross-device task transfer

**Status:** Proposed, revision 2 · 2026-10-03 · line numbers checked against `main` @ `466e5580`

This plan makes **Send to** and **Move to** carry a task's downloaded data between Ketch instances
("devices"): partial bytes, per-segment progress, source resume state and finished files, so the
destination continues without fetching those bytes again. HTTP/FTP ship first (M1); torrents of
every format follow (M2). It evolves the existing request-only feature (`AppState.sendTo`/`send`,
`app/state/AppState.kt:1302-1421`), which today re-adds the request on the target and starts over.

Paths: `api/` = `library/api/src/commonMain/kotlin/com/linroid/ketch/api/`, `core/` =
`library/core/src/commonMain/.../core/`, `torrent/` = `library/torrent/src/commonMain/.../torrent/`,
`server/` = `library/server/src/main/kotlin/.../server/`, `remote/` =
`library/remote/src/commonMain/.../remote/`, `sqlite/` = `library/sqlite/src/`, `app/` =
`app/shared/src/commonMain/.../app/`, `cli/` = `cli/src/main/kotlin/com/linroid/ketch/cli/`.

The body describes M1; torrents (M2) are §8.3, everything later §13, and §15 records how
conflicting review findings were decided.

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
| K2 | Protocol types behind `@RequiresOptIn` `@KetchTransferProtocol`; callers use the `TaskTransfers` facade in a new `library:transfer`, which declares only M1 surface. | Small stable surface; the library is unpublished, so later roles arrive when built. |
| K3 | `library:transfer` depends only on `library:api` and coroutines, never on core-`internal` types. | `internal` does not cross modules; the build has no friend paths. |
| K4 | S is **frozen** for the whole export: a gate checked **before any state write** (queue `enqueue`/`startTask`, scheduler, top of coordinator `start`/`resume`), a persisted row and a `PAUSED` record. | One runnable owner despite preemption, `setPriority`, restarts or other clients. |
| K5 | Imports are **invisible** until commit (a row plus reserved files, no `TaskRecord`) and every later import call needs the per-import key. | Queue, scheduler, `remove()`, failure cleanup and LAN strangers cannot touch staged bytes. |
| K6 | D's commit point is **one SQLite transaction** strict-inserting a freshly minted task id. | One durable decision; no id collisions. |
| K7 | MOVE release needs a **hash-lock**: D mints the key at `beginImport` and returns its hash to the owner; S stores it at `prepare`; `release` must show the key, is valid **only from `PREPARED`**, and D stops revealing it once its task is removed. | No confused driver, late redelivery or "Keep both" can delete the last copy. |
| K8 | S **prepares** only if D's content root (hash of chunk digests) equals its own. | The source confirms equality before it can delete. |
| K9 | Per-chunk SHA-256 before writing; chunks in any order into a persisted received set; write → fsync → ledger. | Simple drivers, no reorder livelock; the manifest stays core-internal. |
| K10 | `PREPARED` never expires by clock: release, the user's "Keep here", or D's confirmed abort ends it. | No lease-expiry duplicates, no clock-jump hazard. |
| K11 | D chooses paths: sanitized, under its roots, `mustCreate`, never overwritten. Remote export needs the output's recorded identity. | Peer paths mean nothing elsewhere; export must not read arbitrary files. |
| K12 | No new `DownloadState`, `KetchError`, `TaskEvent` subtypes or snapshot fields in M1; other clients see a frozen task as Paused and get `409 task_locked`. | Old clients decode whole lists (`remote/RemoteKetch.kt:297-319`); M1 drivers run in the app. |
| K13 | `CredentialPolicy.AUTO` carries what D needs for the origin and strips the rest; a D reached **without a token is public**, so AUTO strips all there. | Token-less servers broadcast request headers (`server/TaskMapper.kt:8-16`). |
| K14 | Instance identity lives in the task store and rotates with the host fingerprint; on the JVM one process per store takes part (OS lock file). | App, `ketch server` and `ketch mcp` share `ketch.db`; store copies; no heartbeat writes. |
| K15 | Token-less servers import but never export. | Any LAN host could otherwise read their downloads. |
| K16 | Engine data-loss bugs a transfer would hit are fixed first (M0). | `close()`, `remove(false)` and failures delete partial files today. |
| K17 | M1 is one vertical slice: HTTP/FTP in every state, driven from the desktop, Android and iOS apps; embedded ⇄ daemon directly, daemon ⇄ daemon by relay. A default `ketch server` (no token) only receives data. | The user's case end to end in one merge; the token limit is stated. |

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
| Orchestrator, driver | The app that plans, drives and recovers a transfer (holds owner tokens; id = its embedded `instanceId`) / its code pumping chunks between two handles. |
| MOVE / COPY | D becomes the only owner and S deletes after a proven commit / both keep independent tasks. |
| Freeze, seal, prepare, commit, release | S's export lock; D's fsync + root; S's vote; D's atomic insert; S's gated deletion (§9). |
| Hold | The Undo window after commit, during which S refuses `release` from other orchestrators. |
| Import key | Capability from `beginImport`, required by later import calls unless the caller holds D's owner token. |
| Fallback / metadata-only | Today's request-only send (§12.2) / a protocol export without payload. |

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
| Same `ketch.db`: desktop app (`app/desktop/.../main.kt:490`), `ketch server`, `ketch mcp` (`cli/Main.kt:365-367, 629-631`) | one identity, refused (`same_instance`), as is the app's own server re-added as a remote; separate stores on one host relay over loopback |
| Web app, CLI, MCP | fallback / — / — (§13) |
| Desktop browser-extension server | never (`KetchServer(transfers = false)`, §4.5) |

### 3.3 Planner (`TransferPlanner`, library:transfer)

1. `status()` from both. Equal `instanceId` → `same_instance`. `transfer == null`, a missing
   capability or the source type missing from D's `sources` → fallback plan ("starts over").
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
  /* existing fields */
  val instanceId: String? = null,              // the task store's identity; null from old servers
  val transfer: TransferCapabilities? = null,      // null: unsupported or old server
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
because the planner reads `instanceId` in the same call. `TorrentCapability.IMPORT/EXPORT`
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

/** Destination role; [key] is ImportOpened.importKey, optional for D's owner. */
@KetchTransferProtocol interface TransferSink {
  /** Validates, probes the origin, reserves paths; mints the task id and keys. */
  suspend fun beginImport(request: ImportRequest): ImportOpened
  suspend fun ledger(transferId: String, key: String? = null): Ledger
  /** Verifies the digest, writes at the chunk's offset; replays are no-ops. */
  suspend fun writeChunk(transferId: String, chunk: TransferChunk, key: String? = null): ChunkAck
  suspend fun seal(transferId: String, key: String? = null): Sealed
  /** One-transaction insert, adoption, then the commit key. */
  suspend fun commit(transferId: String, prepared: Prepared, key: String? = null): Committed
  /** Discards staging (only files this import created); refused after commit. */
  suspend fun abortImport(transferId: String, key: String? = null): TransferInfo
}

@KetchTransferProtocol interface TransferController : TransferSource, TransferSink {
  suspend fun info(transferId: String): TransferInfo?
  suspend fun list(): List<TransferInfo>
}
```

DTOs (`@Serializable`; chunks travel as raw bodies). Requests decode enums strictly (unknown →
400, never coerced to another mode); responses carry phase and role as strings. Refusals are
exceptions, never booleans inside a success.

| Type | Fields |
|---|---|
| `ExportRequest` / `ExportOpened` | `transferId` (driver-minted UUID), `taskId`, `mode`, `destination` and `orchestrator: TransferPeer(instanceId, name)`, `credentials` (`AUTO`/`INCLUDE`/`STRIP`), `chunkBytes` / `manifestSha256`, `displayName`, `kind` (`data`/`metadata-only`), `chunkCount`, `payloadBytes`, `totalBytes`, `disclosures` |
| `ManifestEnvelope` | `json` (exact bytes, opaque to orchestrators), `sha256` |
| `ImportRequest` / `ImportOpened` | `manifest`, `folder?` (relative for remote callers) / `taskId`, `outputName`, `importKey`, `commitLock` (sha256 of the commit key), `ledger`, `origin` (`match`/`weak`/`skipped`/`needs_credentials`), `warnings` |
| `Ledger`, `Sealed` | `phase`, `chunkCount`, `missing` (index runs), `bytes`; `Ready(contentRoot, taskId)` or `Missing(runs)` |
| `TransferChunk` / `ChunkAck` | `index`, `bytes`, `sha256` / `index`, `status` (`accepted`/`duplicate`), `durable` |
| `PrepareRequest` / `Prepared` | `manifestSha256`, `contentRoot`, `commitLock`, `hold` (≤ 60 s) / `sourceInstanceId`, `contentRoot` |
| `ReleaseRequest` / `Released` / `Committed` | `commitKey`, `orchestratorId` / `kept` (files S could not prove it owned, left in place) / `taskId`, `commitKey` |
| `TransferInfo` | `transferId`, `role`, `mode`, `phase`, `taskId?`, `displayName`, `peer?`, `orchestrator?`, `holdUntil?`, `bytesDone`, `bytesTotal`, `reason?`, `error?`, `updatedAt` |

### 4.3 Errors

`TransferException(TransferError(code: String, message, retryable, detail))`, never a `KetchError`.
The wire body is the existing `ErrorResponse(error = code, message)`
(`library/endpoints/.../model/ErrorResponse.kt:9`) plus a defaulted `detail: Map<String, String>`.

| Codes | HTTP | Retry | Meaning |
|---|---|---|---|
| `starting` / `busy` | 503 / 429, `Retry-After` | yes | before recovery and task loading finish (§7.9) / concurrency caps |
| `insufficient_space` / `io` | 507 / 500 | yes (10 min) / yes | D's disk / a read or write failed |
| `digest_mismatch` | 412 | 3× | body ≠ `Content-Digest`, or a replay ≠ the first receipt |
| `chunk_too_large` | 413 | no | proxy body limit; the message names `client_max_body_size` |
| `same_instance`, `not_durable`, `store_busy` | 409 | no | self; `InMemoryTaskStore`; another process holds the transfer lock |
| `task_locked` | 409 | no | action on a frozen source, existing task routes included |
| `transfer_conflict`, `phase` | 409 | no | same `tid`, other parameters; call invalid now (`detail.phase`) |
| `source_data_changed`, `content_mismatch`, `origin_changed` | 409 | no | frozen file changed; roots differ; origin no longer matches S's validators |
| `held`, `expired`, `revoked`, `committed` | 409 | `held` | inside another holder's hold; S left `PREPARED`; D's task removed; abort after commit |
| `bad_commit_key`, `bad_import_key`, `path_rejected`, `not_exportable` | 403 | no | wrong key; unsafe folder; output identity unproven for a remote caller |
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
  suspend fun undo()                          // during the hold: keep S, remove D's copy
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
`Missing`) → `prepare` → `commit` → hold → `release`. The driver never parses the manifest; it
forwards the envelope and works from indices. Cancellation order is §9.6.

### 4.5 Implementations

- **`Ketch`**: an internal `CoreTransferController`, always non-null, over `SqliteTaskStore` as a
  `TransferStore` (§7.2); any other `TaskStore` gets an in-memory one with `durable = false` (COPY
  only). New parameter `transferPolicy` (roots, caps, `enabled`; `ketch mcp` and one-shot CLI
  engines pass `enabled = false`, so they never take the store lock, §7.9).
- **`RemoteKetch`**: `RemoteTransferController` calls `Api.Transfers.*` Resources on the existing
  client (`install(Resources)`, `remote/RemoteKetch.kt:105`; library:remote already depends on
  endpoints). It works unstarted; after `close()` (`remote/RemoteKetch.kt:240-247`) calls fail
  `closed`. 404, 405 and 501 map to `unsupported` (an unknown POST can reach the `{path...}`
  catch-all, `server/KetchServer.kt:366-384`); other statuses decode `ErrorResponse`, never
  `checkSuccess`, which drops bodies (`remote/RemoteKetch.kt:406-414`). It sends
  `Ketch-Transfer-Key` and per-request timeouts (§6.6).
- **`KetchServer`**: new parameter `transfers: Boolean = false`; `ketch server`
  (`cli/Main.kt:377`) and the apps' Sharing servers pass true, `BrowserExtensionServer` keeps the
  default (`app/desktop/.../BrowserExtensionServer.kt:83-89`), since it serves the full embedded
  API with a token and would otherwise export. `transferRoutes` joins `apiRoutes`
  (`server/KetchServer.kt:321-330`). `StatusPages` gains `exception<TransferException>` mapping
  codes per §4.3 to `ErrorResponse(error = code, …)`, which also covers `resume`/`cancel`/`remove`
  of a frozen task on the existing routes (`server/api/DownloadRoutes.kt:83-140`); today any such
  exception becomes the generic 500 `internal_error` echoing `cause.message`
  (`server/KetchServer.kt:296-304`).

### 4.6 Consumers

**app/shared**: a `TransferCoordinator` owned by `InstanceManager` (in `KetchService` on Android)
wraps `TaskTransfers`. Parties resolve `entryOf(deviceId).instance` per call: `reconcile` swaps a
disconnected device to a fresh unstarted client, never a closed one
(`app/instance/InstanceManager.kt:420-439`), and REST works unstarted, so there are no dedicated
clients, no `wanted()` pinning and no `InstanceFactory` change; a call caught by a swap fails
`closed` and retries through the resolver. Tasks are addressed by `(deviceId, taskId)`; no
`DownloadTask` is held across suspension. CLI, MCP and web: fallback in M1 (§13).

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
  val segments: List<Segment>? = null,       // source layout when any progress exists
  val payload: TransferPayload? = null,      // null: metadata-only
  val credentials: CredentialDisclosure,     // carried and stripped item names
)
@Serializable internal data class TransferTask(
  val request: DownloadRequest,              // §5.3 and §5.5 applied
  val createdAt: Instant, val downloadTime: Duration? = null,
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

The chunk plan is deterministic: ranges are each segment's valid prefix `[start, start +
downloadedBytes)` (a completed file is `[0, totalBytes)`); chunk *k* of range *r* covers
`[start + k·chunkBytes, min(end, start + (k+1)·chunkBytes))`; indices run over ranges in order.
The payload comes from the record's segments whenever any segment has progress, whatever the
state, so a task rescheduled after it started (`core/engine/DownloadScheduler.kt:44-46`) keeps
its bytes.

### 5.2 Portable state (schema 1)

| sourceType | `data` | Notes |
|---|---|---|
| `http` | `{etag, lastModified, totalBytes}` | Same fields as `HttpResumeState` (`core/engine/HttpDownloadSource.kt:375-379`); D decodes leniently and re-encodes. |
| `ftp` | `{totalBytes, mdtm}` | Same as `FtpResumeState`. |
| other | — | Sources without `SourceTransfer` use the fallback (§12.2). Torrent schema: §8.3. |

### 5.3 Carried and not carried

| Carried | Not carried |
|---|---|
| `url`, `connections`, `speedLimit`, `priority`, `selectedFileIds`, `properties` (such as `ketch.origin`) | `outputPath`, `content://` URIs: machine-bound. |
| `schedule` verbatim; `AfterDelay` is re-armed in full on D, as every restart already does on S (`DownloadScheduler.kt:124-127`, `core/Ketch.kt:383`) | `DownloadCondition`s (`hadConditions` lands paused). |
| `headers`, URL userinfo per §5.5 | `resolvedSource` (rebuilt from portable state); `SourceResumeState` verbatim. |
| Segments, `totalBytes`, `sourceType`, portable state, `createdAt`, `downloadTime`, error | Tokens, keys, file identities. |
| `destination` mapped as today's `forDevice` (`AppState.kt:2098-2112`): a name is kept, a file path is reduced to its file name, a folder or `content://` value is dropped | |

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
  ranges equal the prefixes inside `totalBytes`; `chunkCount` recomputed.
- **Versioning**: `format` is a major; minors add defaulted fields (`ignoreUnknownKeys`). Portable
  schemas are versioned per source; an unknown one fails at `beginImport`, not as
  `CorruptResumeState` at resume.

### 5.5 Secrets policy

One predicate, `isSensitiveHeader(name)` in `api/log/LogFormat.kt`, covers `Cookie`,
`Authorization`, `Proxy-Authorization` and names matching `redactUrl`'s keys
(`api/log/LogFormat.kt:132-135`); F11 and `KtorHttpEngine`'s log masking use it too.

| Item | AUTO (default) | INCLUDE | STRIP |
|---|---|---|---|
| Sensitive headers | carried for partial and metadata-only tasks; stripped for completed ones | carried | stripped |
| URL userinfo (FTP `user:pass@`) | as headers | carried | stripped; D lands the task PAUSED and the existing credential prompt asks (§7.5) |
| Signed query values | carried (the URL is the identity) and disclosed | | |

- **Public destinations**: when D is reached without a token (`TransferParty.publicAccess`, i.e.
  `RemoteConfig.apiToken == null`), AUTO behaves as STRIP and the confirmation says why ("anyone
  on <network> can read sign-ins sent to <D>"); INCLUDE needs an explicit choice. The fallback
  follows the same rule.
- Credentials never cross plaintext HTTP to a non-private address (private: loopback, RFC 1918,
  ULA, link-local, `.local`, 100.64/10) without explicit consent.
- Carried items are listed in `disclosures` and confirmed in the UI. Manifests and keys are never
  logged; transfer logs name `transferId` and `taskId`.

---

## 6. Data plane and wire protocol

### 6.1 Resources and models

New `@Resource` classes in `library/endpoints/.../KetchEndpoints.kt`: `Api.Transfers`
(`/api/transfers`) with `ById(tid)`, which has `Export` (`manifest`, `chunks/{index}`, `prepare`,
`release`, `abort`) and `Import` (`chunks/{index}`, `seal`, `commit`, `abort`); server and
`RemoteKetch` share them, and core never sees them (endpoints has no js or wasmWasi target). The
only model change is `ErrorResponse.detail`. Rule for later: an enum in a type that reaches task
snapshots or events must be a string or defaulted, since `coerceInputValues`
(`remote/RemoteKetch.kt:93`) only rescues defaulted properties.

### 6.2 Routes (under `/api/transfers`)

Auth: the owner token where the server has one (`authenticate(AUTH_API)`, tree unchanged; the
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
| `POST /{tid}/import/seal`, `/commit`, `/abort` | D | `Sealed`, `Committed`, `TransferInfo` |

### 6.3 Chunks

- **Size**: `min(S.maxChunkBytes, D.maxChunkBytes)`, 1 MiB by default, fixed per manifest; that
  passes nginx's default `client_max_body_size`. A 413 aborts with `chunk_too_large` naming the
  proxy setting; there is no halving loop. Larger chunks later come from a larger advertised
  `maxChunkBytes`.
- **S reads** through `FileReader` (§7.4), hashes with okio (`commonMain`), answers
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

- **Owner token**: LOCAL and RELAY drivers already hold it, so M1 adds no new trust. The compare
  becomes constant-time (today `credential.token == expectedToken`, `server/KetchServer.kt:311`).
- **Token-less servers** install no Authentication (`server/KetchServer.kt:256-262, 307-328`).
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

`TransferHandle.progress` feeds the driving app. Other clients see a frozen source as Paused; its
actions answer `task_locked` with `detail.transferId` and `detail.peer`. Staged imports appear
only in `list()`. A badge or event stream comes with delegated routes (§13.2).

---

## 7. Core engine changes

### 7.1 Prerequisite fixes (M0)

**Gating M1**, each shippable alone:

| # | Fix | Where | Why |
|---|---|---|---|
| F1 | `cleanupAfterExecution` deletes only on explicit cancel or `remove(deleteFiles = true)` (a caller-set stop reason), never after failure, `close()` or `remove(false)` | `core/engine/DownloadExecution.kt:393-416`, `DownloadCoordinator.kt:165-190`, `core/Ketch.kt:265-292,499-506` | A moved task's failed first resume on D (403, 410) would delete the only copy; `remove(false)` cancels with the state still Downloading, so the partial is deleted. |
| F2 | Non-null `[]` segments with `totalBytes > 0` mean "no progress" in `coordinator.resume` and `validateLocalFile`; pause never persists `[]` | `DownloadCoordinator.kt:136`, `HttpDownloadSource.kt:207-246` | Unfreeze re-enqueues with `preferResume`; such a record resumes into a zero-filled "Completed" file. |
| F4 | `AtomicSaver` tombstone after `remove` | `core/task/AtomicSaver.kt:6-20` | `INSERT OR IGNORE + UPDATE` (`sqlite/.../SqliteTaskStore.kt:54-66`) lets a late `setPriority` resurrect a released task. |
| F5 | `synchronous = FULL` set explicitly everywhere; `fullfsync`, `checkpoint_fullfsync` on Apple (iOS, macOS JVM) | `sqlite/{jvm,android,ios}Main/.../DriverFactory.*.kt` | JVM's `JdbcSqliteDriver(url, Properties())` gets SQLite's defaults (rollback journal, `FULL`), but Android may run compatibility WAL at `NORMAL` and iOS's `NativeSqliteDriver` opens in WAL with no level set; Apple's `fsync` leaves the drive cache. |
| F7 | `instance_meta`: `instanceId` plus a host fingerprint (host name, OS user, canonical db path) whose change mints a new id; `KetchStatus.instanceId`; the app stores `RemoteConfig.instanceId` on connect and refuses its own server by id | `4.sqm`, `api/KetchStatus.kt:17-24`, `app/ui/connect/AddDeviceSheet.kt:117-125` (port-and-name heuristic kept for the nearby list) | Self-transfer refusal; a copied store (backup, Migration Assistant) stops sharing an id. |
| F10a | `ErrorResponse.detail`; `StatusPages` maps `TransferException` | `server/KetchServer.kt:278-305` | `task_locked` as 409, not 500. |
| F11 | Credential detection covers userinfo, `isSensitiveHeader` and query keys; `sendConfirmation` becomes a queue | `AppState.kt:1318-1328, 1946-1958` | FTP passwords go without asking; a second send overwrites the first. |

**Independent hardening**, any time: F3 fsync after `job.join()` in pause
(`DownloadCoordinator.kt:66-112`); F5b skip undecodable rows, insert `created_at`
(`SqliteTaskStore.kt:39-147`); F6 lenient resume decoding (`HttpDownloadSource.kt:137`,
`ftp/.../FtpDownloadSource.kt:173`, `TorrentDownloadSource.kt:287-288`); F9 `forDevice()` keeps
`resolvedSource` for `torrent:` and content-resolved tasks, which fail on the target today
(`TorrentDownloadSource.kt:193-195`), F11 then counting passkeys in carried metainfo and magnet
`tr=`; F10b server Json `coerceInputValues`, constant-time compare; F12 `KtorHttpEngine` masks
every `isSensitiveHeader`, not only `set-cookie`, `cookie`, `authorization`
(`library/ktor/.../KtorHttpEngine.kt:212-221`).

### 7.2 `TransferStore` and schema

```kotlin
/** Durable transfer bookkeeping; SqliteTaskStore implements it next to TaskStore. */
interface TransferStore {
  val durable: Boolean
  suspend fun instanceId(): String
  suspend fun save(row: TransferRow)
  suspend fun load(transferId: String): TransferRow?
  suspend fun active(): List<TransferRow>
  /** Commit point: strict-inserts [record] and saves [row] in one transaction. */
  suspend fun commitImport(record: TaskRecord, row: TransferRow)
}
```

```sql
-- 4.sqm (schema 4 -> 5; databases/5.db)
ALTER TABLE task_records ADD COLUMN output_identity TEXT;   -- §7.6
CREATE TABLE instance_meta(key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE transfers(
  transfer_id TEXT NOT NULL PRIMARY KEY,           -- one role per store (self-transfer refused)
  role TEXT NOT NULL, task_id TEXT NOT NULL, mode TEXT NOT NULL, phase TEXT NOT NULL,
  state_json TEXT NOT NULL,   -- S: prior state, stat, lock, hold, orchestrator;
                              -- D: received runs, output, identity, commitKey, importKey hash
  manifest_json TEXT, manifest_sha256 TEXT,        -- write-once; compacted when terminal
  expires_at INTEGER,                              -- OPEN lease / staging TTL; null for PREPARED
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX transfers_frozen ON transfers(task_id)
  WHERE role = 'SOURCE' AND phase IN ('FREEZING','OPEN','PREPARED','RELEASING');
-- TaskRecords.sq gains insertNew: INSERT (no OR IGNORE), used only by commitImport.
```

`TaskRecord` gains `outputIdentity: String? = null`; "Moved from MacBook" comes from D's row.

### 7.3 Freeze, unfreeze, release (source)

**Gate.** An internal `TransferGate` holds frozen ids, checked before any state write:
`DownloadQueue.enqueue` under its mutex before `markQueued` (`core/engine/DownloadQueue.kt:59-82`);
`DownloadQueue.startTask` before the `activeEntries` put (`:250-268`), which every start passes
through: `promoteNext` (from `enqueue`, `updateLimits`, `dequeue`, completions), `setPriority`
(`:185-212`, which calls `startTask` and `tryPreemptAndStart` directly) and URGENT preemption; the
URGENT victim choice (`:90-138`), so preemption never writes `QUEUED` to a task being frozen;
`DownloadScheduler.schedule`/`reschedule`; and the top of `DownloadCoordinator.start`/`resume`,
before their `QUEUED`/`DOWNLOADING` writes (`DownloadCoordinator.kt:56-63, 119-161`), with
`launchExecution` (`:211`) only asserting. `TaskController.resume`, `reschedule`, `cancel` and
`remove` throw `task_locked`; speed, priority and connection changes persist as today but cannot
start the task.

**Freeze** (`openExport`), under the per-transfer mutex; steps 2–7 run `NonCancellable`:
1. Task exists and is not frozen (same `tid` → stored result); `destination ≠ self`; store lock
   held (§7.9); MOVE needs `durable`.
2. Persist the S row `FREEZING` with `prior = {state, schedule}` and the orchestrator.
3. `gate.freeze(taskId)`.
4. `scheduler.cancel`, `queue.dequeue`, `coordinator.pause` (cancels, flushes, joins,
   `DownloadCoordinator.kt:66-112`), `awaitCompletion`.
5. Prior SCHEDULED, QUEUED or DOWNLOADING → `record.update { state = PAUSED }` unconditionally
   (pause skips it when segments are empty); publish `Paused(progress)`.
6. Read `handle.record.value` (exact after pause) and call `source.transfer.openExport`: the file
   must exist with `size ≥` the payload's end (partial) or `== totalBytes` (completed); remote
   callers need a provable output (§7.6); capture `stat = (size, mtime, identity)`; build the
   plan and portable state, or a metadata-only export (§8.2).
7. Persist `OPEN` (manifest, stat, 24 h idle lease renewed by chunk reads and `prepare`, not by
   `info`). Failure in 6–7 unfreezes and marks the row `ABORTED`.

**Unfreeze** (COPY prepare, abort, OPEN expiry, force): `gate.thaw`; SCHEDULED →
`scheduler.schedule`; QUEUED/DOWNLOADING → `queue.enqueue(preferResume = true)` (safe after F2).
Row → `DONE`, `ABORTED`, `EXPIRED` or `FORCE_ABORTED` (with `undo` when Undo asked).

**Release** (MOVE): only from `PREPARED` (or a redo from `RELEASING`, an answer from `RELEASED`);
any other phase answers `expired` or `phase`. `sha256(key) == lock`, else `bad_commit_key`. Before
`holdUntil`, only the holding orchestrator may release (`held` otherwise). Persist `RELEASING`,
then an internal `releaseTransferred` (not `cancel`, which would flash `Canceled` over SSE):
tombstone the saver; `coordinator.release`; `coordinator.cleanup`, where single-file sources first
compare the output's stat and identity with the frozen ones and keep a replaced or changed file
rather than delete by path; `taskStore.remove`; drop from `_tasks`; thaw; `RELEASED`, listing kept
files in `Released.kept`. Every step is idempotent and redone from `RELEASING` on restart.

### 7.4 Export reading

```kotlin
internal interface FileReader : AutoCloseable {
  suspend fun stat(): FileStat                                  // size, mtime, identity
  suspend fun readFully(offset: Long, into: ByteArray, length: Int)
}
internal expect fun openFileReader(path: String, io: CoroutineDispatcher): FileReader
internal expect fun fileIdentity(path: String): String?
```

`fileIdentity` is core's own copy of torrent's `internal expect fun torrentFileIdentity`
(`torrent/TorrentCheckpoint.kt:122`), since `internal` cannot cross modules; core adds js and
wasmWasi actuals returning null (those targets do not advertise transfers). Reads use okio
`openReadOnly` + `FileHandle.read`, and on Android `content://` `openFileDescriptor(uri, "r")` +
`Os.pread`, identified by document id. `FileAccessor` stays write-only
(`core/file/FileAccessor.kt:14-35`); the reader never creates or truncates, needs no rw SAF grant
and does not queue behind the writer's `limitedParallelism(1)`.

### 7.5 Import staging and commit (destination)

`beginImport`:
1. Validate (§5.4); same `tid` and manifest hash → stored result, otherwise 409.
2. Origin preflight for partial HTTP/FTP (`SourceTransfer.probeOrigin`, extracted from the
   HEAD/MDTM checks in `HttpDownloadSource.kt:132-184` and FTP resume): validators equal,
   `Content-Length == totalBytes`, ranges supported → `match`; no validators → `weak`; changed →
   409 `origin_changed` with nothing created (the app offers the fallback). An authentication
   failure (stripped cookies or FTP login) → `needs_credentials`: validators are not compared and
   the task lands PAUSED, so the existing credential prompt runs on first resume.
3. Mint a fresh task id (MOVE and COPY alike), `importKey` and `commitKey`.
4. Folder and name (§7.6), space (§7.7).
5. Persist row `RECEIVING` (task id, output, keys, empty received set) **before** any file.
6. `openImport`: create with `mustCreate`, record the identity, `preallocate(totalBytes)` (sparse,
   so D's size-only `validateLocalFile` passes).

There is no `TaskRecord` or task; only `abortImport` or the TTL deletes staged files, and only
identity-matched files this import created.

`seal`: all chunks received → `durableSync` the file and `syncDirectory` its parent (best effort)
→ `contentRoot = sha256(digest₀ ‖ … ‖ digestₙ)` over receipt digests, re-reading only chunks whose
digests a restart lost → `SEALED` → `Ready(root, taskId)`.

`commit(prepared)`: require `SEALED`, `prepared.contentRoot == root` and `sourceInstanceId` equal
to the manifest's source. `finish()` yields path, segments, `SourceResumeState` and completeness;
the `TaskRecord` takes the manifest's request with `destination = Destination(outputPath)`, the
§8.2 state, `createdAt`, `downloadTime` and `outputIdentity`.
`transferStore.commitImport(record, row → COMMITTED)` is **the decision point** (strict insert).
Then `Ketch.adoptRecord` (`createTaskFromRecord` + append under `tasksMutex`, as `download()`
does, `core/Ketch.kt:182-186`), then the row is marked adopted, and only then is the key returned.
If adoption throws, the call fails retryably with the key still hidden; the next `commit()` or
`start()` adopts idempotently (`loadTasks` restores the record anyway). A repeated commit returns
the same key while the row is `COMMITTED`. Removing, canceling or releasing the imported task on D
first moves the row to `REVOKED` (same mutex); `commit()` then answers `revoked`.

### 7.6 Destination path policy and export confinement

`internal class DestinationPathPolicy` extracts `resolveDestPath`/`deduplicatePath`
(`core/engine/DownloadExecution.kt:545-604`) for fresh downloads and imports alike.
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
  (`DownloadCoordinator.kt:146-157`) or a `Destination.isFile()` names an existing file
  (`resolveDestPath` returns it raw, `DownloadExecution.kt:551-553`). A remote caller exports only
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
`SEALED`; commit under F5's pragmas. `durableSync` is `fcntl(F_FULLFSYNC)` on iOS and
`fsync`/`FileChannel.force(true)` elsewhere. On macOS JVM the commit's `F_FULLFSYNC` (SQLite
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
third-party source may keep machine-bound resume state.

### 7.9 Readiness, store lock and recovery

- **Readiness**: `CoreTransferController` answers `starting` (503, `Retry-After: 1`) until
  `Ketch.start()` has run recovery and then `loadTasks()` (`core/Ketch.kt:207-210, 344-368`).
  `ketch server` deliberately listens before `start()` (`serveDaemon`, `cli/Main.kt:425-434`), so
  a daemon that cannot bind never resumes tasks; the gate makes that order harmless. Recovery and
  route handlers take the same per-transfer mutex.
- **Store lock (JVM)**: before recovery and any transfer, the controller takes an exclusive OS lock
  on `<db>.transfer-lock` (`FileChannel.tryLock`; the OS releases it on exit or crash). Without it,
  calls answer `store_busy` and rows stay untouched; it is retried on the next call, and recovery
  runs when it is finally taken (OPEN and PREPARED tasks have loaded as PAUSED meanwhile). Android
  and iOS stores belong to one process. Engines with `transferPolicy.enabled = false` (`ketch
  mcp`, one-shot CLI downloads) never take it, so they cannot block the desktop app.
- **Recovery** (lock holder only):
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
| HTTP | Valid prefixes (completed: whole file); `HttpResumeState` fields | Reserve, preallocate, any-order writes, seal; segments = manifest segments | `resume()`: HEAD with carried headers, size check passes, resegments to D's connections |
| FTP | Same; `{totalBytes, mdtm}` | Same | MDTM re-probe; stripped credentials → the existing prompt |
| Source without `SourceTransfer` (torrents until M2, third-party) | — | — | fallback (§12.2) |

### 8.2 By state

| State on S | Freeze | Payload | Lands on D | S after COPY or aborted MOVE |
|---|---|---|---|---|
| Scheduled, no progress | `scheduler.cancel`, PAUSED | none | SCHEDULED | re-scheduled |
| Scheduled after it started (has segments) | same | prefixes | SCHEDULED with segments | re-scheduled |
| Queued, no progress (`segments == null`) | `dequeue`, PAUSED | none | QUEUED, fresh | re-enqueued |
| Downloading, or Queued after preemption | pause (join), PAUSED | prefixes | QUEUED + segments → resumes | `enqueue(preferResume)` |
| Paused | — | prefixes | PAUSED | PAUSED |
| Failed, retryable, file intact | `awaitCompletion` | prefixes | FAILED (user resumes) | FAILED |
| Failed with `FileChanged`/`CorruptResumeState`, or file gone | — | none | QUEUED, fresh | unchanged |
| Completed, file intact | — | whole file | COMPLETED(outputPath, totalBytes, downloadTime) | unchanged |
| Completed with missing file, Canceled | — | none | QUEUED, fresh ("Download again on D") | unchanged |
| Zero-byte completed | — | one empty range | COMPLETED, empty file created | unchanged |

`hadConditions` and `needs_credentials` land PAUSED. Metadata-only exports happen only when S
holds no usable data, so their release deletes nothing of value.

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
  root S matched, and only while D's task still exists.
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
  FREEZING --crash-------------------------------> ABORTED (prior state restored)
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
  COMMITTED (key revealable; purged after 30 days)
     | D's task removed, canceled or moved on
     v
  REVOKED
```

Orchestrator phases (`planning → exporting → copying → sealing → preparing → committing → holding
→ releasing → done | aborted | duplicated`) are derived from S and D and never authoritative.

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
  |                             | PREPARED{lock, holdUntil}, frozen (c2)
  |<------------ Prepared(S.id, root) -------|                      |
  | commit{prepared} ----------------------------------------------->| check root, S.id; one tx:
  |                             |                                   | TaskRecord + COMMITTED;
  |<-------------------------------------- Committed(taskId, key) --| adopt, then reveal   (c3)
  | [Undo window: hold]         |                                   |
  | release{key, orch} -------->| PREPARED? sha256(key)==lock? -> RELEASING (c4)
  |                             | stop, identity-checked delete, remove -> RELEASED (c5)
  |<------------- Released(kept)|                                   |

c1  seal is idempotent; S is OPEN and frozen.   c2  PREPARED survives restarts; retries get
the stored answer.   c3  SEALED (retry) or COMMITTED with the task (retry returns the key;
REVOKED answers `revoked`).   c4/c5  RELEASING is redone on restart; later calls get Released.
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
| Release | `RELEASING` redone | — | Recovery re-obtains the key and releases (unless D revoked) |

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
  `FORCE_ABORTED` does the app remove D's task and files (revoking D's row). Already released →
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
| PREPARED | COMMITTED | after `holdUntil`: `commit()` re-obtains the key, then `release` |
| PREPARED | REVOKED | force S ("the copy on NAS was removed") |
| PREPARED | ABORTED, or D reachable without the row | force S |
| FORCE_ABORTED (undo) | COMMITTED | ask: [Remove from NAS] [Keep both] |
| any | unreachable | "Waiting for NAS" on the task, with [Keep here] |

Other orchestrators' rows show on S's device as "Being moved to NAS by Pixel"; after 10 minutes
without progress they offer [Keep here] and [Finish here] (the same table). On a token-less D the
import key lives only in the driver's memory, so after a restart recovery cannot act on D: the
import expires and S's "Keep here" leaves, at worst, a visible duplicate.

### 9.8 Integrity checklist

1. Per chunk: S hashes what it read; D re-hashes before writing (412).
2. Pinning: a different repeat read or a stat change of the frozen file aborts.
3. Durability before trust: D's received set advances after fsync; committed segments come from
   fully received ranges, so size-only validation never meets a hole.
4. Content root over chunk digests in plan order; S prepares only on equality and an equal
   manifest hash.
5. Origin preflight at `beginImport`. S never verified HTTP/FTP data against the origin; the
   transfer preserves exactly what S had. `Content-Digest` does not stop an active attacker on
   plaintext HTTP; use TLS (a proxy with `RemoteConfig.secure`,
   `app/instance/InstanceFactory.kt:74`) or a VPN.

---

## 10. Security and privacy

- **Early deletion** by a confused or replaying driver: hash-lock from D's owner-channel
  `ImportOpened`, release only from `PREPARED`, `revoked` on D (§7.3, §7.5).
- **LAN host vs token-less D**: import key on every later call; S checks the root before deleting.
  **vs token-less S**: no export routes.
- **Token holder reading arbitrary files** via `resume?destination=`: output identity (§7.6).
- **Credentials**: AUTO strips for public D, itemized confirmation, plaintext-public rule (§5.5),
  S rows compacted when terminal, never logged (`redactUrl`, `core/Ketch.kt:164,193`; F12).
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
| Old `RemoteKetch`, new daemon | Ignores new fields (`remote/RemoteKetch.kt:90`); a frozen task looks Paused, its resume gets 409 (`checkSuccess` says "HTTP 409: Conflict"); released tasks disappear, imported ones appear. |
| Different transfer versions | `protocolMajor` must match; manifest `format` major; per-source schemas; phases and roles are strings; strict enums in requests. |
| D lacks a source type | Not in `sources` → fallback. |
| Native image | New DTOs and `$Companion`s in `cli/src/main/resources/META-INF/native-image/com.linroid.ketch.cli/reflect-config.json`, checked by `NativeImageConfigTest`. |
| Stores | `4.sqm` is additive; downgrades unsupported as for any bump. |
| `KetchApi.VERSION` | Not used; capabilities decide. |
| mDNS | Unchanged: identity comes from authenticated `status()`, so no stable id is broadcast. |

---

## 12. UX

### 12.1 Evolving Send to / Move to

`AppState.sendTo` takes keys: `sendTo(keys: List<TaskKey>, target, move = false, confirmed =
false)` (`TaskKey`: `app/state/TaskKey.kt:18`). Today it takes `List<DownloadTask>`
(`AppState.kt:1311-1316`) and every caller drops the key first: `RowActionRunner.sendTo` passes
`rows.map { it.task }` (`app/ui/downloads/actions/RowActionRunner.kt:309`), `DeviceDrops` maps
`dropped.keys` back to tasks (`app/ui/shell/DeviceDrops.kt:186`), `SendConfirmation` stores tasks
(`AppState.kt:1939-1944`) and Try again re-calls with them (`:1374`). All pass keys now, which also
ends `deviceOf(task)`'s fallback to the active device for a stale handle (`:1513-1515`);
`AllDevicesTest`, `AppStateCommandsTest` and `AllDevicesSnapshots` change mechanically. `sendTo`:

1. plans each key: Data, Fallback or Refused with a reason (same device, older version, not
   reachable from here, no access code to send, torrents not yet);
2. confirms once per batch, queued (F11), extending `SendConfirmationDialog`
   (`app/ui/downloads/actions/RowActionDialogs.kt:237`) with the data size ("1.9 GB of downloaded
   data will be copied to NAS"), itemized credentials with a "Don't send sign-ins" toggle (forced
   on for a public D), "starts over" items, relay and metered warnings, and free space from
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

**Lifecycle**: Android's `ForegroundPolicy` (`app/state/ForegroundPolicy.kt:26-75`) counts
transfers, which stop at the 6 h data-sync `onTimeout` (`app/android/.../KetchService.kt:260-266`)
and recover next launch; iOS adds them to `ContinuedDownloads`; desktop counts them in the close
prompt (`app/desktop/.../CloseBehavior.kt:113-126`). Over 100 MB through this device on a metered
network asks first.

### 12.2 Fallback

For old daemons, sources without `SourceTransfer`, token-less sources and the web app. It keeps
today's semantics exactly, as `AllDevicesTest.kt:267-280` asserts: the request is re-added on D
via `forDevice()`; a Move removes the source task when the Undo window ends, deleting an
unfinished partial file and keeping a finished one (`AppState.kt:1383-1388, 2091-2096`). The
confirmation says "Starts over on NAS; progress here is discarded" or "Downloads again on NAS;
the file stays here".

### 12.3 Deferred

Copy to / Move to groups for touch (Move needs ⌥ in Compact density and is absent in Comfortable,
`app/ui/downloads/actions/RowMenu.kt:249-261`), plan captions in `sendTargets` (`RowMenu.kt:517`),
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

### 13.2 M3: delegated daemon ⇄ daemon routes and observation

PULL (a job on D) or PUSH (a job on S) lets daemons continue after the orchestrator exits and lets
the web app move data. Beyond M1 it needs:

- **Per-transfer tickets** (`kt1_` + 32 random bytes, stored hashed with role, `tid`, renewable
  expiry, bound to `sha256(apiToken)`) under `authenticate(AUTH_API, AUTH_TICKET)` outside the
  owner block (nested `authenticate` blocks must all pass), handlers checking principal, `tid` and
  role, and a test that a ticket gets 401 on `/api/tasks`. The import key serves as import ticket.
- **Lock binding first**: S mints an export ticket only after an owner call bound D's
  `commitLock`; `prepare` then refuses any other lock. Otherwise a leaked export ticket could pick
  its own key, prepare and release with no commit on D.
- **Abort lock**: D returns `sha256(abortKey)` at seal, S stores it at prepare, D reveals it once
  `ABORTED`, so a ticket-holding job can unfreeze S without owner force.
- **`hello`/`probe`**: `hello` answers `sha256(instanceId ‖ callerNonce)`, sends no CORS headers
  and refuses requests carrying `Origin`; it prevents misdirection, it does not authenticate. Peer
  clients use a separate `HttpClient` without the owner bearer (`remote/RemoteKetch.kt:106-113`),
  outside `MultiNetworkHttpEngine`.
- **Delegated hold and Undo**: `DelegateRequest.hold` plus owner `/import/release` and
  `/import/undo` on the job's daemon; its outbox releases only while D's row is `COMMITTED`.
- **Observation**: a defaulted task badge with string `mode`/`phase`, or `/api/transfers/events`,
  not both.
- **Web**: both daemons need tokens and allow the origin; CORS adds `Content-Digest`, `If-Match`,
  `Ketch-Transfer-Key` and exposes `Content-Digest`, `Retry-After`. No Undo hold and no
  `pagehide` flush (an authenticated cross-origin fetch during unload is usually dropped);
  recovery re-attaches on load.

### 13.3 M4: same host, different stores

Proof of a shared filesystem: S writes `.ketch-probe-<nonce>` beside the output and lists paths,
identities and the nonce in an owner-requested manifest; D adopts only if it reads the nonce and
the identities match under its roots. HANDOFF keeps or `atomicMove`s the files; torrents keep
taskId, output and `SourceResumeState`, so their checkpoints stay valid. LOCAL_COPY clones files.

### 13.4 M5: as needed

Hot copy; opt-in verify-at-rest that drops the page cache first (`posix_fadvise(DONTNEED)`,
`F_NOCACHE`, `O_DIRECT`), since a plain re-read after fsync only catches write-path bugs; a
seal-time origin re-probe and `ORIGIN_SAMPLE` for HTTP tasks without validators; skipping the
torrent recheck for trusted imports; partial-commit salvage; built-in TLS with fingerprint and
`instanceId` in the pairing link (mDNS is spoofable), trust on first use only for devices added
by address; `F_FULLFSYNC` from the JVM via FFM on JDK 22+.

---

## 14. Phased delivery and tests

| M | Content | Ships | Size |
|---|---|---|---|
| **M0** | Gating fixes F1, F2, F4, F5, F7, F10a, F11 (§7.1); hardening any time | Engine safety; FTP passwords confirmed; self-refusal by id | 1 wk |
| **M1** | api types; library:transfer; core gate, store, lock, `FileReader`, path policy, identity, protocol, readiness, recovery; `SingleFileTransfer`; endpoints, routes, `StatusPages`; `RemoteTransferController`; app coordinator, keyed `sendTo`, confirmation, overlay, held release, lifecycle. One merge behind a flag. | HTTP/FTP move/copy in every state: laptop/phone ⇄ NAS on desktop, Android, iOS; NAS ⇄ NAS by relay; data leaves a NAS only if it has a token | 4–5 wk |
| **M2** | Torrents (§8.3), CLI, MCP | Torrents of every format; scripted and agent transfers | 3 wk |
| **M3** | Tickets, delegated jobs, observation, web (§13.2) | Daemon ⇄ daemon direct; web; orchestrators may exit | 3 wk |
| **M4** | Same-host handoff (§13.3) | Zero-copy moves on one host | 1.5 wk |
| **M5** | §13.4 | As needed | — |

**Tests**
- **Unit**: chunk planner (0/1-byte files, uneven splits, chunk-aligned range ends); property-based
  manifest validation (overlaps, gaps, wrong indices, `[]` with `totalBytes > 0`); sanitizer;
  received set and replays; content root; transition tables; credential detection; error mapping.
- **Core integration**: two `Ketch` instances with separate stores and folders, in-process;
  partial HTTP via `FakeHttpEngine`, resumed on D until the SHA-256 equals the origin's; every §8.2
  row × {MOVE, COPY} for HTTP, Paused/Completed × {MOVE, COPY} for FTP; restarts over the same
  store (`KetchQueueIntegrationTest.kt:97-130`); an injectable okio `FileSystem` for ENOSPC and
  torn writes.
- **Crash-point harness (gate for M1)**: a `FaultInjector` at each durable step kills S, D or the
  driver, rebuilds, recovers and runs to quiescence asserting I1–I5, while dropping, duplicating
  and reordering messages. Named cases: release after "Keep here"; release re-delivered after D's
  task was removed; Undo racing another orchestrator; adoption failing after the commit
  transaction; calls while `starting`; a second JVM process on the store.
- **Races**: freeze vs URGENT preemption, `promoteNext`, `setPriority`, `updateLimits`, scheduler
  triggers and remote resume; release vs `setPriority` (F4).
- **SQLite**: `4.sqm` under `verifyMigrations`; commit atomicity and strict insert; pragmas; the
  store lock across two processes.
- **Server/remote**: token-less servers (no export, import key enforced), the extension server
  without routes, `task_locked` → 409 on the existing resume route, `starting`, body caps,
  `MockEngine` mapping incl. 404/405/501, closed clients. **End to end (JVM)**: two
  `KetchServer`s and a Range origin; a server killed mid-copy; cutting and 1 MiB-body proxies.
- **App**: `FakeInstanceFactory` as is; `AllDevicesTest`, `AppStateCommandsTest` (keyed `sendTo`,
  held release, Undo ordering, fallback labels, confirmation queue, public-D stripping); UI
  snapshots. **Compatibility**: golden `KetchStatus` JSON through the previous `RemoteKetch`;
  unknown manifest major → 422; native-image metadata.

---

## 15. Review decisions, alternatives rejected, risks, open questions

**Decisions where review findings conflicted** (least mechanism that keeps I1–I5):
1. *Shared store*: heartbeat vs per-row owners → one lazily taken JVM lock file; only its holder
   recovers, freezes and releases; transfer-disabled engines (`ketch mcp`) never take it.
2. *Forged votes on D*: a second hash-lock (`prepareKey`) vs a per-import key → the import key,
   which also blocks foreign aborts and becomes M3's import ticket.
3. *Stuck `PREPARED`*: monotonic leases vs an abort lock vs no expiry → no expiry; owners force S
   once D reports `ABORTED`; the abort lock waits for M3 tickets.
4. *Undo*: a persisted undo journal vs a hold on S's row → the hold plus strict ordering (S
   force-aborted before D's copy goes); an interrupted Undo is surfaced, never auto-completed.
5. *Recovery*: journal + `list()` → `list()` only. 6. *Clients*: dedicated clients, pinning and
   rebuild → a resolver per call. 7. *Observation*: badge + stream + handle → handle in M1.
8. *Write order*: prefix rule vs any order → any order; the manifest is internal.
9. *Verify at rest*: default for MOVE vs off → off (a page-cache re-read proves little); M5 opt-in.
10. *Export confinement*: roots vs identity → identity, roots for legacy records.
11. *Token-less daemons*: auto-token vs loopback export vs stated limit → stated limit with hints
    (§3.2); fresh-install tokens are open question 6.
12. *Fallback Move*: keep, refuse or never delete → today's semantics exactly, labelled.
13. *Task ids on D*: keep vs mint → always mint (HANDOFF in M4 keeps them).
14. *M1 scope*: every state stays (one freeze path); `LandingPolicy`, CLI, MCP, badge, touch Move
    groups and verify-at-rest leave M1.

**Rejected**: (1) serving files as HTTP ranges for D's `HttpDownloadSource`: single files only,
no sparse ranges or integrity, and the peer credential in persisted request headers; (2) shipping
`TaskRecord`/`SourceResumeState`: machine-bound torrent state, strict decoding, leaked
credentials; (3) a visible `Importing` state: breaks old clients' list decoding and ~16 exhaustive
`when`s; (4) "add there, delete here" as the data path: it discards progress, so it stays only as
the labelled fallback (§12.2), which never deletes finished files; (5) presumed commit without a
vote; (6) 3PC or consensus: needs synchrony we lack; (7) HMAC-signed grants: clock skew and a
crypto dependency while payloads stay plaintext; (8) temp-folder staging + rename: breaks torrent
output binding and SAF; (9) hidden staging rows in `task_records`: every loader, older builds
included, would filter; (10) a hard lock on `ketch.db`: breaks co-running engines (the transfer
lock only gates transfers); (11) the driver in library:remote or app/shared: no js/wasmWasi for M3
jobs in core, no CLI or MCP reuse; (12) tar streams or one PUT per file: not resumable per chunk,
not sparse-aware, hostile to proxies.

**Risks**: a `PREPARED` source stays frozen until its orchestrator, the user or D's answer resolves
it (shown); relay doubles traffic until M3; plaintext HTTP until TLS; on macOS JVM a payload off
`ketch.db`'s volume has power-loss exposure until release; iOS suspension and Android's 6 h cap
stretch long transfers; IP-, cookie- or agent-bound and expiring URLs may fail on D after a MOVE
(F1 keeps D's file when that resume fails); sparse files reserve nothing; a JVM process without
the store lock can let a user resume a frozen task, which pins and identity checks turn into an
abort or a kept file, never a deletion of D's copy.

**Open questions** (recommended default first)
1. Menu default: **Copy** (as today); Move only when chosen.
2. Leases: **24 h idle** for OPEN and D staging; none for PREPARED; 30 days retention.
3. Release only after D's first successful resume? **No**; F1 keeps D's file if that resume fails.
4. Incoming placeholder rows on D before commit: **no** in M1.
5. Roots: **`transferRoots`** for imports now; confining existing destination parameters for
   remote callers behind a server flag.
6. `ketch server` creating and saving a token on a fresh install that binds a non-loopback address
   (printing a pairing link; `--no-token` opts out; existing configs untouched): **yes**, as a
   separate change.
7. Transfers from JS/WASI core engines: **not advertised** (no `HttpEngine` ships there).
