package com.linroid.ketch.torrent.webseed

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.core.engine.ConnectionReporter
import com.linroid.ketch.torrent.metainfo.TorrentContentLayout
import com.linroid.ketch.torrent.metainfo.TorrentV2Document
import com.linroid.ketch.torrent.net.TorrentHttp
import com.linroid.ketch.torrent.storage.CommitOutcome
import com.linroid.ketch.torrent.storage.TorrentStorageException
import com.linroid.ketch.torrent.storage.TorrentV2PieceStore
import com.linroid.ketch.torrent.v2.TorrentV2PieceScheduler
import com.linroid.ketch.torrent.v2.wantedPieces
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update

/**
 * Maps v2 and hybrid pieces onto the files of one web seed (BEP 19). A v2 piece lies in a single
 * file, so it is one range request, and padding is never asked for. A single-file torrent's URL
 * names the file, or, ending in `/`, the folder holding it; otherwise each file is at the URL, the
 * torrent name and the file's path, every segment percent-encoded, as for v1 ([WebSeedLayout]).
 */
internal class WebSeedV2Layout private constructor(
  private val layout: TorrentContentLayout,
  private val urls: Map<String, String>,
) {
  /** The request that fetches piece [index]. */
  fun span(index: Int): WebSeedSpan {
    val extent = layout.v2Piece(index.toLong())
    val url = urls.getValue(checkNotNull(extent.fileId))
    return WebSeedSpan(url, extent.fileOffset..<extent.fileOffset + extent.length, 0)
  }

  /** Every piece of the file at [url]. */
  fun piecesOf(url: String): Set<Int> = buildSet {
    for (file in layout.files) {
      if (urls[file.id] != url || file.length == 0L) continue
      val first = (file.offset / layout.pieceLength).toInt()
      val count = ((file.length - 1) / layout.pieceLength + 1).toInt()
      for (index in first until first + count) add(index)
    }
  }

  companion object {
    /** Null when a multi-file torrent has no name to find its files under. */
    fun of(
      document: TorrentV2Document,
      layout: TorrentContentLayout,
      base: String,
    ): WebSeedV2Layout? {
      val info = document.info
      // A hybrid says so in its v1 view; a v2 file tree holds a single file at its root.
      val single = document.hybrid?.singleFile
        ?: (info.files.size == 1 && info.files.single().path.size == 1)
      val folder = if (base.endsWith('/')) base else "$base/"
      val name = info.displayName
      if (!single && name == null) return null
      val urls = layout.files.associate { file ->
        val path = info.files[file.v2Index].path.map { encodeWebSeedSegment(it.toByteArray()) }
        file.id to when {
          single && base.endsWith('/') -> base + path.last()
          single -> base
          else -> folder + (listOf(encodeWebSeedSegment(checkNotNull(name).toByteArray())) +
            path).joinToString("/")
        }
      }
      return WebSeedV2Layout(layout, urls)
    }
  }
}

/**
 * The web seeds of one v2 or hybrid transfer. Each runs a [WebSeedWorker] whose pieces the session
 * loop hands out: a seed tells the loop it is idle through [updates], the loop claims a run of
 * pieces from its scheduler ([TorrentV2PieceScheduler.claimWebSeedRun]) and [assign]s it, and the
 * seed reports each piece [Event.Done] once the store verified and wrote it, or gave it back. The
 * store verifies a mirror's piece against the piece layers as it does a peer's. A seed that
 * stopped can be started again ([relaunch]). Only the session loop calls [assign], [reclaim] and
 * [relaunch].
 */
