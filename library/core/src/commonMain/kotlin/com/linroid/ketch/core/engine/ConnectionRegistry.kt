@file:OptIn(ExperimentalAtomicApi::class)

package com.linroid.ketch.core.engine

import com.linroid.ketch.api.ActiveConnection
import com.linroid.ketch.api.ActiveConnections
import com.linroid.ketch.api.ConnectionDirection
import com.linroid.ketch.api.ConnectionRoute
import com.linroid.ketch.api.PeerDetails
import com.linroid.ketch.api.Proxy
import com.linroid.ketch.api.ProxyAddress
import com.linroid.ketch.api.log.KetchLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * What a source knows about a connection when it opens one. [toString] leaves [host] out, so a
 * spec can be logged.
 *
 * @property source the [DownloadSource.type] the connection transfers for
 * @property host the remote host name or IP literal, IPv6 without brackets
 * @property port the remote port, or `null` when unknown
 * @property protocol a display label such as `"HTTP/1.1"` or `"FTP"`, or `null` when unknown
 * @property secure whether the transport is TLS, or `null` when unknown
 * @property direction which side opened the connection
 * @property route how the connection reaches [host]
 * @property peer torrent peer details, `null` for other sources
 */
data class ConnectionSpec(
  val source: String,
  val host: String,
  val port: Int?,
  val protocol: String?,
  val secure: Boolean?,
  val direction: ConnectionDirection = ConnectionDirection.OUTGOING,
  val route: ConnectionRoute = ConnectionRoute.UNKNOWN,
  val peer: PeerDetails? = null,
) {
  override fun toString(): String =
    "ConnectionSpec(source=$source, protocol=$protocol, secure=$secure, " +
      "direction=$direction, route=$route)"
}

/**
 * Opens the connections of one task, reported by
 * [com.linroid.ketch.api.Interest.Connections]. Valid for the task's lifetime, not one run.
 */
interface ConnectionReporter {
  /** Registers a connection described by [spec]; close the handle when the connection ends. */
  fun open(spec: ConnectionSpec): ConnectionHandle

  companion object {
    /** Reports nothing; [open] returns [ConnectionHandle.None]. */
    val None: ConnectionReporter = object : ConnectionReporter {
      override fun open(spec: ConnectionSpec): ConnectionHandle = ConnectionHandle.None
    }
  }
}

/**
 * The counters of one open connection. Every method is safe to call from any thread, never
 * suspends and, apart from [describe] and [close], never allocates.
 */
interface ConnectionHandle {
  /** Counts [bytes] of payload received. */
  fun received(bytes: Int)

  /** Counts [bytes] of payload sent. */
  fun sent(bytes: Int)

  /** Replaces what is known about the connection, such as the final host after redirects. */
  fun describe(spec: ConnectionSpec)

  /** Ends the connection; later calls do nothing. */
  fun close()

  companion object {
    /** A handle that counts nothing. */
    val None: ConnectionHandle = object : ConnectionHandle {
      override fun received(bytes: Int) {}
      override fun sent(bytes: Int) {}
      override fun describe(spec: ConnectionSpec) {}
      override fun close() {}
    }
  }
}

/**
 * Every connection open at [sampledAt], with rates, and how many were not tracked; the
 * connections that closed lately, newest first; and the bytes every connection moved since the
 * registry started.
 */
internal class ConnectionSample(
  val sampledAt: Instant,
  val connections: List<ActiveConnection>,
  val dropped: Long,
  val closed: List<ActiveConnection> = emptyList(),
  val receivedBytes: Long = 0,
  val sentBytes: Long = 0,
) {
  /**
   * At most [limit] connections, the fastest by combined rate, ordered by when they opened, and
   * at most [limit] of the latest closed ones; the totals cover every connection.
   */
  fun snapshot(limit: Int): ActiveConnections {
    val chosen = if (connections.size <= limit) {
      connections
    } else {
      connections.sortedWith(BY_RATE).take(limit)
    }
    return ActiveConnections(
      sampledAt = sampledAt,
      connections = chosen.sortedWith(BY_AGE),
      total = connections.size,
      downloadBps = connections.sumOf { it.downloadBps },
      uploadBps = connections.sumOf { it.uploadBps },
      dropped = dropped,
      receivedBytes = receivedBytes,
      sentBytes = sentBytes,
      closed = if (closed.size <= limit) closed else closed.take(limit),
    )
  }

  private companion object {
    val BY_AGE: Comparator<ActiveConnection> = compareBy({ it.openedAt }, { it.id })
    val BY_RATE: Comparator<ActiveConnection> =
      compareByDescending<ActiveConnection> { it.downloadBps + it.uploadBps }.then(BY_AGE)
  }
}

