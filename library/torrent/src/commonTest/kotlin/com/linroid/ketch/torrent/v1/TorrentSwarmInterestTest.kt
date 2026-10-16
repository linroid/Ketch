package com.linroid.ketch.torrent.v1

import com.linroid.ketch.torrent.TorrentUploadPolicy
import com.linroid.ketch.torrent.crypto.sha1Digest
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.createTorrentNetwork
import com.linroid.ketch.torrent.peer.PeerHandshake
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerWire
import com.linroid.ketch.torrent.peer.pieceBitfield
import com.linroid.ketch.torrent.storage.TorrentPieceStore
import com.linroid.ketch.torrent.storage.torrentFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A v1 swarm is interested in a peer only while the peer has a piece it still wants (BEP 3). */
class TorrentSwarmInterestTest {
  private val interested = PeerMessage.Control(PeerMessage.Signal.INTERESTED)
  private val notInterested = PeerMessage.Control(PeerMessage.Signal.NOT_INTERESTED)
  private val unchoke = PeerMessage.Control(PeerMessage.Signal.UNCHOKE)

  private val pieceLength = 16_384
  private val payload = ByteArray(2 * pieceLength) { (it * 13 + 5).toByte() }
  private val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
    "name" to "data", "length" to payload.size.toLong(), "piece length" to pieceLength.toLong(),
    "pieces" to sha1Digest(payload.copyOfRange(0, pieceLength)) +
      sha1Digest(payload.copyOfRange(pieceLength, payload.size)),
  ))))

  private fun interestTest(block: suspend CoroutineScope.(TorrentPieceStore) -> Unit) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        coroutineScope {
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-interest-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          val store = TorrentPieceStore(metadata, root / "data", emptySet(), "interest")
          try {
            block(store)
          } finally {
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
        }
      }
    }
  }

  private fun block(request: PeerMessage.Request): PeerMessage.Piece {
    val start = request.index * pieceLength + request.begin
    return PeerMessage.Piece(request.index, request.begin,
      payload.copyOfRange(start, start + request.length))
  }

  /** Reads until [expected], answering requests, and returns what came before it. */
  private suspend fun PeerWire.readUntil(expected: PeerMessage): List<PeerMessage> {
    val before = mutableListOf<PeerMessage>()
    while (true) {
      val message = read()
      if (message == expected) return before
      if (message is PeerMessage.Request) send(block(message))
      before += message
    }
  }

  @Test
  fun interestFollowsThePiecesThePeerHasAndWeStillWant() = interestTest { store ->
    val network = createTorrentNetwork()
    val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
    val peers = Channel<PeerEndpoint>(1)
    peers.send(listener.local)
    peers.close()
    val budget = TorrentBufferBudget(1024 * 1024)
    val swarm = launch { TorrentSwarm(store, network, budget).run(peers) }
    val connection = listener.accept()
    try {
      val wire = PeerWire(connection, metadata)
      wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), false, false))
      wire.send(PeerMessage.Bitfield(pieceBitfield(booleanArrayOf(false, false))))
      wire.send(unchoke)
      // Nothing it has is wanted, so the swarm says nothing of interest.
      assertTrue(wire.read() is PeerMessage.Bitfield)
      assertNull(withTimeoutOrNull(500) { wire.read() })
      // Once it has a wanted piece, the swarm is interested and downloads it.
      wire.send(PeerMessage.Have(1))
      assertTrue(wire.readUntil(interested).isEmpty())
      // With that piece stored, the peer has nothing more to give.
      val before = wire.readUntil(notInterested)
      assertTrue(PeerMessage.Have(1) in before, "$before")
      wire.send(PeerMessage.Have(0))
      wire.readUntil(interested)
      val serving = launch {
        try {
          wire.readUntil(PeerMessage.KeepAlive)
        } catch (_: IOException) {
          // The complete swarm closes the connection.
        }
      }
      swarm.join()
      serving.cancelAndJoin()
      assertContentEquals(payload,
        torrentFileSystem.read(store.outputPath.toPath()) { readByteArray() })
      assertEquals(0, budget.allocated)
    } finally {
      swarm.cancelAndJoin()
      connection.close()
      listener.close()
      network.close()
    }
  }

  @Test
  fun seedIsNeverInterestedAndClosesOtherSeeds() = interestTest { store ->
    store.initialize()
    store.commit(0, payload.copyOfRange(0, pieceLength))
    store.commit(1, payload.copyOfRange(pieceLength, payload.size))
    val network = createTorrentNetwork()
    val leecherListener = network.listen(PeerEndpoint("127.0.0.1", 0))
    val seedListener = network.listen(PeerEndpoint("127.0.0.1", 0))
    val peers = Channel<PeerEndpoint>(2)
    peers.send(leecherListener.local)
    peers.send(seedListener.local)
    peers.close()
    val budget = TorrentBufferBudget(1024 * 1024)
    val swarm = launch {
      TorrentSwarm(store, network, budget,
        uploadPolicy = { TorrentUploadPolicy.SEED_AFTER_COMPLETION }).run(peers)
    }
    val leecher = leecherListener.accept()
    val seed = seedListener.accept()
    try {
      val seedWire = PeerWire(seed, metadata)
      seedWire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), false, false))
      seedWire.send(PeerMessage.Bitfield(pieceBitfield(booleanArrayOf(true, true))))
      // Neither side wants anything: the swarm hangs up without ever declaring interest.
      val fromSeed = mutableListOf<PeerMessage>()
      assertFailsWith<IOException> { while (true) fromSeed += seedWire.read() }
      assertTrue(interested !in fromSeed, "$fromSeed")
      val wire = PeerWire(leecher, metadata)
      wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), false, false))
      wire.send(PeerMessage.Bitfield(pieceBitfield(booleanArrayOf(false, false))))
      wire.send(interested)
      // The leecher is served, but never asked for anything.
      val beforeUnchoke = wire.readUntil(unchoke)
      assertTrue(interested !in beforeUnchoke, "$beforeUnchoke")
      wire.send(PeerMessage.Request(1, 0, pieceLength))
      val served = wire.read()
      assertTrue(served is PeerMessage.Piece && served.index == 1, "$served")
    } finally {
      swarm.cancelAndJoin()
      leecher.close()
      seed.close()
      leecherListener.close()
      seedListener.close()
      network.close()
    }
    assertEquals(0, budget.allocated)
  }
}
