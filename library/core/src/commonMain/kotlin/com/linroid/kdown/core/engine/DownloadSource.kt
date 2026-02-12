package com.linroid.kdown.core.engine

import com.linroid.kdown.api.ResolvedSource

/**
 * Abstraction for pluggable download source types.
 *
 * Each source handles a specific protocol or download mechanism.
 * The default implementation is [HttpDownloadSource] for HTTP/HTTPS
 * downloads. Future implementations may include torrent, media
 * extraction, or other protocols.
 *
 * Sources are registered with [SourceResolver] which routes
 * download requests to the appropriate source based on URL matching.
 */
interface DownloadSource {
  /** Unique identifier for this source type (e.g., "http", "torrent"). */
  val type: String

  /** Returns true if this source can handle the given URL. */
  fun canHandle(url: String): Boolean

  /**
   * Resolves source metadata for the given URL without downloading.
   * This is analogous to an HTTP HEAD request but generalized for
   * any source type.
   */
  suspend fun resolve(
    url: String,
    headers: Map<String, String> = emptyMap(),
  ): ResolvedSource

  /**
   * Executes a fresh download. The source is responsible for writing
   * data via [DownloadContext.fileAccessor], reporting progress via
   * [DownloadContext.onProgress], and updating segments via
   * [DownloadContext.segments].
   *
   * The source must respect cancellation by checking coroutine
   * context and must apply throttling via
   * [DownloadContext.throttle].
   */
  suspend fun download(context: DownloadContext)

  /**
   * Resumes a previously interrupted download using persisted
   * state. Sources that do not support resume should throw
   * [com.linroid.kdown.api.KDownError.Unsupported].
   */
  suspend fun resume(context: DownloadContext, resumeState: SourceResumeState)
}
