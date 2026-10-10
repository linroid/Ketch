package com.linroid.ketch.torrent

import kotlinx.coroutines.test.runTest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hash serving checked against a naive full tree, and round-tripped through the verifier. */
class PeerHashServingTest {
  // 64 KiB pieces, so the piece layer is level 2. File a has 37 pieces and one block: 149
  // blocks in a tree 256 wide and 8 high. File b fits one piece; file c is a single block.
  private val fixture = TorrentV2Fixture.build(
    listOf("a" to 37 * 65_536 + 16_384, "b" to 40_000, "c" to 100), pieceLength = 65_536)
  private val document = fixture.document
  private val layout = fixture.layout
  private val trees = fixture.payloads.map { TorrentV2Fixture.merkleNodes(it) }
  private val buffers = TorrentBufferBudget(1_000_000)

  private fun root(file: Int): ByteString = checkNotNull(document.info.files[file].piecesRoot)

  private fun selector(file: Int, base: Int, index: Long, length: Int, proof: Int) =
    PeerHashSelector(root(file), base, index, length, proof)

  /** What a correct answer holds: base hashes, then uncles from the bottom up. */
  private fun expected(file: Int, selector: PeerHashSelector): ByteString {
    val tree = trees[file]
    val hashes = mutableListOf<ByteArray>()
    for (offset in 0 until selector.length) {
      hashes += tree[selector.baseLayer][(selector.index + offset).toInt()]
    }
    val group = selector.length.countTrailingZeroBits()
    var position = selector.index shr group
    for (level in selector.baseLayer + group until
      selector.baseLayer + group + selector.hashCount - selector.length) {
      hashes += tree[level][(position xor 1L).toInt()]
      position = position shr 1
    }
    return hashes.fold(ByteArray(0)) { all, hash -> all + hash }.toByteString()
  }

  /** Reads committed pieces straight from the fixture, recording which were read. */
  private fun reader(reads: MutableList<Int> = mutableListOf()):
    suspend (Int) -> TorrentV2PieceStore.ReadOutcome = { index ->
    reads += index
    val extent = layout.v2Piece(index.toLong())
    val file = layout.files.single { it.id == extent.fileId }
    val bytes = fixture.payloads[file.v2Index].copyOfRange(extent.fileOffset.toInt(),
      (extent.fileOffset + extent.length).toInt())
    TorrentV2PieceStore.ReadOutcome.Read(TorrentV2PieceStore.ReadBuffer(bytes,
      checkNotNull(buffers.reserve(bytes.size))))
  }

  private suspend fun serve(
    server: TorrentV2HashServer,
    selector: PeerHashSelector,
    read: suspend (Int) -> TorrentV2PieceStore.ReadOutcome = reader(),
  ): ByteString? {
    val bounds = peerHashServeBounds(document, layout, selector) ?: return null
    return server.respond(selector, bounds, read)
  }

  private fun verifies(file: Int, selector: PeerHashSelector, hashes: ByteString): Boolean =
    verifyPeerHashes(PeerHashMessage.Hashes(selector, hashes), selector,
      document.info.files[file].length, root(file))

  /** Every served selector of [file] with a full proof, at each base layer and group size. */
  private fun fullSelectors(file: Int): List<PeerHashSelector> {
    val height = trees[file].lastIndex
    return buildList {
      for (base in 0 until height) {
        var length = 1
        while (length <= minOf(512, 1 shl (height - base))) {
          for (index in 0 until (1L shl (height - base)) step length.toLong()) {
            val selector = selector(file, base, index, length, height - base - 1)
            if (peerHashServeBounds(document, layout, selector) != null) add(selector)
          }
          length *= 2
        }
      }
    }
  }

  @Test
  fun fullProofsMatchOracleAndVerify() = runTest {
    for (groupBits in listOf(9, 2)) {
      val state = TorrentBufferBudget(1_000_000)
      val server = TorrentV2HashServer(document, layout, state, groupBits = groupBits)
      for (file in 0..1) {
        val selectors = fullSelectors(file)
        assertTrue(selectors.isNotEmpty())
        for (selector in selectors) {
          val hashes = assertNotNull(serve(server, selector), "$selector")
          assertEquals(expected(file, selector), hashes, "$selector")
          assertTrue(verifies(file, selector, hashes), "$selector")
        }
      }
      server.close()
      assertEquals(0, state.allocated)
    }
    assertEquals(0, buffers.allocated)
  }

