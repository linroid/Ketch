package com.linroid.ketch.core.engine

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.NoDownloadSourceException
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.redactUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
internal class SourceResolver(private val sources: List<DownloadSource<*>>) {
  private val log = KetchLogger("SourceResolver")
  private val closed = AtomicBoolean(false)

  /** Each source once, though one may be registered twice. */
  private val uniqueSources: List<DownloadSource<*>> = buildList {
    for (source in sources) if (none { it === source }) add(source)
  }

  /** Tells every source that Ketch begins to close ([DownloadSource.stopping]). */
  fun stopping() {
    for (source in uniqueSources) {
      try {
        source.stopping()
      } catch (e: Exception) {
        log.w(e) { "Failed to begin stopping source '${source.type}'" }
      }
    }
  }

  /** [Stops][DownloadSource.stop] every source side by side and returns once each has. */
  suspend fun stop() = coroutineScope {
    for (source in uniqueSources) launch {
      try {
        source.stop()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w(e) { "Failed to stop source '${source.type}'" }
      }
    }
  }

  fun close() {
    if (!closed.compareAndSet(false, true)) return
    for (source in uniqueSources) {
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

  /**
   * The first source that [DownloadSource.canHandle]s [input].
   *
   * @throws KetchError.Unsupported caused by a [NoDownloadSourceException] when none does
   */
  fun resolve(input: ResolveInput): DownloadSource<*> {
    check(!closed.load()) { "Download sources are closed" }
    val source = sources.firstOrNull { it.canHandle(input) }
    if (source != null) {
      log.d { "Resolved source '${source.type}' for ${input.describe()}" }
      return source
    }
    // An input no source takes is refused to its caller, which says so: nothing went wrong here.
    log.d { "No source found for ${input.describe()}" }
    throw KetchError.Unsupported(NoDownloadSourceException())
  }

  /** The first source that downloads [url]. */
  fun resolve(url: String): DownloadSource<*> = resolve(ResolveInput.Url(url))

  /** Whether a source downloads [url]. */
  fun canHandle(url: String): Boolean = sources.any { it.canHandle(ResolveInput.Url(url)) }

  /** Whether [resolveByType] finds a source for [type] (and [url], for a renamed type). */
  fun hasType(type: String, url: String? = null): Boolean = sources.any { it.type == type } ||
    url != null && sources.any { type in it.previousTypes && it.canHandle(url) }

  private fun ResolveInput.describe(): String = when (this) {
    is ResolveInput.Url -> "URL: ${redactUrl(url)}"
    is ResolveInput.Content -> "content: fileName=$fileName, size=${bytes.size}"
  }

  fun resolveByType(type: String, url: String? = null): DownloadSource<*> {
    check(!closed.load()) { "Download sources are closed" }
    val source = sources.firstOrNull { it.type == type }
      ?: sources.firstOrNull { url != null && type in it.previousTypes && it.canHandle(url) }
    if (source != null) return source
    log.e { "No source found for type: $type" }
    throw KetchError.Unsupported()
  }
}
