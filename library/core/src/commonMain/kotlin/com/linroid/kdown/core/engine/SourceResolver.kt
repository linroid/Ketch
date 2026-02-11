package com.linroid.kdown.core.engine

import com.linroid.kdown.core.log.KDownLogger
import com.linroid.kdown.api.KDownError

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
    KDownLogger.d("SourceResolver") {
      "Initialized with ${sources.size} source(s): " +
        sources.joinToString { it.type }
    }
  }

  fun resolve(url: String): DownloadSource {
    val source = sources.firstOrNull { it.canHandle(url) }
    if (source != null) {
      KDownLogger.d("SourceResolver") {
        "Resolved source '${source.type}' for URL: $url"
      }
      return source
    }
    KDownLogger.e("SourceResolver") {
      "No source found for URL: $url"
    }
    throw KDownError.Unsupported
  }

  fun resolveByType(type: String): DownloadSource {
    val source = sources.firstOrNull { it.type == type }
    if (source != null) return source
    KDownLogger.e("SourceResolver") {
      "No source found for type: $type"
    }
    throw KDownError.Unsupported
  }
}
