package com.linroid.ketch.torrent

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.IOException
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalAtomicApi::class)
class KotlinRuntimePrivateTest {
  @Test
  fun trackerFailover_closesOldPeersBeforeConnectingNewAndNeverStartsDhtOrPex() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        coroutineScope {
          val bytes = byteArrayOf(1, 2, 3, 4)
          val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf(
            "announce-list" to listOf(listOf("http://a/announce"), listOf("http://b/announce")),
            "info" to mapOf("name" to "private", "private" to 1L, "length" to 4L,
              "piece length" to 4L, "pieces" to sha1Digest(bytes))
          )))
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-private-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          val clock = AtomicLong(0)
          val oldClosed = AtomicBoolean(false)
          val udpSockets = AtomicInt(0)
          val transport = createTorrentNetwork()
          val first = transport.listen(PeerEndpoint("127.0.0.1", 0))
          val second = transport.listen(PeerEndpoint("127.0.0.1", 0))
          val network = object : TorrentNetwork by transport {
            override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket {
              udpSockets.fetchAndAdd(1)
              return transport.bindUdp(local)
            }
            override suspend fun connect(remote: PeerEndpoint): TorrentConnection {
              if (remote == second.local) assertTrue(oldClosed.load())
              val connection = transport.connect(remote)
              return object : TorrentConnection by connection {
                override fun close() {
                  if (remote == first.local) oldClosed.store(true)
                  connection.close()
                }
              }
            }
          }
          val http = object : HttpEngine {
            override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
              error("unused")
            override suspend fun download(url: String, range: LongRange?,
              headers: Map<String, String>, onData: suspend (ByteArray) -> Unit) {
              if (url.startsWith("http://a/") && clock.load() > 0) throw IOException("Unavailable")
              val endpoint = if (url.startsWith("http://a/")) first.local else second.local
              onData(Bencode.encode(mapOf("interval" to 30L, "peers" to listOf(
                mapOf("ip" to endpoint.host, "port" to endpoint.port.toLong())
              ))))
            }
            override fun close() = Unit
          }
          val servers = listOf(first, second).mapIndexed { index, listener -> launch {
            val connection = listener.accept()
            try {
              val wire = PeerWire(connection, metadata)
              wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), true, false))
              val extension = wire.read() as PeerMessage.Extended
              assertEquals(0, extension.id)
              assertNull(Bencode.parse(extension.payload)["m"]?.get("ut_pex"))
              if (index == 0) clock.store(60_000) else {
                wire.send(PeerMessage.Bitfield(byteArrayOf(128.toByte())))
                wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
              }
              while (true) {
                if (wire.read() is PeerMessage.Request) wire.send(PeerMessage.Piece(0, 0, bytes))
              }
            } catch (_: IOException) {
              // Failover/completion closes the corresponding peer.
            } finally { connection.close() }
          } }
          val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = true), network,
            TorrentHttp(http), nowMs = { clock.load() })
          try {
            engine.start()
            val session = engine.addTask(TorrentTaskSpec("private-task", metadata,
              (root / "output").toString(), emptySet()))
            session.resume()
            assertEquals(TorrentSessionState.FINISHED, session.state.first {
              it == TorrentSessionState.FINISHED || it == TorrentSessionState.STOPPED
            }, session.failure.value?.stackTraceToString())
            assertTrue(oldClosed.load())
            assertEquals(0, udpSockets.load())
          } finally {
            engine.stop()
            servers.forEach { it.cancelAndJoin() }
            network.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
        }
      }
    }
  }
}
