package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TorrentV2PieceAssemblyTest {
  private val bytes = ByteArray(16_387) { it.toByte() }
  private val root = sha256Digest(sha256Digest(bytes.copyOfRange(0, 16_384)) +
    sha256Digest(bytes.copyOfRange(16_384, bytes.size)))
  private val document = TorrentV2Document.parse(Bencode.encode(mapOf("info" to mapOf(
    "meta version" to 2L, "piece length" to 32_768L, "file tree" to mapOf(
      "a" to mapOf("" to mapOf("length" to bytes.size.toLong(), "pieces root" to root)))
  ), "piece layers" to emptyMap<String, Any>())))
  private val layout = TorrentContentLayout.from(document.info)

  private fun block(
    request: PeerMessage.Request,
    value: ByteArray,
    budget: TorrentBufferBudget,
  ) = PeerBlockExchange.Response.Block(PeerBlockExchange.Ticket(request), value,
    assertNotNull(budget.reserve(value.size + 256)))

  @Test
  fun admitsBeforeAssemblyAndConsumesOutOfOrderBlocksWithoutReleasingAssemblyCredit() {
    assertNull(TorrentV2PieceAssembly.create(layout, 0, TorrentBufferBudget(bytes.size)))
    val assemblies = TorrentBufferBudget(20_000)
    val incoming = TorrentBufferBudget(20_000)
    val assembly = assertNotNull(TorrentV2PieceAssembly.create(layout, 0, assemblies))
    val retained = assemblies.allocated
    assertEquals(listOf(0, 1), assembly.missingBlocks())
    assertEquals(PeerMessage.Request(0, 16_384, 3), assembly.request(1))
    for (slot in listOf(1, 0)) {
      val request = assembly.request(slot)
      assembly.accept(block(request, bytes.copyOfRange(request.begin,
        request.begin + request.length), incoming))
      assertEquals(0, incoming.allocated)
      assertEquals(retained, assemblies.allocated)
    }
    assertTrue(assembly.complete)
    assertEquals(emptyList(), assembly.missingBlocks())
    assembly.close()
    assertFalse(assembly.complete)
    assertEquals(0, assemblies.allocated)
  }

  @Test
  fun transferredOwnershipCannotBeClosedByTheActorOrAStaleClaim() {
    val budget = TorrentBufferBudget(40_000)
    val assembly = assertNotNull(TorrentV2PieceAssembly.create(layout, 0, budget))
    for (slot in assembly.missingBlocks()) {
      val request = assembly.request(slot)
      assembly.accept(block(request, bytes.copyOfRange(request.begin,
        request.begin + request.length), budget))
    }
    val first = assembly.transfer()
    val retained = budget.allocated
    assembly.close()
    assertFalse(assembly.complete)
    assertEquals(retained, budget.allocated)
    assertFailsWith<IllegalStateException> { assembly.transfer() }
    assertFailsWith<IllegalStateException> { assembly.request(0) }
    first.restore()
    assertTrue(assembly.complete)
    val second = assembly.transfer()
    first.close()
    assertFailsWith<IllegalStateException> { first.restore() }
    assertEquals(retained, budget.allocated)
    second.close()
    assertEquals(0, budget.allocated)
    assertFalse(assembly.complete)
  }

  @Test
  fun invalidOrDuplicateBlocksCannotMarkMissingRangesCompleteAndAlwaysReleaseDeliveryCredit() {
    val assemblies = TorrentBufferBudget(20_000)
    val incoming = TorrentBufferBudget(20_000)
    val assembly = assertNotNull(TorrentV2PieceAssembly.create(layout, 0, assemblies))
    try {
      val tail = assembly.request(1)
      assembly.accept(block(tail, byteArrayOf(1, 2, 3), incoming))
      for ((request, value) in listOf(
        tail to byteArrayOf(1, 2, 3),
        PeerMessage.Request(1, 0, 3) to byteArrayOf(1, 2, 3),
        PeerMessage.Request(0, 1, 3) to byteArrayOf(1, 2, 3),
        PeerMessage.Request(0, 0, 16_384) to byteArrayOf(1),
        PeerMessage.Request(0, 32_768, 3) to byteArrayOf(1, 2, 3)
      )) {
        assertFailsWith<IllegalArgumentException> {
          assembly.accept(block(request, value, incoming))
        }
        assertEquals(0, incoming.allocated)
        assertEquals(listOf(0), assembly.missingBlocks())
        assertFalse(assembly.complete)
      }
    } finally { assembly.close() }
    assertFailsWith<IllegalStateException> {
      assembly.accept(block(PeerMessage.Request(0, 0, 3), byteArrayOf(1, 2, 3), incoming))
    }
    assertEquals(0, incoming.allocated)
  }

  @Test
  fun onlyACompleteAuthenticatedAssemblyPublishesStorageProgress() = runTest {
    val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-assembly-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val budget = TorrentBufferBudget(100_000)
    val store = TorrentV2PieceStore(document, path, emptySet(), "test", budget, Semaphore(1))
    try {
      store.initialize()
      for (corrupt in listOf(true, false)) {
        val assembly = assertNotNull(TorrentV2PieceAssembly.create(layout, 0, budget))
        try {
          assertFailsWith<IllegalStateException> { assembly.commit(store) }
          for (slot in assembly.missingBlocks().reversed()) {
            val request = assembly.request(slot)
            val value = bytes.copyOfRange(request.begin, request.begin + request.length)
            if (corrupt && slot == 0) value[0] = (value[0].toInt() xor 1).toByte()
            assembly.accept(block(request, value, budget))
          }
          assertEquals(!corrupt, assembly.commit(store))
          assertEquals(!corrupt, store.completed())
          assertEquals(if (corrupt) 0L else bytes.size.toLong(),
            torrentFileSystem.metadata(path / "a").size)
          assertEquals(0, budget.allocated)
        } finally { assembly.close() }
      }
      assertContentEquals(bytes, torrentFileSystem.read(path / "a") { readByteArray() })
    } finally { store.cleanup() }
  }

  @Test
  fun sealedAssemblyCommitsWithoutRequiringASecondPayloadReservation() = runTest {
    val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-assembly-pressure-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val budget = TorrentBufferBudget(20_000)
    val incoming = TorrentBufferBudget(20_000)
    val store = TorrentV2PieceStore(document, path, emptySet(), "test", budget, Semaphore(1))
    val assembly = assertNotNull(TorrentV2PieceAssembly.create(layout, 0, budget))
    try {
      store.initialize()
      for (slot in assembly.missingBlocks()) {
        val request = assembly.request(slot)
        assembly.accept(block(request, bytes.copyOfRange(request.begin,
          request.begin + request.length), incoming))
      }
      assertTrue(assembly.commit(store))
      assertFalse(assembly.complete)
      assertTrue(store.completed())
      assertEquals(bytes.size.toLong(), torrentFileSystem.metadata(path / "a").size)
      assertEquals(0, budget.allocated)
      assertEquals(0, incoming.allocated)
    } finally {
      assembly.close()
      store.cleanup()
    }
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun closingAnAssemblyDuringCommitRetainsAdmissionUntilTheCommitUnwinds() = runTest {
    val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-assembly-sealed-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val budget = TorrentBufferBudget(20_000)
    val incoming = TorrentBufferBudget(20_000)
    val slots = Semaphore(1)
    val store = TorrentV2PieceStore(document, path, emptySet(), "test", budget, slots)
    val assembly = assertNotNull(TorrentV2PieceAssembly.create(layout, 0, budget))
    try {
      store.initialize()
      for (slot in assembly.missingBlocks()) {
        val request = assembly.request(slot)
        assembly.accept(block(request, bytes.copyOfRange(request.begin,
          request.begin + request.length), incoming))
      }
      slots.acquire()
      try {
        val commit = async { assembly.commit(store) }
        runCurrent()
        assembly.close()
        assertTrue(budget.allocated > 0)
        assertFailsWith<IllegalStateException> {
          assembly.accept(block(PeerMessage.Request(0, 0, 3), byteArrayOf(1, 2, 3), incoming))
        }
        assertEquals(0, incoming.allocated)
        commit.cancelAndJoin()
        assertEquals(0, budget.allocated)
      } finally { slots.release() }
      assertFalse(store.completed())
    } finally {
      assembly.close()
      store.cleanup()
    }
  }

  @Test
  fun fullSixteenMiBPieceCommitsWithinTheDefaultThirtyTwoMiBTransferBudget() = runTest {
    val length = 16 * 1024 * 1024
    val data = ByteArray(PeerWire.BLOCK_SIZE) { it.toByte() }
    val merkle = TorrentMerkleRoot(length.toLong())
    repeat(length / data.size) { merkle.update(data) }
    val doc = TorrentV2Document.parse(Bencode.encode(mapOf("info" to mapOf(
      "meta version" to 2L, "piece length" to length.toLong(), "file tree" to mapOf(
        "a" to mapOf("" to mapOf("length" to length.toLong(), "pieces root" to merkle.digest())))
    ), "piece layers" to emptyMap<String, Any>())))
    val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-assembly-full-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val budget = TorrentBufferBudget(32 * 1024 * 1024)
    val store = TorrentV2PieceStore(doc, path, emptySet(), "test", budget, Semaphore(1))
    val assembly = assertNotNull(TorrentV2PieceAssembly.create(
      TorrentContentLayout.from(doc.info), 0, budget))
    try {
      store.initialize()
      for (slot in assembly.missingBlocks()) {
        assembly.accept(block(assembly.request(slot), data, budget))
      }
      assertTrue(assembly.commit(store))
      assertTrue(store.completed())
      assertEquals(length.toLong(), torrentFileSystem.metadata(path / "a").size)
      assertEquals(0, budget.allocated)
    } finally {
      assembly.close()
      store.cleanup()
    }
  }

  @Test
  fun negotiatesV2AndCommitsOutOfOrderPeerBlocksOverTcp() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-assembly-tcp-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        val budget = TorrentBufferBudget(200_000)
        val store = TorrentV2PieceStore(document, path, emptySet(), "test", budget, Semaphore(1))
        val assembly = assertNotNull(TorrentV2PieceAssembly.create(layout, 0, budget))
        val network = createTorrentNetwork()
        val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
        val handshake = PeerIdentityHandshake(document.identity)
        val server = async {
          val connection = listener.accept()
          try {
            handshake.respond(connection, ByteArray(20) { 1 }.toByteString(), budget)
            val wire = PeerWire(connection, pieceCount = 1)
            wire.send(PeerMessage.Bitfield(byteArrayOf(128.toByte())))
            wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
            val requests = List(2) { assertIs<PeerMessage.Request>(wire.read()) }
            for (request in requests.reversed()) {
              wire.send(PeerMessage.Piece(request.index, request.begin,
                bytes.copyOfRange(request.begin, request.begin + request.length)))
            }
          } finally { connection.close() }
        }
        var exchange: PeerBlockExchange? = null
        try {
          store.initialize()
          val connection = network.connect(listener.local)
          handshake.initiate(connection, PeerIdentityHandshake.Mode.V2,
            ByteArray(20) { 2 }.toByteString(), budget)
          val transport = PeerHashTransport(connection, PeerHashExchange(budget, { null }), budget,
            pieceCount = 1)
          val peer = PeerBlockExchange(layout, transport, budget)
          exchange = peer
          PeerV2Inbox.run(transport, peer) { inbox ->
            repeat(2) {
              val frame = assertIs<PeerV2Inbox.Event.Frame>(inbox.next()).value
              try { assertNull(peer.receive(frame)) } finally { frame.close() }
            }
            for (slot in assembly.missingBlocks()) {
              assertNotNull(peer.request(assembly.request(slot)))
            }
            repeat(2) {
              val frame = assertIs<PeerV2Inbox.Event.Frame>(inbox.next()).value
              try {
                assembly.accept(assertIs<PeerBlockExchange.Response.Block>(peer.receive(frame)))
              } finally { frame.close() }
            }
          }
          assertTrue(assembly.complete)
          assertFalse(store.completed())
          assertTrue(assembly.commit(store))
          assertTrue(store.completed())
          assertContentEquals(bytes, torrentFileSystem.read(path / "a") { readByteArray() })
          server.await()
        } finally {
          withContext(NonCancellable) {
            exchange?.close()
            listener.close()
            network.close()
            server.cancelAndJoin()
            assembly.close()
            store.cleanup()
          }
        }
        assertEquals(0, budget.allocated)
      }
    }
  }

}
