package com.linroid.ketch.torrent

/** Authenticates streamed payload before storage may commit it or advertise availability. */
internal class TorrentPayloadVerifier(private val document: TorrentV2Document) {
  init {
    // Match the current v1 runtime ceiling before layout allocation or virtual-padding hashing.
    // Metainfo parsing may describe larger pieces, but runtime verification cannot admit them.
    require(document.info.pieceLength <= 16L * 1024 * 1024) { "Unsupported runtime piece length" }
  }

  val layout = TorrentContentLayout.from(document.info, document.hybrid)
  private val filesById = layout.files.associateBy { it.id }

  /** The caller must retain/stage payload independently until verification and durable commit. */
  fun piece(index: Long): Piece {
    val extent = layout.v2Piece(index)
    val file = document.info.files[filesById.getValue(checkNotNull(extent.fileId)).v2Index]
    val root = checkNotNull(file.piecesRoot)
    val hasLayer = file.length > layout.pieceLength
    val expected = if (hasLayer) {
      val layer = document.pieceLayers.getValue(root)
      val offset = extent.fileOffset / layout.pieceLength * 32
      check(offset <= layer.size.toLong() - 32)
      layer.substring(offset.toInt(), offset.toInt() + 32).toByteArray()
    } else root.toByteArray()
    val v1 = document.hybrid?.pieceHashes?.let {
      val offset = index * 20
      check(offset <= it.size.toLong() - 20)
      it.substring(offset.toInt(), offset.toInt() + 20).toByteArray()
    }
    val protocolLength = minOf(layout.pieceLength,
      layout.protocolBytes - index * layout.pieceLength)
    return Piece(extent.length, if (hasLayer) layout.pieceLength else extent.length,
      protocolLength, expected, v1)
  }

  internal class Piece internal constructor(
    val payloadLength: Long,
    private val treeLength: Long,
    private val protocolLength: Long,
    private val expectedV2: ByteArray,
    private val expectedV1: ByteArray?,
  ) {
    private val merkle = TorrentMerkleRoot(payloadLength)
    private val sha1 = expectedV1?.let { Sha1() }
    private var received = 0L
    private var finished = false

    fun update(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset) {
      check(!finished)
      require(offset >= 0 && count >= 0 && offset <= bytes.size - count)
      require(count.toLong() <= payloadLength - received)
      merkle.update(bytes, offset, count)
      sha1?.update(bytes, offset, count)
      received += count
    }

    /** Incomplete input is a caller error; mismatched authenticated hashes return false. */
    fun verify(): Boolean {
      check(!finished && received == payloadLength)
      finished = true
      var hash = merkle.digest()
      var blocks = 1L
      val actualBlocks = (payloadLength - 1) / TorrentMerkleRoot.BLOCK_BYTES + 1
      var zero = ByteArray(32)
      while (blocks < actualBlocks) {
        zero = Sha256().update(zero).update(zero).digest()
        blocks *= 2
      }
      val targetBlocks = (treeLength - 1) / TorrentMerkleRoot.BLOCK_BYTES + 1
      while (blocks < targetBlocks) {
        hash = Sha256().update(hash).update(zero).digest()
        zero = Sha256().update(zero).update(zero).digest()
        blocks *= 2
      }
      if (!hash.contentEquals(expectedV2)) return false
      if (sha1 != null) {
        // Hybrid v1 hashes cover virtual zero bytes; never persist them as user payload.
        val zeros = ByteArray(4096)
        var remaining = protocolLength - payloadLength
        while (remaining > 0) {
          val count = minOf(remaining, zeros.size.toLong()).toInt()
          sha1.update(zeros, 0, count)
          remaining -= count
        }
        if (!sha1.digest().contentEquals(expectedV1)) return false
      }
      return true
    }
  }
}
