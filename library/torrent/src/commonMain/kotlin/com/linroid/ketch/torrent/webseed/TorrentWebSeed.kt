package com.linroid.ketch.torrent.webseed

import com.linroid.ketch.api.ConnectionDirection
import com.linroid.ketch.api.ConnectionRoute
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.core.engine.ConnectionHandle
import com.linroid.ketch.core.engine.ConnectionReporter
import com.linroid.ketch.core.engine.ConnectionSpec
import com.linroid.ketch.core.engine.HttpExchangeObserver
import com.linroid.ketch.torrent.describeWithoutUrls
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import com.linroid.ketch.torrent.net.TorrentHttp
import com.linroid.ketch.torrent.storage.CommitOutcome
import com.linroid.ketch.torrent.storage.TorrentPieceStore
import com.linroid.ketch.torrent.storage.TorrentStorageException
import com.linroid.ketch.torrent.v1.TorrentPieceScheduler
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.IOException
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.update

/** [range] of the file at [url], which fills a piece from [offset]. */
internal class WebSeedSpan(val url: String, val range: LongRange, val offset: Int)

/**
 * Maps pieces onto the files of one web seed (BEP 19). A single-file torrent's URL names the
 * file, or, ending in `/`, the folder holding it under the torrent's name. A multi-file torrent's
 * URL names the folder holding the torrent's own folder: each file is at the URL, the torrent
 * name and the file's path as the metainfo lists them, every segment percent-encoded. BEP 47
 * padding files are never requested: their spans are left out, as the zeros they hold.
 */
internal class WebSeedLayout(private val metadata: TorrentMetadata, base: String) {
  private val urls: List<String?> = if (!metadata.isMultiFile) {
    listOf(if (base.endsWith('/')) base + encodeWebSeedSegment(metadata.name) else base)
  } else {
    val folder = if (base.endsWith('/')) base else "$base/"
    // A multi-file path starts with the torrent name.
    metadata.files.map { file ->
      if (file.padding) null
      else folder + file.rawPath.joinToString("/", transform = ::encodeWebSeedSegment)
    }
  }

  /** Where each file starts in the torrent's byte stream. */
  private val starts = LongArray(metadata.files.size).also { starts ->
    var offset = 0L
    for (index in metadata.files.indices) {
      starts[index] = offset
      offset += metadata.files[index].size
    }
  }

  /**
   * The requests that fetch piece [index] of [size] bytes, in the order of its bytes; the bytes
   * of padding files they skip are zeros.
   */
  fun spans(index: Int, size: Int): List<WebSeedSpan> {
    val start = index * metadata.pieceLength
    val end = start + size
    // The last file starting at or before the piece, then every file it reaches into.
    var low = 0
    var high = starts.size - 1
    while (low < high) {
      val middle = (low + high + 1) / 2
      if (starts[middle] <= start) low = middle else high = middle - 1
    }
    var file = low
    val spans = mutableListOf<WebSeedSpan>()
    while (file < starts.size && starts[file] < end) {
      val fileStart = starts[file]
      val fileEnd = fileStart + metadata.files[file].size
      val from = maxOf(start, fileStart)
      val to = minOf(end, fileEnd)
      val url = urls[file]
      if (to > from && url != null) {
        spans += WebSeedSpan(url, (from - fileStart)..<(to - fileStart), (from - start).toInt())
      }
      file++
    }
    return spans
  }

  /** Every piece holding bytes of the file at [url]. */
  fun piecesOf(url: String): Set<Int> = buildSet {
    for (file in urls.indices) {
      val size = metadata.files[file].size
      if (urls[file] != url || size == 0L) continue
      val first = (starts[file] / metadata.pieceLength).toInt()
      val last = ((starts[file] + size - 1) / metadata.pieceLength).toInt()
      for (index in first..last) add(index)
    }
  }
}

