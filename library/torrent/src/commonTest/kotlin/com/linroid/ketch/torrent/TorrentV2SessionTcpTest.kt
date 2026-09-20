package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TorrentV2SessionTcpTest {
  private val bytes = ByteArray(16_387) { (it * 13).toByte() }
  private val last = byteArrayOf(1, 2, 3)
  private val pieceRoot = sha256Digest(sha256Digest(bytes.copyOfRange(0, 16_384)) +
    sha256Digest(bytes.copyOfRange(16_384, bytes.size)))

  private fun document(
    hybrid: Boolean,
    invalidV1: Boolean,
    name: String = "pack",
  ): TorrentV2Document {
    val info = mutableMapOf<String, Any>(
      "meta version" to 2L, "piece length" to 32_768L, "name" to name,
      "file tree" to mapOf(
        "a" to mapOf("" to mapOf("length" to bytes.size.toLong(), "pieces root" to pieceRoot)),
        "b" to mapOf("" to mapOf("length" to last.size.toLong(),
          "pieces root" to sha256Digest(last))))
    )
    if (hybrid) {
      info["files"] = listOf(
        mapOf("path" to listOf("a"), "length" to bytes.size.toLong()),
        mapOf("attr" to "p", "length" to (32_768L - bytes.size)),
        mapOf("path" to listOf("b"), "length" to last.size.toLong())
      )
      val hashes = sha1Digest(bytes + ByteArray(32_768 - bytes.size)) + sha1Digest(last)
      if (invalidV1) hashes[0] = (hashes[0].toInt() xor 1).toByte()
      info["pieces"] = hashes
    }
    return TorrentV2Document.parse(Bencode.encode(mapOf("info" to info,
      "piece layers" to emptyMap<String, Any>())))
  }

  @Test
  fun pureV2SessionNegotiatesTcpAndStoresOutOfOrderBlocks() = runTest {
    exercise(hybrid = false)
  }

  @Test
  fun hybridSessionUpgradesFromV1AndVerifiesBothHashesBeforeStorage() = runTest {
    exercise(hybrid = true)
  }

  @Test
  fun hybridSessionRejectsV2ValidPayloadThatFailsTheV1Hash() = runTest {
    exercise(hybrid = true, invalidV1 = true)
  }

  @Test
  fun rejectsAnArrivingConnectionForAnotherTorrent() = runTest {
    exercise(hybrid = false, wrongSession = true)
  }

  private suspend fun exercise(
    hybrid: Boolean,
    invalidV1: Boolean = false,
    wrongSession: Boolean = false,
  ) {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val doc = document(hybrid, invalidV1)
        val peerLayout = TorrentContentLayout.from(doc.info)
        val sessionDoc = if (wrongSession) document(hybrid, invalidV1, "other") else doc
        val layout = if (wrongSession) TorrentContentLayout.from(sessionDoc.info) else peerLayout
        val output = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-session-tcp-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        val buffers = TorrentBufferBudget(1_000_000)
        val state = TorrentBufferBudget(4_000_000)
        val store = TorrentV2PieceStore(sessionDoc, output, setOf("0"), "test",
          buffers, Semaphore(1))
        val network = createTorrentNetwork()
        val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
        val handshake = PeerIdentityHandshake(doc.identity)
        val clientId = ByteArray(20) { 1 }.toByteString()
        val serverId = ByteArray(20) { 2 }.toByteString()
        val server = async {
          val connection = listener.accept()
          try {
            val route = handshake.respond(connection, serverId, buffers, allowUpgrade = hybrid)
            assertEquals(PeerIdentityHandshake.Mode.V2, route.mode)
            assertEquals(doc.identity, route.identity)
            assertEquals(clientId, route.peerId)
            if (wrongSession) return@async
            val wire = PeerWire(connection, pieceCount = 2)
            wire.send(PeerMessage.Bitfield(byteArrayOf(192.toByte())))
            wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
            assertEquals(PeerMessage.Control(PeerMessage.Signal.INTERESTED), wire.read())
            val requests = List(2) { assertIs<PeerMessage.Request>(wire.read()) }
            assertEquals(setOf(0, 16_384), requests.map { it.begin }.toSet())
            for (request in requests.reversed()) {
              assertEquals(0, request.index)
              wire.send(PeerMessage.Piece(request.index, request.begin,
                bytes.copyOfRange(request.begin, request.begin + request.length)))
            }
            // Deliberately leave before the session's storage worker reports its result.
          } finally { connection.close() }
        }
        val endpoints = Channel<PeerEndpoint>(1)
        endpoints.send(listener.local)
        endpoints.close()
        try {
          store.initialize()
          suspend fun download() {
            PeerV2Pool.run(state, maxPeers = 1) { pool ->
              PeerV2Dialer.run(
                endpoints = endpoints,
                state = state,
                parallelism = 1,
                connect = { remote ->
                  val mode = if (hybrid) PeerIdentityHandshake.Mode.V1 else
                    PeerIdentityHandshake.Mode.V2
                  PeerV2Connector.connect(network, remote, doc, peerLayout, clientId, buffers,
                    state, mode = mode, allowUpgrade = hybrid, expectedPeerId = serverId)
                },
              ) { dialer ->
                TorrentV2CommitWorker.run(store) { worker ->
                  TorrentV2SessionLoop.download(layout, setOf("0"), store, pool, worker,
                    buffers, state, maxPeers = 1, connections = dialer.connections)
                }
              }
            }
          }
          if (wrongSession) {
            val failure = assertFailsWith<IllegalArgumentException> { download() }
            assertEquals("Wrong incoming torrent", failure.message)
            assertFalse(store.completed())
            assertEquals(0L, store.progress().getValue("0"))
          } else if (invalidV1) {
            val failure = assertFailsWith<IllegalStateException> { download() }
            assertEquals("All torrent peers disconnected before completion", failure.message)
            assertFalse(store.completed())
            assertEquals(0L, store.progress().getValue("0"))
            assertEquals(0L, torrentFileSystem.metadata(output / "a").size)
          } else {
            download()
            assertTrue(store.completed())
            assertContentEquals(bytes, torrentFileSystem.read(output / "a") { readByteArray() })
          }
          assertFalse(torrentFileSystem.exists(output / "b"))
          server.await()
        } finally {
          withContext(NonCancellable) {
            endpoints.cancel()
            listener.close()
            network.close()
            server.cancelAndJoin()
            store.cleanup()
          }
        }
        assertEquals(0, buffers.allocated)
        assertEquals(0, state.allocated)
      }
    }
  }
}
