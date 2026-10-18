package com.linroid.ketch.core.mirror

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.core.digest.newDigest
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.SpeedLimiter
import com.linroid.ketch.core.engine.UrlAuthority
import com.linroid.ketch.core.engine.httpConnectionSpec
import com.linroid.ketch.core.engine.observeExchanges
import com.linroid.ketch.core.engine.through
import com.linroid.ketch.core.segment.SegmentCalculator
import com.linroid.ketch.core.segment.SegmentDownloader
import com.linroid.ketch.core.segment.SegmentedDownloadHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.time.TimeSource

/**
 * Downloads one file of known size from several HTTP(S) mirrors at once, for sources such as
 * Metalink that list them. The file is split into segments as an HTTP download is, sized from
 * [DownloadContext.effectiveConnections] and resegmented when it changes, and each segment is
 * fetched from the least busy mirror ([MirrorPool]). A segment whose mirror fails continues
 * from the byte it reached on another mirror; once every mirror but one is gone, failures go to
 * Ketch's retry policy as a plain HTTP download's do.
 *
 * With [PieceHashes], the finished file is checked piece by piece: the mirrors that wrote a
 * piece that does not match are dropped while another remains, and the failed pieces are
 * fetched again, at most [MAX_REPAIRS] times. Ketch then verifies the whole file against
 * [DownloadContext.publishedChecksum] or the request's checksum, as for any download.
 *
 * Requests go through the task's proxy ([DownloadContext.config]). The request headers go only
 * to mirrors on the origin of [DownloadContext.url]; others get `User-Agent`, `Accept`,
 * `Accept-Language` and `Accept-Encoding` alone.
 *
 * @param type the source type, for errors and live connections
 * @param timeSource measures mirror backoff; tests pass a virtual one
 */