  @Test
  fun partialProofsArePrefixesOfFullProofs() = runTest {
    val server = TorrentV2HashServer(document, layout, TorrentBufferBudget(1_000_000),
      groupBits = 2)
    // Base layer, index and length; file a's tree is 8 high, so full proofs have 7 - base.
    for ((base, index, length) in listOf(Triple(0, 4L, 1), Triple(0, 16L, 4), Triple(2, 4L, 1),
      Triple(2, 32L, 8), Triple(3, 16L, 4), Triple(5, 4L, 2))) {
      val full = assertNotNull(serve(server, selector(0, base, index, length, 7 - base)))
      for (proof in 0 until 7 - base) {
        val partial = selector(0, base, index, length, proof)
        val hashes = assertNotNull(serve(server, partial), "$partial")
        assertEquals(full.substring(0, partial.hashCount * 32), hashes, "$partial")
        assertEquals(expected(0, partial), hashes, "$partial")
      }
    }
    server.close()
  }

  @Test
  fun upperLevelBaseVerifies() = runTest {
    val state = TorrentBufferBudget(1_000_000)
    // Levels from 4 come from the cache, folded once from the piece layer.
    val server = TorrentV2HashServer(document, layout, state, groupBits = 2)
    val reads = mutableListOf<Int>()
    for (selector in listOf(selector(0, 5, 0, 8, 2), selector(0, 6, 2, 2, 1),
      selector(0, 3, 16, 16, 4), selector(0, 7, 0, 2, 0))) {
      val hashes = assertNotNull(serve(server, selector, reader(reads)), "$selector")
      assertEquals(expected(0, selector), hashes, "$selector")
      assertTrue(verifies(0, selector, hashes), "$selector")
    }
    // Upper levels need no payload.
    assertTrue(reads.isEmpty())
    assertTrue(server.cacheBytes > 0)
    server.close()
    assertEquals(0, state.allocated)
  }

  @Test
  fun paddingPositionsAreZeroHashes() = runTest {
    val server = TorrentV2HashServer(document, layout, TorrentBufferBudget(1_000_000))
    // The piece layer has 38 hashes; the tree's level 2 is 64 wide.
    val selector = selector(0, 2, 32, 32, 5)
    val hashes = assertNotNull(serve(server, selector))
    for (offset in 6 until 32) {
      assertContentEquals(merkleZeroHash(2), hashes.substring(offset * 32, offset * 32 + 32)
        .toByteArray(), "position ${32 + offset}")
    }
    assertEquals(expected(0, selector), hashes)
    assertTrue(verifies(0, selector, hashes))
    // A zero subtree is the hash of two zero subtrees one level down.
    assertContentEquals(Sha256().update(merkleZeroHash(4)).update(merkleZeroHash(4)).digest(),
      merkleZeroHash(5))
    server.close()
  }

  @Test
  fun blockLayerProofWithinOnePieceVerifies() = runTest {
    val state = TorrentBufferBudget(1_000_000)
    val server = TorrentV2HashServer(document, layout, state)
    val reads = mutableListOf<Int>()
    for (selector in listOf(selector(0, 0, 8, 4, 7), selector(0, 1, 4, 2, 6),
      selector(0, 0, 148, 1, 7), selector(0, 0, 148, 4, 7))) {
      val hashes = assertNotNull(serve(server, selector, reader(reads)), "$selector")
      assertEquals(expected(0, selector), hashes, "$selector")
      assertTrue(verifies(0, selector, hashes), "$selector")
    }
    // Pieces 2 and 37 were read once each: recent pieces' hashes are kept.
    assertEquals(listOf(2, 37), reads)
    // A piece that is not committed cannot be proven below the piece layer.
    assertNull(serve(server, selector(0, 0, 0, 4, 7)) {
      TorrentV2PieceStore.ReadOutcome.NotCommitted
    })
    server.close()
    assertEquals(0, state.allocated)
    assertEquals(0, buffers.allocated)
  }

  @Test
  fun recentPiecesAreProvedWithoutReadingThemAgain() = runTest {
    val state = TorrentBufferBudget(1_000_000)
    val charged = mutableListOf<Long>()
    var admit = true
    val server = TorrentV2HashServer(document, layout, state, maxSubtrees = 2,
      canRead = { admit }, chargeRead = { bytes -> charged += bytes })
    val reads = mutableListOf<Int>()
    // Block hashes of pieces 2 and 3, asked for in turn: each piece is read and paid for once.
    val pieceTwo = selector(0, 0, 8, 4, 7)
    val pieceThree = selector(0, 0, 12, 4, 7)
    repeat(5) {
      for (selector in listOf(pieceTwo, pieceThree)) {
        assertEquals(expected(0, selector), serve(server, selector, reader(reads)), "$selector")
      }
    }
    assertEquals(listOf(2, 3), reads)
    assertEquals(listOf(65_536L, 65_536L), charged)
    // A read the upload limits refuse is never made; kept pieces need no admission.
    admit = false
    val pieceFour = selector(0, 0, 16, 4, 7)
    assertNull(serve(server, pieceFour, reader(reads)))
    assertEquals(expected(0, pieceTwo), serve(server, pieceTwo, reader(reads)))
    assertEquals(listOf(2, 3), reads)
    // Two are kept: proving piece 4 lets the least recently proved piece 3 go.
    admit = true
    assertEquals(expected(0, pieceFour), serve(server, pieceFour, reader(reads)))
    assertEquals(expected(0, pieceTwo), serve(server, pieceTwo, reader(reads)))
    assertEquals(expected(0, pieceThree), serve(server, pieceThree, reader(reads)))
    assertEquals(listOf(2, 3, 4, 3), reads)
    server.close()
    assertEquals(0, state.allocated)
    assertEquals(0, buffers.allocated)
  }

