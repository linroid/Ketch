package com.linroid.ketch.core.engine

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.log.KetchLogger

/**
 * Routes download URLs to the appropriate [DownloadSource].
 *
 * Sources are checked in registration order. The first source whose
 * [DownloadSource.canHandle] returns true for a given URL is used.
 * Typically [HttpDownloadSource] is registered last as a catch-all
 * fallback.
 */
internal class SourceResolver(private val sources: List<DownloadSource>) {
  init {
    KetchLogger.d("SourceResolver") {
      "Initialized with ${sources.size} source(s): " +
        sources.joinToString { it.type }
    }
  }

  fun resolve(url: String): DownloadSource {
    val source = sources.firstOrNull { it.canHandle(url) }
    if (source != null) {
      KetchLogger.d("SourceResolver") {
        "Resolved source '${source.type}' for URL: $url"
      }
      return source
    }
    KetchLogger.e("SourceResolver") {
      "No source found for URL: $url"
    }
    throw KetchError.Unsupported
  }

  fun resolveByType(type: String): DownloadSource {
    val source = sources.firstOrNull { it.type == type }
    if (source != null) return source
    KetchLogger.e("SourceResolver") {
      "No source found for type: $type"
    }
    throw KetchError.Unsupported
  }
}
