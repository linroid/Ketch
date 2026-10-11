package com.linroid.ketch.torrent

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Serves [files] by URL with Range support; [corrupt] flips bytes, [missing] answers 404. */
@OptIn(ExperimentalAtomicApi::class)
internal class MirrorEngine(
  private val files: Map<String, ByteArray>,
  private val corrupt: Boolean = false,
  private val missing: Set<String> = emptySet(),
) : HttpEngine {
  private val log = AtomicReference<List<Pair<String, LongRange?>>>(emptyList())
  val requests: List<Pair<String, LongRange?>> get() = log.load()

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
    // Odd chunks, so pieces arrive split across callbacks.
    bytes.asList().chunked(7_001).forEach { onData(it.toByteArray()) }
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
