package com.linroid.ketch.torrent.v2

import com.linroid.ketch.torrent.ClockLimit
import com.linroid.ketch.torrent.TorrentV2Fixture
import com.linroid.ketch.torrent.api.TorrentStall
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.engine.TorrentSessionState
import com.linroid.ketch.torrent.engine.TorrentStallWatchdog
import com.linroid.ketch.torrent.engine.describe
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentListener
import com.linroid.ketch.torrent.net.createTorrentNetwork
import com.linroid.ketch.torrent.peer.PeerIdentityHandshake
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerWire
import com.linroid.ketch.torrent.storage.TorrentV2PieceStore
import com.linroid.ketch.torrent.storage.torrentFileSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.IOException
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A v2 download whose requests wait on a low speed limit is no stalled one: under a 3 KiB/s task
 * limit each 16 KiB request waits over 5 s before it is sent, so a 256 KiB piece takes 85 s,
 * longer than a stall allows for a piece at that rate. The session's loop counts as one wait while
 * a peer's request waits ([TorrentV2Swarm.throttle]), and only then: a peer that stops answering
 * still stalls the download once nothing is left to request, and one that rejects every request
 * once it rejected a piece's worth. Transfers run against one seed over loopback TCP, on a test
 * clock that moves on in steps once the seed has unchoked us, waking the loop each step so it
 * checks itself every second of the clock.
 */
@OptIn(ExperimentalAtomicApi::class)
class TorrentV2LowLimitTest {
  private val fixture = TorrentV2Fixture.build(listOf("data.bin" to PIECE), pieceLength = PIECE)

  @Test
  fun session_peerHeldByALowLimit_neverStalls() = runTest {
    transfer(answers = BLOCKS) { session, clock, seed ->
      // Collected from now on: none may show before the piece verifies.
      val stalled = AtomicReference<TorrentStall?>(null)
      val watching = launch { stalled.store(session.stall.filterNotNull().first()) }
      while (session.state.value != TorrentSessionState.FINISHED &&
        session.state.value != TorrentSessionState.STOPPED) step(session, clock)
      watching.cancelAndJoin()
      assertEquals(TorrentSessionState.FINISHED, session.state.value,
        session.failure.value?.toString())
      assertNull(stalled.load()?.describe())
      assertEquals(BLOCKS, seed.requests.load())
      // The limit held the piece longer than a stall allows: four pieces' time at the least rate.
      assertTrue(clock.load() > TorrentStallWatchdog.MIN_PIECE_MS, "Done at ${clock.load()} ms")
    }
  }

  @Test
  fun session_peerStopsAnsweringUnderALowLimit_stallsOnceNothingWaits() = runTest {
    transfer(answers = BLOCKS / 4) { session, clock, seed ->
      while (session.stall.value == null) step(session, clock)
      // Every block was requested, the last one when the loop stopped waiting on the limit.
      assertEquals(BLOCKS, seed.requests.load(), session.stall.value?.describe())
      val waited = clock.load() - seed.lastRequestAt.load()
      assertTrue(waited <= TorrentStallWatchdog.NO_PAYLOAD_MS + CHECK_SLACK_MS,
        "Stalled ${waited / 1000} s after the last request")
    }
  }

  @Test
  fun session_peerRejectsEveryRequestUnderALowLimit_stallsOnceItRejectedAPiece() = runTest {
    // Two blocks a piece: rejected twice, each piece goes to other peers, so without a stall the
    // peer would keep the download waiting on the limit for every piece in turn.
    val pieces = TorrentV2Fixture.build(listOf("data.bin" to PIECE), pieceLength = SMALL_PIECE)
    transfer(answers = 0, fixture = pieces, rejects = true) { session, clock, seed ->
      while (session.stall.value == null) step(session, clock)
      val rejected = seed.requestTimes.load()
      val pieceRejectedAt = rejected[SMALL_PIECE / 16_384 - 1]
      val waited = clock.load() - pieceRejectedAt
      assertTrue(waited <= TorrentStallWatchdog.NO_PAYLOAD_MS + CHECK_SLACK_MS,
        "Stalled ${waited / 1000} s after a piece's worth of rejects, ${rejected.size} in all")
    }
  }

  /** The peer: what it was asked for, and when on the test clock. */
  private class Seed {
    val requests = AtomicInt(0)
    val lastRequestAt = AtomicLong(0)

    /** When each request came, written by the seed alone. */
    val requestTimes = AtomicReference(emptyList<Long>())
  }

