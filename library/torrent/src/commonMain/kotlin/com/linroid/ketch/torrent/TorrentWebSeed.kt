package com.linroid.ketch.torrent

import com.linroid.ketch.api.ConnectionDirection
import com.linroid.ketch.api.ConnectionRoute
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.core.engine.ConnectionHandle
import com.linroid.ketch.core.engine.ConnectionReporter
import com.linroid.ketch.core.engine.ConnectionSpec
import com.linroid.ketch.core.engine.HttpExchangeObserver
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okio.IOException
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/** [range] of the file at [url], which fills a piece from [offset]. */
internal class WebSeedSpan(val url: String, val range: LongRange, val offset: Int)

/**
 * Maps pieces onto the files of one web seed (BEP 19). A single-file torrent's URL names the
 * file, or, ending in `/`, the folder holding it under the torrent's name. A multi-file torrent's
 * URL names the folder holding the torrent's own folder: each file is at the URL, the torrent
 * name and the file's path, every segment percent-encoded.
 */
internal class WebSeedLayout(private val metadata: TorrentMetadata, base: String) {
  private val urls: List<String> = if (!metadata.isMultiFile) {
    listOf(if (base.endsWith('/')) base + encodeWebSeedSegment(metadata.name) else base)
  } else {
    val folder = if (base.endsWith('/')) base else "$base/"
    // A multi-file path starts with the torrent name.
    metadata.files.map { file ->
      folder + file.path.split('/').joinToString("/", transform = ::encodeWebSeedSegment)
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

  /** The requests that fetch piece [index] of [size] bytes, in the order of its bytes. */
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
      if (to > from) {
        spans += WebSeedSpan(urls[file], (from - fileStart)..<(to - fileStart),
          (from - start).toInt())
      }
      file++
    }
    return spans
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

/** Web seed counters of one swarm, for its periodic summary; any worker may count. */
@OptIn(ExperimentalAtomicApi::class)
internal class WebSeedStats {
  val payload = AtomicLong(0)
  val pieces = AtomicInt(0)
  val failures = AtomicInt(0)
  val dropped = AtomicInt(0)

  fun summary(running: Int, total: Int): String =
    "webSeeds=$running/$total (dropped=${dropped.load()}), webSeedPieces=${pieces.load()}, " +
      "webSeedPayload=${payload.load()}, webSeedFailures=${failures.load()}"
}

/** A piece one web seed fetches into [bytes], which it owns until it releases the piece. */
internal interface WebSeedPiece {
  val index: Int
  val bytes: ByteArray
}

/**
 * Where a [WebSeedWorker]'s pieces come from and go: a v1 swarm's scheduler and store, or a v2
 * session loop and its store. Called from the worker's coroutine only.
 */
internal interface WebSeedPieces<P : WebSeedPiece> {
  /** The next piece to fetch, or null when there is none to fetch now. */
  suspend fun claim(): P?

  /** Waits, the seed's connection closed, until [claim] may have a piece again. */
  suspend fun idle()

  /** The requests that fetch [piece], in the order of its bytes. */
  fun spans(piece: P): List<WebSeedSpan>

  /** Verifies [piece] and writes it once it does; an [IOException] is a storage failure. */
  suspend fun commit(piece: P): CommitOutcome

  /** Gives [piece] back, stored or not; runs once for every claimed piece, never cancelled. */
  suspend fun release(piece: P)

  /** The seed stopped: dropped, or its transfer ended. Never cancelled. */
  suspend fun stop()
}

/**
 * Downloads whole pieces from one web seed, one HTTP range request per file a piece spans, and
 * verifies each like a peer's. [pieces] hands it pieces nobody else fetches, except in the
 * endgame. A failed request or piece backs it off, doubling from [retryMs]; [MAX_FAILURES]
 * failures in a row drop it for the rest of the run. Only [TorrentStorageException] escapes
 * [run].
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
  /** Counts and rate-limits payload, as for peers. */
  private val downloadPayload: suspend (Int) -> Unit = {},
) {
  private val log = KetchLogger("TorrentWebSeed")
  private val label = redactUrl(url)
  private var connection: ConnectionHandle? = null

  suspend fun run() {
    var failures = 0
    try {
      while (true) {
        val claim = pieces.claim()
        if (claim == null) {
          // Nothing left to fetch, or no buffer free: the selection may still grow.
          disconnect()
          pieces.idle()
          continue
        }
        val failure = try {
          fetch(claim)
          null
        } catch (e: CancellationException) {
          throw e
        } catch (e: TorrentStorageException) {
          throw e
        } catch (e: Exception) {
          e
        } finally {
          withContext(NonCancellable) { pieces.release(claim) }
        }
        if (failure == null) {
          failures = 0
          continue
        }
        failures++
        stats.failures.incrementAndFetch()
        disconnect()
        if (failures >= MAX_FAILURES) {
          stats.dropped.incrementAndFetch()
          log.i {
            "Dropped web seed $label of $logLabel after $failures failures: " +
              failure.describeWithoutUrls()
          }
          return
        }
        val backoff = (retryMs shl (failures - 1)).coerceAtMost(WEB_SEED_MAX_BACKOFF_MS)
        log.d {
          "Web seed $label of $logLabel failed piece ${claim.index}, retrying in $backoff ms: " +
            failure.describeWithoutUrls()
        }
        delay(backoff)
      }
    } finally {
      disconnect()
      withContext(NonCancellable) { pieces.stop() }
    }
  }

  private suspend fun fetch(claim: P) {
    val handle = connection ?: open().also { connection = it }
    for (span in pieces.spans(claim)) {
      var position = span.offset
      val end = span.offset + (span.range.last - span.range.first + 1).toInt()
      observe(handle) {
        http.fetchRange(span.url, span.range, idleTimeoutMs) { bytes ->
          if (bytes.size > end - position) throw IOException("Web seed sent more than asked")
          bytes.copyInto(claim.bytes, position)
          position += bytes.size
          handle.received(bytes.size)
          stats.payload.addAndFetch(bytes.size.toLong())
          downloadPayload(bytes.size)
        }
      }
      if (position != end) throw IOException("Web seed response ended early")
    }
    when (webSeedStorage { pieces.commit(claim) }) {
      CommitOutcome.VERIFIED -> stats.pieces.incrementAndFetch()
      // The selection dropped the piece while it arrived.
      CommitOutcome.NOT_WANTED -> Unit
      CommitOutcome.CORRUPT -> {
        log.w { "Piece ${claim.index} of $logLabel from web seed $label failed its hash check" }
        throw IOException("Web seed sent a corrupt piece")
      }
    }
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
    /** Failures in a row after which a web seed is dropped for the rest of the run. */
    const val MAX_FAILURES = 5

    private const val SOURCE = "torrent"
    private const val PROTOCOL = "HTTP"
  }
}

/**
 * A v1 swarm's pieces for one web seed: to the [scheduler] it is a peer with every piece that
 * claims them in order, so it never fetches what a peer or another seed fetches, except in the
 * endgame.
 */
internal class SwarmWebSeedPieces(
  private val id: Int,
  url: String,
  private val store: TorrentPieceStore,
  private val scheduler: TorrentPieceScheduler,
  private val onVerified: () -> Unit = {},
) : WebSeedPieces<TorrentPieceScheduler.Claim> {
  private val layout = WebSeedLayout(store.metadata, url)
  private var joined = false

  override suspend fun claim(): TorrentPieceScheduler.Claim? {
    if (!joined) {
      scheduler.availability(id, BooleanArray(store.pieceCount) { true })
      joined = true
    }
    return scheduler.claim(id, sequential = true)
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

  override suspend fun release(piece: TorrentPieceScheduler.Claim) {
    scheduler.release(id)
  }

  override suspend fun stop() {
    scheduler.remove(id)
  }
}

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
