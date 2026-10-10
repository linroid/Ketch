package com.linroid.ketch.torrent

import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import java.security.MessageDigest
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TorrentMerkleProofServingReferenceTest {
  @Test
  fun randomSelectorsMatchMessageDigestTree() = runTest {
    val random = Random(52)
    // 32 KiB pieces put the piece layer at level 1. File a's layer of 601 hashes crosses the
    // default cache group of 512; b is shorter, and c fits one piece.
    val fixture = TorrentV2Fixture.build(listOf("a" to 600 * 32_768 + 777,
      "b" to 70 * 32_768 + 16_384, "c" to 20_000), pieceLength = 32_768, seed = 9)
    val document = fixture.document
    val layout = fixture.layout
    val trees = fixture.payloads.map(::referenceTree)
    val buffers = TorrentBufferBudget(1_000_000)
    val state = TorrentBufferBudget(1_000_000)
    // The small cache holds one file's upper levels at a time, so files evict each other.
    val servers = listOf(TorrentV2HashServer(document, layout, state),
      TorrentV2HashServer(document, layout, state, maxCacheBytes = 5_200, groupBits = 3))
    suspend fun read(index: Int): TorrentV2PieceStore.ReadOutcome {
      val extent = layout.v2Piece(index.toLong())
      val file = layout.files.single { it.id == extent.fileId }
      val bytes = fixture.payloads[file.v2Index].copyOfRange(extent.fileOffset.toInt(),
        (extent.fileOffset + extent.length).toInt())
      return TorrentV2PieceStore.ReadOutcome.Read(TorrentV2PieceStore.ReadBuffer(bytes,
        checkNotNull(buffers.reserve(bytes.size))))
    }
    var served = 0
    var full = 0
    var blocks = 0
    while (served < 500) {
      val file = random.nextInt(trees.size)
      val tree = trees[file]
      val height = tree.lastIndex
      if (height == 0) continue
      val base = random.nextInt(height)
      val length = 1 shl random.nextInt(minOf(9, height - base) + 1)
      val index = random.nextInt((1 shl (height - base)) / length).toLong() * length
      val proof = random.nextInt(height - base)
      val selector = PeerHashSelector(checkNotNull(document.info.files[file].piecesRoot), base,
        index, length, proof)
      val bounds = peerHashServeBounds(document, layout, selector) ?: continue
      val hashes = checkNotNull(servers[served % 2].respond(selector, bounds, ::read)) {
        "Rejected $selector"
      }
      val expected = buildList {
        for (offset in 0 until length) add(tree[base][(index + offset).toInt()])
        var position = index shr length.countTrailingZeroBits()
        var level = base + length.countTrailingZeroBits()
        repeat(selector.hashCount - length) {
          add(tree[level++][(position xor 1L).toInt()])
          position = position shr 1
        }
      }.fold(ByteArray(0)) { all, hash -> all + hash }.toByteString()
      assertEquals(expected, hashes, "$selector")
      if (proof == height - base - 1) {
        full++
        assertTrue(verifyPeerHashes(PeerHashMessage.Hashes(selector, hashes), selector,
          document.info.files[file].length, selector.root), "$selector")
      }
      if (bounds.blockPath) blocks++
      served++
    }
    assertTrue(full > 50 && blocks > 50, "full=$full blocks=$blocks")
    servers.forEach { it.close() }
    assertEquals(0, state.allocated)
    assertEquals(0, buffers.allocated)
  }

  /** Every level of [payload]'s BEP 52 tree, hashed with the platform's SHA-256. */
  private fun referenceTree(payload: ByteArray): List<List<ByteArray>> {
    val digest = MessageDigest.getInstance("SHA-256")
    val count = (payload.size + 16_383) / 16_384
    var width = 1
    while (width < count) width *= 2
    val levels = mutableListOf(List(width) { index ->
      if (index >= count) ByteArray(32) else digest.digest(payload.copyOfRange(index * 16_384,
        minOf((index + 1) * 16_384, payload.size)))
    })
    while (levels.last().size > 1) {
      levels += levels.last().chunked(2).map { digest.digest(it[0] + it[1]) }
    }
    return levels
  }
}
