package com.linroid.ketch.torrent.webseed

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.torrent.ClockLimit
import com.linroid.ketch.torrent.TorrentV2Fixture
import com.linroid.ketch.torrent.api.TorrentStall
import com.linroid.ketch.torrent.crypto.sha1Digest
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.engine.TorrentSessionState
import com.linroid.ketch.torrent.engine.TorrentStallWatchdog
import com.linroid.ketch.torrent.engine.describe
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentHttp
import com.linroid.ketch.torrent.net.createTorrentNetwork
import com.linroid.ketch.torrent.storage.TorrentPieceStore
import com.linroid.ketch.torrent.storage.TorrentV2PieceStore
import com.linroid.ketch.torrent.storage.torrentFileSystem
import com.linroid.ketch.torrent.v1.TorrentSwarm
import com.linroid.ketch.torrent.v2.TorrentV2DownloadSession
import com.linroid.ketch.torrent.v2.TorrentV2Runtime
import com.linroid.ketch.torrent.v2.TorrentV2SessionOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A download a low speed limit holds is no stalled one, however long the limit holds a chunk: a
 * web seed under a 3 KiB/s limit hands over a 256 KiB piece in one chunk, which waits about 85 s,
 * far longer than a stall allows without payload. Waits count as they pass, before anyone knows
 * whether the piece is kept, and are not taken back when it is not: a mirror that sends only
 * corrupt pieces under such a limit shows a stall once it is dropped, after
 * [WebSeedWorker.MAX_FAILURES] runs. Transfers run without peers on a test clock that moves on in
 * steps once the web seed is asked, faster than the swarms check themselves.
 */
