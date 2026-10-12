package com.linroid.ketch.core.engine

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import kotlinx.coroutines.CancellationException

/**
 * A download protocol, such as HTTP ([HttpDownloadSource]), FTP or BitTorrent. [SourceResolver]
 * routes each URL, or file content, to the first registered source that [canHandle]s it.
 *
 * A source probes a URL with [resolve], transfers it with [download] and [resume], and saves what
 * a resume needs with [buildResumeState]. The other members are optional.
 */
interface DownloadSource {
  /** A unique name for this kind of source, such as `http` or `torrent`. */
  val type: String

  /** Names this source used before, so tasks saved under them still find it by URL. */
  val previousTypes: Set<String> get() = emptySet()

  /** Optional behaviors this source adds to [com.linroid.ketch.api.KetchStatus.features]. */
  val features: Set<String> get() = emptySet()

  /**
   * Whether this source writes its own files, as torrents do, so the engine opens no
   * [FileAccessor][com.linroid.ketch.core.file.FileAccessor] for it.
   */
  val managesOwnFileIo: Boolean get() = false

  /**
   * Returns this source's controllers ([com.linroid.ketch.api.KetchApi.controller]) and task
   * lifecycle hooks for the Ketch instance behind [host], or `null` for none. Called once per
   * instance, while it is created; don't use [host] before the hooks run.
   */
  fun attach(host: SourceHost): SourceExtension? = null

  /** Releases what this source owns, once Ketch closes. Must be safe to call twice. */
  fun close() {}

  // Resolving

  /**
   * Whether this source resolves [input]: a URL it downloads, or file content it reads, such as a
   * `.torrent` file. Most sources accept only [ResolveInput.Url]s.
   */
  fun canHandle(input: ResolveInput): Boolean

  /**
   * Probes [input], which [canHandle] accepted, without downloading it, as an HTTP `HEAD` does.
   * It may take as long as finding the content does, as a magnet link waits for peers to come
   * online, and stops when cancelled. [config] is Ketch's own for a preview, or the task's.
   *
   * A result for [ResolveInput.Content] must carry everything [download] needs, as it gets it as
   * [DownloadContext.preResolved] rather than resolving [ResolvedSource.url] again.
   */
  suspend fun resolve(
    input: ResolveInput,
    config: DownloadConfig = DownloadConfig.Default,
  ): ResolvedSource

  /**
   * The [ResolvedSource] a task saved in [resumeState], rebuilt offline, or `null` when it holds
   * none. A task that already resolved, such as one that waited for a file selection, starts
   * from it.
   */
  suspend fun resolveStored(resumeState: SourceResumeState): ResolvedSource? = null

  // File selection

  /**
   * Checks [request] against this source's own file list and sizes it, without side effects. A
   * running download follows [DownloadContext.selection] and confirms each revision with
   * [DownloadContext.acknowledgeSelection]. For a single choice the request holds exactly one ID.
   *
   * Sources that implement it list [com.linroid.ketch.api.KetchFeatures.FILE_SELECTION], and
   * [com.linroid.ketch.api.KetchFeatures.AWAIT_FILE_SELECTION] when their tasks can wait for a
   * choice. The default refuses, as the source has no files to choose.
   *
   * @throws IllegalArgumentException for an empty selection, unknown IDs, or several IDs for a
   *   single choice
   * @throws IllegalStateException when the file list is not known yet, or a file the task does
   *   not own is in the way
   */
  suspend fun planSelection(request: SelectionRequest): SelectionPlan =
    throw UnsupportedOperationException("This download has no files to choose")

  // Transferring

  /**
   * Downloads from scratch: writes through [DownloadContext.fileAccessor], reports
   * [DownloadContext.onProgress] and [DownloadContext.segments], waits on
   * [DownloadContext.throttle], and stops when cancelled.
   *
   * Settings come from [DownloadContext.config]. Segmented sources size their transfers with
   * [DownloadContext.effectiveConnections] and follow later [DownloadContext.maxConnections]
   * changes. After a retryable failure the engine calls this again with the same context, so a
   * source that can should continue from the progress in [DownloadContext.segments].
   *
   * When [ResolvedSource.totalBytes] is -1 the size is unknown: a source that can stream such
   * content writes it from offset zero and the engine records the final size, while one that
   * can't throws [KetchError.SourceError]. Sources that [write their own files][managesOwnFileIo]
   * never get such a download.
   */
  suspend fun download(context: DownloadContext)

  /**
   * Continues an interrupted download from [resumeState]. Sources that can't resume throw
   * [KetchError.Unsupported].
   */
  suspend fun resume(context: DownloadContext, resumeState: SourceResumeState)

  /**
   * What a resume needs to check that the content is unchanged, such as an HTTP `ETag`, an FTP
   * `MDTM` or a torrent's info hash. Saved with the task once it resolves, and passed to [resume].
   */
  fun buildResumeState(resolved: ResolvedSource, totalBytes: Long): SourceResumeState

  /**
   * A newer resume state to save while [context] downloads, such as a torrent's verified pieces,
   * or `null` to keep the current one. Called periodically.
   */
  suspend fun updateResumeState(context: DownloadContext): SourceResumeState? = null

  // Removing

  /**
   * Stops what this source keeps running for a removed task after its download returned, such as
   * a seeding torrent. Called before [cleanup], whether or not files are deleted; best-effort.
   */
  suspend fun release(taskId: String, resumeState: SourceResumeState?) {}

  /**
   * Deletes the files this source wrote for a task removed with `deleteFiles = true`, after its
   * download stopped. Best-effort: report failures without throwing, except cancellation.
   *
   * The default deletes [DownloadContext.fileAccessor]'s file, which suits sources that write one
   * file. Sources that write their own ([managesOwnFileIo]) find their files from [resumeState].
   */
  suspend fun cleanup(context: DownloadContext, resumeState: SourceResumeState?) {
    try {
      context.fileAccessor.delete()
    } catch (e: CancellationException) {
      throw e
    } catch (_: Throwable) {
      // Best-effort; the caller is responsible for logging.
    }
  }
}

/** Whether this source downloads [url]. */
fun DownloadSource.canHandle(url: String): Boolean = canHandle(ResolveInput.Url(url))

/** [DownloadSource.resolve] for [url], with [headers] for HTTP sources. */
suspend fun DownloadSource.resolve(
  url: String,
  headers: Map<String, String> = emptyMap(),
  config: DownloadConfig = DownloadConfig.Default,
): ResolvedSource = resolve(ResolveInput.Url(url, headers), config)
