package com.linroid.ketch.torrent

import okio.IOException
import kotlin.time.TimeSource

/** One verified upload piece per peer, charged to the engine budget and evicted when idle. */
internal class TorrentUploadCache(
  private val store: TorrentPieceStore,
  private val budget: TorrentBufferBudget,
) {
  private var index = -1
  private var bytes: ByteArray? = null
  private var lease: TorrentBufferBudget.Lease? = null
  private var usedAt = TimeSource.Monotonic.markNow()

  suspend fun read(piece: Int): ByteArray? {
    if (piece != index) {
      close()
      val reserved = budget.reserve(store.pieceSize(piece)) ?: return null
      try {
        val data = store.read(piece)
        if (!sha1Digest(data).contentEquals(store.metadata.pieceHashes.copyOfRange(
            piece * 20, piece * 20 + 20))) {
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
