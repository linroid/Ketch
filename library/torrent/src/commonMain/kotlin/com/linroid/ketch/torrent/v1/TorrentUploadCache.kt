package com.linroid.ketch.torrent.v1

import com.linroid.ketch.torrent.crypto.sha1Digest
import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.storage.PieceRevokedException
import com.linroid.ketch.torrent.storage.TorrentPieceStore
import okio.IOException
import kotlin.time.TimeSource

/**
 * One verified upload piece per peer, evicted when idle. It is charged to [budget], the engine's
 * upload partition, so a piece larger than that partition is never read for a peer: [read]
 * returns null and the peer's upload slot goes to another. A piece the store revoked while reading
 * it throws [PieceRevokedException].
 */
internal class TorrentUploadCache(
  private val store: TorrentPieceStore,
  private val budget: TorrentBufferBudget,
) {
  private var index = -1
  private var bytes: ByteArray? = null
  private var lease: TorrentBufferBudget.Lease? = null
  private var usedAt = TimeSource.Monotonic.markNow()

  /** Whether [read] has [piece] at hand, without reading it again. */
  fun holds(piece: Int): Boolean = piece == index && bytes != null

  suspend fun read(piece: Int): ByteArray? {
    if (piece != index) {
      close()
      val reserved = budget.reserve(store.pieceSize(piece)) ?: return null
      try {
        val data = store.read(piece)
        if (!sha1Digest(data).contentEquals(store.metadata.pieceHashes.copyOfRange(
            piece * 20, piece * 20 + 20))) {
          // A file outside the selection may change; the piece is dropped instead of failing.
          store.revoke(piece)?.let { throw it }
          throw IOException("Verified torrent output changed")
        }
        bytes = data
        index = piece
        lease = reserved
      } catch (error: Throwable) {
        reserved.close()
        throw error
      }
    }
    usedAt = TimeSource.Monotonic.markNow()
    return bytes
  }

  fun expire() {
    if (usedAt.elapsedNow().inWholeSeconds >= 1) close()
  }

  fun close() {
    bytes = null
    index = -1
    lease?.close()
    lease = null
  }
}