@OptIn(ExperimentalAtomicApi::class)
internal class TorrentV2WebSeeds private constructor(
  private val contentLayout: TorrentContentLayout,
  private val layouts: List<WebSeedV2Layout>,
  val urls: List<String>,
  private val store: TorrentV2PieceStore,
  private val http: TorrentHttp,
  private val reporter: ConnectionReporter,
  private val logLabel: String,
  private val retryMs: Long,
  /** Counts and rate-limits payload, as for peers. */
  private val downloadPayload: suspend (Int) -> Unit,
) {
  private val log = KetchLogger("TorrentWebSeed")
  val stats = WebSeedStats()

  /** Payload the seeds received and threw away so far ([WebSeedStats.wasted]). */
  val wastedBytes: Long get() = stats.wasted.load()

  /** Payload the seeds received that may still be thrown away ([WebSeedStats.unsettled]). */
  val unsettledBytes: Long get() = stats.unsettled.load()
  private val events = Channel<Event>(Channel.UNLIMITED)
  // A run no seed took goes back to the loop, which returns its pieces to the scheduler.
  private val assignments = urls.indices.map { seed ->
    Channel<List<TorrentV2PieceScheduler.WebSeedClaim>>(1, onUndeliveredElement = { run ->
      for (claim in run) {
        events.trySend(Event.Done(seed, claim.index, verified = false))
        claim.lease.close()
      }
    })
  }

  // The pieces each seed cannot serve, which its workers report and the loop reads: those it is
  // not asked for until it starts again, and all it could not serve since it last started, kept
  // once it stops.
  private val unservable = urls.indices.map { AtomicReference(emptySet<Int>()) }
  private val refused = urls.indices.map { AtomicReference(emptySet<Int>()) }

  // One worker per seed for the whole transfer: started again, a seed keeps what it learned.
  private val workers = urls.indices.map { seed ->
    WebSeedWorker(urls[seed], Pieces(seed), http, stats, logLabel, reporter, retryMs,
      downloadPayload = downloadPayload)
  }

  // Seeds started again after they stopped, which [run] launches.
  private val relaunches = Channel<Int>(Channel.UNLIMITED)

  /** What the seeds tell the session loop. */
  val updates: ReceiveChannel<Event> get() = events

  /**
   * Whether [seed] may be asked for piece [index]: false once it could not serve it, until it is
   * started again.
   */
  fun serves(seed: Int, index: Int): Boolean = index !in unservable[seed].load()

  /**
   * Whether [seed] could not serve piece [index] since it last started, whether it runs or
   * stopped.
   */
  fun refuses(seed: Int, index: Int): Boolean = index in refused[seed].load()

  sealed interface Event {
    /** [seed] waits for a piece. */
    class Idle(val seed: Int) : Event

    /** [seed] gave piece [index] back: [verified] once the store holds it. */
    class Done(val seed: Int, val index: Int, val verified: Boolean) : Event

    /** [seed] was dropped, or stopped with the transfer. */
    class Stopped(val seed: Int) : Event

    /** A seed could not write a piece; the transfer fails, as on a peer's storage failure. */
    class Failed(val cause: TorrentStorageException) : Event
  }

  // Whether a run was handed out yet: a transfer that only seeds never hands one out.
  private var assigned = false

  /** Hands [run] to [seed], which said it was idle; false when it cannot take it. */
  fun assign(seed: Int, run: List<TorrentV2PieceScheduler.WebSeedClaim>): Boolean {
    if (!assignments[seed].trySend(run).isSuccess) return false
    if (!assigned) {
      assigned = true
      log.i { "Transfer $logLabel: downloading from ${urls.size} web seed(s) too" }
    }
    return true
  }

  /**
   * The run handed to [seed] that it stopped before taking, for the loop to give back; empty
   * when there is none. The loop asks once it heard the seed [Event.Stopped].
   */
  fun reclaim(seed: Int): List<TorrentV2PieceScheduler.WebSeedClaim> =
    assignments[seed].tryReceive().getOrNull().orEmpty()

  /**
   * Starts [seed], which [Event.Stopped], again, as a stalled transfer asks: it may be asked once
   * more for every piece it could not serve, as its mirror may have them by now. False once the
   * seeds closed.
   */
  fun relaunch(seed: Int): Boolean {
    unservable[seed].store(emptySet())
    refused[seed].store(emptySet())
    return relaunches.trySend(seed).isSuccess
  }

  /** Runs every seed, and those [relaunch] starts again, until the transfer cancels them. */
  suspend fun run(): Unit = coroutineScope {
    for (seed in urls.indices) launchSeed(seed)
    for (seed in relaunches) {
      log.i { "Transfer $logLabel: starting web seed ${redactUrl(urls[seed])} again" }
      launchSeed(seed)
    }
  }

  private fun CoroutineScope.launchSeed(seed: Int) = launch {
    try {
      workers[seed].run()
    } catch (e: TorrentStorageException) {
      events.trySend(Event.Failed(e))
    }
  }

  /** Once [run] was cancelled: pieces still assigned go back to the budget. */
  fun close() {
    events.close()
    relaunches.close()
    assignments.forEach { it.cancel() }
  }

  /**
   * One seed's view: pieces arrive from the loop through its assignment queue. It serves the
   * seed's worker each time the seed starts.
   */
  private inner class Pieces(private val seed: Int) :
    WebSeedPieces<TorrentV2PieceScheduler.WebSeedClaim> {
    private val layout = layouts[seed]
    private var waiting = false
    private var next: List<TorrentV2PieceScheduler.WebSeedClaim>? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun claim(): List<TorrentV2PieceScheduler.WebSeedClaim> {
      next?.let {
        next = null
        return it
      }
      if (!waiting) {
        events.trySend(Event.Idle(seed))
        waiting = true
      }
      // A run usually follows at once; a seed left waiting closes its connection meanwhile.
      // A select either takes the run or times out, never both, so no claim is lost.
      val run = select<List<TorrentV2PieceScheduler.WebSeedClaim>?> {
        assignments[seed].onReceive { it }
        onTimeout(IDLE_CONNECTION_MS) { null }
      } ?: return emptyList()
      waiting = false
      return run
    }

    override suspend fun idle() {
      next = assignments[seed].receive()
      waiting = false
    }

    override fun spans(piece: TorrentV2PieceScheduler.WebSeedClaim): List<WebSeedSpan> =
      listOf(layout.span(piece.index))

    // The claim's buffer is this seed's alone and never changes once fetched.
    override suspend fun commit(piece: TorrentV2PieceScheduler.WebSeedClaim): CommitOutcome =
      store.commitOwned(piece.index, piece.bytes)

    override suspend fun release(piece: TorrentV2PieceScheduler.WebSeedClaim, verified: Boolean) {
      events.trySend(Event.Done(seed, piece.index, verified))
      piece.lease.close()
    }

    override suspend fun unavailable(indices: Set<Int>) {
      refused[seed].update { it + indices }
      unservable[seed].update { it + indices }
    }

    override fun piecesOf(url: String): Set<Int> = layout.piecesOf(url)

    override suspend fun servable(): Boolean {
      val verified = store.verifiedPieces()
      val wanted = wantedPieces(contentLayout, store.selectedIds(), verified.size)
      return verified.indices.any { wanted[it] && !verified[it] && serves(seed, it) }
    }

    override suspend fun stop() {
      // Started again, it tells the loop it is idle anew.
      waiting = false
      events.trySend(Event.Stopped(seed))
    }
  }

  companion object {
    /** How long an idle seed keeps its connection open for its next piece. */
    private const val IDLE_CONNECTION_MS = 1_000L

    /**
     * The web seeds of [document] fetched over [http]; null when it has none Ketch can map, so
     * the transfer runs without.
     */
    fun of(
      document: TorrentV2Document,
      layout: TorrentContentLayout,
      store: TorrentV2PieceStore,
      http: TorrentHttp?,
      reporter: ConnectionReporter,
      logLabel: String,
      retryMs: Long,
      downloadPayload: suspend (Int) -> Unit,
    ): TorrentV2WebSeeds? {
      if (http == null) return null
      val seeds = document.webSeeds.mapNotNull { url ->
        WebSeedV2Layout.of(document, layout, url)?.let { url to it }
      }
      if (seeds.isEmpty()) return null
      return TorrentV2WebSeeds(layout, seeds.map { it.second }, seeds.map { it.first }, store,
        http, reporter, logLabel, retryMs, downloadPayload)
    }
  }
}
