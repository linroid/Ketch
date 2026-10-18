package com.linroid.ketch.torrent.v1

import com.linroid.ketch.torrent.api.TorrentStall
import com.linroid.ketch.torrent.api.TorrentStallReason
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
import com.linroid.ketch.torrent.storage.TorrentPieceStore
import com.linroid.ketch.torrent.storage.torrentFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A v1 swarm against a Fast peer (BEP 6) that has every piece but refuses piece 0, rejecting
 * each of its blocks, until it announces the piece again: the swarm leaves the piece to other
 * peers meanwhile, asks the peer again once it announces it or a minute later, and calls a
 * stall for it missing pieces rather than an unknown reason, also while it asks again, until the
 * peer sends a block of it, announces it or unchokes the swarm again. Two pieces of four blocks.
 */
@OptIn(ExperimentalAtomicApi::class)
class TorrentSwarmRefusalTest {
  private val pieceLength = 65_536
  private val payload = ByteArray(2 * pieceLength) { (it * 31 + 7).toByte() }
  private val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
    "name" to "data", "length" to payload.size.toLong(), "piece length" to pieceLength.toLong(),
    "pieces" to sha1Digest(payload.copyOfRange(0, pieceLength)) +
      sha1Digest(payload.copyOfRange(pieceLength, payload.size)),
  ))))

  @Test
  fun refusedPiece_announcedAgain_isAskedForAgain() = refusalTest { rig ->
    rig.refusing.await()
    rig.peer { answerWith(Answer.SERVE); announce() }
    rig.swarm.join()
  }

  @Test
  fun refusedPiece_isAskedForAgainAMinuteLater() = refusalTest { rig ->
    rig.refusing.await()
    rig.peer { answerWith(Answer.SERVE) }
    // Asked for nothing while the refusal stands, then for the piece once a minute passed.
    rig.advanceTo(55_000)
    assertTrue(rig.swarm.isActive)
    rig.advanceTo(65_000)
    rig.swarm.join()
  }

  @Test
  fun refusedPiece_stallsAsMissing() = refusalTest { rig ->
    rig.refusing.await()
    rig.advanceTo(35_000)
    rig.assertMissing()
    rig.peer { answerWith(Answer.SERVE); announce() }
    rig.swarm.join()
  }

  @Test
  fun refusedPiece_blockStillOwedArrives_isNoLongerMissing() =
    refusalTest(holdsFirst = true) { rig ->
      rig.refusing.await()
      rig.advanceTo(35_000)
      rig.assertMissing()
      // Within the refusal's minute, the peer sends the block it held back, and holds the rest.
      rig.peer { answerWith(Answer.HOLD); sendHeld() }
      // Stalled again once 30 s pass without payload.
      rig.advanceTo(75_000)
      rig.assertStalledNotMissing()
      rig.peer { answerWith(Answer.SERVE) }
      rig.swarm.join()
    }

  @Test
  fun refusedPiece_refusedAgainAMinuteLater_staysMissingThroughout() =
    refusalTest(holdsAskedAgain = true) { rig ->
      rig.refusing.await()
      rig.advanceTo(35_000)
      rig.assertMissing()
      // Every stall published from now on, which the peer's refusal must not leave.
      val reasons = mutableListOf<TorrentStallReason?>()
      val following = launch { rig.stall.collect { reasons += it?.reason } }
      // Asked again a minute later, the peer takes its time to reject the piece once more.
      rig.advanceTo(65_000)
      rig.askedAgain.await()
      rig.advanceTo(70_000)
      rig.peer { answerWith(Answer.REJECT) }
      rig.advanceTo(75_000)
      following.cancelAndJoin()
      assertEquals(listOf(TorrentStallReason.MISSING_PIECES), reasons.distinct(),
        "Stalls: $reasons")
      rig.assertMissing()
      rig.peer { answerWith(Answer.SERVE); announce() }
      rig.swarm.join()
    }

  @Test
  fun probedPiece_announcedAgain_isNoLongerMissing() =
    probedPieceTest(checkAt = 75_000) { announce() }

  @Test
  fun probedPiece_unchokedAgain_isNoLongerMissing() =
    probedPieceTest(checkAt = 75_000) { unchokeAgain() }

  // A block is payload, so the swarm stalls again only once 30 s pass without more.
  @Test
  fun probedPiece_blockArrives_isNoLongerMissing() =
    probedPieceTest(checkAt = 100_000) { sendHeld() }

  /**
   * Has the peer hold back the requests for piece 0 the swarm makes once its refusal lapsed, a
   * minute on, and [lift] the refusal while they wait: the stall at [checkAt] names no missing
   * piece, as the peer is no longer refusing it. [checkAt] comes before the swarm drops the peer,
   * 90 s after the last useful payload.
   */
  private fun probedPieceTest(checkAt: Long, lift: suspend RefusingPeer.() -> Unit) =
    refusalTest(holdsAskedAgain = true) { rig ->
      rig.refusing.await()
      rig.advanceTo(65_000)
      rig.askedAgain.await()
      rig.assertMissing()
      rig.peer(lift)
      rig.advanceTo(checkAt)
      rig.assertStalledNotMissing()
      rig.peer { answerWith(Answer.SERVE) }
      rig.swarm.join()
    }

  private inner class Rig(
    val clock: AtomicLong,
    val stall: MutableStateFlow<TorrentStall?>,
    /** Completes once the peer rejected piece 0 and the swarm stopped asking for it. */
    val refusing: CompletableDeferred<Unit>,
    /** Completes once the swarm asks for piece 0 again after its refusal, held back by the peer. */
    val askedAgain: CompletableDeferred<Unit>,
    private val actions: Channel<PeerAction>,
    val swarm: Job,
  ) {
    /**
     * Moves the clock on in 1 s steps, the swarm's stall check interval, up to [until]. The swarm
     * counts payload from the first check that sees it, so a coarser step, which a slow machine's
     * swarm may check only steps later, would count payload as arriving later than it did. The
     * first call has the swarm check at 1 s, so the payload it got before counts from then.
     */
    suspend fun advanceTo(until: Long) {
      if (clock.load() == 0L) {
        clock.store(1_000)
        delay(500)
      }
      while (clock.load() < until) {
        delay(60)
        clock.addAndFetch(1_000)
      }
      delay(300)
    }

    /** Has the peer run [action] between two messages, and waits until it did. */
    suspend fun peer(action: suspend RefusingPeer.() -> Unit) {
      val queued = PeerAction(action)
      actions.send(queued)
      queued.done.await()
    }

    /** Checks the swarm stalls for piece 0, missing as the peer refuses it. */
    suspend fun assertMissing() {
      val stall = settled { it?.reason == TorrentStallReason.MISSING_PIECES }
      assertEquals(TorrentStallReason.MISSING_PIECES, stall?.reason, "Stall: $stall")
      assertEquals(1, stall?.missingPieces)
    }

    /** Checks the swarm stalls, but with no piece missing. */
    suspend fun assertStalledNotMissing() {
      val stall = assertNotNull(settled { it != null && it.missingPieces == 0 })
      assertNotEquals(TorrentStallReason.MISSING_PIECES, stall.reason, "Stall: $stall")
      assertEquals(0, stall.missingPieces)
    }

    /**
     * The stall once [check] holds for it, else the last one after [SETTLE_MS]: on a slow machine
     * the swarm checks itself at the time [advanceTo] set a while after the clock moved.
     */
    private suspend fun settled(check: (TorrentStall?) -> Boolean): TorrentStall? =
      withTimeoutOrNull(SETTLE_MS) { stall.first(check) } ?: stall.value
  }

  /**
   * Runs a swarm on a test clock against the refusing peer, which [body] drives, and checks the
   * swarm completes. A peer that [holdsFirst] holds back its first request for piece 0, rejecting
   * the rest; one that [holdsAskedAgain] holds back those that come after its refusal.
   */
  private fun refusalTest(
    holdsFirst: Boolean = false,
    holdsAskedAgain: Boolean = false,
    body: suspend CoroutineScope.(Rig) -> Unit,
  ) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(60_000) {
        coroutineScope {
          val root: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-refusal-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          val store = TorrentPieceStore(metadata, root / "data", emptySet(), "refusal")
          val network = createTorrentNetwork()
          val budget = TorrentBufferBudget(4 * 1024 * 1024)
          val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
          val peers = Channel<PeerEndpoint>(1)
          peers.send(listener.local)
          peers.close()
          val refusing = CompletableDeferred<Unit>()
          val askedAgain = CompletableDeferred<Unit>()
          val actions = Channel<PeerAction>(Channel.UNLIMITED)
          val peer = launch {
            val connection = listener.accept()
            try {
              val wire = PeerWire(connection, metadata)
              wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), false,
                false, fast = true))
              RefusingPeer(wire, holdsFirst, holdsAskedAgain, refusing, askedAgain)
                .play(actions)
            } catch (_: IOException) {
              // The swarm closes its peers once it is done.
            } catch (e: CancellationException) {
              throw e
            } finally {
              connection.close()
              listener.close()
            }
          }
          val clock = AtomicLong(0)
          val stall = MutableStateFlow<TorrentStall?>(null)
          val swarm = launch {
            TorrentSwarm(store, network, budget, nowMs = { clock.load() }, stall = stall)
              .run(peers)
          }
          try {
            body(Rig(clock, stall, refusing, askedAgain, actions, swarm))
            assertTrue(store.completed())
            assertContentEquals(payload, torrentFileSystem.read(root / "data") { readByteArray() })
            assertEquals(0, budget.allocated)
          } finally {
            swarm.cancelAndJoin()
            peer.cancelAndJoin()
            network.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
        }
      }
    }
  }

  /** How the peer answers requests for piece 0. */
  private enum class Answer { REJECT, HOLD, SERVE }

  /** What a test has the peer do ([Rig.peer]), done once it ran. */
  private class PeerAction(val body: suspend RefusingPeer.() -> Unit) {
    val done = CompletableDeferred<Unit>()
  }

  /**
   * Plays a peer with every piece that serves piece 1 and answers requests for piece 0 as
   * [answer] says, rejecting them at first, between the [PeerAction]s a test sends it.
   * [refusing] completes once the peer sent all of piece 1 and the swarm asked for nothing for a
   * while after a rejection, so no payload arrives once the test moves its clock; [askedAgain]
   * once it asks for piece 0 again after that and the peer holds the request back.
   */
  private inner class RefusingPeer(
    private val wire: PeerWire,
    private val holdsFirst: Boolean,
    private val holdsAskedAgain: Boolean,
    private val refusing: CompletableDeferred<Unit>,
    private val askedAgain: CompletableDeferred<Unit>,
  ) {
    private var answer = Answer.REJECT
    /** Requests for piece 0 held back, oldest first. */
    private val held = ArrayDeque<PeerMessage.Request>()
    private var heldFirst = false
    private var rejected = false
    /** Bytes of piece 1 sent: the swarm gets all of its payload before [refusing] completes. */
    private var servedOther = 0

    /** Announces piece 0 (HAVE), as a seeder that verified it again does. */
    suspend fun announce() = wire.send(PeerMessage.Have(0))

    /** Chokes the swarm and unchokes it again at once. */
    suspend fun unchokeAgain() = wire.sendAll(listOf(
      PeerMessage.Control(PeerMessage.Signal.CHOKE),
      PeerMessage.Control(PeerMessage.Signal.UNCHOKE)
    ))

    /** Sends the block of the oldest request held back. */
    suspend fun sendHeld() = wire.send(block(held.removeFirst()))

    /** Answers the requests held back, and those for piece 0 from now on, as [how] says. */
    suspend fun answerWith(how: Answer) {
      answer = how
      if (how == Answer.HOLD) return
      wire.sendAll(held.map { if (how == Answer.SERVE) block(it) else reject(it) })
      held.clear()
    }

    suspend fun play(actions: Channel<PeerAction>) = coroutineScope {
      val messages = Channel<PeerMessage>(Channel.UNLIMITED)
      val reader = launch {
        try {
          while (true) messages.send(wire.read())
        } catch (e: IOException) {
          messages.close(e)
        }
      }
      try {
        wire.send(PeerMessage.HaveAll)
        wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
        while (true) {
          while (true) {
            val action = actions.tryReceive().getOrNull() ?: break
            action.body(this@RefusingPeer)
            action.done.complete(Unit)
          }
          val message = withTimeoutOrNull(300) { messages.receive() }
          if (message == null) {
            if (rejected && servedOther >= pieceLength && refusing.complete(Unit) &&
              holdsAskedAgain) {
              answer = Answer.HOLD
            }
            continue
          }
          if (message is PeerMessage.Request) receive(message)
        }
      } finally {
        reader.cancel()
      }
    }

    private suspend fun receive(request: PeerMessage.Request) {
      if (request.index != 0) {
        wire.send(block(request))
        servedOther += request.length
        return
      }
      if (holdsFirst && !heldFirst) {
        heldFirst = true
        held += request
        return
      }
      when (answer) {
        Answer.REJECT -> {
          wire.send(reject(request))
          rejected = true
        }
        Answer.HOLD -> {
          held += request
          if (refusing.isCompleted) askedAgain.complete(Unit)
        }
        Answer.SERVE -> wire.send(block(request))
      }
    }

    private fun block(request: PeerMessage.Request): PeerMessage.Piece {
      val start = request.index * pieceLength + request.begin
      return PeerMessage.Piece(request.index, request.begin,
        payload.copyOfRange(start, start + request.length))
    }

    private fun reject(request: PeerMessage.Request) =
      PeerMessage.Reject(request.index, request.begin, request.length)
  }
}

/** How long [TorrentSwarmRefusalTest] waits, in real time, for the swarm to publish a stall. */
private const val SETTLE_MS = 5_000L
