# Torrent v2 integrity primitives

The common Kotlin integrity layer follows SHA-256 in
[FIPS 180-4](https://nvlpubs.nist.gov/nistpubs/FIPS/NIST.FIPS.180-4.pdf) and the file-tree rules in
[BEP 52](https://www.bittorrent.org/beps/bep_0052.html). These internal primitives do not yet enable
v2 downloads: metainfo identity, wire proof acquisition, proof caches, and verified storage commits
must be integrated before advertising that capability.

`Sha256` consumes caller-provided slices with fixed working buffers. Finalization is one-shot;
invalid slices leave the accumulated message unchanged. The byte counter rejects input beyond the
algorithm's unsigned 64-bit bit-length representation. No native torrent or crypto dependency is
required by this implementation.

`TorrentMerkleRoot` consumes the declared bytes of one non-empty file. It hashes 16 KiB blocks,
retains only the incomplete block and a frontier of subtree roots, and folds that frontier at
completion. Short final blocks use their actual bytes. Missing leaves use 32 zero bytes, with
higher empty subtrees computed by hashing pairs of those zero hashes. Padding is never appended
to the user's payload. Empty files have no pieces root and are handled by the metainfo layer.

`verifyTorrentMerkleBlock` accepts an independently trusted file root, file length, block index,
block payload, and a bottom-up sibling path. It checks the exact payload and path lengths before
hashing, enforces zero-hash subtrees outside the file, and returns false for invalid input or a
root mismatch. It retains no caller-owned proof or payload. The maximum path depth is determined
by the declared file length, including lengths that cannot be materialized in memory.

Incoming wire frames must be bounded before building a proof list. A successful proof does not
publish download progress: the eventual storage integration must commit verified bytes under its
generation/cancellation barrier first. Batched hash messages, piece-layer storage and validation,
proof scheduling, hybrid cross-checks, and independent client interoperability remain work under
the roadmap.

Tests include published SHA-256 digests, every split of short known messages, a streamed million-
byte vector, and JDK differential checks at padding and torrent-block boundaries. Merkle tests
compare the frontier against an independent full tree, verify every block of uneven file shapes,
and reject altered payloads, siblings, indices, sizes, and malformed padding even when the supplied
root matches that malformed tree. These checks establish primitive behavior only after execution;
they do not replace the v2 integration and release gates.

## Raw info and imported piece layers

`TorrentV2Info.parse` decodes the v2 fields of an exact info dictionary with byte, node, and file
limits. It preserves original binary path components and raw info bytes, validates the v2 version,
piece geometry, file/directory separation, lengths, and per-file roots, and can authenticate all
supplied magnet topics before tree decoding. These paths are metadata, not filesystem paths;
normalization, collision handling, and destination mapping still belong to the storage boundary.
The parser does not validate a hybrid's v1 layout or an enclosing `.torrent` document.

`TorrentPieceLayerVerifier` consumes individual hashes from the piece-size layer and folds them
with a bounded Merkle frontier. Missing branches use the zero hash for that layer, rather than
an all-zero hash at every tree height. `validatePieceLayers` checks an imported layer dictionary
against the files that require layers, rejects missing/unreferenced roots and wrong byte counts,
and authenticates each distinct root/topology once. Repeated files with identical roots reuse
one layer without repeating its hash work. Unauthenticated layers are never returned on failure.

The dictionary adapter receives already loaded immutable byte strings under a total layer-byte
limit. It does not provide disk spilling or the streaming metainfo loader. Those resource paths
must be added before importing documents at the full production profile limits. The incremental
verifier can consume hashes from those future bounded streams without retaining the entire layer.

## Bounded document and hybrid validation

`TorrentV2Document` requires an enclosing info dictionary and a valid piece-layer dictionary;
BEP 9 info-only data cannot accidentally pass through the document import path. Envelope, raw-info,
node, file, and layer-byte limits apply together. This in-memory path is for bounded documents;
large streamed/spilled imports and runtime resource admission remain separate work.

When v1 fields are present, `TorrentHybridLayout` compares raw filenames, order, lengths, piece
alignment, and v1 piece-hash counts against the v2 file tree. It rejects partial or conflicting v1
layouts and symlinks. BEP 47 padding is retained as virtual zero spans, including optional trailing
padding; padding paths may be omitted and never become output destinations. Real files retain
their original v1 file indices so migrated selections can map through the hybrid layout.

These are metadata consistency checks. Hash strings alone cannot prove that a hybrid's payload
matches both formats. The download/storage integration must verify both integrity schemes before
publishing shared availability or committed progress. Filesystem path mapping and the complete
v1/v2 runtime input adapter are still required before advertising v2/hybrid download support.

## Payload authentication before commit

`TorrentPayloadVerifier` binds streamed piece bytes to an authenticated `TorrentV2Document` and
its content layout. Small files use their file root directly. Larger files use the imported,
authenticated piece-layer hash; short final pieces are expanded with zero-hash subtrees to that
layer's height. Hybrid pieces must also match their v1 SHA-1 hash, including virtual alignment
zeros generated in bounded chunks. Callers provide only actual payload bytes.

Construction enforces the current runtime's 16 MiB piece ceiling before allocating a layout or
hashing virtual bytes. Metainfo parsing retains larger protocol-valid dimensions, but those
cannot enter runtime verification. This also bounds hybrid padding work for tiny payloads.

Verification retains hashing state rather than a full piece buffer. It rejects excess input,
allows incomplete input to be completed, and finalizes once. A successful result authorizes no
progress publication by itself: storage must retain or stage the same bytes, commit them under
its cancellation/generation barrier, and only then publish availability. That storage adapter,
network proof acquisition, and v2 interoperability remain pending.

## Versioned output mapping

`TorrentOutputMapping` policy version 1 maps the complete authenticated file tree before file
selection. Logical IDs remain separate from paths; hybrids keep their original v1 file indices
and never map padding into output files. Ordinary valid UTF-8 components of at most 240 bytes
are preserved. Unsafe components, reserved device names, invalid UTF-8, and names containing the
policy's `%`/`~` markers are encoded byte-for-byte; larger components use a full SHA-256 name.

Sibling names are compared after canonical Unicode normalization and conservative case folding.
Colliding siblings receive deterministic full-hash suffixes, including directory/file collisions.
Residual collisions fail rather than alias two destinations. Shared directories are mapped once,
so changing selections cannot rename their parents. Raw names remain in authenticated metadata.
The device-name rules follow [Microsoft's naming guidance](https://learn.microsoft.com/en-us/windows/win32/fileio/naming-a-file).

This computes relative components only. Destination provider capabilities, full-path limits,
existing files/aliases, symlink and ownership checks, persistent mapping migration, and joining
under a trusted caller root remain responsibilities of the storage adapter. The mapper does not
rename existing v1 downloads or authorize overwriting an existing destination.

## Fresh-directory v2 storage adapter

`TorrentV2PieceStore` connects the document, output mapping, layout and dual payload verifier for
fresh filesystem downloads. Initialization exclusively creates a new caller-named directory;
an existing destination is rejected, not adopted. Empty selected files are created. Writes target
only selected logical files, with v2 file-relative offsets and no padding files. A buffer lease
precedes copying received bytes, and verification and I/O consume that same private copy.

A shared payload-handle permit spans blocking I/O, including cancellation. Files are flushed before
the operation returns through the coroutine cancellation boundary; only then does the store mark
the piece committed and increment selected progress under its mutex. Duplicate commits do not
count twice. Closing joins outstanding operations through that mutex. Live cleanup checks recorded
OS identities and removes only created files and empty owned directories.

This adapter is not yet installed in the download engine. Session admission must cover its
metadata, mapping and index state before construction; its payload-copy leases alone do not prove
the total memory profile. Persistent ownership, checkpoint v2, resume/import/recheck, committed
read/proof-serving APIs, generation-tagged session integration, and alternate destination providers
remain pending. Fresh-root ownership is intentionally not inferred from preexisting directories.

## Committed reads and bounded rechecks

The v2 store serves only committed pieces. Each read copies owned-file bytes under a buffer lease
and authenticates the snapshot before returning it. The consumer retains the lease until closing
the returned buffer. Changed, truncated or replaced files revoke the affected piece's availability
and selected progress; foreign replacements are not read or adopted. Cancellation releases the
read reservation after any active provider operation returns.

Recheck clears prior availability and scans selected owned files with at most 64 KiB of payload
scratch. It verifies the same v2/hybrid hashes as commit, flushes matching data left by any earlier
interrupted write, and publishes each piece only after returning through the cancellation boundary.
A canceled scan can be retried; it never trusts the old committed bitmap. Rechecks do not adopt
preexisting roots or override ownership changes. Wire proof serving and restart/import integration
remain separate work.

Recheck scans each non-empty selected file through one handle and flushes once if any pieces
match. Its bitmap updates remain tentative under the store mutex until that file's flush and
cancellation boundary succeed. Failure rolls back that file's tentative bits before unlocking;
previously completed files remain verified. This avoids a durable flush for every small piece.

## Bounded content catalog

`TorrentContentCatalog` stores authenticated raw info and external piece layers under the full
v2 hash in a caller-private catalog directory. Hybrid loads authenticate every requested topic.
Serialization retains exact raw info bytes and omits caller context such as tracker URLs and
comments. Existing entries are authenticated before reuse; corrupt objects are rejected rather
than overwritten. Reads check a 32 MiB size bound before loading and reject growth during a read.

Publication flushes a uniquely created temporary file before no-replace hard-link creation, retaining
the I/O
permit through completion. Failed publication removes only its own unchanged temporary file.
A reopened catalog revalidates identities and layers instead of trusting its filenames. Tests
exercise restart reads, hybrid topics/layers, corrupt/oversized entries and publication failure.

This is the bounded in-memory catalog path. Runtime memory admission, streamed/spilled objects
at full production layer limits, catalog reference accounting/garbage collection, directory-fsync
power-loss guarantees and checkpoint v2 references remain pending. It does not yet persist task
ownership, selections or progress and does not enable restart of a download by itself.

Catalog publication uses the platform's atomic hard-link creation and never replaces an existing
name. Both a new object and a competing winner are authenticated before returning its reference.
JVM/Android use `Files.createLink`; iOS uses POSIX `link`. Filesystems without hard-link support
fail explicitly; they need a separate provider adapter, not a replacing-move fallback. The
publication-race regression inserts valid and invalid competing targets at the exact boundary.

## Version 2 checkpoint format

`TorrentV2Checkpoint` references a full v2 catalog identity and retains the v1 topic for hybrids.
It records task/output binding, mapping policy version, explicit selections, layout/selection
generations, transfer counters and a piece-bitfield hint. Ownership records use prior-parent
references with one component per row, avoiding repeated long prefixes. File/directory kind is
encoded in the sign of the parent reference; the root is an explicit owned directory.

Decoding bounds the document to 32 MiB, 220,000 ownership rows and 100,000 selections. It rejects
future format/mapping versions, invalid UTF-8, duplicate paths/selections, missing or non-directory
parents, forward references, traversal and negative counters. After catalog resolution,
`validateContent` verifies the full identity, logical selections, mapped ownership paths and exact
bitfield size/padding. Version 1 state continues through its existing decoder; version 2 is not
mistaken for a legacy native blob.

This is the codec and binding layer, not task recovery. Before using ownership claims, recovery
must also match the expected task and caller-authorized root, verify current OS identities, and
rehash files instead of trusting hint bits. TaskStore publication, ownership journals, v1 migration,
resume wiring, streaming large state and full-profile admission/power-loss tests remain pending.

## Store snapshots and ownership recovery

The v2 store now has an explicit task identity. `checkpoint` publishes authenticated catalog
content first, then captures its owned files/directories, selections, generations, counters and
hint bits under the store mutex. Transfer counters cannot move backwards. The returned codec
object still needs atomic publication by the task-state layer.

`restore` accepts only an untouched store and checks the expected task, caller-authorized output,
selection and authenticated content mapping. It validates every recorded OS identity and kind
before adopting any records, so a failed restore leaves the store empty and retryable. Successful
restore retains generations/counters but discards all availability hints. Initialization handles
only validated owned paths or exclusive new creation, and recheck/commit prove bytes before
progress. Fully verified owned files are trimmed to their declared lengths; foreign replacements
are neither adopted nor deleted.

This supports checkpoint-backed live-store recovery, not the complete engine resume path. Atomic
TaskStore publication, durable creation journals for files created after the last snapshot,
missing-file repair/import, v1 migration, state admission and power-loss qualification remain open.

### Atomic checkpoint files

`TorrentCheckpointFile` publishes task snapshots in an existing caller-private state directory.
It validates the snapshot against its document and publishes authenticated catalog content first.
A unique temporary file is written and flushed before atomic replacement of the task's state file;
failed writes, flushes or replacement retain the previous complete checkpoint. Temporary cleanup
checks the identity of the file created by this attempt. Loads distinguish missing state from
malformed state, reject oversized files before allocation, check the task binding, and authenticate
the referenced catalog document before returning recovery inputs. Store recovery still checks the
expected output/selection and OS ownership; loaded bitfields remain hints.

Replacement rejects changed content/output bindings, regressed counters or generations, and a
selection change without a new selection generation. A runtime task owner must serialize writers
through one adapter instance; cross-process arbitration is not provided. Cancellation observed
before replacement discards the staged file. Cancellation concurrent with atomic replacement can
leave the complete new snapshot persisted even though the coroutine returns cancellation; recovery
must load the persisted state instead of inferring publication from the caller's return value.

This adapter does not yet wire v2 state into `TaskStore` or the engine. Durable creation journals,
state-memory admission, migration and directory-fsync power-loss recovery remain required work.

### Creation-log recovery before a checkpoint

A v2 store can receive a caller-private `creationLogPath` outside its payload root. Initialization
publishes a complete task/content/output/selection/mapping-bound header before creating payload
paths. Each created path's OS identity and kind are flushed in a checksummed, length-framed record;
compact parent references avoid repeatedly serializing full prefixes. Replay bounds file size,
record size, count and depth, discards a torn final append, and rejects complete checksum corruption.
The log uses the same no-replace header publication primitive as the content catalog.

Initialization can recover directly from this log without a checkpoint. All recovered claims must
belong to the authenticated output mapping and match current filesystem identities before any are
adopted. Checkpoint claims and log claims must agree when both exist. Missing uncreated files can
then be created, and payload recheck rebuilds availability. A failed live append is retried before
further creation. A process exit between creation and successful ownership recording can leave an
unclaimed path; restart preserves it and refuses to adopt or delete it. This conservative boundary
also applies to the existing v1 implementation.

The log requires one task writer and a private state directory; it does not arbitrate independent
processes. Payload cleanup leaves private log disposal to the task-state owner, after successful
cleanup. Runtime wiring, state admission, log lifecycle/compaction, process-kill coverage and
power-loss guarantees remain pending; these tests exercise new store instances and injected I/O
failures rather than claiming physical crash or mobile lifecycle coverage.

### Controlled process-exit evidence

`TorrentV2ProcessCrashTest` runs four independent JVM children that call `Runtime.halt` at actual
storage boundaries, bypassing coroutine cleanup and shutdown hooks. Recovery rejects a partially
written piece, rechecks a flushed piece without any checkpoint, preserves the old complete snapshot
when exit occurs before atomic replacement, and reads the new snapshot after replacement. Each
case then completes the payload, cleans up owned files and verifies an unrelated neighbor remains.
The parent enforces a child deadline and joins forced termination before deleting test fixtures.

These executed process-exit cases extend the injected-I/O tests. They do not prove power-loss
ordering, directory-entry durability, physical mobile lifecycle behavior, or the still-pending v2
runtime/network integration. The iOS simulator task requires `-PenableIosSimulatorTests=true`;
a successful Gradle build without that flag skips simulator execution and is not test evidence.

### BEP 52 hash-message wire codec

`PeerHashWire` encodes and decodes hash request (21), hashes (22), and hash reject (23) payloads
through the existing bounded `PeerWire` frames. It preserves full file-root hashes and unsigned
32-bit indices, requires aligned power-of-two ranges, and checks exact response lengths before
copying hash bytes. The response count omits the first `log2(length)-1` proof layers while retaining
the requested proof-layer count in the selector, as specified by
[BEP 52](https://raw.githubusercontent.com/bittorrent/bittorrent.org/master/beps/bep_0052.rst).

This adapter limits requests to 512 hashes, following BEP 52's recommended maximum, and bounds
layer fields to 63. Authenticated file-tree bounds, supported base-layer policy, outstanding-request
correlation, buffer admission, response proof authentication and hash serving remain required
connection-handler work. The codec alone does not authorize any hashes or payload progress.
It is separate from the v1 runtime: later v2 negotiation must explicitly route these frames to it.

### Authenticating peer hash responses

`verifyPeerHashes` checks exact selector correlation and authenticates a bounded response against a
file root supplied by trusted metadata. It validates the tree dimensions from file length, rejects
out-of-tree ranges and proofs that do not reach the root, reduces the aligned base-hash group,
and incorporates uncle hashes in the correct left/right order. Any supplied node covering only
padding must equal the canonical zero subtree at its layer, even if a noncanonical tree would
otherwise match the supplied root. Tree arithmetic supports the signed 64-bit file-length range
without allocating a tree proportional to file size.

This verifier accepts complete proofs to a trusted root. Intermediate cached anchors, outstanding
request ownership, hash-byte admission/caching and connection handling remain separate work. A
successful hash proof authenticates metadata hashes; actual payload still requires verification
and the storage commit barrier before availability is published.

### Hash exchange ownership and admission

`PeerHashExchange` belongs to one connection event loop. Its root-to-length lookup must be restricted
to authenticated metainfo. It validates complete-proof tree bounds and reserves shared hash-exchange
credit before returning a request ticket. Duplicate requests, pipeline saturation and exhausted
credit cannot grow the pending set. The allowance covers three hash-byte representations plus
bounded verification scratch; unsolicited-frame parser admission remains the reader's responsibility.

Replies must match an outstanding selector and arrive before its deadline. Full-root proof
verification precedes delivery; rejection, invalid proof, expiry, cancellation and connection close
release pending credit. Local ticket identity prevents a stale send/cancel callback from releasing a
new request at the same coordinates. A successful result carries its reservation until the consumer
closes it, including after connection shutdown. Consumers must drop retained hash bytes before
releasing that reservation. The connection timer must invoke expiry even while payloads are choked.

Wire replies have no transaction identifier beyond their selector. A delayed response matching a
new request for the same immutable root/coordinates can satisfy it only after proof authentication;
it cannot change the trusted content. Live connection routing, timers, parser admission and hash
cache ownership still need runtime integration. This does not mark step 08 complete.

### Bounded transport dispatch

`PeerHashTransport` connects request ownership to frame issuance, bounded body reads and actor
response dispatch on an already negotiated v2 connection. It reserves body/decode credit after
checking the length prefix and before reading the body; consumers keep frame credit until dispatch
finishes. Authenticated results retain their separate exchange reservation. Ordinary peer messages
remain available to other actor handlers, and incoming hash requests are surfaced for serving.

Write failure or cancellation closes the possibly partial stream and releases pending exchange
credit. Read failure, timeout or inability to admit a body closes the stream and releases its frame
credit; the actor must then close the transport and join the reader. A frame reservation is handed
off only after the timeout scope returns, preventing cancellation at that boundary from losing its
lease. Request/accept/expiry/close belong to one actor, with one separately serialized reader.

Tests exercise framed exchange over loopback TCP as well as injected failures and timeout/budget
boundaries. They do not perform v2 handshake negotiation or independent-client interoperability.
The full runtime actor, handshake routing, timer wiring, reply serving and payload integration are
still required before this adapter becomes a supported torrent capability.

Trusted piece-count frame bounds now permit the desktop-profile one-million-piece bitfield
(125,000 bytes plus its message ID). Unrelated messages retain the ordinary 64 KiB ceiling.


### Piece-count-aware frame bounds

`PeerFrameLimits` derives exact bitfield size from a known piece count up to one million. Generic
peer decoding defaults to the existing 64 KiB ceiling when no count is known. With a known count,
only bitfield frames can exceed that ceiling; extended, unknown and hash messages retain it.
Both peer readers inspect the ID and exact bitfield length before reading the remaining body.
Spare bits and piece indices are validated against the count, and availability state supports the
same one-million-piece ceiling. V1 metadata supplies its own count; v2 callers supply the authenticated
layout count without synthesizing SHA-1 hashes.

The hash transport reserves all frame/decode credit for the larger body before reading it and
retains that credit through dispatch. This removes the wire-size obstacle to the desktop target.
Aggregate session/peer admission, complete v2 runtime integration and measured production resource
gates still need verification; accepting a large bitfield alone does not prove those gates.

### Full-identity handshake routes

`PeerIdentityHandshake` retains the caller's full v1/v2 identity while using a separate 20-byte tag
only for the wire handshake. It supports direct v1/v2 initiation and response, plus explicitly
offered hybrid upgrades using BEP 52's reserved-byte flag. An upgrade can be accepted only when it
was offered; a v2 initiation cannot silently downgrade. Incoming tags matching both local aliases
are rejected as ambiguous. Protocol framing, self-connections and optional expected peer IDs are
validated. The handshake reserves bounded scratch before I/O, has a ten-second deadline and closes
the connection on admission, validation, cancellation or I/O failure.

A handshake tag remains a routing hint, not authentication of the remote's full content identity.
Metainfo and payload/hash proofs still have to satisfy the full expected topics. A multi-torrent
listener must also reject ambiguous registry matches before selecting a route. Extension/DHT flags
default off and must only be enabled by a runtime that implements and permits those capabilities.

A loopback test negotiates direct v2 from a validated document before sending a hash request through
the admitted transport and authenticating the reply. This connects the primitives over real TCP;
it does not establish independent-client interoperability, a complete payload actor, global listener
routing, magnet resolution or the remaining production release gates.

### Explicit payload-request responses

The wire codec recognizes reject messages (ID 16) with the same bounded index/begin/length fields
as requests. `PeerProtocolState` has an explicit-response mode for v2: choking retains outstanding
requests, and local cancellation marks their eventual payload for discard without freeing a pipeline
slot. Only the matching piece or rejection completes that request. Mismatched/unsolicited rejections
and unsolicited or duplicate v2 pieces fail; the legacy mode preserves its existing choke and late
duplicate behavior.

This follows [BEP 52's request/cancel/reject rules](
https://raw.githubusercontent.com/bittorrent/bittorrent.org/master/beps/bep_0052.rst).
The v2 actor must select this mode, transmit cancellation/rejection frames as required, and enforce
request deadlines/connection teardown. This state slice does not yet implement that actor or the
remaining optional Fast Extension messages.

### Admitted outbound peer frames

The v2 transport now sends ordinary peer messages and typed hash responses. It validates and
computes encoded size without copying the payload, reserves encoder/write scratch before encoding,
and holds that credit until the write unwinds. Hash responses reserve before converting immutable
proof bytes into a generic wire frame. Generic sends reject hash IDs so outgoing hash requests
cannot bypass the exchange's pending-request registration.

Admission or input validation failure emits no bytes and leaves the stream usable. A write failure,
timeout or cancellation may have emitted a partial frame, so it closes the stream and releases all
pending hash tickets. The actor serializes all writes and retains ownership of supplied payload
buffers until the call returns; this adapter does not admit those preexisting buffers. Tests cover
backpressure/cancellation, partial writes, typed replies and a two-way TCP hash exchange.

This supplies the outbound path for the forthcoming v2 payload actor. Proof generation, incoming
request ownership, choking/seeding policy, block deadlines and runtime integration remain required.

### V2 block response ownership and deadlines

`PeerBlockExchange` connects the explicit-response state to admitted transport writes and the
file-aligned content layout. Requests cannot cross a real file tail into alignment padding. It
reserves pending/retained-block credit before registering or sending a request, and bounds duplicate
requests and pipeline slots. Chokes and cancels retain that ownership until a matching response;
repeated or stale cancellation tickets cannot cancel a later request with the same coordinates.

Every request has an absolute response deadline, including time spent writing. Writes are bounded
by the earliest pending deadline, so a blocked new request or cancel cannot conceal an older expired
request. Control traffic and cancellation do not refresh deadlines. The actor schedules the next
expiry using `nextDeadlineMs` and calls `expire`; inbound dispatch also checks expiry. Expiry, invalid
responses and failed/partial writes close the transport and release all pending blocks. Closing a
connection instead of recycling its timed-out request slots prevents late responses from silently
being attributed to a retry on that same stream.

Delivered blocks are explicitly unverified and retain their own credit after the input frame closes
and even after connection shutdown. The consumer closes the block only after verifying or copying
it into an independently admitted piece assembly buffer. Session admission still must cover the
availability array before constructing this actor-owned helper. The runtime must join the reader
before releasing connection admission. Full event-loop/timer wiring, piece assembly and commit,
upload/hash serving and scheduler integration remain required; this helper is not a complete v2
session or production-capability claim.

Outbound frame admission also precedes block request registration. Temporary frame pressure returns
null for a new request without disturbing existing requests. Cancellation returns false when its
frame cannot be admitted and leaves the request uncanceled, so the actor may retry after credit
returns. Neither path emits bytes or resets deadlines; real partial writes still close the stream.

### Admitted piece assembly and verified commit

`TorrentV2PieceAssembly` reserves payload and bounded block-index capacity before allocation. It
uses canonical 16 KiB requests with a short final block matching the real file tail. Out-of-order
blocks populate distinct slots; wrong pieces, noncanonical ranges, size mismatches and duplicates
cannot mark a missing slot complete. Every accepted or rejected delivered block releases its own
credit after copying/validation, while the independent assembly reservation remains held.

Only a complete assembly can invoke the v2 store. The assembly seals its admitted buffer against
further mutation and retains credit until the store's validation/write/flush operation unwinds.
Hash rejection, storage failure or cancellation consumes and releases the assembly without bypassing
that barrier. Ordinary mutable caller buffers still use a privately admitted store copy. Incomplete assemblies remain available
for further blocks. A real TCP test joins full-identity v2 negotiation, bounded block requests,
reverse-order replies, assembly, verification and disk commit, checking all buffer credit returns.

This is component integration against a controlled peer, not independent-client v2 interoperability
or the finished runtime. The concurrent event loop and timers, multi-peer scheduling, hash serving,
seeding, session admission/wiring and all remaining production gates still require completion.


The sealed assembly path avoids reserving a second full payload: a 16 MiB piece plus bookkeeping
can now commit under the default 32 MiB transfer budget. The full-size regression failed before
this change with payload budget exhaustion and now verifies successful disk commit. Closing during
an in-flight commit defers credit release until that commit unwinds, including cancellation while
waiting for storage admission.

### Deadline-aware inbox and reader ownership

`PeerV2Inbox.run` owns one child frame reader and a channel with one queued frame. An additional
sender-held frame can wait behind it; every decoded frame retains its transport reservation.
The actor dispatches serially and closes delivered frames after use. `next` selects between the
next frame and the earliest block/hash response deadline. Block expiry closes the connection;
hash expiry returns exact expired tickets for rescheduling while leaving the healthy stream open.
Backward hash clocks now expire ownership consistently instead of producing repeated zero-delay
wakeups without releasing requests.

Scope exit closes the transport and block exchange, cancels and joins the reader, and reclaims
queued or undelivered frames. Already delivered frames remain explicitly consumer-owned. The
runtime releases connection admission only after this scope finishes. Deadline selection uses
channel select rather than wrapping resource return in a timeout scope. The real TCP assembly test
now uses the inbox for frame handoff before committing authenticated payloads after reader shutdown.

The actor still must offload long storage work and call the inbox while idle; this layer is not a
background timer that makes arbitrary blocking actor callbacks safe. Scheduler/event-loop command
wiring, upload/hash serving, full session integration and the remaining production gates remain
required.

### Bounded commit workers and actor commands

`TorrentV2CommitWorker` owns a bounded assembly queue, a bounded completion queue and one child
worker. Successful submission transfers an already admitted complete assembly; queue saturation
leaves ownership with the actor for retry. The worker defaults to `Dispatchers.Default`, moving
piece verification off the actor, while store I/O still uses its storage admission/provider path.
It reports verified/rejected commits only after the store returns, and storage failures as explicit
completion values. Scope exit cancels and joins the worker and closes every undelivered assembly.

The inbox can now select a typed actor-command queue alongside frames and response deadlines.
When both queues stay ready it alternates preference, preventing either stream from starving the
other. Command queue ownership stays with its producer; commit completions contain no retained
payload lease. A shared test blocks storage admission while the actor waits on both peer input and
worker completion: the pending block still expires at its deadline and teardown returns all credit.

These components supply worker dispatch and completion plumbing. The production scheduler still
must create/admit assemblies, route completions across peer/session lifetimes, retry queue pressure,
and reconcile checkpoint/progress generations. Full upload/hash serving, session integration and
all remaining capabilities and release gates remain required.

### Commit handoff identity

Commit submission now returns an opaque ticket, and every completion carries that exact ticket.
The ticket contains only the piece index, without retaining an assembly or payload. The scheduler
can associate it with its current session/selection generation and distinguish later submissions
for the same piece. Generation reconciliation still belongs to the runtime integration.

Assembly ownership moves atomically from the actor to a unique queue claim, then to the committing
worker and finally to closed. A queued assembly cannot be submitted twice, mutated, directly
committed, or released through its old actor handle. If queue admission fails, the exact claim
restores actor ownership. A stale claim cannot restore or close a newer handoff. Cancellation and
undelivered queue cleanup close only the current claim, and an in-flight commit retains its lease
until the store operation unwinds.

Tests exercise duplicate submission before the worker runs, actor close after transfer, restoration
on queue pressure, stale claims after a second handoff, and exact ticket identity across corrupt
and successful submissions of the same piece. This strengthens scheduler-facing ownership; full
scheduler/session routing and the remaining production gates are still required.

### Bounded multi-peer piece ledger

`TorrentV2PieceScheduler` reserves state before copying verified/selection inputs or creating
assignment maps. The active-piece ceiling includes assemblies already transferred to the commit
worker. Candidate pieces must be selected, unverified and admitted; canonical missing blocks are
assigned once across peer pipelines. Unavailable/full/choked peers are skipped and each scheduling
call bounds outbound admission attempts. Piece selection policy remains separate from this ledger.

Removing a peer closes its pipeline before freeing unanswered assignments for reassignment. Late
callbacks must match both peer identity and the exact request ticket; stale delivered blocks release
their credit without displacing a replacement. Completed assemblies stay admitted during commit
queue pressure. Only a known commit ticket updates scheduler verification; corrupt/failed commits
make the piece retryable, while unknown or repeated completions cannot publish state.

Tests connect two peer pipelines to one assembly and the real store/worker, exercise replacement
and late callbacks, selected-file and state/payload admission, queue pressure and corrupt-piece
retry. Scheduler shutdown releases actor-owned state and assemblies; outer session ownership still
joins peer readers and storage workers. Automatic piece-selection policy, transport/session wiring,
generation barriers, upload/hash serving and remaining production capabilities/gates are unfinished.

Scheduler request failures now remove the failed peer's earlier assignments before propagating the
error. The pipeline may already have closed itself after a partial write; the ledger must still
release its unanswered blocks so a replacement peer can request them. The regression injects a
failure on the second request and verifies the replacement starts with the first abandoned block.

### Compact peer availability

Peer protocol state now retains the packed bitfield instead of a BooleanArray per piece. At the
one-million-piece ceiling, its availability payload is 125,000 bytes per peer. V2 scheduling and
single-peer lookup read bits directly; the legacy swarm scheduler explicitly requests a detached
Boolean snapshot for its existing interface. Input bitfields are copied, and snapshots cannot mutate
protocol state. Have updates and spare-bit validation operate on the same packed representation.

Tests cover byte boundaries, input/snapshot isolation, invalid spare bits without partial state
publication, empty torrents and the final index of the million-piece profile. This removes one
large per-peer allocation obstacle. Aggregate peer admission, the legacy scheduler's retained
snapshots, process RSS and the full simultaneous production resource profiles still need validation.

### Admitted rarity and automatic candidates

`TorrentV2RarityPicker` admits its count index before allocation and charges each retained compact
peer snapshot separately. Bitfield replacement, duplicate have messages and peer removal maintain
rarity without aliasing caller buffers. Peer keys identify connection lifetimes. It chooses the
rarest eligible piece available from the requested peer, rotating equal-rarity choices and avoiding
candidate-list allocations. A rarity of one permits an early return; other choices may scan the
full bounded piece index. Runtime selection should be driven by state changes, with large-swarm
CPU/throughput measurements still required.

The piece scheduler now exposes automatic `beginNext` and uses admitted wanted/verified arrays plus
a packed busy index for allocation-free candidate eligibility. State allowance scales with actual
blocks per piece instead of always charging the 16 MiB maximum; up to 1024 active pieces can be
requested subject to state and payload admission. A test admits 128 small pieces within bounded
state/payload budgets. This is admission evidence, not a completed simultaneous-peer performance gate.

Tests cover exact rarity choices, rotating ties, replacements/removal, duplicate haves, malformed
and unadmitted snapshots, empty/million-piece indexes and automatic scheduler integration. Full
peer/session availability routing, generation barriers, adaptive streaming/endgame policy, upload/
hash serving, independent-client interoperability and all remaining production gates are unfinished.

### Interest signaling before unchoke

The scheduler derives interest from selected, unverified pieces advertised by the peer, independently
of choke state, pipeline capacity and assembly admission. Its request path establishes initial
interest even when candidate admission is waiting for unchoke. The actor calls `updateInterest` after
availability or verification changes to clear interest once no wanted advertised pieces remain.
Already interested request pipelines avoid rescanning the piece index for each block.

The block exchange writes interested/not-interested through frame admission, suppresses duplicate
updates and changes its local flag only after a successful write. Frame pressure leaves the flag
unchanged for retry. Writes honor existing response deadlines plus their own time bound; partial,
canceled or late writes close the pipeline. Scheduler write failures remove abandoned assignments.

Tests cover the advertised-piece/interested/unchoke/request order, selected-file completion, frame
pressure, duplicate suppression and blocked/non-suspending late writes. Full actor event routing,
upload/hash serving and remaining production capabilities and release gates remain unfinished.

### Request handoff between actors

The piece scheduler can reserve a canonical block with `planNext` without reading mutable peer
state or writing to a socket. Its availability callback must use the session actor's own snapshot.
Reservations prevent duplicate assignment while a peer actor waits for outbound admission.
The peer actor sends the request, then queues `resolve(plan, ticket)` before any response event
for that ticket. Null acknowledges an unsent request and releases its reservation.

Plans and issued tickets have separate identities: a stale acknowledgement cannot bind or release
a replacement reservation, and a ticket from another connection cannot bind matching coordinates.
A rejected acknowledgement requires the issuing actor to cancel or close any ticket it created.
After a peer actor closes and joins its pipeline and reader, the session owner calls `detachPeer`
to release abandoned reservations. The synchronous adapters remain for callers owning both objects.
Full per-peer/session actor wiring and production throughput validation remain outstanding.

### Per-peer download actor

`PeerV2DownloadActor` owns a negotiated transport, block exchange, and inbox reader. The session
uses bounded commands for interest, block requests/cancels, and hash requests/rejections. Events
carry exact request acknowledgements, block responses, validated availability/control frames,
and hash results/timeouts. Acknowledgements enter the event queue before responses for that ticket.
Only the peer actor reads or mutates its protocol state. Session availability is updated from events.

Queue metadata is admitted before allocation (including bounded hash-timeout ticket lists); retained
frames and delivered blocks keep separate payload leases. Every consumer closes every event.
Queued and canceled-send events are reclaimed automatically, while already delivered events remain
consumer-owned after actor shutdown. Command and event waits cannot outlive an earlier pending
block/hash deadline. A stalled consumer also has a finite dispatch timeout. Failure closes only this
peer's event stream with its cause after joining the reader; session shutdown joins the actor before
releasing queue admission. The caller retains transport ownership if queue admission fails.

This is an internal actor integration building block. Shared session event multiplexing, complete
hash serving, seeding, and end-to-end production v2 runtime registration remain outstanding.

### Dynamic peer pool and shared events

`PeerV2Pool` adds and stops negotiated peers while one session actor owns membership. Each peer
publishes `Ready`, then ordered actor events, then one `Closed` event into an admitted shared queue.
The `Closed` event is sent only after its actor and reader have joined; the session can then detach
scheduler assignments and retire the exact connection lifetime. Capacity is retained until that
terminal event is retired. Duplicate or stale terminal events cannot retire a replacement peer.

The pool admits bounded membership and one forwarding event per peer, in addition to shared queue
capacity. Payloads retain their existing leases through forwarding. Failed/canceled sends and queued
events close automatically; delivered events remain consumer-owned after pool shutdown. A failed
peer does not cancel its siblings. Stop requests are nonblocking, while full shutdown cancels and
joins all members before releasing state admission. A successful attach transfers connection cleanup
responsibility even when actor queue admission fails or cancellation precedes child startup; a null
attach leaves the connection with the caller. The caller admits availability before constructing peers.

The pool and the existing scheduler/commit worker now have compatible ownership boundaries. Automatic
session scheduling from availability and completion events, connection discovery, full hash serving,
and production runtime registration still need implementation and end-to-end validation.
