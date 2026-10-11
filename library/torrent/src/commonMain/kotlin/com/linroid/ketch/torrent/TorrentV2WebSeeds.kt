package com.linroid.ketch.torrent

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.core.engine.ConnectionReporter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

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
 * store verifies a mirror's piece against the piece layers as it does a peer's.
 */
internal class TorrentV2WebSeeds private constructor(
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

  /** What the seeds tell the session loop. */
  val updates: ReceiveChannel<Event> get() = events

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

  /** Hands [run] to [seed], which said it was idle; false when it cannot take it. */
  fun assign(seed: Int, run: List<TorrentV2PieceScheduler.WebSeedClaim>): Boolean =
    assignments[seed].trySend(run).isSuccess

  /** Runs every seed until they are all dropped or the transfer cancels them. */
  suspend fun run(): Unit = coroutineScope {
    log.i { "Transfer $logLabel: downloading from ${urls.size} web seed(s) too" }
    for ((seed, url) in urls.withIndex()) {
      launch {
        try {
          WebSeedWorker(url, Pieces(seed), http, stats, logLabel, reporter, retryMs,
            downloadPayload = downloadPayload).run()
        } catch (e: TorrentStorageException) {
          events.trySend(Event.Failed(e))
        }
      }
    }
  }

  /** Once [run] returned: pieces still assigned go back to the budget. */
  fun close() {
    events.close()
    assignments.forEach { it.cancel() }
  }

  /** One seed's view: pieces arrive from the loop through its assignment queue. */
  private inner class Pieces(private val seed: Int) :
    WebSeedPieces<TorrentV2PieceScheduler.WebSeedClaim> {
    private val layout = layouts[seed]
    private var waiting = false
    private var next: List<TorrentV2PieceScheduler.WebSeedClaim>? = null

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

    override suspend fun stop() {
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
      return TorrentV2WebSeeds(seeds.map { it.second }, seeds.map { it.first }, store, http,
        reporter, logLabel, retryMs, downloadPayload)
    }
  }
}
