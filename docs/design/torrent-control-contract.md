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

## Migration gates

Existing `KetchApi` implementations compile with the default null controller. New clients connected
to old daemons keep legacy downloads working and hide unsupported controls. Adding this API does not
change the existing resume format. Checkpoint v2 migration retains old state until verified commit,
rehashes legacy v1 data, and preserves unsupported native blobs for explicit recovery. No optimistic
conversion may claim unverified bytes. Format identity and checkpoint implementation remain in
roadmap steps 04–09. These gates are pending until exercised by their implementation tests.

[roadmap]: https://github.com/linroid/Ketch/issues/162