  /**
   * Downloads [fixture] from one seed that answers its first [answers] requests and then nothing,
   * or with [rejects] rejects every request past them, under a 3 KiB/s task limit; [body] moves
   * the clock on.
   */
  private suspend fun transfer(
    answers: Int,
    fixture: TorrentV2Fixture = this.fixture,
    rejects: Boolean = false,
    body: suspend CoroutineScope.(TorrentV2DownloadSession, AtomicLong, Seed) -> Unit,
  ) = withContext(Dispatchers.Default) {
    withTimeout(30_000) {
      val output = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
        "ketch-v2-low-limit-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
      val buffers = TorrentBufferBudget(4 * 1024 * 1024)
      val state = TorrentBufferBudget(4 * 1024 * 1024)
      val network = createTorrentNetwork()
      val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
      val store = TorrentV2PieceStore(fixture.document, output, emptySet(), "low-limit", buffers,
        Semaphore(4))
      val clock = AtomicLong(0)
      val limit = ClockLimit(clock, LIMIT)
      val seed = Seed()
      val unchoked = CompletableDeferred<Unit>()
      try {
        coroutineScope {
          val seeding = launch {
            serve(listener, fixture, answers, rejects, buffers, clock, seed, unchoked)
          }
          val session = TorrentV2DownloadSession.open(this, fixture.document, fixture.layout,
            emptySet(), store,
            TorrentV2Runtime(network, ByteArray(20) { 1 }.toByteString(), buffers, state),
            TorrentV2SessionOptions(maxPeers = 4, throttle = { limit.acquire(it) },
              nowMs = { clock.load() },
              discovery = { sink ->
                sink.relay { peers ->
                  peers.send(listener.local)
                  awaitCancellation()
                }
              }))
          try {
            session.restore(null)
            session.resume()
            unchoked.await()
            body(session, clock, seed)
          } finally {
            withContext(NonCancellable) {
              session.close()
              seeding.cancelAndJoin()
            }
          }
        }
      } finally {
        withContext(NonCancellable) {
          listener.close()
          network.close()
          store.cleanup()
          torrentFileSystem.deleteRecursively(output, mustExist = false)
        }
      }
    }
  }

  /**
   * Seeds [fixture]'s single file to the first peer at [listener], answering its first [answers]
   * requests; past them it says nothing, or with [rejects] negotiates the Fast extension and
   * rejects each one.
   */
  private suspend fun serve(
    listener: TorrentListener,
    fixture: TorrentV2Fixture,
    answers: Int,
    rejects: Boolean,
    buffers: TorrentBufferBudget,
    clock: AtomicLong,
    seed: Seed,
    unchoked: CompletableDeferred<Unit>,
  ) {
    val connection = listener.accept()
    try {
      PeerIdentityHandshake(fixture.document.identity, fast = rejects).respond(connection,
        ByteArray(20) { 2 }.toByteString(), buffers)
      val pieceCount = fixture.layout.pieceCount.toInt()
      val wire = PeerWire(connection, pieceCount = pieceCount)
      wire.send(PeerMessage.Bitfield(ByteArray((pieceCount + 7) / 8) { byte ->
        // Every piece, high bit first.
        (0xFF shl (8 - minOf(8, pieceCount - byte * 8))).toByte()
      }))
      wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
      unchoked.complete(Unit)
      val payload = fixture.payloads[0]
      while (true) {
        val request = wire.read() as? PeerMessage.Request ?: continue
        seed.lastRequestAt.store(clock.load())
        seed.requestTimes.store(seed.requestTimes.load() + clock.load())
        if (seed.requests.incrementAndFetch() > answers) {
          if (rejects) wire.send(PeerMessage.Reject(request.index, request.begin, request.length))
          continue
        }
        val start = request.index * fixture.pieceLength + request.begin
        wire.send(PeerMessage.Piece(request.index, request.begin,
          payload.copyOfRange(start, start + request.length)))
      }
    } catch (_: IOException) {
      // The session closed the connection.
    } finally {
      connection.close()
    }
  }

  /** Moves [clock] on a step and wakes the loop; gives up past ten minutes. */
  private suspend fun step(session: TorrentV2DownloadSession, clock: AtomicLong) {
    delay(10)
    check(clock.addAndFetch(STEP_MS) < 600_000) { "Nothing happened within ten minutes" }
    session.uploadPolicyChanged()
  }

  private companion object {
    const val PIECE = 262_144
    const val BLOCKS = PIECE / 16_384
    const val SMALL_PIECE = 32_768
    const val LIMIT = 3_072L
    const val STEP_MS = 250L

    /** How much later than [TorrentStallWatchdog.NO_PAYLOAD_MS] the loop may show a stall. */
    const val CHECK_SLACK_MS = 5_000L
  }
}
