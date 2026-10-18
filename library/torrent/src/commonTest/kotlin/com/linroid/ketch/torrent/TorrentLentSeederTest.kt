package com.linroid.ketch.torrent

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.KotlinTorrentEngine
import com.linroid.ketch.torrent.engine.KotlinTorrentSession
import com.linroid.ketch.torrent.engine.TorrentSessionState
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentConnection
import com.linroid.ketch.torrent.net.TorrentHttp
import com.linroid.ketch.torrent.net.TorrentNetwork
import com.linroid.ketch.torrent.net.createTorrentNetwork
import com.linroid.ketch.torrent.peer.PeerHandshake
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerWire
import com.linroid.ketch.torrent.storage.torrentFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A finished v1 torrent that keeps seeding on its lent slot and downloads a piece it lost again
 * takes a smaller selection live meanwhile, and seeds on afterwards as it did before: shown
 * sharing and saving what it uploads, so a file dropped as it downloaded is one it serves around.
 * Files of 100,000, 70,000 and 86,144 bytes in 16 KiB pieces, so pieces span files.
 */
class TorrentLentSeederTest {
  private val unchoke = PeerMessage.Control(PeerMessage.Signal.UNCHOKE)
  private val choke = PeerMessage.Control(PeerMessage.Signal.CHOKE)
  private val interested = PeerMessage.Control(PeerMessage.Signal.INTERESTED)

