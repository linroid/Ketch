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
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndUpdate
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
 * check or that a failed request cut off. Stall checks take web seed payload from [checked], which
 * counts them: each byte [arrive]s after some number of checks, which tells the check that counts
 * it, and workers tally their pieces' bytes by it ([WebSeedArrivals]), so the bytes of a piece
 * stored ([keep]) or thrown away ([throwAway]) settle the checks that counted them, and no other.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class WebSeedStats {
  val payload = AtomicLong(0)
  val pieces = AtomicInt(0)
  val failures = AtomicInt(0)
  val dropped = AtomicInt(0)
  private val thrownAway = AtomicLong(0)
  // One value, so a stall check reads what changed and starts the next check at once.
  private val ledger = AtomicReference(WebSeedLedger(checks = 0, arrived = 0))

  /** The part of [payload] thrown away so far. */
  val wasted: Long get() = thrownAway.load()

  /**
   * [count] bytes arrived, which the next stall check counts; returns the checks before them, by
   * which [WebSeedArrivals] tally them.
   */
  fun arrive(count: Int): Long =
    ledger.fetchAndUpdate { it.copy(arrived = it.arrived + count) }.checks

  /** The bytes [arrivals] tally were stored: nothing throws them away any more. */
  fun keep(arrivals: List<WebSeedArrivals>) {
    val bytes = merged(arrivals)
    if (bytes.isNotEmpty()) ledger.update { it.copy(kept = it.kept.plusBytes(bytes)) }
  }

  /**
   * The bytes [arrivals] tally were thrown away. The ledger takes them before [wasted] counts
   * them, so a stall check made once [wasted] grew finds them.
   */
  fun throwAway(arrivals: List<WebSeedArrivals>) {
    val bytes = merged(arrivals)
    if (bytes.isEmpty()) return
    ledger.update { it.copy(wasted = it.wasted.plusBytes(bytes)) }
    thrownAway.addAndFetch(bytes.values.sum())
  }

  /** What changed since the last stall check, for one: the bytes arriving next come after it. */
  fun checked(): WebSeedLedger =
    ledger.fetchAndUpdate { WebSeedLedger(checks = it.checks + 1, arrived = 0) }

  fun summary(running: Int, total: Int): String =
    "webSeeds=$running/$total (dropped=${dropped.load()}), webSeedPieces=${pieces.load()}, " +
      "webSeedPayload=${payload.load()}, webSeedWasted=$wasted, " +
      "webSeedFailures=${failures.load()}"

  private fun merged(arrivals: List<WebSeedArrivals>): Map<Long, Long> =
    arrivals.fold(emptyMap()) { bytes, tally -> bytes.plusBytes(tally.byChecks) }

  private fun Map<Long, Long>.plusBytes(other: Map<Long, Long>): Map<Long, Long> {
    if (other.isEmpty()) return this
    val sum = HashMap(this)
    for ((checks, bytes) in other) sum[checks] = (sum[checks] ?: 0L) + bytes
    return sum
  }
}

/**
 * Web seed payload as a stall check takes it ([WebSeedStats.checked]): the bytes [arrived] after
 * [checks] checks, which this check counts, and of the bytes that arrived so far, those stored
 * ([kept]) or thrown away ([wasted]) since the last check, by the checks before they arrived.
 */
internal data class WebSeedLedger(
  val checks: Long,
  val arrived: Long,
  val kept: Map<Long, Long> = emptyMap(),
  val wasted: Map<Long, Long> = emptyMap(),
)

/**
 * The bytes of a piece, or of the part of it one request fills, by the stall checks before they
 * arrived ([WebSeedStats.arrive]). One writer at a time.
 */
internal class WebSeedArrivals {
  private val bytes = HashMap<Long, Long>()

  val byChecks: Map<Long, Long> get() = bytes

  /** [count] bytes arrived after [checks] stall checks. */
  fun add(checks: Long, count: Int) {
    bytes[checks] = (bytes[checks] ?: 0L) + count
  }
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
 * after one more corrupt copy. Until it answers again, sending payload or verifying or refusing a
 * piece, stall checks judge it by what it could not serve before ([unproven], [refusals]).
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
  // Whether it was dropped and has not answered since, and whether it was then dropped again so;
  // the swarm reads both.
  private val quiet = AtomicBoolean(false)
  private val gone = AtomicBoolean(false)
  // Whether it stopped and has not answered since, and what it could not serve as of its last
  // answer; the swarm reads both.
  private val stale = AtomicBoolean(false)
  private val refusedPieces = AtomicReference(emptySet<Int>())

  /**
   * Whether this seed was dropped, started again and dropped again, without answering in between
   * (sending payload, or verifying or refusing a piece): its mirror looks down rather than briefly
   * unavailable, so what it may serve is no evidence of anything until a later start finds it
   * answering, however soon its mirror is back.
   */
  val downAcrossRelaunch: Boolean get() = gone.load()

  /**
   * Whether this seed stopped, dropped or no longer needed, and has not answered since: started
   * again, it offers every piece but has yet to show what its mirror serves, so stall checks take
   * it as stopped, by its [refusals], until it does.
   */
  val unproven: Boolean get() = stale.load()

