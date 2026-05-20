package com.linroid.ketch.core.engine

import com.linroid.ketch.api.ResolvedSource
import kotlinx.coroutines.CancellationException

/**
 * Abstraction for pluggable download source types.
 *
 * Each source handles a specific protocol or download mechanism.
 * The default implementation is [HttpDownloadSource] for HTTP/HTTPS
 * downloads. Other implementations include FTP, BitTorrent, etc.
 *
 * Sources are registered with [SourceResolver] which routes
 * download requests to the appropriate source based on URL matching.
 */
interface DownloadSource {
  /** Unique identifier for this source type (e.g., "http", "torrent"). */
  val type: String

  /**
   * Whether this source manages its own file I/O instead of using
   * [DownloadContext.fileAccessor]. When `true`, the download engine
   * skips [FileAccessor][com.linroid.ketch.core.file.FileAccessor]
   * creation, flush, and cleanup.
   */
  val managesOwnFileIo: Boolean get() = false

  /** Returns true if this source can handle the given URL. */
  fun canHandle(url: String): Boolean

  /**
   * Resolves source metadata for the given URL without downloading.
   * This is analogous to an HTTP HEAD request but generalized for
   * any source type.
   *
   * @param url the URL to resolve
   * @param properties source-specific key-value pairs. For HTTP
   *   sources this contains HTTP headers; other sources may
   *   interpret them differently or ignore them.
   */
  suspend fun resolve(
    url: String,
    properties: Map<String, String> = emptyMap(),
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
   * [com.linroid.ketch.api.KetchError.Unsupported].
   */
  suspend fun resume(context: DownloadContext, resumeState: SourceResumeState)

  /**
   * Builds an opaque [SourceResumeState] from resolved metadata.
   *
   * Called after [resolve] completes to persist source-specific
   * state needed for resume validation (e.g., HTTP ETag/Last-Modified,
   * FTP MDTM, torrent info hash). The returned state is stored in
   * the task record and passed back to [resume] on restart.
   *
   * @param resolved the metadata returned by [resolve]
   * @param totalBytes total download size in bytes
   */
  fun buildResumeState(
    resolved: ResolvedSource,
    totalBytes: Long,
  ): SourceResumeState

  /**
   * Called periodically during download to let the source update
   * its resume state. The returned state replaces the current
   * resume state in the task record.
   *
   * Default implementation returns `null` (no update needed).
   * Sources like BitTorrent override this to persist bitfield
   * progress incrementally.
   *
   * @param context the active download context
   * @return updated resume state, or `null` to keep the current one
   */
  suspend fun updateResumeState(
    context: DownloadContext,
  ): SourceResumeState? = null

  /**
   * Deletes any data this source wrote for the given task. Called by
   * the engine when a task is removed with `deleteFiles = true`,
   * after the active download (if any) has been cancelled.
   *
   * Best-effort: implementations should log and swallow non-fatal
   * errors rather than throwing. [CancellationException] must still
   * propagate.
   *
   * The default implementation deletes [DownloadContext.fileAccessor],
   * which is the right behavior for sources that write to a single
   * output file. Sources with `managesOwnFileIo = true` (e.g.
   * torrents) should override this and use [resumeState] to locate
   * their internal handle (such as an info hash) for cleanup.
   *
   * @param context the download context, with a [DownloadContext.fileAccessor]
   *   pointing at the persisted output path
   * @param resumeState the source-specific resume state persisted in the
   *   task record, or `null` if the task never produced one
   */
  suspend fun cleanup(
    context: DownloadContext,
    resumeState: SourceResumeState?,
  ) {
    try {
      context.fileAccessor.delete()
    } catch (e: CancellationException) {
      throw e
    } catch (_: Throwable) {
      // best-effort; caller is responsible for logging
    }
  }
}
