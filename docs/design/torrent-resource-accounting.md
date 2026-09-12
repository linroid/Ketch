# Torrent resource accounting

The v2 roadmap requires simultaneous limits for memory, peers, active tasks, payload handles,
metadata, files, pieces, and hash layers. The current foundation provides some of these limits;
it does not yet implement or certify the complete mobile and desktop production profiles.

## Payload handles

`TorrentConfig.maxOpenPayloadFiles` bounds aggregate open payload handles across an engine's
sessions. It defaults to 32 and accepts values from 1 through 128. The engine gives every piece
store the same coroutine semaphore. Waiting is cancellable and happens before dispatch to the
blocking I/O executor, so a waiting session does not occupy an I/O thread or open a payload file.

A piece-store operation opens at most one payload handle at a time, including the boundary-piece
sidecars used for partial selection. A semaphore permit spans the whole storage operation, rather
than an individual open/close pair. Initialization, verification, reads, commits, final truncation,
and cleanup consequently share admission. Long scans can hold a permit across multiple sequential
file opens; the implementation favors a strict bound over maximum concurrency.

Cancellation cannot release a permit while a blocking provider call is still executing. The
permit returns only after the I/O block returns or throws and its scoped handles close. Canceling
a waiter removes that wait without taking a permit. A provider failure returns the permit so
another torrent can proceed. This does not make a blocked filesystem provider cancellable or prove
the production pause/stop latency gate.

Ownership journals and checkpoints can open an additional non-payload handle while a payload
handle is open. They are outside this payload ceiling, as are sockets and platform/runtime file
descriptors. Process descriptor counts must be measured separately. Recovery-only stores used by
source cleanup read ownership records and remove files; they do not open payload handles.

## Remaining production work

The current buffer, metadata-cache, and session admission estimates are not a complete accounting
of engine working memory and cannot establish a process RSS ceiling. Raw metainfo parsing still
has limits below the proposed desktop profile. Separate seed admission, external hash-layer
storage, complete parser/index accounting, and the named production profiles remain roadmap work.
Physical-device, lifecycle, memory, descriptor, and soak evidence must cover the completed runtime
before either profile is described as production-ready.
