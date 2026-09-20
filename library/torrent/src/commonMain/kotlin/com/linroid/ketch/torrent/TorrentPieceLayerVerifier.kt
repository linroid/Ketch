package com.linroid.ketch.torrent

/**
 * Authenticates a BEP 52 piece layer one hash at a time with bounded retained state.
 * Only files larger than one piece have an external layer. No payload bytes are consumed here.
 */
internal class TorrentPieceLayerVerifier(
  fileLength: Long,
  pieceLength: Long,
  expectedRoot: ByteArray,
) {
  val expectedHashes: Long
  private val root: ByteArray
  private val frontier: TorrentMerkleFrontier
  private var received = 0L
  private var finished = false

  init {
    require(pieceLength >= 16_384 && pieceLength and (pieceLength - 1) == 0L)
    require(fileLength > pieceLength) { "File does not need an external piece layer" }
    require(expectedRoot.size == 32)
    expectedHashes = (fileLength - 1) / pieceLength + 1
    root = expectedRoot.copyOf()
    var blocks = pieceLength / TorrentMerkleRoot.BLOCK_BYTES
    var layer = 0
    while (blocks > 1) { layer++; blocks /= 2 }
    frontier = TorrentMerkleFrontier(layer)
  }

  fun append(hash: ByteArray) {
    check(!finished)
    require(hash.size == 32)
    require(received < expectedHashes) { "Too many piece-layer hashes" }
    frontier.append(hash)
    received++
  }

  fun verify(): Boolean {
    check(!finished)
    check(received == expectedHashes) { "Incomplete piece layer" }
    finished = true
    return frontier.digest().contentEquals(root)
  }
}