@OptIn(ExperimentalAtomicApi::class)
class WebSeedLowLimitTest {
  @Test
  fun v1Swarm_heldByALowLimit_neverStalls() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        val payload = ByteArray(PIECE) { (it * 7 + 3).toByte() }
        val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf(
          "info" to mapOf("name" to "data", "length" to payload.size.toLong(),
            "piece length" to PIECE.toLong(), "pieces" to sha1Digest(payload)),
          "url-list" to URL,
        )))
        val clock = AtomicLong(0)
        val limit = ClockLimit(clock, LIMIT)
        val mirror = WholePieces(payload)
        val stall = MutableStateFlow<TorrentStall?>(null)
        withOutput { root ->
          val store = TorrentPieceStore(metadata, root / "data", emptySet(), "low-limit")
          val network = createTorrentNetwork()
          try {
            coroutineScope {
              val stalled = firstStall(stall)
              val swarm = launch {
                TorrentSwarm(store, network, TorrentBufferBudget(4 * 1024 * 1024),
                  downloadPayload = { limit.acquire(it) }, webSeedHttp = TorrentHttp(mirror),
                  nowMs = { clock.load() }, stall = stall)
                  .run(Channel<PeerEndpoint>().also { it.close() })
              }
              mirror.asked.await()
              while (swarm.isActive) step(clock)
              assertTrue(store.completed())
              assertNoStall(stalled)
            }
          } finally {
            network.close()
          }
        }
      }
    }
  }

  @Test
  fun v2Session_heldByALowLimit_neverStalls() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        val fixture = TorrentV2Fixture.build(listOf("data.bin" to PIECE), pieceLength = PIECE,
          webSeeds = listOf(URL))
        val clock = AtomicLong(0)
        val limit = ClockLimit(clock, LIMIT)
        val mirror = WholePieces(fixture.payloads[0])
        withOutput { root ->
          val buffers = TorrentBufferBudget(4 * 1024 * 1024)
          val state = TorrentBufferBudget(4 * 1024 * 1024)
          val network = createTorrentNetwork()
          val store = TorrentV2PieceStore(fixture.document, root, emptySet(), "low-limit",
            buffers, Semaphore(4))
          try {
            coroutineScope {
              val session = TorrentV2DownloadSession.open(this, fixture.document, fixture.layout,
                emptySet(), store,
                TorrentV2Runtime(network, ByteArray(20) { 1 }.toByteString(), buffers, state,
                  webSeedHttp = TorrentHttp(mirror)),
                TorrentV2SessionOptions(maxPeers = 4, discovery = {},
                  throttle = { limit.acquire(it) }, nowMs = { clock.load() }))
              try {
                val stalled = firstStall(session.stall)
                session.restore(null)
                session.resume()
                mirror.asked.await()
                while (session.state.value != TorrentSessionState.FINISHED &&
                  session.state.value != TorrentSessionState.STOPPED) step(clock)
                assertEquals(TorrentSessionState.FINISHED, session.state.value,
                  session.failure.value?.toString())
                assertNoStall(stalled)
              } finally {
                withContext(NonCancellable) { session.close() }
              }
            }
          } finally {
            withContext(NonCancellable) { network.close() }
          }
        }
      }
    }
  }

  @Test
  fun v1Swarm_corruptMirrorHeldByALowLimit_stallsOnceTheSeedIsDropped() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val payload = ByteArray(PIECES * PIECE) { (it * 7 + 3).toByte() }
        val hashes = (0 until PIECES).fold(ByteArray(0)) { hashes, index ->
          hashes + sha1Digest(payload.copyOfRange(index * PIECE, (index + 1) * PIECE))
        }
        val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf(
          "info" to mapOf("name" to "data", "length" to payload.size.toLong(),
            "piece length" to PIECE.toLong(), "pieces" to hashes),
          "url-list" to URL,
        )))
        val clock = AtomicLong(0)
        val limit = ClockLimit(clock, LIMIT)
        val mirror = WholePieces(otherBuild(payload))
        val stall = MutableStateFlow<TorrentStall?>(null)
        withOutput { root ->
          val store = TorrentPieceStore(metadata, root / "data", emptySet(), "corrupt-mirror")
          val network = createTorrentNetwork()
          // Discovery stays open, as an engine's does, so the swarm outlives its dropped seed.
          val peers = Channel<PeerEndpoint>()
          try {
            coroutineScope {
              val swarm = launch {
                TorrentSwarm(store, network, TorrentBufferBudget(4 * 1024 * 1024),
                  downloadPayload = { limit.acquire(it) }, webSeedHttp = TorrentHttp(mirror),
                  webSeedRetryMs = 1, nowMs = { clock.load() }, stall = stall)
                  .run(peers)
              }
              mirror.asked.await()
              while (stall.value == null) corruptStep(clock, limit)
              assertStalledOnceDropped(stall.value, clock.load(), mirror)
              swarm.cancel()
            }
          } finally {
            peers.close()
            network.close()
          }
        }
      }
    }
  }

  @Test
  fun v2Session_corruptMirrorHeldByALowLimit_stallsOnceTheSeedIsDropped() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val fixture = TorrentV2Fixture.build(listOf("data.bin" to PIECES * PIECE),
          pieceLength = PIECE, webSeeds = listOf(URL))
        val clock = AtomicLong(0)
        val limit = ClockLimit(clock, LIMIT)
        val mirror = WholePieces(otherBuild(fixture.payloads[0]))
        withOutput { root ->
          val buffers = TorrentBufferBudget(4 * 1024 * 1024)
          val state = TorrentBufferBudget(4 * 1024 * 1024)
          val network = createTorrentNetwork()
          val store = TorrentV2PieceStore(fixture.document, root, emptySet(), "corrupt-mirror",
            buffers, Semaphore(4))
          try {
            coroutineScope {
              // It waits for peers once its seed is dropped, as engine owners do.
              val session = TorrentV2DownloadSession.open(this, fixture.document, fixture.layout,
                emptySet(), store,
                TorrentV2Runtime(network, ByteArray(20) { 1 }.toByteString(), buffers, state,
                  webSeedHttp = TorrentHttp(mirror)),
                TorrentV2SessionOptions(maxPeers = 4, waitForPeers = true,
                  throttle = { limit.acquire(it) }, webSeedRetryMs = 1,
                  nowMs = { clock.load() }))
              try {
                session.restore(null)
                session.resume()
                mirror.asked.await()
                while (session.stall.value == null) {
                  corruptStep(clock, limit)
                  // Wakes the loop, so it checks itself every second of the clock.
                  session.uploadPolicyChanged()
                }
                assertStalledOnceDropped(session.stall.value, clock.load(), mirror)
              } finally {
                withContext(NonCancellable) { session.close() }
              }
            }
          } finally {
            withContext(NonCancellable) { network.close() }
          }
        }
      }
    }
  }

  /** The first stall [stall] publishes, collected from now on. */
  private fun CoroutineScope.firstStall(
    stall: StateFlow<TorrentStall?>,
  ): Deferred<TorrentStall> = async { stall.filterNotNull().first() }

  @OptIn(ExperimentalCoroutinesApi::class)
  private fun assertNoStall(stalled: Deferred<TorrentStall>) {
    assertFalse(stalled.isCompleted,
      "Stalled: ${if (stalled.isCompleted) stalled.getCompleted().describe() else ""}")
    stalled.cancel()
  }

  /**
   * The download [mirror], sending only corrupt pieces, holds stalled by [now], at the latest
   * [TorrentStallWatchdog.NO_PAYLOAD_MS] after the [WebSeedWorker.MAX_FAILURES] runs that drop
   * the mirror, in each of which the limit held a piece for about 85 s.
   */
  private fun assertStalledOnceDropped(stall: TorrentStall?, now: Long, mirror: WholePieces) {
    assertNotNull(stall)
    assertTrue(mirror.requests.load() <= WebSeedWorker.MAX_FAILURES,
      "Asked ${mirror.requests.load()} times before ${stall.describe()}")
    val bound = WebSeedWorker.MAX_FAILURES * PIECE * 1_000L / LIMIT +
      TorrentStallWatchdog.NO_PAYLOAD_MS + CHECK_SLACK_MS
    assertTrue(now <= bound, "Stalled at ${now / 1000} s, after ${bound / 1000} s")
  }

  /** Another build of [payload]: the same size, every piece different. */
  private fun otherBuild(payload: ByteArray) =
    ByteArray(payload.size) { (payload[it] + 1).toByte() }

  /**
   * Moves [clock] on a step of [ms], as the transfer waits on its limit, and gives up past ten
   * minutes.
   */
  private suspend fun step(clock: AtomicLong, ms: Long = STEP_MS) {
    delay(10)
    check(clock.addAndFetch(ms) < 600_000) { "Nothing happened within ten minutes" }
  }

  /**
   * A step while a corrupt mirror runs out its failures, minutes on the clock: long while the
   * [limit] holds a piece, short otherwise, so the swarms check themselves often once it is
   * dropped.
   */
  private suspend fun corruptStep(clock: AtomicLong, limit: ClockLimit) =
    step(clock, if (limit.waiting) HELD_STEP_MS else STEP_MS)

  private suspend fun withOutput(block: suspend (Path) -> Unit) {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-low-limit-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    try {
      block(root)
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  /** Serves [payload] at [URL], each piece of a range in one chunk. */
  private class WholePieces(private val payload: ByteArray) : HttpEngine {
    val asked = CompletableDeferred<Unit>()
    val requests = AtomicInt(0)

    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      error("unused")

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      check(url == URL) { "Asked for $url" }
      asked.complete(Unit)
      requests.incrementAndFetch()
      val wanted = range ?: 0L..payload.lastIndex.toLong()
      payload.copyOfRange(wanted.first.toInt(), wanted.last.toInt() + 1).asList().chunked(PIECE)
        .forEach { onData(it.toByteArray()) }
    }

    override fun close() = Unit
  }

  private companion object {
    const val URL = "http://mirror.test/data.bin"
    const val PIECE = 262_144
    const val LIMIT = 3_072L
    const val STEP_MS = 250L

    /** Pieces of a corrupt mirror's torrent: enough for [WebSeedWorker.MAX_FAILURES] runs. */
    const val PIECES = 3

    /** Steps while the limit holds a corrupt mirror's piece. */
    const val HELD_STEP_MS = 1_000L

    /**
     * How much later than the limit and [TorrentStallWatchdog.NO_PAYLOAD_MS] allow a stall may
     * show, as steps run past the end of each wait and swarms check themselves at their own pace.
     */
    const val CHECK_SLACK_MS = 30_000L
  }
}
