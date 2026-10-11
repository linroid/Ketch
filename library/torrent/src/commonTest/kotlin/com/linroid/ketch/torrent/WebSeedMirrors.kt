package com.linroid.ketch.torrent

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import okio.IOException
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

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