/** Percent-encodes one path segment as UTF-8, keeping only unreserved characters. */
internal fun encodeWebSeedSegment(segment: String): String =
  encodeWebSeedSegment(segment.encodeToByteArray())

/** Percent-encodes the raw bytes of one path segment, keeping only unreserved characters. */
internal fun encodeWebSeedSegment(segment: ByteArray): String = buildString {
  for (byte in segment) {
    val code = byte.toInt() and 0xFF
    val char = code.toChar()
    if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-._~") append(char)
    else {
      append('%')
      append(HEX[code shr 4])
      append(HEX[code and 0xF])
    }
  }
}

private const val HEX = "0123456789ABCDEF"

/**
 * Web seed counters of one swarm, for its periodic summary and stall checks; any worker may
 * count. [wasted] is the part of [payload] thrown away: bytes of pieces that failed their hash
 * check or that a failed request cut off. [unsettled] is the part that may still be: bytes of
 * pieces not stored yet, counted before [payload] as they arrive and settled after [wasted].
 */
@OptIn(ExperimentalAtomicApi::class)
internal class WebSeedStats {
  val payload = AtomicLong(0)
  val wasted = AtomicLong(0)
  val unsettled = AtomicLong(0)
  val pieces = AtomicInt(0)
  val failures = AtomicInt(0)
  val dropped = AtomicInt(0)

  fun summary(running: Int, total: Int): String =
    "webSeeds=$running/$total (dropped=${dropped.load()}), webSeedPieces=${pieces.load()}, " +
      "webSeedPayload=${payload.load()}, webSeedWasted=${wasted.load()}, " +
      "webSeedFailures=${failures.load()}"
}

/**
 * A piece one web seed fetches into [bytes], which it owns until it releases the piece. They
 * start as zeros, which stand for the bytes no span asks for.
 */
internal interface WebSeedPiece {
  val index: Int
  val bytes: ByteArray
}

/**
 * Where a [WebSeedWorker]'s pieces come from and go: a v1 swarm's scheduler and store, or a v2
 * session loop and its store. Called from the worker's coroutine only.
 */
internal interface WebSeedPieces<P : WebSeedPiece> {
  /**
   * The next run to fetch: consecutive pieces in order, at most [WEB_SEED_RUN_PIECES] and
   * [WEB_SEED_RUN_BYTES], or empty when there is none to fetch now.
   */
  suspend fun claim(): List<P>

  /** Waits, the seed's connection closed, until [claim] may have a piece again. */
  suspend fun idle()

  /** The requests that fetch [piece], in the order of its bytes. */
  fun spans(piece: P): List<WebSeedSpan>

  /** Verifies [piece] and writes it once it does; an [IOException] is a storage failure. */
  suspend fun commit(piece: P): CommitOutcome

  /**
   * Gives [piece] back, [verified] when [commit] stored it; runs once for every claimed piece,
   * as soon as it is stored or else when its run ends, never cancelled.
   */
  suspend fun release(piece: P, verified: Boolean)

  /**
   * This seed cannot serve [indices]: their files are missing there or not the torrent's, as
   * when a mirror has since changed one. Later claims leave them to peers and other seeds until
   * the seed starts again, which asks for them once more, as its mirror may have them by then.
   */
  suspend fun unavailable(indices: Set<Int>)

  /** Every piece holding bytes of the file at [url]. */
  fun piecesOf(url: String): Set<Int>

  /**
   * Whether a wanted piece that is not verified yet is one this seed may still serve, whoever
   * fetches it now: an empty [claim] may only mean that the buffers are full.
   */
  suspend fun servable(): Boolean

  /** The seed stopped: dropped, or its transfer ended. Never cancelled. */
  suspend fun stop()
}