class MirrorDownloadHelper(
  private val http: HttpEngine,
  private val type: String,
  private val timeSource: TimeSource = TimeSource.Monotonic,
) {
  private val log = KetchLogger("Mirrors")

  /**
   * Downloads [totalBytes] bytes from [mirrors], continuing from [DownloadContext.segments]
   * when they describe a file of that size, else from the beginning.
   *
   * @throws KetchError.SourceError when there is no mirror or no size, or pieces keep failing
   *   verification
   */
  suspend fun download(
    context: DownloadContext,
    mirrors: List<Mirror>,
    totalBytes: Long,
    pieces: PieceHashes? = null,
  ) {
    if (totalBytes <= 0) throw KetchError.SourceError(type, detail = "The file size is unknown")
    if (mirrors.isEmpty()) throw KetchError.SourceError(type, detail = "No usable mirror")
    val checked = pieces?.takeIf { it.covers(totalBytes) }
    if (pieces != null && checked == null) {
      log.w {
        "Piece hashes of taskId=${context.taskId} do not cover $totalBytes bytes; " +
          "checking the whole file only"
      }
    }
    val pool = MirrorPool(mirrors, timeSource)
    val engine = http.through(context.config.proxy)
    val spans = Spans()
    log.i {
      "Downloading taskId=${context.taskId} from ${mirrors.size} mirrors, totalBytes=$totalBytes"
    }

    val requested = context.maxConnections.value
    val layout = prepare(context, totalBytes, context.effectiveConnections(requested))
    transfer(context, engine, pool, spans, layout, totalBytes, requested)
    checked ?: return

    var repairs = 0
    while (true) {
      val bad = badPieces(context, checked, totalBytes) ?: return
      if (bad.isEmpty()) {
        log.i { "Verified ${checked.hashes.size} pieces of taskId=${context.taskId}" }
        return
      }
      val ranges = mergedRanges(bad, checked.length, totalBytes)
      val dropped = pool.blame(spans.overlapping(ranges))
      log.w {
        "${bad.size} of ${checked.hashes.size} pieces of taskId=${context.taskId} failed " +
          "${checked.algorithm.label} verification; dropped mirrors $dropped"
      }
      if (repairs++ == MAX_REPAIRS) {
        throw KetchError.SourceError(
          type,
          detail = "${bad.size} pieces kept failing verification on every mirror",
        )
      }
      spans.clear()
      // A batch runs every incomplete segment at once, so at most one per connection.
      for (group in ranges.chunked(context.effectiveConnections().coerceAtLeast(1))) {
        val repair = cover(group, totalBytes)
        context.segments.value = repair
        transfer(context, engine, pool, spans, repair, totalBytes, context.maxConnections.value)
      }
    }
  }

  /**
   * The segments to start from: [DownloadContext.segments] when they cover [totalBytes] and the
   * file holds them, resegmented for [connections], else a fresh layout over a preallocated file.
   */
  private suspend fun prepare(
    context: DownloadContext,
    totalBytes: Long,
    connections: Int,
  ): List<Segment> {
    val existing = context.segments.value
    val fits = existing.isNotEmpty() && existing.first().start == 0L &&
      existing.last().end == totalBytes - 1 &&
      existing.zipWithNext().all { (a, b) -> a.end + 1 == b.start }
    var segments = if (fits) existing else emptyList()
    val fileSize = if (fits) disk { context.fileAccessor.size() } else 0L
    if (segments.isEmpty() || fileSize < totalBytes) {
      if (segments.any { it.downloadedBytes > 0 }) {
        log.w { "File of taskId=${context.taskId} is shorter than its progress; starting over" }
      }
      disk { context.fileAccessor.preallocate(totalBytes) }
      segments = SegmentCalculator.calculateSegments(totalBytes, connections.coerceAtLeast(1))
    } else {
      val incomplete = segments.count { !it.isComplete }
      if (incomplete > 0 && incomplete != connections) {
        segments = SegmentCalculator.resegment(segments, connections.coerceAtLeast(1))
      }
    }
    context.segments.value = segments
    return segments
  }

  private suspend fun transfer(
    context: DownloadContext,
    engine: HttpEngine,
    pool: MirrorPool,
    spans: Spans,
    segments: List<Segment>,
    totalBytes: Long,
    requested: Int,
  ) {
    val helper = SegmentedDownloadHelper(context.config.progressIntervalMs, tag = "Mirrors")
    val throttle = object : SpeedLimiter {
      override suspend fun acquire(bytes: Int) {
        context.throttle(bytes)
      }
    }
    helper.downloadAll(context, segments, totalBytes, true, requested) { lane ->
      // A connection that finishes early may take over this lane's tail, whichever mirror it
      // fetches from; the lane then completes and the transfer ends by throwing.
      while (!lane.isComplete) {
        currentCoroutineContext().ensureActive()
        val index = pool.acquire()
        val mirror = pool.mirror(index)
        val from = lane.offset
        val spec = httpConnectionSpec(type, mirror.url, context.config.proxy)
        val handle = context.connections.open(spec)
        try {
          val downloader = SegmentDownloader(
            engine, context.fileAccessor, throttle, SpeedLimiter.Unlimited, context.taskId,
            context.watchdog,
          )
          val headers = mirrorHeaders(context.url, mirror.url, context.headers)
          observeExchanges(handle, spec) {
            downloader.download(mirror.url, lane, headers, handle)
          }
          pool.succeeded(index)
        } catch (e: CancellationException) {
          throw e
        } catch (e: KetchError.Disk) {
          throw e
        } catch (e: KetchError.Canceled) {
          throw e
        } catch (e: Exception) {
          // Another connection took the rest of the lane: not the mirror's fault.
          if (lane.isComplete) throw e
          if (!pool.failed(index, e)) throw e
          log.w {
            "Mirror #$index failed for taskId=${context.taskId} at byte ${lane.offset}: " +
              "${e.describeCauses()}; trying another"
          }
        } finally {
          handle.close()
          withContext(NonCancellable) {
            spans.add(from, lane.offset, index)
            pool.release(index)
          }
        }
      }
    }
  }

  /**
   * The indices of [pieces] the file does not match, or `null` when the file cannot be read
   * back, as some Android documents cannot.
   */
  private suspend fun badPieces(
    context: DownloadContext,
    pieces: PieceHashes,
    totalBytes: Long,
  ): List<Int>? {
    val buffer = ByteArray(minOf(pieces.length, READ_SIZE.toLong()).toInt())
    val bad = mutableListOf<Int>()
    try {
      for ((index, expected) in pieces.hashes.withIndex()) {
        val start = index * pieces.length
        val end = minOf(start + pieces.length, totalBytes)
        val digest = pieces.algorithm.newDigest()
        var offset = start
        while (offset < end) {
          yield()
          val count = minOf(buffer.size.toLong(), end - offset).toInt()
          val read = disk { context.fileAccessor.readAt(offset, buffer, 0, count) }
          if (read <= 0) break
          digest.update(buffer, 0, read)
          offset += read
        }
        if (offset < end || !expected.matches(digest.digest())) bad += index
      }
    } catch (_: UnsupportedOperationException) {
      log.w { "Cannot read the file of taskId=${context.taskId} back; skipping piece checks" }
      return null
    }
    return bad
  }

  private suspend fun <T> disk(action: suspend () -> T): T = try {
    action()
  } catch (e: CancellationException) {
    throw e
  } catch (e: UnsupportedOperationException) {
    throw e
  } catch (e: KetchError) {
    throw e
  } catch (e: Exception) {
    throw KetchError.Disk(e)
  }

  /** Which mirror wrote which bytes during this round, to blame the pieces that fail. */
  private class Spans {
    private val mutex = Mutex()
    private val spans = mutableListOf<Triple<Long, Long, Int>>()

    suspend fun add(start: Long, endExclusive: Long, mirror: Int) {
      if (endExclusive > start) mutex.withLock { spans += Triple(start, endExclusive, mirror) }
    }

    suspend fun clear() = mutex.withLock { spans.clear() }

    suspend fun overlapping(ranges: List<LongRange>): Set<Int> = mutex.withLock {
      spans.filter { (start, end, _) ->
        ranges.any { it.first < end && start <= it.last }
      }.mapTo(LinkedHashSet()) { it.third }
    }
  }

  companion object {
    /** Times failed pieces are fetched again before the download fails. */
    const val MAX_REPAIRS: Int = 3

    private const val READ_SIZE = 256 * 1024
  }
}