  @Test
  fun proofReadsThatFailChargeNothing() = runTest {
    // Both upload buckets hold exactly one block, and the clock stands still.
    val global = TorrentRateLimiter(16_384) { 0L }
    val task = TorrentRateLimiter(16_384) { 0L }
    val state = TorrentBufferBudget(1_000_000)
    val server = TorrentV2HashServer(document, layout, state,
      canRead = { global.canCharge(task) }, chargeRead = { bytes -> global.charge(bytes, task) })
    val pieceTwo = selector(0, 0, 8, 4, 7)
    // No budget to read the piece into, or the piece is gone: nothing was read, nothing is owed.
    assertNull(serve(server, pieceTwo) { TorrentV2PieceStore.ReadOutcome.NoBudget })
    assertNull(serve(server, pieceTwo) { TorrentV2PieceStore.ReadOutcome.NotCommitted })
    assertTrue(global.canCharge(task))
    // A piece that was read pays for itself: both buckets owe the rest of it.
    assertEquals(expected(0, pieceTwo), serve(server, pieceTwo))
    assertFalse(global.canCharge(task))
    server.close()
    // No room for the piece's block hashes (512 bytes): the proof reads nothing and costs
    // nothing either.
    val fresh = TorrentRateLimiter(16_384) { 0L }
    val freshTask = TorrentRateLimiter(16_384) { 0L }
    val reads = mutableListOf<Int>()
    val cramped = TorrentV2HashServer(document, layout, TorrentBufferBudget(400),
      canRead = { fresh.canCharge(freshTask) },
      chargeRead = { bytes -> fresh.charge(bytes, freshTask) })
    assertNull(serve(cramped, pieceTwo, reader(reads)))
    assertTrue(reads.isEmpty())
    assertTrue(fresh.canCharge(freshTask))
    cramped.close()
    assertEquals(0, state.allocated)
    assertEquals(0, buffers.allocated)
  }

  @Test
  fun singlePieceFileBlockProofVerifies() = runTest {
    val server = TorrentV2HashServer(document, layout, TorrentBufferBudget(1_000_000))
    // File b: three blocks, a tree four wide and two high, and no piece layer.
    for (selector in listOf(selector(1, 0, 0, 4, 1), selector(1, 0, 2, 1, 1),
      selector(1, 1, 0, 2, 0))) {
      val hashes = assertNotNull(serve(server, selector), "$selector")
      assertEquals(expected(1, selector), hashes, "$selector")
      assertTrue(verifies(1, selector, hashes), "$selector")
    }
    // Its padding leaf alone proves nothing.
    assertNull(serve(server, selector(1, 0, 3, 1, 1)))
    server.close()
  }

  @Test
  fun rejectsUnknownRootBaseAtHeightIndexPastWidthAndExcessProofLayers() {
    val unknown = PeerHashSelector(ByteArray(32) { 7 }.toByteString(), 2, 0, 1, 5)
    assertNull(peerHashServeBounds(document, layout, unknown))
    assertNull(peerHashServeBounds(document, layout, selector(0, 8, 0, 1, 0)))
    assertNull(peerHashServeBounds(document, layout, selector(0, 2, 64, 1, 5)))
    assertNull(peerHashServeBounds(document, layout, selector(0, 2, 0, 128, 0)))
    assertNull(peerHashServeBounds(document, layout, selector(0, 2, 0, 1, 6)))
    assertNotNull(peerHashServeBounds(document, layout, selector(0, 2, 0, 1, 5)))
    // A single-block file is its own root: there is nothing below it to ask for.
    assertNull(peerHashServeBounds(document, layout, selector(2, 0, 0, 1, 0)))
  }