/**
 * The live connections of one Ketch instance. Sources count bytes on their handles with one
 * atomic add per chunk; [samples] reads the counters once per [interval], only while collected.
 *
 * Holds at most [maxRegistered] connections at once: past that, [ConnectionReporter.open]
 * returns a handle that only adds to the totals and counts the connection as dropped.
 * [countryOf] names each connection's country as it is sampled.
 *
 * A connection that closes adds its bytes to the registry's totals, so they never drop, and is
 * kept with its final counts among the last [maxClosed] closed ones for [closedRetention].
 */
internal class ConnectionRegistry(
  scope: CoroutineScope,
  private val clock: Clock = Clock.System,
  private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
  private val maxRegistered: Int = MAX_REGISTERED,
  private val interval: Duration = 1.seconds,
  private val countryOf: (String) -> String? = { null },
  private val maxClosed: Int = MAX_CLOSED,
  private val closedRetention: Duration = CLOSED_RETENTION,
) {
  private val log = KetchLogger("Connections")
  private val nextId = AtomicLong(0)
  private val live = AtomicReference<Map<Long, Entry>>(emptyMap())
  private val dropped = AtomicLong(0)

  /** The bytes of every connection that closed, and of those never tracked. */
  private val closedReceived = AtomicLong(0)
  private val closedSent = AtomicLong(0)

  /** The handle of connections past [maxRegistered]: their bytes still count in the totals. */
  private val untracked = object : ConnectionHandle {
    override fun received(bytes: Int) {
      if (bytes > 0) closedReceived.addAndFetch(bytes.toLong())
    }

    override fun sent(bytes: Int) {
      if (bytes > 0) closedSent.addAndFetch(bytes.toLong())
    }

    override fun describe(spec: ConnectionSpec) {}

    override fun close() {}
  }

  /** The last [maxClosed] connections that closed, newest first. */
  private val recent = AtomicReference<List<Closed>>(emptyList())

  /** The totals the last sample reported, which later ones never go below. */
  @Volatile private var lastReceived = 0L
  @Volatile private var lastSent = 0L
  private val openedSinceLog = AtomicLong(0)
  private val closedSinceLog = AtomicLong(0)
  private val droppedSinceLog = AtomicLong(0)
  private val lastLog = AtomicReference(timeSource.markNow())

  /** A point older than this is too old to measure a current rate from. */
  private val maxAge = interval * (HISTORY + 1)

  /** A rate measured over less than this is too noisy to start a collection with. */
  private val minSpan = interval / 2

  /**
   * A sample each [interval] while anything collects. Rates cover the last two samples, which
   * each connection keeps across collections, so a collection that starts soon after another
   * measures from its samples and sends its first one at once. A collection that has nothing
   * recent to measure some connection from, such as a one-shot read while nothing else collects,
   * notes every connection's counters and sends its first sample one [interval] later, rather
   * than an average since the connection opened.
   */
  val samples: SharedFlow<ConnectionSample> = flow {
    if (!measurable()) {
      markAll()
      delay(interval)
    }
    while (true) {
      emit(sample())
      delay(interval)
    }
  }.shareIn(scope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1)

  /** The number of connections open now. */
  val size: Int get() = live.load().size

  /** Snapshots of at most [limit] connections, sent only when something changed. */
  fun snapshots(limit: Int): Flow<ActiveConnections> {
    require(limit in 1..ActiveConnections.MAX_LIMIT) {
      "limit must be in 1..${ActiveConnections.MAX_LIMIT}"
    }
    return samples.map { it.snapshot(limit) }
      .distinctUntilChanged { old, new -> old.copy(sampledAt = new.sampledAt) == new }
  }

  /** The reporter of [taskId]'s connections. */
  fun reporter(taskId: String): ConnectionReporter = object : ConnectionReporter {
    override fun open(spec: ConnectionSpec): ConnectionHandle = open(taskId, spec)
  }

  /** Closes every connection of [taskId] a source left open. */
  fun closeTask(taskId: String) {
    live.load().values.forEach { if (it.taskId == taskId) it.close() }
  }

  /** Closes every connection. */
  fun closeAll() {
    live.load().values.forEach { it.close() }
  }

  private fun open(taskId: String, spec: ConnectionSpec): ConnectionHandle {
    val entry = Entry(nextId.addAndFetch(1), taskId, spec, clock.now(), timeSource.markNow())
    while (true) {
      val current = live.load()
      if (current.size >= maxRegistered) {
        dropped.addAndFetch(1)
        droppedSinceLog.addAndFetch(1)
        logCounts()
        return untracked
      }
      if (live.compareAndSet(current, current + (entry.id to entry))) break
    }
    openedSinceLog.addAndFetch(1)
    logCounts()
    return entry
  }

  private fun remove(entry: Entry) {
    while (true) {
      val current = live.load()
      if (entry.id !in current) return
      if (live.compareAndSet(current, current - entry.id)) break
    }
    // Added once it left the live map, so a sample between the two counts it at most once; the
    // sampler keeps the totals from dropping meanwhile.
    val received = entry.receivedBytes.load()
    val sent = entry.sentBytes.load()
    closedReceived.addAndFetch(received)
    closedSent.addAndFetch(sent)
    if (maxClosed > 0) {
      val closed = Closed(entry.connection(received, sent, clock.now()), timeSource.markNow())
      while (true) {
        val current = recent.load()
        val next = (listOf(closed) + current).take(maxClosed)
        if (recent.compareAndSet(current, next)) break
      }
    }
    closedSinceLog.addAndFetch(1)
    logCounts()
  }

  /** The closed connections kept, dropping those closed more than [closedRetention] ago. */
  private fun recentClosed(now: ComparableTimeMark): List<ActiveConnection> {
    while (true) {
      val current = recent.load()
      val kept = current.filter { now - it.mark < closedRetention }
      if (kept.size == current.size || recent.compareAndSet(current, kept)) {
        return kept.map { it.connection }
      }
    }
  }

  /** Logs the counts of the last minute, at most once a minute, when something happens. */
  private fun logCounts() {
    val mark = lastLog.load()
    if (mark.elapsedNow() < LOG_INTERVAL) return
    if (!lastLog.compareAndSet(mark, timeSource.markNow())) return
    val opened = openedSinceLog.exchange(0)
    val closed = closedSinceLog.exchange(0)
    val refused = droppedSinceLog.exchange(0)
    log.d { "Connections in the last minute: opened=$opened, closed=$closed, open=$size" }
    if (refused > 0) {
      log.w { "Not tracking $refused connections: $maxRegistered connections are open" }
    }
  }

  private inner class Entry(
    val id: Long,
    val taskId: String,
    @Volatile var spec: ConnectionSpec,
    val openedAt: Instant,
    val openedMark: ComparableTimeMark,
  ) : ConnectionHandle {
    val receivedBytes = AtomicLong(0)
    val sentBytes = AtomicLong(0)
    private val closed = AtomicBoolean(false)

    /** The counters at the last samples, oldest first; only the sampler running reads them. */
    @Volatile var points: List<Point> = emptyList()

    override fun received(bytes: Int) {
      if (bytes > 0) receivedBytes.addAndFetch(bytes.toLong())
    }

    override fun sent(bytes: Int) {
      if (bytes > 0) sentBytes.addAndFetch(bytes.toLong())
    }

    override fun describe(spec: ConnectionSpec) {
      this.spec = spec
    }

    override fun close() {
      if (closed.compareAndSet(false, true)) remove(this)
    }

    /** The oldest point at most [maxAge] before [now], else the latest one. */
    fun base(now: ComparableTimeMark): Point? =
      points.firstOrNull { now - it.mark <= maxAge } ?: points.lastOrNull()

    /** Adds the counters at [now], keeping the last [HISTORY] points. */
    fun record(now: ComparableTimeMark, received: Long, sent: Long) {
      points = (points + Point(now, received, sent)).takeLast(HISTORY)
    }

    /** What is known about it, with the counters given and rates of [downloadBps]/[uploadBps]. */
    fun connection(
      received: Long,
      sent: Long,
      closedAt: Instant? = null,
      downloadBps: Long = 0,
      uploadBps: Long = 0,
    ): ActiveConnection {
      val spec = spec
      return ActiveConnection(
        id = id,
        taskId = taskId,
        source = spec.source,
        protocol = spec.protocol,
        secure = spec.secure,
        direction = spec.direction,
        host = spec.host,
        port = spec.port,
        route = spec.route,
        downloadBps = downloadBps,
        uploadBps = uploadBps,
        downloadedBytes = received,
        uploadedBytes = sent,
        openedAt = openedAt,
        peer = spec.peer,
        country = countryOf(spec.host),
        closedAt = closedAt,
      )
    }
  }

  /** A connection that closed at [mark], as it was then. */
  private class Closed(val connection: ActiveConnection, val mark: ComparableTimeMark)

  /** The counters of one connection at one sample. */
  private class Point(val mark: ComparableTimeMark, val received: Long, val sent: Long)

  /**
   * Whether a rate can be measured now for every connection: from a point at least [minSpan] and
   * at most [maxAge] old, or, with no points yet, since it opened at most [maxAge] ago.
   */
  private fun measurable(): Boolean {
    val now = timeSource.markNow()
    return live.load().values.all { entry ->
      val base = entry.points.firstOrNull { now - it.mark <= maxAge }
      when {
        base != null -> now - base.mark >= minSpan
        entry.points.isNotEmpty() -> false
        else -> now - entry.openedMark <= maxAge
      }
    }
  }

  /** Starts every connection's points afresh from its counters now. */
  private fun markAll() {
    val now = timeSource.markNow()
    for (entry in live.load().values) {
      entry.points = listOf(Point(now, entry.receivedBytes.load(), entry.sentBytes.load()))
    }
  }

  /**
   * Every connection with its rates since its [Entry.base], or since it opened while it has no
   * points. Run by one collection of [samples] at a time.
   */
  private fun sample(): ConnectionSample {
    val now = timeSource.markNow()
    // Read before the live connections, so one closing meanwhile is counted at most once.
    var totalReceived = closedReceived.load()
    var totalSent = closedSent.load()
    val entries = live.load()
    val connections = ArrayList<ActiveConnection>(entries.size)
    for (entry in entries.values) {
      val received = entry.receivedBytes.load()
      val sent = entry.sentBytes.load()
      totalReceived += received
      totalSent += sent
      val base = entry.base(now) ?: Point(entry.openedMark, 0, 0)
      val elapsedMs = (now - base.mark).inWholeMilliseconds
      entry.record(now, received, sent)
      connections += entry.connection(
        received = received,
        sent = sent,
        downloadBps = rate(received - base.received, elapsedMs),
        uploadBps = rate(sent - base.sent, elapsedMs),
      )
    }
    // A connection that left the live map but has not added its bytes yet is missed until the
    // next sample; the totals never go back meanwhile.
    totalReceived = maxOf(totalReceived, lastReceived).also { lastReceived = it }
    totalSent = maxOf(totalSent, lastSent).also { lastSent = it }
    return ConnectionSample(
      sampledAt = clock.now(),
      connections = connections,
      dropped = dropped.load(),
      // One that closed since the live connections were read is listed as open until next time.
      closed = recentClosed(now).filter { it.id !in entries },
      receivedBytes = totalReceived,
      sentBytes = totalSent,
    )
  }

  private fun rate(bytes: Long, elapsedMs: Long): Long =
    if (elapsedMs <= 0 || bytes <= 0) 0 else bytes * 1000 / elapsedMs

  companion object {
    /** The most connections one instance tracks at once. */
    const val MAX_REGISTERED: Int = 4096

    /** The most closed connections one instance keeps. */
    const val MAX_CLOSED: Int = 256

    /** How long one instance keeps a closed connection. */
    val CLOSED_RETENTION: Duration = 10.minutes

    private const val HISTORY = 2
    private val LOG_INTERVAL = 1.minutes
  }
}

/**
 * The spec of an HTTP(S) request to [url] for [source], before the engine says more: the URL's
 * host and port, and the route [proxy] would take.
 */
internal fun httpConnectionSpec(source: String, url: String, proxy: Proxy): ConnectionSpec {
  val authority = UrlAuthority.parse(url)
  val host = authority?.host.orEmpty()
  val route = when (proxy) {
    Proxy.System -> ConnectionRoute.UNKNOWN
    Proxy.Direct -> ConnectionRoute.DIRECT
    is Proxy.Manual -> when {
      proxy.bypasses(host) -> ConnectionRoute.DIRECT
      proxy.address.type == ProxyAddress.Type.SOCKS5 -> ConnectionRoute.SOCKS5_PROXY
      else -> ConnectionRoute.HTTP_PROXY
    }
  }
  return ConnectionSpec(
    source = source,
    host = host,
    port = authority?.port,
    protocol = null,
    secure = authority?.scheme?.let { it == "https" },
    route = route,
  )
}