/** The byte ranges of [pieces], joined where they follow each other. */
internal fun mergedRanges(pieces: List<Int>, length: Long, totalBytes: Long): List<LongRange> {
  val ranges = mutableListOf<LongRange>()
  for (piece in pieces.sorted()) {
    val start = piece * length
    val end = minOf(start + length, totalBytes) - 1
    val last = ranges.lastOrNull()
    if (last != null && last.last + 1 == start) {
      ranges[ranges.lastIndex] = last.first..end
    } else {
      ranges += start..end
    }
  }
  return ranges
}

/**
 * Segments over [totalBytes] where [ranges], sorted and apart, are still to fetch and every
 * other byte is done.
 */
internal fun cover(ranges: List<LongRange>, totalBytes: Long): List<Segment> {
  val segments = mutableListOf<Segment>()
  fun add(start: Long, end: Long, done: Boolean) {
    segments += Segment(segments.size, start, end, if (done) end - start + 1 else 0)
  }
  var cursor = 0L
  for (range in ranges) {
    if (range.first > cursor) add(cursor, range.first - 1, done = true)
    add(range.first, range.last, done = false)
    cursor = range.last + 1
  }
  if (cursor < totalBytes) add(cursor, totalBytes - 1, done = true)
  return segments
}

/**
 * [headers] for a request to [to] that a download of [from] sends: all of them on the same
 * origin, else only those safe to send anywhere.
 */
fun mirrorHeaders(
  from: String,
  to: String,
  headers: Map<String, String>,
): Map<String, String> {
  val a = UrlAuthority.parse(from)
  val b = UrlAuthority.parse(to)
  if (a != null && b != null && a.scheme == b.scheme && a.host == b.host && a.port == b.port) {
    return headers
  }
  return headers.filterKeys { it.lowercase() in SAFE_HEADERS }
}

private val SAFE_HEADERS = setOf("user-agent", "accept", "accept-language", "accept-encoding")
