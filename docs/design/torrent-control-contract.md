# Torrent control protocol, version 1

This contract implements the API boundary decision in [roadmap #162][roadmap]. The types live in
`library:api`, so WebAssembly and remote clients can use them without linking a torrent engine.
This PR defines inspection, capability negotiation, ordering, and command preconditions. Runtime
wiring and the typed mutation methods ship with their capabilities, with SDK/HTTP/SSE parity checked
in roadmap step 27. A contract declaration is not an implemented runtime capability.

## Availability and negotiation

`KetchApi.torrents` is nullable and defaults to null for existing backend implementations. Null means
no typed control protocol, not no torrent downloader. Local and remote adapters expose a controller
only when they implement inspection. They advertise only executable features. The backend's
platform determines capabilities: a browser connected to a daemon can have capabilities unavailable
in a local browser. Protocol major versions other than 1 disable all known features in this SDK.
Unknown capability strings survive decoding. New mandatory semantics require a new major version;
optional fields may be added without changing the interpretation of existing fields.

Page sizes and subscription limits are explicit bounded ceilings. Oversized requests fail rather
than being silently truncated. Cursors bind to task, authenticated principal, filters, sort, and
revision. Mutations that invalidate a page return a stale-cursor error; no mixed-revision file list.
Peer addresses and other sensitive details are requested separately, not broadcast in every event.

## State and completion

`TorrentSnapshot` is a bounded aggregate. Metadata-unavailable counters are null, not invented zeroes.
Selected verified bytes, wanted bytes, payload received, uploaded payload, discarded payload, and
protocol overhead are separate. Virtual padding never counts toward user payload. A recheck can
reduce verified progress. Counters cannot exceed their content bounds or become negative.

A selection has a monotonically increasing generation. Completion belongs to that generation and
can coexist with seeding. Expanding a completed selection requires an explicit restart; existing
`awaitCompletion` callers retain their original generation. Empty selections can complete once
metadata is known. They do not imply possession of the torrent's payload. Download queue slots and
seed slots remain separate. Legacy upload remains disabled unless explicitly enabled; the named
production profile can enable uploads while downloading. Post-completion seeding remains opt-in.

## Revision and reconnect ordering

Every published task state has an opaque catalog epoch and a nonnegative sequence. Sequences never
wrap. A backend changes the epoch if it loses monotonic state, including restoration of an older
catalog. Epoch strings are compared for equality, never sorted. Full snapshots can jump forward;
deltas require their exact predecessor. Older or duplicate same-epoch messages are ignored. Gaps
in delta history and epoch changes require an authoritative resync.

`compareIncoming` implements this merge decision. It assumes messages belong to the active
connection generation. Adapters separately reject late responses from old connections. Reconnect
cancels the old subscription, fetches authoritative state, and installs the new epoch under a new
connection generation. An old HTTP response cannot overwrite a newer event in the same epoch.
An HTTP response from an obsolete connection cannot reset the new epoch.

Inspection streams emit full snapshots and conflate slow consumers to the latest snapshot. Null
means a tombstone and completes the task stream. A tombstone is terminal for that task ID, which is
never reused. The client rejects all subsequent responses for that removed task in the current
connection generation. Transport failures terminate observation; reconnect is explicit. Persisted
idempotency outcomes for removed tasks remain available during the supported retry window.

## Mutations and operations

Each existing-task mutation carries `TorrentCommandContext`: an idempotency key and the expected
revision. Scope the key to principal and task. Check the persisted retry ledger before revision
validation: an exact retry returns its original result, even if state has since advanced. Reusing
a key for a different canonical request fails. New requests with stale revisions return a conflict
and current revision, without partial mutation. Concurrent duplicates execute only once.

Long-running operations return an operation ID with queued/running/succeeded/failed/canceled state,
progress, and cancellation support. Acceptance never implies completion. A mutation's committed
outcome and idempotency record are persisted atomically. Ledger capacity is bounded; reject new
admissions rather than evicting entries still inside the advertised retry window. After expiration,
a retry fails explicitly instead of unexpectedly executing an old destructive command again.

The typed command families and required semantics are:

| Command family | Contract |
| --- | --- |
| Selection | Stable file IDs; priorities; sequential mode; verified-range deadlines; explicit restart |
| Transfer | Download/upload limits; pause/resume; runtime admission applies to both directions |
| Seeding | Ratio and duration goals; start/stop; separate seed queue; no implicit upload opt-in |
| Integrity | Recheck/import as cancelable operations; publish only verified state |
| Trackers | Edit ordered tiers and reannounce; retain private/proxy policy and credential context |
| Storage | Move/rename as recoverable operations; reject unsafe paths and ownership conflicts |
| Removal | Explicit keep-data or remove-owned-data policy; never delete unrelated files |
| Export | Export metainfo/magnets without publication, tracker requests, or starting seeding |
| Creation | Stable source snapshot, format and piece policy; cancelable; no implicit publication |
| Streaming | Authenticated task/file/range reads; only verified ranges; bounded lifetime and buffers |

Creation has no existing task revision; its request is scoped to the principal's creation ledger.
Operation cancellation has its own idempotency key and operation revision. Errors distinguish
unsupported capability, invalid input, conflict, policy denial, resource exhaustion, storage failure,
integrity failure, not found, and cancellation. They must not expose tracker credentials or raw
untrusted paths. Unsupported requests fail before any side effect or network access.

## Version 1 runtime

`Ketch` and `RemoteKetch` implement version 1 for torrent tasks. `Ketch.torrents` adapts the first
registered source that is a `TorrentControlSource` (`KetchTorrentController`);
`RemoteKetch.torrents` calls a server's `/api/torrents` routes and their `events` stream
(`RemoteTorrentController`). Servers list `torrent.control` in `KetchStatus.features` when they
serve them. What the runtime does, where it narrows the contract above:

- **Capabilities.** `inspect`, `file-selection`, `v1`, `v2` and `hybrid`, plus `seeding` only while
  the live upload policy is `SEED_AFTER_COMPLETION`. Every other name is never advertised.
  `maxPageSize` is 1000, `maxSubscriptions` 16, and `backgroundTransfers` is false: seeding runs
  only while the process runs. The remote adapter fails closed: it checks the server's capabilities,
  read once per connection, before every command and refuses with `UNSUPPORTED` before sending
  anything when a capability is missing, as with older servers, and a response, or a capabilities
  answer, that arrives after the connection changed fails with `CONNECTION_CHANGED`.
- **Preconditions.** `expectedRevision` is selection-scoped: it must be at least the revision of the
  task's last control change (a selection or seeding change) in the same epoch, so progress-only
  revisions never make a command conflict. A stale one fails with `CONFLICT` and the current
  revision.
- **Retry ledger.** Each task keeps its last 32 commands for 15 minutes in its record, saved in the
  same write as the change, so an exact retry returns its first outcome across restarts. A 33rd
  command inside the window fails with `RESOURCE_EXHAUSTED` rather than evicting an entry. The
  ledger goes with the task: a command for a removed task fails with `NOT_FOUND`, not its recorded
  outcome. Keys are scoped to the task; a server has one principal, its API token.
- **Selection.** `select` takes 1 to 100,000 stable file IDs and applies them at once, live in a
  running session; there are no priorities, sequential mode or deadlines, and no operation ID.
  Completion shows as `selectionComplete` of the selection's generation. Expanding a completed
  selection needs no explicit restart: the task reopens and downloads the new files. Empty
  selections cannot be represented yet; `DownloadRequest.awaitFileSelection` expresses waiting for a
  choice instead.
- **Seeding.** `setSeeding` starts (after a recheck, on a free engine slot only, else
  `RESOURCE_EXHAUSTED`) or stops sharing a completed task, and records the intent that restores
  seeding after a restart; `POLICY_DENIED` while uploads do not seed, `INVALID_STATE` for a task
  that is not completed. There are no ratio or duration goals and no separate seed queue: seeders
  use free engine slots and yield them to waiting downloads, keeping their intent.
- **Counters.** Received and uploaded payload and upload speed come from a live session; without
  one, as for a completed task that does not seed, they are `null`.
- **File pages.** `files` sorts by `TorrentFileOrder` (torrent, natural name, size, extension,
  selected first), optionally descending. Cursors bind the task, the epoch, the selection generation
  and the order; one used after any of them changed fails with `STALE_CURSOR`.

## Migration gates

Existing `KetchApi` implementations compile with the default null controller. New clients connected
to old daemons keep legacy downloads working and hide unsupported controls. Adding this API does not
change the existing resume format. Checkpoint v2 migration retains old state until verified commit,
rehashes legacy v1 data, and preserves unsupported native blobs for explicit recovery. No optimistic
conversion may claim unverified bytes. Format identity and checkpoint implementation remain in
roadmap steps 04–09. These gates are pending until exercised by their implementation tests.

[roadmap]: https://github.com/linroid/Ketch/issues/162
