package com.linroid.ketch.torrent.magnet

import com.linroid.ketch.torrent.TorrentConfig
import com.linroid.ketch.torrent.TorrentV2Fixture
import com.linroid.ketch.torrent.crypto.sha1Digest
import com.linroid.ketch.torrent.crypto.sha256Digest
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.TorrentExchangeBudgets
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import com.linroid.ketch.torrent.metainfo.V2InfoHash
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentNetwork
import com.linroid.ketch.torrent.net.createTorrentNetwork
import com.linroid.ketch.torrent.peer.PeerExtensions
import com.linroid.ketch.torrent.peer.PeerHandshake
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerWire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TorrentMetadataExchangeTest {
  @Test
  fun exchange_assemblesReorderedBlocksWhileTransferBudgetIsFull() = runTest { exchange("valid") }

  @Test
  fun exchange_hashMismatchFailsBeforeHandoff() = runTest { exchange("corrupt") }

  @Test
  fun exchange_oversizedMetadataFailsBeforeAllocation() = runTest { exchange("oversized") }

  @Test
  fun exchange_privateMetainfoRequiresExplicitInput() = runTest { exchange("private") }

  @Test
  fun exchange_infoMatchingItsHashThatDoesNotParse_isUnusable() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        coroutineScope {
          // Pieces of 32 MiB, which Ketch does not take: every peer would send the same.
          val info = Bencode.encode(mapOf("name" to "fixture", "length" to 0L,
            "piece length" to 32L * 1024 * 1024, "pieces" to ByteArray(0)))
          val hash = InfoHash.fromBytes(sha1Digest(info))
          val network = createTorrentNetwork()
          val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
          val budgets = TorrentExchangeBudgets(TorrentConfig())
          val server = async {
            val connection = listener.accept()
            try {
              val wire = PeerWire(connection)
              wire.handshake(PeerHandshake(hash, torrentRandomBytes(20), true, false))
              assertEquals(0, (wire.read() as PeerMessage.Extended).id)
              wire.send(PeerMessage.Extended(0, Bencode.encode(mapOf(
                "m" to mapOf("ut_metadata" to 7L), "metadata_size" to info.size.toLong()))))
              val request = wire.read() as PeerMessage.Extended
              val piece = Bencode.parse(request.payload)["piece"]!!.integer!!.toInt()
              wire.send(TorrentMetadataExchange.response(1, piece, info.toByteString()))
            } finally { connection.close() }
          }
          try {
            val error = assertFailsWith<UnusableTorrentMetadataException> {
              TorrentMetadataExchange(network, budget = budgets.metadata)
                .fetch(hash, listener.local)
            }
            assertEquals("The torrent's pieces are 32 MiB, larger than the 16 MiB Ketch supports",
              error.cause?.message)
            server.await()
          } finally {
            server.cancelAndJoin()
            network.close()
          }
          assertEquals(0, budgets.allocated)
        }
      }
    }
  }

  @Test
  fun handshake_reservesNoMetadataBufferAndCancelPreservesTransferReservation() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(5_000) {
        val budgets = TorrentExchangeBudgets(TorrentConfig())
        val payload = assertNotNull(budgets.transfer.reserve(budgets.transfer.capacity))
        val network = createTorrentNetwork()
        val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
        val ready = CompletableDeferred<Unit>()
        val server = async {
          val connection = listener.accept()
          try {
            connection.readExactly(68)
            ready.complete(Unit)
            awaitCancellation()
          } finally { connection.close() }
        }
        val client = async {
          TorrentMetadataExchange(network, budget = budgets.metadata)
            .fetch(InfoHash.fromBytes(ByteArray(20)), listener.local)
        }
        try {
          ready.await()
          // Peers that never answer the handshake must not hold the shared metadata buffer.
          assertEquals(0, budgets.metadata.allocated)
          client.cancelAndJoin()
          assertEquals(0, budgets.metadata.allocated)
          assertEquals(budgets.transfer.capacity, budgets.allocated)
        } finally {
          withContext(NonCancellable) {
            client.cancel()
            server.cancel()
            network.close()
            client.cancelAndJoin()
            server.cancelAndJoin()
            payload.close()
          }
        }
        assertEquals(0, budgets.allocated)
      }
    }
  }

  @Test
  fun fetchAcceptsHybridInfoForMagnetResolution() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        coroutineScope {
          val hybrid = TorrentV2Fixture.build(listOf("a" to 40_000, "b" to 5, "c" to 20_000),
            hybrid = true)
          val view = TorrentMetadata.fromBencode(hybrid.metainfo, allowHybrid = true)
          // Torrent files and sessions still refuse a hybrid's v1 view.
          assertFailsWith<IllegalArgumentException> { TorrentMetadata.fromBencode(hybrid.metainfo) }
          val network = createTorrentNetwork()
          val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
          val budgets = TorrentExchangeBudgets(TorrentConfig())
          val server = async {
            val connection = listener.accept()
            try {
              val wire = PeerWire(connection)
              wire.handshake(PeerHandshake(view.infoHash, torrentRandomBytes(20), true, false))
              assertEquals(0, (wire.read() as PeerMessage.Extended).id)
              wire.send(PeerMessage.Extended(0, Bencode.encode(mapOf(
                "m" to mapOf("ut_metadata" to 7L),
                "metadata_size" to view.infoBytes.size.toLong()))))
              val request = wire.read() as PeerMessage.Extended
              val piece = Bencode.parse(request.payload)["piece"]!!.integer!!.toInt()
              wire.send(TorrentMetadataExchange.response(1, piece, view))
            } finally { connection.close() }
          }
          try {
            // A btih magnet names only the v1 hash; the info it fetches is the hybrid's.
            val metadata = TorrentMetadataExchange(network, budget = budgets.metadata)
              .fetch(view.infoHash, listener.local)
            assertTrue(metadata.isHybrid)
            assertContentEquals(hybrid.document.info.rawInfo.toByteArray(), metadata.infoBytes)
            assertEquals(hybrid.document.info.hash,
              V2InfoHash.fromBytes(sha256Digest(metadata.infoBytes)))
            // Its v1 view counts the padding the v1 swarm hashes, which no session writes.
            assertEquals(listOf(40_000L, 25_536L, 5L, 32_763L, 20_000L),
              metadata.files.map { it.size })
            server.await()
          } finally {
            server.cancelAndJoin()
            network.close()
          }
          assertEquals(0, budgets.allocated)
        }
      }
    }
  }

  @Test
  fun extensions_updatesAreAdditiveAndCanDisableAnId() {
    val extensions = PeerExtensions()
    extensions.receive(Bencode.encode(mapOf("m" to mapOf("ut_metadata" to 7L, "ut_pex" to 4L))),
      1024)
    extensions.receive(Bencode.encode(mapOf("m" to mapOf("ut_metadata" to 0L))), 1024)
    assertEquals(0, extensions.id("ut_metadata"))
    assertEquals(4, extensions.id("ut_pex"))
    assertFailsWith<IllegalArgumentException> {
      extensions.receive(Bencode.encode(mapOf("m" to mapOf("ut_metadata" to 4L))), 1024)
    }
  }

  @Test
  fun exchange_slowPeerThatKeepsSending_finishesPastItsTimeout() = runTest {
    // Three blocks, 600 ms apart, against a peer timeout of 1 s.
    val info = paced(blockDelayMs = 600, blocks = 3) { network, hash, endpoint ->
      TorrentMetadataExchange(network, timeoutMs = 1_000).fetch(hash, endpoint)
    }
    assertEquals(3, (info.infoBytes.size + 16_383) / 16_384)
  }

  @Test
  fun exchange_peerThatStopsSending_isLeftAfterItsTimeout() = runTest {
    assertFailsWith<TimeoutCancellationException> {
      paced(blockDelayMs = 0, blocks = 1) { network, hash, endpoint ->
        TorrentMetadataExchange(network, timeoutMs = 1_000).fetch(hash, endpoint)
      }
    }
  }

  /**
   * Fetches 40 KB of metadata from a peer that sends [blocks] of its blocks, each [blockDelayMs]
   * after the last, and then nothing.
   */
  private suspend fun paced(
    blockDelayMs: Long,
    blocks: Int,
    fetch: suspend (TorrentNetwork, InfoHash, PeerEndpoint) -> TorrentMetadata,
  ): TorrentMetadata = withContext(Dispatchers.Default) {
    withTimeout(15_000) {
      coroutineScope {
        val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
          "name" to "fixture", "length" to 0L, "piece length" to 16_384L,
          "pieces" to ByteArray(0), "padding" to "x".repeat(40_000)))))
        val network = createTorrentNetwork()
        val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
        val server = async {
          val connection = listener.accept()
          try {
            val wire = PeerWire(connection)
            wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), true, false))
            wire.read()
            wire.send(PeerMessage.Extended(0, Bencode.encode(mapOf(
              "m" to mapOf("ut_metadata" to 7L),
              "metadata_size" to metadata.infoBytes.size.toLong()))))
            val requests = (0..2).map {
              Bencode.parse((wire.read() as PeerMessage.Extended).payload)["piece"]!!
                .integer!!.toInt()
            }
            for (piece in requests.take(blocks)) {
              delay(blockDelayMs)
              wire.send(TorrentMetadataExchange.response(1, piece, metadata))
            }
            awaitCancellation()
          } finally {
            connection.close()
          }
        }
        try {
          fetch(network, metadata.infoHash, listener.local)
        } finally {
          server.cancelAndJoin()
          network.close()
        }
      }
    }
  }

  private suspend fun exchange(mode: String) = withContext(Dispatchers.Default) {
    withTimeout(15_000) {
      coroutineScope {
        val info = mutableMapOf<String, Any>("name" to "fixture", "length" to 0L,
          "piece length" to 16_384L, "pieces" to ByteArray(0), "padding" to "x".repeat(40_000))
        if (mode == "private") info["private"] = 1L
        val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to info)))
        val network = createTorrentNetwork()
        val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
        val budgets = TorrentExchangeBudgets(TorrentConfig())
        val payload = assertNotNull(budgets.transfer.reserve(budgets.transfer.capacity))
        val budget = budgets.metadata
        val server = async {
          val connection = listener.accept()
          try {
            val wire = PeerWire(connection)
            wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), true, false))
            val local = wire.read() as PeerMessage.Extended
            assertEquals(1L, Bencode.parse(local.payload)["m"]?.get("ut_metadata")?.integer)
            wire.send(PeerMessage.Extended(0, Bencode.encode(mapOf(
              "m" to mapOf("ut_metadata" to 7L),
              "metadata_size" to if (mode == "oversized") 5_000_000L
                else metadata.infoBytes.size.toLong()
            ))))
            if (mode != "oversized") {
              val requests = (0..2).map {
                val request = wire.read() as PeerMessage.Extended
                assertEquals(7, request.id)
                Bencode.parse(request.payload)["piece"]!!.integer!!.toInt()
              }
              for (piece in requests.reversed()) {
                val response = TorrentMetadataExchange.response(1, piece, metadata)
                if (mode == "corrupt") {
                  response.payload[response.payload.lastIndex] = '!'.code.toByte()
                }
                wire.send(response)
              }
            }
          } finally {
            connection.close()
          }
        }
        try {
          val exchange = TorrentMetadataExchange(network, budget = budget)
          if (mode == "valid") {
            val result = exchange.fetch(metadata.infoHash, listener.local,
              listOf(listOf("http://tracker/announce")))
            assertContentEquals(metadata.infoBytes, result.infoBytes)
            assertEquals(listOf("http://tracker/announce"), result.trackers)
          } else {
            assertFailsWith<IllegalArgumentException> {
              exchange.fetch(metadata.infoHash, listener.local)
            }
          }
          server.await()
          assertEquals(0, budget.allocated)
          assertEquals(budgets.transfer.capacity, budgets.allocated)
        } finally {
          server.cancelAndJoin()
          network.close()
          payload.close()
          assertEquals(0, budgets.allocated)
        }
      }
    }
  }
}
