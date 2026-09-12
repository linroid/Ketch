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
