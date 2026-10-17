package com.linroid.ketch.torrent.webseed

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import okio.IOException
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * Serves [files] by URL with Range support; [corrupt] flips bytes, [missing] answers 404. The
 * first response holding byte [damage] of a URL flips that byte, and the first response of a URL
 * in [drop] fails once it sent that many bytes.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class MirrorEngine(
  private val files: Map<String, ByteArray>,
  private val corrupt: Boolean = false,
  private val missing: Set<String> = emptySet(),
  private val damage: Map<String, Long> = emptyMap(),
  private val drop: Map<String, Int> = emptyMap(),
) : HttpEngine {
  private val log = AtomicReference<List<Pair<String, LongRange?>>>(emptyList())
  private val faults = AtomicReference<Set<String>>(emptySet())
  val requests: List<Pair<String, LongRange?>> get() = log.load()

  /** True the first time [fault] is asked for. */
  private fun once(fault: String): Boolean {
    while (true) {
      val current = faults.load()
      if (fault in current) return false
      if (faults.compareAndSet(current, current + fault)) return true
    }
  }

  override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
    error("unused")

  override suspend fun download(
    url: String,
    range: LongRange?,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ) {
    while (true) {
      val current = log.load()
      if (log.compareAndSet(current, current + (url to range))) break
    }
    if (url in missing) throw KetchError.Http(404)
    val file = files[url] ?: throw KetchError.Http(404)
    val bytes = if (range == null) file else file.copyOfRange(range.first.toInt(),
      range.last.toInt() + 1)
    if (corrupt) bytes.indices.forEach { bytes[it] = (bytes[it].toInt() xor 0x5A).toByte() }
    val first = range?.first ?: 0L
    damage[url]?.let { offset ->
      if (offset - first in bytes.indices && once("damage $url")) {
        val at = (offset - first).toInt()
        bytes[at] = (bytes[at].toInt() xor 0x5A).toByte()
      }
    }
    val cut = drop[url]?.takeIf { it < bytes.size && once("drop $url") }
    // Odd chunks, so pieces arrive split across callbacks.
    bytes.copyOf(cut ?: bytes.size).asList().chunked(7_001).forEach { onData(it.toByteArray()) }
    if (cut != null) throw IOException("Connection reset")
  }

  override fun close() = Unit
}

/**
 * One engine per mirror host: `mirrorN.` URLs go to [engines] N as `mirror.` URLs, any other
 * host to the first.
 */
internal fun mirrorHosts(vararg engines: HttpEngine): HttpEngine = object : HttpEngine {
  override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
    error("unused")

  override suspend fun download(
    url: String,
    range: LongRange?,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ) {
    val index = url.substringAfter("://").substringBefore('.').removePrefix("mirror")
      .toIntOrNull() ?: 0
    engines[index].download(url.replace("mirror$index.", "mirror."), range, headers, onData)
  }

  override fun close() = Unit
}

/**
 * Answers through [engine], except while [down] says the mirror is down: then every request fails
 * with a network error, counted in [refused].
 */
@OptIn(ExperimentalAtomicApi::class)
internal class Outage(
  private val engine: HttpEngine,
  private val down: () -> Boolean,
) : HttpEngine by engine {
  private val refusals = AtomicInt(0)
  val refused: Int get() = refusals.load()

  override suspend fun download(
    url: String,
    range: LongRange?,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ) {
    if (down()) {
      refusals.incrementAndFetch()
      throw KetchError.Network()
    }
    engine.download(url, range, headers, onData)
  }
}

/** Holds every request of [engine] until [open] completes; [asked] once one comes. */
internal class Gate(private val engine: HttpEngine) : HttpEngine by engine {
  val open = CompletableDeferred<Unit>()
  val asked = CompletableDeferred<Unit>()

  override suspend fun download(
    url: String,
    range: LongRange?,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ) {
    asked.complete(Unit)
    open.await()
    engine.download(url, range, headers, onData)
  }
}

/**
 * Sends what [engine] answers a part at a time: each part waits until [steps] lets it through;
 * [asked] once a request comes.
 */
internal class Stepped(private val engine: HttpEngine) : HttpEngine by engine {
  val steps = Channel<Unit>()
  val asked = CompletableDeferred<Unit>()

  override suspend fun download(
    url: String,
    range: LongRange?,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ) {
    asked.complete(Unit)
    engine.download(url, range, headers) { part ->
      steps.receive()
      onData(part)
    }
  }
}

/**
 * Refuses a request to [engine] with a network error while another is under way, as some proxies
 * do, counting them in [refused]; each request it lets through takes a moment.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class OneAtATime(private val engine: HttpEngine) : HttpEngine by engine {
  private val running = AtomicInt(0)
  private val refusals = AtomicInt(0)
  val refused: Int get() = refusals.load()

  override suspend fun download(
    url: String,
    range: LongRange?,
    headers: Map<String, String>,
    onData: suspend (ByteArray) -> Unit,
  ) {
    if (running.incrementAndFetch() > 1) {
      running.decrementAndFetch()
      refusals.incrementAndFetch()
      throw KetchError.Network()
    }
    try {
      delay(100)
      engine.download(url, range, headers, onData)
    } finally {
      running.decrementAndFetch()
    }
  }
}

/** Runs [block] with the info lines Ketch logs meanwhile, which [block] gets as they come. */
internal suspend fun withInfoLog(block: suspend (Channel<String>) -> Unit) {
  val lines = Channel<String>(Channel.UNLIMITED)
  KetchLogger.setLogger(object : Logger {
    override fun v(message: String) = Unit
    override fun d(message: String) = Unit
    override fun i(message: String) {
      lines.trySend(message)
    }
    override fun w(message: String, throwable: Throwable?) = Unit
    override fun e(message: String, throwable: Throwable?) = Unit
    override fun isLoggable(level: LogLevel): Boolean = level >= LogLevel.INFO
  })
  try {
    block(lines)
  } finally {
    KetchLogger.setLogger(Logger.None)
  }
}

/** The lines logged so far that were not taken yet. */
internal fun Channel<String>.drain(): List<String> = buildList {
  while (true) add(tryReceive().getOrNull() ?: break)
}