  @Test
  fun rejectsCrossPieceBlockRange() {
    // Leaves 0 to 7 belong to pieces 0 and 1.
    assertNull(peerHashServeBounds(document, layout, selector(0, 0, 0, 8, 4)))
    assertNull(peerHashServeBounds(document, layout, selector(0, 1, 0, 4, 4)))
    // Leaves 144 to 151 hold blocks of pieces 36 and 37.
    assertNull(peerHashServeBounds(document, layout, selector(0, 0, 144, 8, 4)))
    // Leaves 152 onwards are only padding.
    assertNull(peerHashServeBounds(document, layout, selector(0, 0, 152, 8, 4)))
    // Leaves 148 to 151 hold the last piece's one block and its padding.
    val last = assertNotNull(peerHashServeBounds(document, layout, selector(0, 0, 148, 4, 7)))
    assertTrue(last.blockPath)
    assertEquals(0L, last.firstPiece)
    val b = assertNotNull(peerHashServeBounds(document, layout, selector(1, 0, 0, 4, 1)))
    assertEquals(38L, b.firstPiece)
  }

  @Test
  fun wholeLargePieceBlockProofIsServed() = runTest {
    // libtorrent asks for every block hash of a failed 16 MiB piece in one request: 1024 of them.
    val pieceLength = 16 * 1024 * 1024
    val large = TorrentV2Fixture.build(listOf("a" to pieceLength + 16_384),
      pieceLength = pieceLength)
    val root = checkNotNull(large.document.info.files[0].piecesRoot)
    val tree = TorrentV2Fixture.merkleNodes(large.payloads[0])
    val reads = TorrentBufferBudget(pieceLength + 1024)
    val state = TorrentBufferBudget(1 shl 20)
    val server = TorrentV2HashServer(large.document, large.layout, state)
    for (index in listOf(0L, 1024L)) {
      val selector = PeerHashSelector(root, 0, index, 1024, 10)
      val bounds = assertNotNull(peerHashServeBounds(large.document, large.layout, selector))
      assertTrue(bounds.blockPath)
      val hashes = assertNotNull(server.respond(selector, bounds) { piece ->
        val extent = large.layout.v2Piece(piece.toLong())
        val bytes = large.payloads[0].copyOfRange(extent.fileOffset.toInt(),
          (extent.fileOffset + extent.length).toInt())
        TorrentV2PieceStore.ReadOutcome.Read(TorrentV2PieceStore.ReadBuffer(bytes,
          checkNotNull(reads.reserve(bytes.size))))
      })
      // One frame carries it, and its peer can check it against the root.
      assertTrue(48 + hashes.size <= PeerWire.MAX_FRAME_SIZE - 1)
      assertTrue(verifyPeerHashes(PeerHashMessage.Hashes(selector, hashes), selector,
        pieceLength + 16_384L, root))
      assertEquals(tree[0][index.toInt()].toByteString(), hashes.substring(0, 32))
    }
    server.close()
    assertEquals(0, state.allocated)
    assertEquals(0, reads.allocated)
  }

  @Test
  fun rejectsMoreThanBep52HashesAboveThePieceLayer() {
    // 16 KiB pieces: the piece layer is the leaves, so no hash comes from a piece read back.
    val small = TorrentV2Fixture.build(listOf("a" to 513 * 16_384), pieceLength = 16_384)
    val root = checkNotNull(small.document.info.files[0].piecesRoot)
    // Each would be folded from the layer per answer, so more than 512 at once is refused.
    assertNull(peerHashServeBounds(small.document, small.layout,
      PeerHashSelector(root, 0, 0, 1024, 0)))
    assertNotNull(peerHashServeBounds(small.document, small.layout,
      PeerHashSelector(root, 0, 0, 512, 0)))
  }

  @Test
  fun rejectsWhenUpperCacheCannotBeAdmitted() = runTest {
    // The cache for levels 4 to 8 of file a needs about 1 KiB.
    val small = TorrentBufferBudget(512)
    val starved = TorrentV2HashServer(document, layout, small, groupBits = 2)
    assertNull(serve(starved, selector(0, 5, 0, 8, 2)))
    // Proofs that stay below the cached levels need no cache.
    val below = selector(0, 2, 0, 4, 0)
    assertEquals(expected(0, below), serve(starved, below))
    starved.close()
    assertEquals(0, small.allocated)
    val capped = TorrentV2HashServer(document, layout, TorrentBufferBudget(1_000_000),
      maxCacheBytes = 512, groupBits = 2)
    assertNull(serve(capped, selector(0, 5, 0, 8, 2)))
    assertEquals(0, capped.cacheBytes)
    capped.close()
  }

  @Test
  fun cacheReleasesItsBudgetOnClose() = runTest {
    val state = TorrentBufferBudget(1_000_000)
    val server = TorrentV2HashServer(document, layout, state, groupBits = 2)
    assertNotNull(serve(server, selector(0, 5, 0, 8, 2)))
    assertNotNull(serve(server, selector(0, 0, 8, 4, 7)))
    assertTrue(server.cacheBytes > 0)
    assertTrue(state.allocated > server.cacheBytes)
    server.close()
    assertEquals(0, server.cacheBytes)
    assertEquals(0, state.allocated)
    assertEquals(0, buffers.allocated)
  }
}
