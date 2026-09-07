package com.linroid.ketch.core.engine

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.log.KetchLogger
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Routes download URLs to the appropriate [DownloadSource].
 *
 * Sources are checked in registration order. The first source whose
 * [DownloadSource.canHandle] returns true for a given URL is used.
 * Typically [HttpDownloadSource] is registered last as a catch-all
 * fallback.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class SourceResolver(private val sources: List<DownloadSource>) {
  private val log = KetchLogger("SourceResolver")
  private val closed = AtomicBoolean(false)

  fun close() {
    if (!closed.compareAndSet(false, true)) return
    val uniqueSources = mutableListOf<DownloadSource>()
    for (source in sources) {
      if (uniqueSources.any { it === source }) continue
      uniqueSources.add(source)
      try {
        source.close()
      } catch (e: Exception) {
        log.w(e) { "Failed to close source '${source.type}'" }
      }
    }
  }

  init {
    log.d {
      "Initialized with ${sources.size} source(s): " +
        sources.joinToString { it.type }
    }
  }

  fun resolve(url: String): DownloadSource {
    check(!closed.load()) { "Download sources are closed" }
    val source = sources.firstOrNull { it.canHandle(url) }
    if (source != null) {
      log.d { "Resolved source '${source.type}' for URL: $url" }
      return source
    }
    log.e { "No source found for URL: $url" }
    throw KetchError.Unsupported()
  }

  fun resolveByType(type: String): DownloadSource {
    check(!closed.load()) { "Download sources are closed" }
    val source = sources.firstOrNull { it.type == type }
    if (source != null) return source
    log.e { "No source found for type: $type" }
    throw KetchError.Unsupported()
  }
}
