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
