@file:OptIn(ExperimentalAtomicApi::class)

package com.linroid.ketch.core.engine

import com.linroid.ketch.api.ActiveConnection
import com.linroid.ketch.api.ActiveConnections
import com.linroid.ketch.api.ConnectionDirection
import com.linroid.ketch.api.ConnectionRoute
import com.linroid.ketch.api.PeerDetails
import com.linroid.ketch.api.ProxyAddress
import com.linroid.ketch.api.ProxyConfig
import com.linroid.ketch.api.ProxyMode
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
 * [com.linroid.ketch.api.KetchApi.activeConnections]. Valid for the task's lifetime, not one run.
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

/** Every connection open at [sampledAt], with rates, and how many were not tracked. */
internal class ConnectionSample(
  val sampledAt: Instant,
  val connections: List<ActiveConnection>,
  val dropped: Long,
) {
  /**
   * At most [limit] connections, the fastest by combined rate, ordered by when they opened;
   * the totals cover every connection.
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
 * returns [ConnectionHandle.None] and counts the connection as dropped.
 */
internal class ConnectionRegistry(
  scope: CoroutineScope,
  private val clock: Clock = Clock.System,
  private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
  private val maxRegistered: Int = MAX_REGISTERED,
  private val interval: Duration = 1.seconds,
) {
  private val log = KetchLogger("Connections")
  private val nextId = AtomicLong(0)
  private val live = AtomicReference<Map<Long, Entry>>(emptyMap())
  private val dropped = AtomicLong(0)
  private val openedSinceLog = AtomicLong(0)
  private val closedSinceLog = AtomicLong(0)
  private val droppedSinceLog = AtomicLong(0)
  private val lastLog = AtomicReference(timeSource.markNow())

  /** A sample right away, then one each [interval], while anything collects. */
  val samples: SharedFlow<ConnectionSample> = flow {
    val sampler = Sampler()
    while (true) {
      emit(sampler.sample())
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
        return ConnectionHandle.None
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
    closedSinceLog.addAndFetch(1)
    logCounts()
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
  }

  /** The counters of one connection at one sample. */
  private class Point(val mark: ComparableTimeMark, val received: Long, val sent: Long)

  /**
   * Turns counters into rates over the last two samples, or since the connection opened while
   * it has fewer. Used by one collection of [samples] at a time.
   */
  private inner class Sampler {
    private val history = HashMap<Long, ArrayList<Point>>()

    fun sample(): ConnectionSample {
      val now = timeSource.markNow()
      val entries = live.load()
      val connections = ArrayList<ActiveConnection>(entries.size)
      for (entry in entries.values) {
        val received = entry.receivedBytes.load()
        val sent = entry.sentBytes.load()
        val points = history.getOrPut(entry.id) { ArrayList(HISTORY + 1) }
        val base = points.firstOrNull() ?: Point(entry.openedMark, 0, 0)
        val elapsedMs = (now - base.mark).inWholeMilliseconds
        points.add(Point(now, received, sent))
        if (points.size > HISTORY) points.removeAt(0)
        val spec = entry.spec
        connections += ActiveConnection(
          id = entry.id,
          taskId = entry.taskId,
          source = spec.source,
          protocol = spec.protocol,
          secure = spec.secure,
          direction = spec.direction,
          host = spec.host,
          port = spec.port,
          route = spec.route,
          downloadBps = rate(received - base.received, elapsedMs),
          uploadBps = rate(sent - base.sent, elapsedMs),
          downloadedBytes = received,
          uploadedBytes = sent,
          openedAt = entry.openedAt,
          peer = spec.peer,
        )
      }
      history.keys.retainAll(entries.keys)
      return ConnectionSample(clock.now(), connections, dropped.load())
    }

    private fun rate(bytes: Long, elapsedMs: Long): Long =
      if (elapsedMs <= 0 || bytes <= 0) 0 else bytes * 1000 / elapsedMs
  }

  companion object {
    /** The most connections one instance tracks at once. */
    const val MAX_REGISTERED: Int = 4096

    private const val HISTORY = 2
    private val LOG_INTERVAL = 1.minutes
  }
}

/**
 * The spec of an HTTP(S) request to [url] for [source], before the engine says more: the URL's
 * host and port, and the route [proxy] would take.
 */
internal fun httpConnectionSpec(source: String, url: String, proxy: ProxyConfig): ConnectionSpec {
  val authority = UrlAuthority.parse(url)
  val host = authority?.host.orEmpty()
  val route = when (proxy.mode) {
    ProxyMode.SYSTEM -> ConnectionRoute.UNKNOWN
    ProxyMode.DIRECT -> ConnectionRoute.DIRECT
    ProxyMode.MANUAL -> when {
      proxy.bypasses(host) -> ConnectionRoute.DIRECT
      proxy.address?.type == ProxyAddress.Type.SOCKS5 -> ConnectionRoute.SOCKS5_PROXY
      proxy.address?.type == ProxyAddress.Type.HTTP -> ConnectionRoute.HTTP_PROXY
      else -> ConnectionRoute.UNKNOWN
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