  @Test
  fun lentSeeder_downloadsALostPieceAgain_seedsOnAndTakesSmallerSelectionsLive() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(45_000) {
        coroutineScope {
          val seeder = GatedSeeder()
          seeder.start(this)
          seeder.openAll()
          val torrent = seeder.torrent
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-lent-seeder-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          torrentFileSystem.createDirectories(root)
          val output = root / "out"
          var engine: KotlinTorrentEngine? = null
          val source = TorrentDownloadSource(TorrentConfig(encryption = TorrentEncryption.DISABLED,
            dhtEnabled = false, uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION),
            seeder.http).also { source ->
            source.engineFactory = { config ->
              KotlinTorrentEngine(config, http = TorrentHttp(seeder.http),
                listenHost = "127.0.0.1").also { engine = it }
            }
            source.seedingSaveIntervalMs = 50
          }
          val ketch = Ketch(seeder.http, taskStore = MemoryTaskStore(),
            additionalSources = listOf(source))
          val network = createTorrentNetwork()
          try {
            ketch.start()
            val task = ketch.download(DownloadRequest(GatedSeeder.URL,
              destination = Destination(output.toString()),
              selectedFileIds = setOf("0", "1", "2")))
            awaitStage("completion", task) { task.await().getOrThrow() }
            val session = assertIs<KotlinTorrentSession>(source.sessionOf(task.taskId))
            eventually("the task to show it seeds") { task.sharing() }
            // What the seeder saves from now on; Ketch's records no longer hear of it.
            val saved = Channel<String>(Channel.UNLIMITED)
            source.saveSessionStates { taskId, _ -> saved.send(taskId) }

            // f1 is dropped, then deleted; a peer asks for a piece f0 shares with it.
            task.selectFiles(setOf("0", "2"))
            assertEquals(setOf("0", "2"), session.selectedFileIds)
            torrentFileSystem.delete(output / "f1")
            val shared = torrent.piecesOf(1).first
            val enginePort = checkNotNull(engine).listenPort
            peer(network, torrent, enginePort) { wire ->
              wire.send(interested)
              while (wire.read() != unchoke) { }
              wire.send(PeerMessage.Request(shared, 0, 16_384))
              // The piece cannot be read back: the seeder chokes us rather than send it.
              while (true) {
                val message = wire.read()
                assertFalse(message is PeerMessage.Piece)
                if (message == choke) break
              }
              // f0 is still chosen, so the seeder downloads the piece again: no longer shown
              // seeding, and waiting for this peer, which has not said it has the piece yet.
              eventually("the seeder to download the piece again") {
                session.state.value == TorrentSessionState.DOWNLOADING &&
                  task.taskId !in source.seedingTaskIds.value
              }
              // A smaller selection reaches it meanwhile.
              task.selectFiles(setOf("0"))
              assertEquals(setOf("0"), session.selectedFileIds)
              assertEquals(TorrentSessionState.DOWNLOADING, session.state.value)
              assertTrue(task.taskId !in source.seedingTaskIds.value)
              wire.send(PeerMessage.Have(shared))
              wire.send(unchoke)
              val serving = launch { serve(wire, torrent) }
              try {
                eventually("the seeder to seed again") {
                  session.verifiedPieces()[shared] &&
                    session.state.value == TorrentSessionState.SEEDING
                }
              } finally {
                serving.cancel()
              }
            }

            // It is a seeder as before: shown sharing, and saving what it uploads.
            eventually("the task to show it seeds again") { task.sharing() }
            assertTrue(task.taskId in source.seedingTaskIds.value)
            while (saved.tryReceive().isSuccess) { }
            assertNotNull(withTimeoutOrNull(5_000) { saved.receive() },
              "The seeder no longer saves what it uploads")
            assertEquals(setOf("0"), session.selectedFileIds)
            // The owner edits the file it dropped as the seeder downloaded, in a piece only that
            // file holds: the seeder drops the piece rather than fail its storage, and seeds on.
            val edited = torrent.pieceCount - 1
            editInPlace(output / "f2", torrent.sizes[2] - 1)
            peer(network, torrent, enginePort) { wire ->
              wire.send(interested)
              while (wire.read() != unchoke) { }
              wire.send(PeerMessage.Request(edited, 0, torrent.piece(edited).size))
              eventually("the edited piece to be dropped") { !session.verifiedPieces()[edited] }
            }
            assertNull(withTimeoutOrNull(1_000) {
              session.state.first { it != TorrentSessionState.SEEDING }
            })
            assertNull(session.failure.value)
            assertTrue(task.taskId in source.seedingTaskIds.value)
            source.stopSeeding(task.taskId)
            source.assertNoLeaks()
          } finally {
            ketch.close()
            network.close()
            engine?.stop()
            seeder.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
          checkNotNull(engine).assertNoLeaks()
        }
      }
    }
  }

  private fun DownloadTask.sharing(): Boolean =
    (state.value as? DownloadState.Completed)?.sharing == true

  /** Connects to the engine at [port] as a peer of [torrent] with no piece, and runs [script]. */
  private suspend fun peer(
    network: TorrentNetwork,
    torrent: MultiFileTorrent,
    port: Int,
    script: suspend (PeerWire) -> Unit,
  ) {
    val connection: TorrentConnection = network.connect(PeerEndpoint("127.0.0.1", port))
    try {
      val wire = PeerWire(connection, torrent.metadata)
      wire.handshake(PeerHandshake(torrent.metadata.infoHash, torrentRandomBytes(20), false,
        false))
      script(wire)
    } finally {
      connection.close()
    }
  }

  /** Sends each block of [torrent] the other side asks for, until the connection closes. */
  private suspend fun serve(wire: PeerWire, torrent: MultiFileTorrent) {
    try {
      while (true) {
        val message = wire.read()
        if (message is PeerMessage.Request) {
          wire.send(PeerMessage.Piece(message.index, message.begin,
            torrent.piece(message.index).copyOfRange(message.begin,
              message.begin + message.length)))
        }
      }
    } catch (_: IOException) {
      // The connection closed.
    }
  }

  /**
   * Rewrites the bytes [at] of [path] in place, as an editor would, late enough that the file's
   * modification time tells the edit apart from the last write.
   */
  private suspend fun editInPlace(path: Path, vararg at: Int) {
    val bytes = torrentFileSystem.read(path) { readByteArray() }
    for (index in at) bytes[index] = (bytes[index] + 1).toByte()
    val before = torrentFileSystem.metadata(path).lastModifiedAtMillis
    while (true) {
      torrentFileSystem.write(path) { write(bytes) }
      if (torrentFileSystem.metadata(path).lastModifiedAtMillis != before) return
      delay(5)
    }
  }
}