  /**
   * The pieces this seed could not serve as of its last answer: since it last started, or, while
   * it is [unproven], before. Stall checks read them, also once it stopped.
   */
  val refusals: Set<Int> get() = refusedPieces.load()

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
          dropped()
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
          // Once the swarm can see the refusal, so it never sees the seed answered without it.
          answered(unservable)
        }
        // A failure that may pass sends fewer requests at once before it counts.
        if (unservable.isEmpty() && failure !is CorruptWebSeedPiece && parallel > 1) {
          parallel /= 2
        } else {
          strikes++
        }
        if (strikes >= MAX_FAILURES) {
          dropped()
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
      // Before the swarm can see it stop: started again, it counts as stopped until it answers.
      stale.store(true)
      disconnect()
      withContext(NonCancellable) { pieces.stop() }
    }
  }

  /**
   * The [length] bytes of a response that fill run piece [piece] from [offset], and those that
   * arrived so far by the stall checks before them; only its request's coroutine adds to them.
   */
  private class Part(val piece: Int, val offset: Int, val length: Int) {
    val arrivals = WebSeedArrivals()
  }

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
   * one request per run. Each piece's bytes are tallied by the stall checks before they arrived
   * ([Part.arrivals]), and the piece tells [stats] they were kept once stored, or thrown away
   * ([WebSeedStats.wasted]) when the run fails without storing it. Once the run ends, whatever
   * arrived that no verified piece keeps goes to [discardPayload].
   */
  private suspend fun fetch(run: MutableList<P?>) {
    val handle = connection ?: open().also { connection = it }
    val requests = requests(run)
    // The bytes each piece still waits for: padding is never asked for.
    val missing = IntArray(run.size)
    for (request in requests) for (part in request.parts) missing[part.piece] += part.length
    val asked = missing.copyOf()
    val parts = requests.flatMap { it.parts }.groupBy { it.piece }
    fun arrivalsOf(piece: Int) = parts[piece].orEmpty().map { it.arrivals }
    // What arrived for the run, and of it what verified pieces keep.
    val arrived = AtomicLong(0)
    val verified = AtomicLong(0)
    try {
      for (piece in run.indices) if (run[piece] != null && missing[piece] == 0) store(run, piece)
      // A piece spanning files completes from whichever request brings its last part, after
      // every request filling it tallied its bytes.
      val completion = Mutex()
      val received: suspend (Int, Int) -> Unit = { piece, length ->
        completion.withLock {
          missing[piece] -= length
          if (missing[piece] == 0) {
            if (store(run, piece)) verified.addAndFetch(asked[piece].toLong())
            stats.keep(arrivalsOf(piece))
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
      // Every request has ended: what arrived for the pieces not stored is thrown away.
      stats.throwAway(run.indices.filter { run[it] != null }.flatMap(::arrivalsOf))
      throw e
    } finally {
      // Counted as it arrived, before the throttle, so a run stopped while it waits still counts
      // the chunk; a piece the selection dropped is discarded too.
      val discarded = arrived.load() - verified.load()
      if (discarded > 0) discardPayload(discarded)
    }
  }

  /**
   * Fetches [request] into [run]'s buffers, adding what comes to [arrived] and its parts' tallies,
   * and telling [received] of each part as it fills.
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
            // A mirror that sends data is not down.
            answered()
            handle.received(bytes.size)
            // In the next stall check's payload, and tallied by it in the parts it fills, before
            // anything suspends: a run stopped meanwhile throws away all of it.
            val checks = stats.arrive(bytes.size)
            arrived.addAndFetch(bytes.size.toLong())
            stats.payload.addAndFetch(bytes.size.toLong())
            var read = 0
            var filled = 0
            while (read < bytes.size) {
              val target = request.parts[part]
              val count = minOf(bytes.size - read, target.length - position)
              val buffer = checkNotNull(run[target.piece]).bytes
              bytes.copyInto(buffer, target.offset + position, read, read + count)
              target.arrivals.add(checks, count)
              read += count
              position += count
              if (position == target.length) {
                part++
                position = 0
                filled++
              }
            }
            downloadPayload(bytes.size)
            for (done in request.parts.subList(part - filled, part)) {
              received(done.piece, done.length)
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
      answered()
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

  /** The seed is dropped, before the swarm can see it stop: a second time if still [quiet]. */
  private fun dropped() {
    if (quiet.load()) gone.store(true)
    quiet.store(true)
  }

  /**
   * The seed sent payload, verified a piece or could not serve [refused]: its mirror answers.
   * Started again, what it could not serve before no longer counts.
   */
  private fun answered(refused: Set<Int> = emptySet()) {
    // Every chunk answers: one that changes nothing writes nothing. [gone] implies [quiet].
    if (refused.isEmpty() && !stale.load() && !quiet.load()) return
    // [refusals] before [unproven], so the swarm never sees the seed answered without them.
    if (stale.load()) refusedPieces.store(refused)
    else if (refused.isNotEmpty()) refusedPieces.update { it + refused }
    stale.store(false)
    quiet.store(false)
    gone.store(false)
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
 * never fetches what a peer or another seed fetches, except a single piece in the endgame. Its
 * [id] is the seed's in the [scheduler].
 */
internal class SwarmWebSeedPieces(
  val id: Int,
  url: String,
  private val store: TorrentPieceStore,
  private val scheduler: TorrentPieceScheduler,
  private val onVerified: () -> Unit = {},
) : WebSeedPieces<TorrentPieceScheduler.Claim> {
  private val layout = WebSeedLayout(store.metadata, url)
  private var joined = false

  /**
   * Tells the [scheduler] of this seed, which has every piece; [claim] does if it was not. Joining
   * again, it is asked once more for the pieces it could not serve: its mirror may have them by
   * now.
   */
  suspend fun join() {
    if (joined) return
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

  override suspend fun unavailable(indices: Set<Int>) = scheduler.withdraw(id, indices)

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
