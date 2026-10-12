package com.linroid.ketch.core.engine

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.redactUrl
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

  /** The first source that [DownloadSource.canHandle]s [input]. */
  fun resolve(input: ResolveInput): DownloadSource {
    check(!closed.load()) { "Download sources are closed" }
    val source = sources.firstOrNull { it.canHandle(input) }
    if (source != null) {
      log.d { "Resolved source '${source.type}' for ${input.describe()}" }
      return source
    }
    log.e { "No source found for ${input.describe()}" }
    throw KetchError.Unsupported()
  }

  /** The first source that downloads [url]. */
  fun resolve(url: String): DownloadSource = resolve(ResolveInput.Url(url))

  private fun ResolveInput.describe(): String = when (this) {
    is ResolveInput.Url -> "URL: ${redactUrl(url)}"
    is ResolveInput.Content -> "content: fileName=$fileName, size=${bytes.size}"
  }

  fun resolveByType(type: String, url: String? = null): DownloadSource {
    check(!closed.load()) { "Download sources are closed" }
    val source = sources.firstOrNull { it.type == type }
      ?: sources.firstOrNull { url != null && type in it.previousTypes && it.canHandle(url) }
    if (source != null) return source
    log.e { "No source found for type: $type" }
    throw KetchError.Unsupported()
  }
}
