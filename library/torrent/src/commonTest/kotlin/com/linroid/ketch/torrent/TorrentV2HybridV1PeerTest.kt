package com.linroid.ketch.torrent

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A hybrid owner downloads from a peer that only knows the v1 swarm, in either direction. */
class TorrentV2HybridV1PeerTest {
  // Pieces of 32 KiB with BEP 47 padding after a (40000 bytes) and b (5 bytes).
  private val fixture = TorrentV2Fixture.build(listOf("a" to 40_000, "b" to 5, "c" to 20_000),
    hybrid = true)
  private val legacy = TorrentMetadata.fromBencode(fixture.metainfo, allowHybrid = true)

  @Test
  fun hybridDownloadsFromScriptedV1OnlySeeder() = runTest {
    // The seeder dials the owner with the v1 tag and no upgrade bit, then the owner dials it.
    for (incoming in listOf(true, false)) download(incoming)
  }

  private suspend fun download(incoming: Boolean) = withContext(Dispatchers.Default) {
    withTimeout(30_000) {
      coroutineScope {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-hybrid-v1-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        torrentFileSystem.createDirectories(root)
        val output = root / "payload"
        val network = createTorrentNetwork()
        val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
        val requests = mutableListOf<PeerMessage.Request>()
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false),
          listenHost = "127.0.0.1")
        val seeder = async(start = CoroutineStart.LAZY) {
          val connection = if (incoming) {
            network.connect(PeerEndpoint("127.0.0.1", engine.listenPort)).also {
              it.write(handshake())
              // The owner answers in the v1 swarm: there is no upgrade to take.
              PeerWire.decodeHandshake(it.readExactly(68), legacy.infoHash)
            }
          } else {
            listener.accept().also {
              val header = it.readExactly(68)
              PeerWire.decodeHandshake(header, legacy.infoHash)
              // The owner offers the upgrade, which a v1-only peer ignores.
              assertTrue(header[27].toInt() and 0x10 != 0)
              it.write(handshake())
            }
          }
          try { seed(connection, requests) } catch (_: IOException) {
            // The owner closes its peers once the download completes.
          } finally { connection.close() }
        }
        try {
          engine.start()
          val session = engine.addV2Task(TorrentV2TaskSpec("hybrid-v1", fixture.document,
            output.toString(), privacy = TorrentDiscoveryPrivacy.PUBLIC,
            discover = { peers ->
              if (!incoming) peers.send(listener.local)
              awaitCancellation()
            },
            discoverMode = PeerIdentityHandshake.Mode.V1))
          session.resume()
          session.state.first { it == TorrentSessionState.DOWNLOADING }
          seeder.start()
          assertEquals(TorrentSessionState.FINISHED, session.state.first {
            it == TorrentSessionState.FINISHED || it == TorrentSessionState.STOPPED
          }, session.failure.value?.stackTraceToString())
          seeder.await()
          for ((index, name) in fixture.names.withIndex()) {
            assertContentEquals(fixture.payloads[index],
              torrentFileSystem.read(output / name) { readByteArray() })
          }
          // Padding is never written; only the three files exist.
          assertEquals(fixture.names.toSet(),
            torrentFileSystem.list(output).map { it.name }.toSet())
          // Every block was canonical for the v1 piece it belongs to, padding included.
          for (request in requests) {
            val size = minOf(fixture.pieceLength, fixture.v1Bytes.size - request.index *
              fixture.pieceLength)
            assertEquals(minOf(16_384, size - request.begin), request.length, "$request")
          }
          assertTrue(PeerMessage.Request(1, 0, 16_384) in requests)
          assertTrue(PeerMessage.Request(2, 0, 16_384) in requests)
          assertTrue(PeerMessage.Request(3, 16_384, 3_616) in requests)
          engine.removeTorrent(fixture.document.identity.v1!!.hex, deleteFiles = false)
          assertEquals(0, engine.admittedSessionBytes)
        } finally {
          withContext(NonCancellable) {
            seeder.cancelAndJoin()
            engine.stop()
            listener.close()
            network.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
        }
        assertEquals(0, engine.admittedSessionBytes)
        assertEquals(0, engine.allocatedExchangeBytes)
      }
    }
  }

  private fun handshake(): ByteArray = PeerWire.encodeHandshake(PeerHandshake(legacy.infoHash,
    torrentRandomBytes(20), extensions = false, dht = false))

  /** Offers every piece and answers each request from the v1 byte stream, padding included. */
  private suspend fun seed(
    connection: TorrentConnection,
    requests: MutableList<PeerMessage.Request>,
  ) {
    val wire = PeerWire(connection, legacy)
    wire.send(PeerMessage.Bitfield(pieceBitfield(BooleanArray(4) { true })))
    wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
    while (true) {
      val message = wire.read()
      if (message !is PeerMessage.Request) continue
      requests += message
      wire.send(PeerMessage.Piece(message.index, message.begin, fixture.v1Piece(message.index)
        .copyOfRange(message.begin, message.begin + message.length)))
    }
  }
}
