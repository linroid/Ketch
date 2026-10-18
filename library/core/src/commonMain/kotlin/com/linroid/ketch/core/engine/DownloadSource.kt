package com.linroid.ketch.core.engine

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer

/**
 * A download protocol, such as HTTP ([HttpDownloadSource]), FTP or BitTorrent. [SourceResolver]
 * routes each URL, or file content, to the first registered source that [canHandle]s it.
 *
 * A source probes a URL with [resolve], transfers it with [download], and saves what a later
 * [download] needs to continue with [store]. The other members are optional.
 *
 * A task resumes from two parts: Ketch's [DownloadContext.segments], the byte ranges done, which
 * Ketch saves and shows for every source, and this source's own state [S].
 *
 * @param S what only this source knows about the task, such as the HTTP `ETag` the content had
 *   or a torrent's checkpoint of verified pieces. Ketch encodes it with [stateSerializer] and
 *   hands it back decoded; a source without one uses [Unit].
 */
interface DownloadSource<S : Any> {
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

  /**
   * Tells this source that Ketch begins to close: it stops the downloads next, and calls [stop]
   * once they have. A source can begin to stop what it runs apart from its downloads now, so it
   * stops alongside them rather than after them, as seeding torrents do, whose `stopped`
   * announces then share the time the downloads' take. Must return at once; Ketch calls it once.
   */
  fun stopping() {}

  /**
   * Stops what this source runs apart from its downloads, such as seeding torrents and the peers
   * and trackers behind them, and returns once it has. Ketch calls it while it closes, after
   * [stopping], once every download stopped and saved its state, and before it closes the
   * [HttpEngine], so a source can still say goodbye over the network, as a torrent's `stopped`
   * announce does; [close] follows. Must be safe to call twice.
   */
  suspend fun stop() {}

  /** Releases what this source owns, once Ketch closes. Must be safe to call twice. */
  fun close() {}

  /**
   * Serializer of [S]. Tasks save the state as JSON, so later builds must still read what earlier
   * ones wrote: add fields with defaults, never rename or retype them. Unknown fields are ignored.
   */
  val stateSerializer: KSerializer<S>

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
  suspend fun planSelection(request: SelectionRequest<S>): SelectionPlan =
    throw UnsupportedOperationException("This download has no files to choose")

  // Transferring

  /**
   * Downloads, continuing from the [state] the task last [store]d, or from the beginning when it
   * is `null`. Writes through [DownloadContext.fileAccessor], reports [DownloadContext.onProgress]
   * and [DownloadContext.segments], waits on [DownloadContext.throttle], and stops when cancelled,
   * which is how a task pauses.
   *
   * With a [state], a source checks that the content did not change since it was stored (failing
   * with [KetchError.FileChanged]) before it continues from [DownloadContext.segments]. Ketch
   * passes the state the run started from, then on each retry the latest one stored. A state that
   * can't be decoded fails the task with [KetchError.CorruptResumeState] before this is called.
   * [DownloadContext.preResolved] holds the resolution the run starts from, if any, such as the
   * one the task kept ([com.linroid.ketch.core.task.TaskRecord.resolvedSource]).
   *
   * Settings come from [DownloadContext.config]. Segmented sources size their transfers with
   * [DownloadContext.effectiveConnections] and follow later [DownloadContext.maxConnections]
   * changes.
   *
   * When [ResolvedSource.totalBytes] is -1 the size is unknown: a source that can stream such
   * content writes it from offset zero and the engine records the final size, while one that
   * can't throws [KetchError.SourceError]. Sources that [write their own files][managesOwnFileIo]
   * never get such a download.
   */
  suspend fun download(context: DownloadContext, state: S?)

  /**
   * What a later [download] needs to continue, such as the HTTP `ETag` the content had or a
   * torrent's verified pieces, or `null` to keep what the task saved last. Ketch calls it as each
   * run starts, before any byte is written, then periodically, and once more as it stops.
   */
  suspend fun store(context: DownloadContext): S? = null

  // Removing

  /**
   * Called when a task is removed, after its download stopped: stops what this source keeps
   * running for it, such as a seeding torrent, and with [deleteFiles] deletes the files it wrote.
   * Best-effort: report failures without throwing, except cancellation.
   *
   * The default deletes [DownloadContext.fileAccessor]'s file, which suits sources that write one
   * file. Sources that write their own ([managesOwnFileIo]) find their files from [state], which
   * is `null` when the task stored none or it can't be decoded.
   */
  suspend fun cleanup(context: DownloadContext, state: S?, deleteFiles: Boolean) {
    if (!deleteFiles) return
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
fun DownloadSource<*>.canHandle(url: String): Boolean = canHandle(ResolveInput.Url(url))

/** [DownloadSource.resolve] for [url], with [headers] for HTTP sources. */
suspend fun DownloadSource<*>.resolve(
  url: String,
  headers: Map<String, String> = emptyMap(),
  config: DownloadConfig = DownloadConfig.Default,
): ResolvedSource = resolve(ResolveInput.Url(url, headers), config)
