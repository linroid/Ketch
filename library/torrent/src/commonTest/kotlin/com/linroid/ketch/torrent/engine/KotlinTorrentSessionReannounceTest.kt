package com.linroid.ketch.torrent.engine

import com.linroid.ketch.torrent.GatedSeeder
import com.linroid.ketch.torrent.MultiFileTorrent
import com.linroid.ketch.torrent.TorrentUploadPolicy
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.createTorrentNetwork
import com.linroid.ketch.torrent.peer.PeerHandshake
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerWire
import com.linroid.ketch.torrent.storage.TorrentPieceStore
import com.linroid.ketch.torrent.storage.torrentFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A v1 session that seeds, loses a piece it told its peers it has and downloads it again tells
 * them it has it again (HAVE), so a peer that asked for it while it was gone asks again. Files of
 * 100,000, 70,000 and 86,144 bytes in 16 KiB pieces, so pieces span files.
 */
class KotlinTorrentSessionReannounceTest {
  private val unchoke = PeerMessage.Control(PeerMessage.Signal.UNCHOKE)
  private val choke = PeerMessage.Control(PeerMessage.Signal.CHOKE)

  @Test
  fun seeder_lostPieceVerifiedAgain_announcesItAgain() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        coroutineScope {
          val torrent = MultiFileTorrent()
          val seeder = GatedSeeder(torrent)
          seeder.start(this)
          seeder.openAll()
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-reannounce-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          val network = createTorrentNetwork()
          val leecher = network.listen(PeerEndpoint("127.0.0.1", 0))
          val found = Channel<PeerEndpoint>(Channel.UNLIMITED)
          found.send(seeder.endpoint)
          val budget = TorrentBufferBudget(4 * 1024 * 1024)
          val session = KotlinTorrentSession(
            TorrentPieceStore(torrent.metadata, root / "pack", setOf(0, 1, 2), "reannounce"),
            network, budget, this,
            uploadPolicy = { TorrentUploadPolicy.SEED_AFTER_COMPLETION },
            discover = { peers, _ -> for (peer in found) peers.send(peer) },
          )
          try {
            session.resume()
            awaitState(session, TorrentSessionState.SEEDING)
            // f1 is dropped and deleted: the pieces it shares with f0 no longer verify.
            assertTrue(session.changeSelection(setOf("0", "2")))
            torrentFileSystem.delete(root / "pack/f1")
            found.send(leecher.local)
            val connection = leecher.accept()
            try {
              val wire = PeerWire(connection, torrent.metadata)
              wire.handshake(PeerHandshake(torrent.metadata.infoHash, torrentRandomBytes(20),
                false, false))
              wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
              while (wire.read() != unchoke) { }
              val shared = torrent.piecesOf(1).first
              wire.send(PeerMessage.Request(shared, 0, 16_384))
              while (true) {
                val message = wire.read()
                assertFalse(message is PeerMessage.Piece)
                if (message == choke) break
              }
              // f0 is still chosen: the seeder downloads the piece again, from this peer maybe.
              wire.send(PeerMessage.Have(shared))
              wire.send(unchoke)
              val announced = withTimeoutOrNull(10_000) {
                while (true) {
                  val message = wire.read()
                  if (message is PeerMessage.Have && message.index == shared) break
                  if (message is PeerMessage.Request) {
                    wire.send(PeerMessage.Piece(message.index, message.begin,
                      torrent.piece(message.index).copyOfRange(message.begin,
                        message.begin + message.length)))
                  }
                }
              }
              assertNotNull(announced, "The seeder never announced the piece again")
              awaitState(session, TorrentSessionState.SEEDING)
            } finally {
              connection.close()
            }
          } finally {
            session.close()
            leecher.close()
            network.close()
            seeder.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
          assertEquals(0, budget.allocated)
        }
      }
    }
  }

  private suspend fun awaitState(session: KotlinTorrentSession, expected: TorrentSessionState) {
    val state = session.state.first { it == expected || it == TorrentSessionState.STOPPED }
    assertEquals(expected, state, session.failure.value?.stackTraceToString())
  }
}
