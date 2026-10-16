package com.linroid.ketch.torrent.v1

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import okio.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The v1 swarm requests blocks, not pieces: its requests run across piece ends, peers share one
 * piece's buffer, and blocks received before a choke are kept.
 */
class TorrentSwarmPipelineTest {
  private val block = PeerWire.BLOCK_SIZE
  private val unchoke = PeerMessage.Control(PeerMessage.Signal.UNCHOKE)
  private val choke = PeerMessage.Control(PeerMessage.Signal.CHOKE)

  private class Fixture(val pieces: Int, val pieceLength: Int) {
    val payload = ByteArray(pieces * pieceLength) { (it * 29 + 3).toByte() }
    val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
      "name" to "data", "length" to payload.size.toLong(), "piece length" to pieceLength.toLong(),
      "pieces" to payload.asList().chunked(pieceLength).fold(ByteArray(0)) { result, chunk ->
        result + sha1Digest(chunk.toByteArray())
      },
    ))))
    val root: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-pipeline-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val store = TorrentPieceStore(metadata, root / "data", emptySet(), "pipeline")
    val network = createTorrentNetwork()

    fun block(request: PeerMessage.Request): PeerMessage.Piece {
      val start = request.index * pieceLength + request.begin
      return PeerMessage.Piece(request.index, request.begin,
        payload.copyOfRange(start, start + request.length))
    }

    fun output(): ByteArray = torrentFileSystem.read(root / "data") { readByteArray() }

    fun close() {
      network.close()
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  /** A budget with room for [pieces] pieces and [peers] peers' wires, as the swarm charges them. */
  private fun budget(fixture: Fixture, pieces: Int, peers: Int) = TorrentBufferBudget(
    pieces * fixture.pieceLength + peers * (2 * PeerWire.MAX_FRAME_SIZE + fixture.pieces * 4))

  /** Accepts the swarm's connection on a fresh listener and runs [script] as a seed. */
  private suspend fun CoroutineScope.seed(
    fixture: Fixture,
    peers: Channel<PeerEndpoint>,
    fast: Boolean = false,
    script: suspend (PeerWire) -> Unit,
  ) = fixture.network.listen(PeerEndpoint("127.0.0.1", 0)).let { listener ->
    peers.send(listener.local)
    launch {
      val connection = listener.accept()
      try {
        val wire = PeerWire(connection, fixture.metadata)
        wire.handshake(PeerHandshake(fixture.metadata.infoHash, torrentRandomBytes(20), false,
          false, fast = fast))
        wire.send(PeerMessage.Bitfield(pieceBitfield(BooleanArray(fixture.pieces) { true })))
        script(wire)
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

  /** Answers every request from now on. */
  private suspend fun PeerWire.serve(fixture: Fixture) {
    while (true) {
      val message = read()
      if (message is PeerMessage.Request) send(fixture.block(message))
    }
  }

  /** Reads until [count] requests have come, answering none. */
  private suspend fun PeerWire.requests(count: Int): List<PeerMessage.Request> {
    val requests = mutableListOf<PeerMessage.Request>()
    while (requests.size < count) {
      val message = read()
      if (message is PeerMessage.Request) requests += message
    }
    return requests
  }

  private fun pipelineTest(block: suspend CoroutineScope.() -> Unit) = runTest {
    withContext(Dispatchers.Default) { withTimeout(20_000) { coroutineScope { block() } } }
  }

  @Test
  fun requestsRunAcrossPieceEnds() = pipelineTest {
    // Pieces of two blocks: one piece at a time would never have more than two outstanding.
    val fixture = Fixture(pieces = 8, pieceLength = 2 * block)
    val peers = Channel<PeerEndpoint>(1)
    val server = seed(fixture, peers) { wire ->
      wire.send(unchoke)
      val asked = wire.requests(6)
      assertTrue(asked.map { it.index }.distinct().size >= 3, "$asked")
      for (request in asked) wire.send(fixture.block(request))
      wire.serve(fixture)
    }
    peers.close()
    val budget = budget(fixture, pieces = 8, peers = 1)
    try {
      TorrentSwarm(fixture.store, fixture.network, budget).run(peers)
      assertContentEquals(fixture.payload, fixture.output())
      assertEquals(0, budget.allocated)
    } finally {
      server.cancelAndJoin()
      fixture.close()
    }
  }

  @Test
  fun peersShareOnePiecesBuffer() = pipelineTest {
    // Room for one piece of 32 blocks: both peers fetch it at once, half each.
    val fixture = Fixture(pieces = 2, pieceLength = 32 * block)
    val peers = Channel<PeerEndpoint>(2)
    val asked = Channel<List<PeerMessage.Request>>(2)
    val go = CompletableDeferred<Unit>()
    val servers = (0..1).map {
      seed(fixture, peers) { wire ->
        wire.send(unchoke)
        val requests = wire.requests(1)
        asked.send(requests)
        go.await()
        for (request in requests) wire.send(fixture.block(request))
        wire.serve(fixture)
      }
    }
    peers.close()
    val budget = budget(fixture, pieces = 1, peers = 2)
    val swarm = launch { TorrentSwarm(fixture.store, fixture.network, budget).run(peers) }
    try {
      // Neither peer is answered before both were asked for blocks of the open piece.
      val first = asked.receive().single()
      val second = asked.receive().single()
      assertEquals(0, first.index)
      assertEquals(0, second.index)
      go.complete(Unit)
      swarm.join()
      assertContentEquals(fixture.payload, fixture.output())
      assertEquals(0, budget.allocated)
    } finally {
      swarm.cancelAndJoin()
      servers.forEach { it.cancelAndJoin() }
      fixture.close()
    }
  }

  @Test
  fun chokeKeepsTheBlocksAlreadyReceived() = pipelineTest {
    // Room for one piece of two: the second peer has nothing to fetch until the first chokes.
    val fixture = Fixture(pieces = 2, pieceLength = 8 * block)
    val peers = Channel<PeerEndpoint>(2)
    val choked = CompletableDeferred<Unit>()
    val first = seed(fixture, peers) { wire ->
      wire.send(unchoke)
      val asked = wire.requests(8)
      // Half the piece, then a choke that voids the other requests (BEP 3).
      for (request in asked.take(4)) wire.send(fixture.block(request))
      wire.send(choke)
      choked.complete(Unit)
      while (true) wire.read()
    }
    val missing = CompletableDeferred<Set<Pair<Int, Int>>>()
    val second = seed(fixture, peers) { wire ->
      choked.await()
      wire.send(unchoke)
      val asked = mutableSetOf<Pair<Int, Int>>()
      while (true) {
        val message = wire.read()
        if (message !is PeerMessage.Request) continue
        asked += message.index to message.begin / block
        wire.send(fixture.block(message))
        if (asked.size == 4) missing.complete(asked.toSet())
      }
    }
    peers.close()
    val budget = budget(fixture, pieces = 1, peers = 2)
    try {
      TorrentSwarm(fixture.store, fixture.network, budget).run(peers)
      assertEquals((4..7).map { 0 to it }.toSet(), missing.await())
      assertContentEquals(fixture.payload, fixture.output())
      assertEquals(0, budget.allocated)
    } finally {
      first.cancelAndJoin()
      second.cancelAndJoin()
      fixture.close()
    }
  }

  @Test
  fun fastPeerThatChokesAfterServingLeavesTheRestToOthers() = pipelineTest {
    // Pieces of more blocks than one round of requests, and room for one of them.
    val fixture = Fixture(pieces = 2, pieceLength = 32 * block)
    val peers = Channel<PeerEndpoint>(2)
    val served = CompletableDeferred<Unit>()
    val first = seed(fixture, peers, fast = true) { wire ->
      wire.send(unchoke)
      val asked = wire.requests(16)
      // A Fast peer may serve what it was asked before choking; it never unchokes again.
      wire.send(choke)
      for (request in asked) wire.send(fixture.block(request))
      served.complete(Unit)
      while (true) wire.read()
    }
    val budget = budget(fixture, pieces = 1, peers = 2)
    val swarm = launch { TorrentSwarm(fixture.store, fixture.network, budget).run(peers) }
    try {
      served.await()
      // The choking peer pins nothing: a seed found later finishes the piece and the torrent,
      // long before the 90 s a useless peer is given.
      val second = seed(fixture, peers) { wire ->
        wire.send(unchoke)
        wire.serve(fixture)
      }
      peers.close()
      swarm.join()
      second.cancelAndJoin()
      assertContentEquals(fixture.payload, fixture.output())
      assertEquals(0, budget.allocated)
    } finally {
      swarm.cancelAndJoin()
      first.cancelAndJoin()
      fixture.close()
    }
  }

  @Test
  fun peerWaitsWhileOtherTorrentsLeaveNoRoomForAPiece() = pipelineTest {
    val fixture = Fixture(pieces = 2, pieceLength = 8 * block)
    val peers = Channel<PeerEndpoint>(1)
    val connected = CompletableDeferred<Unit>()
    val server = seed(fixture, peers) { wire ->
      connected.complete(Unit)
      wire.send(unchoke)
      wire.serve(fixture)
    }
    peers.close()
    // The engine's budget, shared with another torrent whose peers hold all but less than a
    // piece once this one's wire is charged: dialing would leave neither a piece to fetch.
    val overhead = 2 * PeerWire.MAX_FRAME_SIZE + fixture.pieces * 4
    val budget = TorrentBufferBudget(fixture.pieceLength + 2 * overhead)
    val other = checkNotNull(budget.reserve(overhead + 1))
    val swarm = launch { TorrentSwarm(fixture.store, fixture.network, budget).run(peers) }
    try {
      assertEquals(null, withTimeoutOrNull(500) { connected.await() })
      other.close()
      swarm.join()
      assertContentEquals(fixture.payload, fixture.output())
      assertEquals(0, budget.allocated)
    } finally {
      swarm.cancelAndJoin()
      server.cancelAndJoin()
      fixture.close()
    }
  }

  @Test
  fun stoppedSwarmReturnsEveryBuffer() = pipelineTest {
    val fixture = Fixture(pieces = 2, pieceLength = 8 * block)
    val peers = Channel<PeerEndpoint>(1)
    val served = CompletableDeferred<Unit>()
    val server = seed(fixture, peers) { wire ->
      wire.send(unchoke)
      val asked = wire.requests(4)
      for (request in asked.take(2)) wire.send(fixture.block(request))
      served.complete(Unit)
      while (true) wire.read()
    }
    peers.close()
    val budget = budget(fixture, pieces = 2, peers = 1)
    val swarm = launch { TorrentSwarm(fixture.store, fixture.network, budget).run(peers) }
    try {
      served.await()
      swarm.cancelAndJoin()
      assertEquals(0, budget.allocated)
    } finally {
      server.cancelAndJoin()
      fixture.close()
    }
  }
}
