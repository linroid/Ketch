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
import com.linroid.ketch.torrent.peer.allowedFastSet
import com.linroid.ketch.torrent.peer.pieceBitfield
import com.linroid.ketch.torrent.storage.TorrentPieceStore
import com.linroid.ketch.torrent.storage.torrentFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The v1 swarm against scripted peers that negotiate the Fast extension (BEP 6). */
class TorrentSwarmFastTest {
  private val unchoke = PeerMessage.Control(PeerMessage.Signal.UNCHOKE)
  private val choke = PeerMessage.Control(PeerMessage.Signal.CHOKE)

  /** A torrent of [pieces] pieces of [pieceLength] bytes, stored under a fresh directory. */
  private class Fixture(pieces: Int, pieceLength: Int = 65_536) {
    val payload = ByteArray(pieces * pieceLength) { (it * 31 + 7).toByte() }
    val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
      "name" to "data", "length" to payload.size.toLong(), "piece length" to pieceLength.toLong(),
      "pieces" to payload.asList().chunked(pieceLength).fold(ByteArray(0)) { result, chunk ->
        result + sha1Digest(chunk.toByteArray())
      },
    ))))
    val pieceLength = pieceLength
    val root: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-fast-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val store = TorrentPieceStore(metadata, root / "data", emptySet(), "fast")
    val network = createTorrentNetwork()
    val budget = TorrentBufferBudget(4 * 1024 * 1024)

    fun block(request: PeerMessage.Request): PeerMessage.Piece {
      val start = request.index * pieceLength + request.begin
      return PeerMessage.Piece(request.index, request.begin,
        payload.copyOfRange(start, start + request.length))
    }

    fun close() {
      network.close()
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  /** Accepts the swarm's connection on a fresh listener and runs [script] as the peer. */
  private suspend fun CoroutineScope.peer(
    fixture: Fixture,
    peers: Channel<PeerEndpoint>,
    fast: Boolean = true,
    script: suspend (PeerWire, PeerHandshake) -> Unit,
  ) = fixture.network.listen(PeerEndpoint("127.0.0.1", 0)).let { listener ->
    peers.send(listener.local)
    launch {
      val connection = listener.accept()
      try {
        val wire = PeerWire(connection, fixture.metadata)
        val remote = wire.handshake(PeerHandshake(fixture.metadata.infoHash,
          torrentRandomBytes(20), false, false, fast = fast))
        script(wire, remote)
      } catch (_: IOException) {
        // The swarm closes its peers once it is done.
      } catch (e: CancellationException) {
        throw e
      } finally {
        connection.close()
        listener.close()
      }
    }
  }

  private fun fastTest(block: suspend CoroutineScope.() -> Unit) = runTest {
    withContext(Dispatchers.Default) { withTimeout(20_000) { coroutineScope { block() } } }
  }

  @Test
  fun chokingPeer_servesAllowedFastPiecesAfterHaveAll() = fastTest {
    val fixture = Fixture(pieces = 2)
    val peers = Channel<PeerEndpoint>(1)
    val server = peer(fixture, peers) { wire, remote ->
      assertTrue(remote.fast)
      // We have nothing yet, so the swarm says so without a bitfield.
      assertEquals(PeerMessage.HaveNone, wire.read())
      wire.send(PeerMessage.HaveAll)
      wire.send(PeerMessage.Suggest(1))
      wire.send(PeerMessage.AllowedFast(0))
      wire.send(PeerMessage.AllowedFast(1))
      // It never unchokes the swarm.
      while (true) {
        val message = wire.read()
        if (message is PeerMessage.Request) wire.send(fixture.block(message))
      }
    }
    peers.close()
    try {
      TorrentSwarm(fixture.store, fixture.network, fixture.budget).run(peers)
      assertContentEquals(fixture.payload,
        torrentFileSystem.read(fixture.root / "data") { readByteArray() })
      assertEquals(0, fixture.budget.allocated)
    } finally {
      server.cancelAndJoin()
      fixture.close()
    }
  }

  @Test
  fun rejectedPiece_isRequestedFromAnotherPeerWithoutDroppingTheFirst() = fastTest {
    val fixture = Fixture(pieces = 2)
    val peers = Channel<PeerEndpoint>(2)
    val rejected = CompletableDeferred<Unit>()
    // The first peer refuses piece 0, which the second peer alone has, and serves piece 1.
    val first = peer(fixture, peers) { wire, _ ->
      wire.send(PeerMessage.HaveAll)
      wire.send(unchoke)
      var rejects = 0
      while (true) {
        val message = wire.read()
        if (message !is PeerMessage.Request) continue
        if (message.index == 0) {
          wire.send(PeerMessage.Reject(message.index, message.begin, message.length))
          if (++rejects == 4) rejected.complete(Unit)
        } else wire.send(fixture.block(message))
      }
    }
    val swarm = async {
      TorrentSwarm(fixture.store, fixture.network, fixture.budget).run(peers)
    }
    rejected.await()
    // Found once the first peer rejected the piece.
    val second = peer(fixture, peers) { wire, _ ->
      wire.send(PeerMessage.Bitfield(pieceBitfield(booleanArrayOf(true, false))))
      wire.send(unchoke)
      while (true) {
        val message = wire.read()
        if (message is PeerMessage.Request) {
          assertEquals(0, message.index)
          wire.send(fixture.block(message))
        }
      }
    }
    peers.close()
    try {
      swarm.await()
      assertContentEquals(fixture.payload,
        torrentFileSystem.read(fixture.root / "data") { readByteArray() })
      assertEquals(0, fixture.budget.allocated)
    } finally {
      first.cancelAndJoin()
      second.cancelAndJoin()
      fixture.close()
    }
  }

  @Test
  fun chokeWithRejects_requestsThePieceAgainOnUnchoke() = fastTest {
    val fixture = Fixture(pieces = 1)
    val peers = Channel<PeerEndpoint>(1)
    val server = peer(fixture, peers) { wire, _ ->
      wire.send(PeerMessage.HaveAll)
      wire.send(unchoke)
      var choked = false
      while (true) {
        val message = wire.read()
        if (message !is PeerMessage.Request) continue
        if (!choked) {
          // It chokes on the first request and rejects what it will not serve.
          choked = true
          wire.send(choke)
          wire.send(PeerMessage.Reject(message.index, message.begin, message.length))
          repeat(3) {
            val next = assertIs<PeerMessage.Request>(wire.read())
            wire.send(PeerMessage.Reject(next.index, next.begin, next.length))
          }
          wire.send(unchoke)
        } else wire.send(fixture.block(message))
      }
    }
    peers.close()
    try {
      TorrentSwarm(fixture.store, fixture.network, fixture.budget).run(peers)
      assertTrue(fixture.store.completed())
      assertEquals(0, fixture.budget.allocated)
    } finally {
      server.cancelAndJoin()
      fixture.close()
    }
  }

  @Test
  fun chokeWithoutRejects_forgetsRequestsAndToleratesLateRejects() = fastTest {
    val fixture = Fixture(pieces = 1)
    val peers = Channel<PeerEndpoint>(1)
    val server = peer(fixture, peers) { wire, _ ->
      wire.send(PeerMessage.HaveAll)
      wire.send(unchoke)
      val held = mutableListOf<PeerMessage.Request>()
      while (held.size < 4) {
        val message = wire.read()
        if (message is PeerMessage.Request) held += message
      }
      // Choked without a reject: the swarm cancels each request once its grace ends.
      wire.send(choke)
      val canceled = mutableSetOf<PeerMessage.Request>()
      while (canceled.size < 4) {
        val message = wire.read()
        if (message is PeerMessage.Cancel) {
          canceled += PeerMessage.Request(message.index, message.begin, message.length)
        }
      }
      assertEquals(held.toSet(), canceled)
      // The rejects come late, after the cancels, and must not cost the connection.
      for (request in held) {
        wire.send(PeerMessage.Reject(request.index, request.begin, request.length))
      }
      wire.send(unchoke)
      while (true) {
        val message = wire.read()
        if (message is PeerMessage.Request) wire.send(fixture.block(message))
      }
    }
    peers.close()
    try {
      TorrentSwarm(fixture.store, fixture.network, fixture.budget, fastRejectGraceMs = 300)
        .run(peers)
      assertTrue(fixture.store.completed())
      assertEquals(0, fixture.budget.allocated)
    } finally {
      server.cancelAndJoin()
      fixture.close()
    }
  }

  @Test
  fun repeatedChokes_doNotHoldTheRequestsPastTheGrace() = fastTest {
    val fixture = Fixture(pieces = 1)
    val peers = Channel<PeerEndpoint>(1)
    val server = peer(fixture, peers) { wire, _ ->
      wire.send(PeerMessage.HaveAll)
      wire.send(unchoke)
      var held = 0
      while (held < 4) if (wire.read() is PeerMessage.Request) held++
      coroutineScope {
        // CHOKE again and again, each well within the grace, never rejecting.
        val choking = launch {
          while (true) {
            wire.send(choke)
            delay(100)
          }
        }
        var canceled = 0
        while (canceled < 4) if (wire.read() is PeerMessage.Cancel) canceled++
        choking.cancel()
      }
      wire.send(unchoke)
      while (true) {
        val message = wire.read()
        if (message is PeerMessage.Request) wire.send(fixture.block(message))
      }
    }
    peers.close()
    try {
      TorrentSwarm(fixture.store, fixture.network, fixture.budget, fastRejectGraceMs = 300)
        .run(peers)
      assertTrue(fixture.store.completed())
    } finally {
      server.cancelAndJoin()
      fixture.close()
    }
  }

  @Test
  fun fastMessageWithoutNegotiation_closesThePeer() = fastTest {
    val fixture = Fixture(pieces = 1)
    val peers = Channel<PeerEndpoint>(1)
    val closed = CompletableDeferred<Unit>()
    val server = peer(fixture, peers, fast = false) { wire, remote ->
      // The swarm offers the extension, but this peer does not.
      assertTrue(remote.fast)
      wire.send(PeerMessage.HaveAll)
      try {
        while (true) wire.read()
      } catch (e: IOException) {
        closed.complete(Unit)
        throw e
      }
    }
    peers.close()
    try {
      // Its only peer gone for good, the swarm cannot finish.
      assertFailsWith<IllegalStateException> {
        TorrentSwarm(fixture.store, fixture.network, fixture.budget).run(peers)
      }
      closed.await()
      assertEquals(0, fixture.budget.allocated)
    } finally {
      server.cancelAndJoin()
      fixture.close()
    }
  }

  @Test
  fun seeder_sendsHaveAllAndServesAllowedFastWhileRejectingOthers() = fastTest {
    val fixture = Fixture(pieces = 40, pieceLength = 16_384)
    fixture.store.initialize()
    for (index in 0 until 40) {
      fixture.store.commit(index,
        fixture.payload.copyOfRange(index * 16_384, (index + 1) * 16_384))
    }
    val allowed = allowedFastSet(byteArrayOf(127, 0, 0, 1), fixture.metadata.infoHash.toBytes(),
      40)
    val other = (0 until 40).first { it !in allowed }
    val peers = Channel<PeerEndpoint>(1)
    val done = CompletableDeferred<Unit>()
    val server = peer(fixture, peers) { wire, _ ->
      assertEquals(PeerMessage.HaveAll, wire.read())
      val offered = mutableListOf<Int>()
      while (true) {
        val message = wire.read()
        if (message == PeerMessage.Control(PeerMessage.Signal.INTERESTED)) break
        offered += assertIs<PeerMessage.AllowedFast>(message).index
      }
      assertEquals(allowed, offered)
      // Not interested, so choked: an allowed-fast piece is served, any other rejected.
      val fastBlock = PeerMessage.Request(allowed.first(), 0, 16_384)
      wire.send(fastBlock)
      assertBlock(fixture.block(fastBlock), wire.read())
      val otherBlock = PeerMessage.Request(other, 0, 16_384)
      wire.send(otherBlock)
      assertEquals(PeerMessage.Reject(other, 0, 16_384), wire.read())
      // Once unchoked the same block is served.
      wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
      while (wire.read() != unchoke) { }
      wire.send(otherBlock)
      assertBlock(fixture.block(otherBlock), wire.read())
      done.complete(Unit)
      while (true) wire.read()
    }
    peers.close()
    val swarm = launch {
      TorrentSwarm(fixture.store, fixture.network, fixture.budget,
        uploadPolicy = { TorrentUploadPolicy.SEED_AFTER_COMPLETION }).run(peers)
    }
    try {
      done.await()
    } finally {
      swarm.cancelAndJoin()
      server.cancelAndJoin()
      fixture.close()
    }
    assertEquals(0, fixture.budget.allocated)
  }

  private fun assertBlock(expected: PeerMessage.Piece, actual: PeerMessage) {
    val block = assertIs<PeerMessage.Piece>(actual)
    assertEquals(expected.index to expected.begin, block.index to block.begin)
    assertContentEquals(expected.bytes, block.bytes)
  }
}
