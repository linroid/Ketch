package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class KotlinTorrentSessionTest {
  @Test
  fun pauseOneOfTwoSessions_thenResumeAndRecoverCompletedCheckpointOffline() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        coroutineScope {
          val network = createTorrentNetwork()
          val budget = TorrentBufferBudget(4 * 1024 * 1024)
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-sessions-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          val payloads = listOf(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8))
          val metadata = payloads.mapIndexed { index, bytes ->
            TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
              "name" to "file$index", "length" to 4L, "piece length" to 4L,
              "pieces" to sha1Digest(bytes)
            ))))
          }
          val gates = List(2) { CompletableDeferred<Unit>() }
          val requested = List(2) { CompletableDeferred<Unit>() }
          val listeners = List(2) { network.listen(PeerEndpoint("127.0.0.1", 0)) }
          val servers = listeners.mapIndexed { index, listener -> launch {
            while (isActive) {
              val connection = listener.accept()
              launch {
                try {
                  val wire = PeerWire(connection, metadata[index])
                  wire.handshake(PeerHandshake(metadata[index].infoHash, torrentRandomBytes(20),
                    false, false))
                  wire.send(PeerMessage.Bitfield(byteArrayOf(128.toByte())))
                  wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
                  while (true) {
                    if (wire.read() is PeerMessage.Request) {
                      requested[index].complete(Unit)
                      gates[index].await()
                      wire.send(PeerMessage.Piece(0, 0, payloads[index]))
                    }
                  }
                } catch (_: okio.IOException) {
                  // Pausing or completing closes this accepted connection.
                } finally { connection.close() }
              }
            }
          } }
          fun store(index: Int) = TorrentPieceStore(metadata[index], root / "file$index",
            emptySet(), "task$index")
          val sessions = List(2) { index ->
            KotlinTorrentSession(store(index), network, budget, this,
              discover = { peers, _ -> peers.send(listeners[index].local) })
          }
          try {
            sessions[0].pause() // Pausing before initialization must not create output or fail.
            sessions.forEach { it.resume() }
            while (requested.any { !it.isCompleted }) {
              sessions.forEach { it.failure.value?.let { failure -> throw failure } }
              delay(10)
            }
            sessions[0].pause()
            assertEquals(TorrentSessionState.PAUSED, sessions[0].state.value)
            gates[1].complete(Unit)
            awaitComplete(sessions[1])
            assertEquals(4L, sessions[1].downloadedBytes.value)
            sessions[0].resume()
            gates[0].complete(Unit)
            awaitComplete(sessions[0])
            val checkpoint = assertNotNull(TorrentCheckpoint.decode(
              assertNotNull(sessions[0].saveResumeData())))
            sessions.forEach { it.close() }
            val restored = KotlinTorrentSession(store(0), network, budget, this,
              checkpoint = checkpoint, discover = { _, _ -> error("Unexpected rediscovery") })
            try {
              restored.resume()
              awaitComplete(restored)
              assertContentEquals(payloads[0],
                torrentFileSystem.read(root / "file0") { readByteArray() })
              assertEquals(4L, restored.receivedBytes)
            } finally { restored.close() }
            assertEquals(0, budget.allocated)
          } finally {
            sessions.forEach { it.close() }
            servers.forEach { it.cancelAndJoin() }
            network.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
        }
      }
    }
  }
  private suspend fun awaitComplete(session: KotlinTorrentSession) {
    val state = session.state.first {
      it == TorrentSessionState.FINISHED || it == TorrentSessionState.STOPPED
    }
    assertEquals(TorrentSessionState.FINISHED, state, session.failure.value?.stackTraceToString())
  }

}