/**
 * Downloads runs of whole pieces from one web seed, one HTTP range request per file a run spans,
 * and verifies each piece like a peer's as soon as its last byte arrives, so the pieces before a
 * corrupt one or a dropped connection are kept and only the rest go back. [pieces] hands it
 * pieces nobody else fetches, except in the endgame. A piece it sends corrupt twice, or a file
 * it answers with an error that will not change (404, a different range), it no longer claims,
 * and fetches the rest; other failed requests back it off, doubling from [retryMs]. A failure
 * that may pass, such as a reset connection, a timeout or a busy server, first halves how many
 * requests it sends at once, down to one, as a server or proxy may refuse that many; each run
 * that works allows one more again. [MAX_FAILURES] failed runs in a row besides those, without a
 * piece verified between them, drop it until it is started again, as does having nothing left it
 * can serve before it served anything. Only [TorrentStorageException] escapes [run].
 *
 * One worker serves its seed for the whole transfer: [run] starts it again after it was
 * dropped, keeping what it learned. Started again, it is asked once more for what it could not
 * serve, as its mirror may have fixed it, and a piece it sent corrupt before is given up again
 * after one more corrupt copy.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class WebSeedWorker<P : WebSeedPiece>(
  private val url: String,
  private val pieces: WebSeedPieces<P>,
  private val http: TorrentHttp,
  private val stats: WebSeedStats,
  private val logLabel: String,
  private val reporter: ConnectionReporter = ConnectionReporter.None,
  private val retryMs: Long = WEB_SEED_RETRY_MS,
  private val idleTimeoutMs: Long = WEB_SEED_IDLE_MS,
  /** Counts payload as received, then rate-limits it, as for peers. */
  private val downloadPayload: suspend (Int) -> Unit = {},
  /**
   * Counts the payload [downloadPayload] counted that no verified piece keeps: pieces that fail
   * their hash check or that the selection dropped, and those a failed or stopped run gives back
   * partly or wholly fetched. Called once a run ends.
   */
  private val discardPayload: (Long) -> Unit = {},
) {
  private val log = KetchLogger("TorrentWebSeed")
  private val label = redactUrl(url)
  private var connection: ConnectionHandle? = null
  // Pieces this seed verified, as the requests of a run store them side by side.
  private val verifiedPieces = AtomicInt(0)
  // Kept from one [run] to the next: whether this seed verified a piece yet, and whether it found
  // pieces it cannot serve.
  private var served = false
  private var refused = false
  // Pieces it sent corrupt once, also kept: a second time, the copy it has is not the torrent's,
  // and once started again, the first time it does is enough.
  private val corrupted = mutableSetOf<Int>()
  // Requests for other files a run sends at once, also kept.
  private var parallel = PARALLEL_REQUESTS

  /** Fetches until the transfer cancels it or it is dropped; never twice at once. */
  suspend fun run() {
    // Failed runs in a row, which set the backoff, and of those the ones that drop the seed: all
    // but the ones that lowered its parallelism.
    var failures = 0
    var strikes = 0
    try {
      while (true) {
        val claimed = pieces.claim()
        if (claimed.isEmpty() && refused && !served && !pieces.servable()) {
          // Every piece it could still be asked for failed: a mirror without the torrent.
          stats.dropped.incrementAndFetch()
          log.i { "Dropped web seed $label of $logLabel: it served none of the pieces asked" }
          return
        }
        if (claimed.isEmpty()) {
          // Nothing left to fetch, or no buffer free: the selection may still grow.
          disconnect()
          pieces.idle()
          continue
        }
        val first = claimed.first().index
        val last = claimed.last().index
        // A piece given back leaves the run, so its buffer is unreachable once its lease is.
        val run: MutableList<P?> = claimed.toMutableList()
        val verifiedBefore = verifiedPieces.load()
        val failure = try {
          fetch(run)
          null
        } catch (e: CancellationException) {
          throw e
        } catch (e: TorrentStorageException) {
          throw e
        } catch (e: Exception) {
          e
        } finally {
          withContext(NonCancellable) {
            for (index in run.indices) {
              run[index]?.let { pieces.release(it, verified = false) }
              run[index] = null
            }
          }
        }
        val progressed = verifiedPieces.load() != verifiedBefore
        if (progressed) served = true
        if (failure == null) {
          failures = 0
          strikes = 0
          parallel = minOf(parallel + 1, PARALLEL_REQUESTS)
          continue
        }
        // A run that verified pieces before failing made progress.
        if (progressed) {
          failures = 0
          strikes = 0
        }
        failures++
        stats.failures.incrementAndFetch()
        disconnect()
        val unservable = unservable(failure).let { found ->
          when {
            failure is CorruptWebSeedPiece && corrupted.add(failure.index) -> emptySet()
            // A file the seed lacks, or has another version of, is the whole file's to skip:
            // its pieces past this run would only fail again.
            failure is WebSeedRequestFailure && found.isNotEmpty() ->
              found + pieces.piecesOf(failure.url)
            else -> found
          }
        }
        if (unservable.isNotEmpty()) {
          refused = true
          withContext(NonCancellable) { pieces.unavailable(unservable) }
        }
        // A failure that may pass sends fewer requests at once before it counts.
        if (unservable.isEmpty() && failure !is CorruptWebSeedPiece && parallel > 1) {
          parallel /= 2
        } else {
          strikes++
        }
        if (strikes >= MAX_FAILURES) {
          stats.dropped.incrementAndFetch()
          log.i {
            "Dropped web seed $label of $logLabel after $failures failures: " +
              failure.describeWithoutUrls()
          }
          return
        }
        if (unservable.isNotEmpty()) {
          // The rest of the torrent may still be fine: go on with it at once.
          log.i {
            "Web seed $label of $logLabel cannot serve ${unservable.size} piece(s) " +
              "(${unservable.min()}..${unservable.max()}): ${failure.describeWithoutUrls()}"
          }
          continue
        }
        val backoff = (retryMs shl (failures - 1)).coerceAtMost(WEB_SEED_MAX_BACKOFF_MS)
        log.d {
          "Web seed $label of $logLabel failed pieces $first..$last" +
            ", retrying in $backoff ms: ${failure.describeWithoutUrls()}"
        }
        delay(backoff)
      }
    } finally {
      disconnect()
      withContext(NonCancellable) { pieces.stop() }
    }
  }

  /** The [length] bytes of a response that fill run piece [piece] from [offset]. */
  private class Part(val piece: Int, val offset: Int, val length: Int)

  /** One range request of a run, from [first]; [last] grows as following spans join it. */
  private class Request(val url: String, val first: Long, var last: Long) {
    val parts = mutableListOf<Part>()
  }

  /** The spans of [run], those of a file that follow each other joined in one request. */
  private fun requests(run: List<P?>): List<Request> {
    val requests = mutableListOf<Request>()
    for ((piece, claim) in run.withIndex()) {
      if (claim == null) continue
      for (span in pieces.spans(claim)) {
        val previous = requests.lastOrNull()
        val request = if (previous != null && previous.url == span.url &&
          previous.last + 1 == span.range.first) {
          previous.also { it.last = span.range.last }
        } else Request(span.url, span.range.first, span.range.last).also { requests += it }
        request.parts += Part(piece, span.offset, (span.range.last - span.range.first + 1).toInt())
      }
    }
    return requests
  }

  /**
   * Fetches [run], storing each piece once its bytes are in and removing it from [run] once
   * given back; a corrupt piece or a failed request ends the run there. Once the first request
   * is answered, those for the other files go out [parallel] at a time: one at a time, a
   * run of many small files spent most of its time waiting for answers, and on mirrors that
   * redirect each file, as archive.org does, for two of them per file. A mirror that fails sees
   * one request per run. What arrived for the pieces a failed run gives back unstored counts as
   * [WebSeedStats.wasted]; until stored or thrown away, it is [WebSeedStats.unsettled]. Once the
   * run ends, whatever arrived that no verified piece keeps goes to [discardPayload].
   */
  private suspend fun fetch(run: MutableList<P?>) {
    val handle = connection ?: open().also { connection = it }
    val requests = requests(run)
    // The bytes each piece still waits for: padding is never asked for.
    val missing = IntArray(run.size)
    for (request in requests) for (part in request.parts) missing[part.piece] += part.length
    val asked = missing.copyOf()
    // What arrived for the run, of it what was stored and so settled, and of that what verified
    // pieces keep.
    val arrived = AtomicLong(0)
    val stored = AtomicLong(0)
    val verified = AtomicLong(0)
    try {
      for (piece in run.indices) if (run[piece] != null && missing[piece] == 0) store(run, piece)
      // A piece spanning files completes from whichever request brings its last part.
      val completion = Mutex()
      val received: suspend (Int, Int) -> Unit = { piece, length ->
        completion.withLock {
          missing[piece] -= length
          if (missing[piece] == 0) {
            if (store(run, piece)) verified.addAndFetch(asked[piece].toLong())
            stored.addAndFetch(asked[piece].toLong())
            stats.unsettled.addAndFetch(-asked[piece].toLong())
          }
        }
      }
      val first = requests.firstOrNull() ?: return
      fetch(handle, first, run, arrived, received)
      val slots = Semaphore(parallel)
      coroutineScope {
        for (request in requests.drop(1)) launch {
          slots.withPermit { fetch(handle, request, run, arrived, received) }
        }
      }
    } catch (e: Throwable) {
      // Every piece stored had all its bytes; the rest of what arrived is thrown away.
      val kept = run.indices.sumOf { if (run[it] == null) asked[it].toLong() else 0L }
      stats.wasted.addAndFetch((arrived.load() - kept).coerceAtLeast(0))
      throw e
    } finally {
      // The rest is settled once counted as wasted, so none of it looks kept meanwhile.
      stats.unsettled.addAndFetch(stored.load() - arrived.load())
      // Counted as it arrived, before the throttle, so a run stopped while it waits still counts
      // the chunk; a piece the selection dropped is discarded too.
      val discarded = arrived.load() - verified.load()
      if (discarded > 0) discardPayload(discarded)
    }
  }

  /**
   * Fetches [request] into [run]'s buffers, adding what comes to [arrived] and telling [received]
   * of each part as it fills.
   */
  private suspend fun fetch(
    handle: ConnectionHandle,
    request: Request,
    run: List<P?>,
    arrived: AtomicLong,
    received: suspend (piece: Int, length: Int) -> Unit,
  ) {
    val length = request.last - request.first + 1
    var total = 0L
    var part = 0
    var position = 0
    // What the callback threw, whatever the engine wraps it in.
    var thrown: Exception? = null
    try {
      observe(handle) {
        http.fetchRange(request.url, request.first..request.last, idleTimeoutMs) { bytes ->
          try {
            if (bytes.size > length - total) throw IOException("Web seed sent more than asked")
            total += bytes.size
            handle.received(bytes.size)
            // At risk until its piece is stored, which counts before it is payload.
            arrived.addAndFetch(bytes.size.toLong())
            stats.unsettled.addAndFetch(bytes.size.toLong())
            stats.payload.addAndFetch(bytes.size.toLong())
            downloadPayload(bytes.size)
            var read = 0
            while (read < bytes.size) {
              val target = request.parts[part]
              val count = minOf(bytes.size - read, target.length - position)
              val buffer = checkNotNull(run[target.piece]).bytes
              bytes.copyInto(buffer, target.offset + position, read, read + count)
              read += count
              position += count
              if (position == target.length) {
                part++
                position = 0
                received(target.piece, target.length)
              }
            }
          } catch (e: Exception) {
            if (e !is CancellationException) thrown = e
            throw e
          }
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      val failure = thrown ?: e
      if (failure is TorrentStorageException || failure is CorruptWebSeedPiece) throw failure
      // The pieces this request still owed, which a lasting error makes unservable.
      throw WebSeedRequestFailure(request.parts.drop(part).mapNotNull { run[it.piece]?.index }
        .toSet(), request.url, failure)
    }
    if (total != length) throw IOException("Web seed response ended early")
  }

  /**
   * Stores piece [piece] of [run] and gives it back, returning whether it verified; a corrupt
   * piece fails the run.
   */
  private suspend fun store(run: MutableList<P?>, piece: Int): Boolean {
    val claim = checkNotNull(run[piece])
    val outcome = webSeedStorage { pieces.commit(claim) }
    if (outcome == CommitOutcome.CORRUPT) {
      log.w { "Piece ${claim.index} of $logLabel from web seed $label failed its hash check" }
      throw CorruptWebSeedPiece(claim.index)
    }
    // A piece the selection dropped while it arrived goes back unstored.
    val verified = outcome == CommitOutcome.VERIFIED
    if (verified) {
      stats.pieces.incrementAndFetch()
      verifiedPieces.incrementAndFetch()
    }
    run[piece] = null
    withContext(NonCancellable) { pieces.release(claim, verified) }
    return verified
  }

  /** This seed among the task's live connections, while it fetches. */
  private fun open(): ConnectionHandle {
    if (reporter === ConnectionReporter.None) return ConnectionHandle.None
    val parsed = runCatching { Url(url) }.getOrNull()
    return reporter.open(ConnectionSpec(
      source = SOURCE,
      host = parsed?.host.orEmpty(),
      port = parsed?.port?.takeIf { it in 1..65535 },
      protocol = PROTOCOL,
      secure = url.startsWith("https://", ignoreCase = true),
      direction = ConnectionDirection.OUTGOING,
      // The engine may follow a system proxy; it reports the route when it knows it.
      route = ConnectionRoute.UNKNOWN,
    ))
  }

  /** Runs [block], whose requests describe [handle] by the hops the engine reports. */
  private suspend fun observe(handle: ConnectionHandle, block: suspend () -> Unit) {
    if (handle === ConnectionHandle.None) return block()
    withContext(HttpExchangeObserver { exchange ->
      handle.describe(ConnectionSpec(
        source = SOURCE,
        host = exchange.host,
        port = exchange.port,
        protocol = exchange.protocol ?: PROTOCOL,
        secure = exchange.secure,
        direction = ConnectionDirection.OUTGOING,
        route = exchange.route,
      ))
    }) { block() }
  }

  private fun disconnect() {
    connection?.close()
    connection = null
  }

  companion object {
    /**
     * Failed runs in a row, besides those that lowered its parallelism, after which a web seed is
     * dropped until it is started again.
     */
    const val MAX_FAILURES = 5

    /** The most requests one seed runs at once, each for another file. */
    const val PARALLEL_REQUESTS = 4

    private const val SOURCE = "torrent"
    private const val PROTOCOL = "HTTP"
  }
}

/** A web seed sent piece [index] with the wrong bytes. */
internal class CorruptWebSeedPiece(val index: Int) : IOException("Web seed sent a corrupt piece")

/** A request for [pieces] failed with [cause]. */
internal class WebSeedRequestFailure(
  val pieces: Set<Int>,
  /** The file the failed request asked for. */
  val url: String,
  override val cause: Throwable,
) : IOException(cause.message, cause)

/**
 * The pieces [failure] shows a web seed cannot serve: a corrupt one, or those of a request its
 * server answered with an error retrying cannot change. Empty for anything that may pass, such as
 * a dropped connection, a timeout or a busy server.
 */
internal fun unservable(failure: Throwable): Set<Int> = when (failure) {
  is CorruptWebSeedPiece -> setOf(failure.index)
  is WebSeedRequestFailure -> when (val cause = failure.cause) {
    // A missing file, or one whose length is no longer the torrent's.
    is KetchError.Http -> if (cause.code in UNSERVABLE_STATUSES) failure.pieces else emptySet()
    // A response that is not the range asked for, or longer.
    is KetchError.Unsupported -> failure.pieces
    else -> emptySet()
  }
  else -> emptySet()
}

private val UNSERVABLE_STATUSES = setOf(404, 410, 416)

/**
 * A v1 swarm's pieces for one web seed: to the [scheduler] it is a peer with every piece but those
 * it cannot serve that claims runs of them in order ([TorrentPieceScheduler.claimRun]), so it
 * never fetches what a peer or another seed fetches, except a single piece in the endgame. It
 * outlives the seed's worker: what the seed could not serve is kept when the worker stops.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class SwarmWebSeedPieces(
  private val id: Int,
  url: String,
  private val store: TorrentPieceStore,
  private val scheduler: TorrentPieceScheduler,
  private val onVerified: () -> Unit = {},
) : WebSeedPieces<TorrentPieceScheduler.Claim> {
  private val layout = WebSeedLayout(store.metadata, url)
  private var joined = false
  // The pieces the seed could not serve since it last joined: the swarm reads them while it
  // checks for a stall, also once the seed stopped.
  private val refused = AtomicReference(emptySet<Int>())

  /**
   * The pieces this seed could not serve since it last started, whether it runs or stopped: its
   * files are missing there or not the torrent's.
   */
  val unservable: Set<Int> get() = refused.load()

  /**
   * Tells the [scheduler] of this seed, which has every piece; [claim] does if it was not. Joining
   * again, it is asked once more for the pieces it could not serve, and forgets that it could not
   * until it fails again: its mirror may have them by now.
   */
  suspend fun join() {
    if (joined) return
    refused.store(emptySet())
    scheduler.availability(id, BooleanArray(store.pieceCount) { true })
    joined = true
  }

  override suspend fun claim(): List<TorrentPieceScheduler.Claim> {
    join()
    return scheduler.claimRun(id, WEB_SEED_RUN_PIECES, WEB_SEED_RUN_BYTES)
  }

  override suspend fun idle() = delay(WEB_SEED_IDLE_POLL_MS)

  override fun spans(piece: TorrentPieceScheduler.Claim): List<WebSeedSpan> =
    layout.spans(piece.index, piece.bytes.size)

  override suspend fun commit(piece: TorrentPieceScheduler.Claim): CommitOutcome =
    store.commit(piece.index, piece.bytes).also { outcome ->
      if (outcome == CommitOutcome.VERIFIED) {
        scheduler.verified(piece.index)
        onVerified()
      }
    }

  override suspend fun release(piece: TorrentPieceScheduler.Claim, verified: Boolean) {
    scheduler.release(id, piece.index)
  }

  override suspend fun unavailable(indices: Set<Int>) {
    refused.update { it + indices }
    scheduler.withdraw(id, indices)
  }

  override fun piecesOf(url: String): Set<Int> = layout.piecesOf(url)

  override suspend fun servable(): Boolean = scheduler.hasWanted(id)

  override suspend fun stop() {
    scheduler.remove(id)
    // Started again, it joins again.
    joined = false
  }
}

/** The most pieces one web seed run fetches. */
internal const val WEB_SEED_RUN_PIECES = 16

/** The most bytes the piece buffers of one web seed run hold. */
internal const val WEB_SEED_RUN_BYTES = 4 * 1024 * 1024

/** The first backoff of a failed web seed; each failure in a row doubles it. */
internal const val WEB_SEED_RETRY_MS = 2_000L

/** The longest a failed web seed waits before trying again. */
private const val WEB_SEED_MAX_BACKOFF_MS = 60_000L

/** How long a web seed response may send nothing before the request fails. */
private const val WEB_SEED_IDLE_MS = 30_000L

/** How often an idle web seed looks for a piece to fetch. */
private const val WEB_SEED_IDLE_POLL_MS = 250L

private suspend fun <T> webSeedStorage(block: suspend () -> T): T = try {
  block()
} catch (e: IOException) {
  throw TorrentStorageException(e)
}
