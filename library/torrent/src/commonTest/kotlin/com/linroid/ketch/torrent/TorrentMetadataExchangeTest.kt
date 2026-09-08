package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TorrentMetadataExchangeTest {
  @Test
  fun exchange_usesPerPeerIdAndAssemblesReorderedBlocks() = runTest { exchange("valid") }

  @Test
  fun exchange_hashMismatchFailsBeforeHandoff() = runTest { exchange("corrupt") }

  @Test
  fun exchange_oversizedMetadataFailsBeforeAllocation() = runTest { exchange("oversized") }

  @Test
  fun exchange_privateMetainfoRequiresExplicitInput() = runTest { exchange("private") }

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

  private suspend fun exchange(mode: String) = withContext(Dispatchers.Default) {
    withTimeout(15_000) {
      coroutineScope {
        val info = mutableMapOf<String, Any>("name" to "fixture", "length" to 0L,
          "piece length" to 16_384L, "pieces" to ByteArray(0), "padding" to "x".repeat(40_000))
        if (mode == "private") info["private"] = 1L
        val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to info)))
        val network = createTorrentNetwork()
        val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
        val budget = TorrentBufferBudget(32 * 1024 * 1024)
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
        } finally {
          server.cancelAndJoin()
          network.close()
        }
      }
    }
  }
}
