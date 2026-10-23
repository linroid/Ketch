package com.linroid.ketch.cli

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.api.reason
import com.linroid.ketch.api.resolve
import com.linroid.ketch.torrent.api.TorrentController
import com.linroid.ketch.torrent.api.TorrentInterest
import com.linroid.ketch.torrent.api.TorrentStall
import com.linroid.ketch.torrent.api.TorrentStallReason
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.PrintStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Exit statuses of `ketch <url>` and `ketch --list-files <url>`, for scripts that run them. */
internal object DownloadExit {
  /** The download completed, or the files were listed. */
  const val OK = 0

  /**
   * Ketch refused the download, such as a link no source handles, or the download failed; for
   * `--list-files`, the URL could not be resolved or has no files to choose.
   */
  const val FAILED = 1
}

/**
 * The level of the Ketch logger of a command that prints its own output, such as a download's
 * progress or a list of files: only warnings, unless -v or --debug asks for more ([verbosity]
 * below [LogLevel.INFO]).
 */
internal fun quietLogLevel(verbosity: LogLevel): LogLevel =
  if (verbosity < LogLevel.INFO) verbosity else LogLevel.WARN

/**
 * Why [error] failed a download, in one line: what the source lacks for
 * [KetchError.Unsupported], whose own message is the same whatever the reason; the source's own
 * explanation for a [KetchError.SourceError] that has one, then its causes from the first whose
 * message it does not already hold, as a torrent's detail repeats (or quotes) its cause while
 * WebDAV's only sums it up; else the error and its causes, as a [KetchError]'s message alone is
 * only a summary such as "Network error occurred".
 */
internal fun failureReason(error: Throwable): String {
  if (error is KetchError.Unsupported) return error.reason()
  val detail = (error as? KetchError.SourceError)?.detail ?: return error.describeCauses()
  val cause = generateSequence(error.cause) { it.cause }
    .take(MAX_REASON_CAUSES)
    .firstOrNull { val message = it.message; message == null || message !in detail }
  return listOfNotNull(detail, cause?.describeCauses()).joinToString(" <- ")
}

/** How deep [failureReason] looks for a cause that says more than a source error's detail. */
private const val MAX_REASON_CAUSES = 8

/**
 * Why the torrent task [taskId] makes no progress, as [ketch]'s torrent snapshots report it, `null`
 * while it progresses; nothing for other tasks, or without a torrent controller.
 */
internal fun torrentStalls(ketch: KetchApi, taskId: String): Flow<TorrentStall?> {
  if (ketch.controller(TorrentController) == null) return emptyFlow()
  return ketch.subscribe(TorrentInterest(taskId))
    .map { it?.stall }
    // Only the progress line misses it, such as when the controller follows too many tasks.
    .catch {}
}

/** Why [stall] holds a torrent, for a line of its own, as the apps' inspector says it. */
internal fun stallReason(stall: TorrentStall): String = when (stall.reason) {
  TorrentStallReason.MISSING_PIECES -> {
    val pieces = stall.missingPieces
    "No peer or web seed has $pieces ${if (pieces == 1) "piece" else "pieces"} it needs"
  }
  TorrentStallReason.NO_PEERS -> "No peers, still looking"
  TorrentStallReason.CHOKED -> "Peers connected, but none is sharing yet"
  TorrentStallReason.SLOW_PEERS -> "Peers are slow, asking others for the same pieces"
  TorrentStallReason.UNKNOWN -> "No progress, reason unknown"
}

/**
 * Prints a download's states to [line] for `ketch <url>`: a line for each state but Downloading,
 * whose progress line each update writes over on a terminal (see [ProgressLine] for output that
 * is captured instead). As that line is printed again every second, it stays narrower than an
 * 80-column terminal, where a wrapped line would leave a row behind at each update: while a
 * torrent stalls it says only for how long, in place of the speed and [speedLimit], and why goes
 * on a line of its own above it each time the reason changes.
 */
internal class ProgressPrinter(
  private val line: ProgressLine,
  speedLimit: SpeedLimit,
  private val magnet: Boolean = false,
) {
  private val limitLabel =
    if (speedLimit.isUnlimited) "" else " [limit: ${formatBytes(speedLimit.bytesPerSecond)}/s]"

  // The stall reason printed above the progress line, until the download progresses again.
  private var shownReason: String? = null

  /**
   * Prints [state]; [stall] is why a downloading torrent makes no progress, `null` if it does,
   * and [queuePosition] where a queued download waits, `null` when it is not waiting for a slot
   * but getting ready, as a magnet link looking for its metadata does.
   */
  fun print(state: DownloadState, stall: TorrentStall?, queuePosition: Int? = null) {
    if (state !is DownloadState.Downloading) shownReason = null
    when (state) {
      is DownloadState.Scheduled -> line.printBelow("[Scheduled] Waiting for scheduled time...")
      is DownloadState.Queued -> line.printBelow(
        when {
          queuePosition != null -> "[Queued] Waiting for download slot (#$queuePosition)..."
          magnet -> "[Starting] Finding peers that have the torrent's metadata..."
          else -> "[Starting] Getting ready..."
        },
      )
      is DownloadState.Downloading -> printProgress(state.progress, stall)
      is DownloadState.Paused -> line.printBelow("[Paused] Download paused.")
      is DownloadState.Completed -> {
        line.printBelow("[Completed] Saved to ${state.outputPath}")
        state.checksum?.let { line.printBelow("[Verified] ${it.algorithm.label} matches") }
      }
      is DownloadState.Failed -> line.printBelow("[Failed] ${state.error.message}")
      is DownloadState.Canceled -> line.printBelow("[Canceled] Download canceled.")
    }
  }

  private fun printProgress(progress: DownloadProgress, stall: TorrentStall?) {
    val reason = stall?.let(::stallReason)
    if (reason != null && reason != shownReason) line.printAbove("[Stalled] $reason")
    shownReason = reason
    val downloaded = formatBytes(progress.downloadedBytes)
    val amount = if (progress.totalBytes > 0) {
      val pct = (progress.percent * 100).toInt()
      "$pct%  $downloaded / ${formatBytes(progress.totalBytes)}"
    } else {
      downloaded
    }
    val rate = if (stall != null) {
      "  stalled ${stall.seconds.seconds}"
    } else {
      val speed = if (progress.bytesPerSecond > 0) {
        "  ${formatBytes(progress.bytesPerSecond)}/s"
      } else ""
      speed + limitLabel
    }
    line.show("[Downloading] $amount$rate")
  }
}

/**
 * Downloads [request] with [ketch] until it completes or fails, printing its progress to
 * [progress], and returns its exit status, one of [DownloadExit]. Why Ketch refused it or it
 * failed goes to [err] in one line ([failureReason]), without a stack trace. The task is removed
 * once it ends. While a torrent makes no progress, its output says why ([stalls], from
 * [torrentStalls]; see [ProgressPrinter]), as Ketch logs only warnings meanwhile; those go above
 * the progress line when [ketch]'s logger writes through [progress] (see [ProgressLine]).
 */
internal suspend fun downloadUntilDone(
  ketch: KetchApi,
  request: DownloadRequest,
  progress: ProgressLine = ProgressLine.stdout(),
  err: PrintStream = System.err,
  stalls: (taskId: String) -> Flow<TorrentStall?> = { torrentStalls(ketch, it) },
): Int = coroutineScope {
  val task = try {
    ketch.download(request)
  } catch (e: IllegalArgumentException) {
    err.println("Download failed: ${e.message}")
    return@coroutineScope DownloadExit.FAILED
  } catch (e: UnsupportedOperationException) {
    err.println("Download failed: ${e.message}")
    return@coroutineScope DownloadExit.FAILED
  }

  val printer = ProgressPrinter(progress, request.speedLimit, request.url.isMagnet())
  val latestStall = MutableStateFlow<TorrentStall?>(null)
  val stallWatch = launch { stalls(task.taskId).collect { latestStall.value = it } }
  val monitor = launch {
    combine(task.state, latestStall, task.queuePosition) { state, stall, position ->
      Triple(state, stall?.takeIf { state is DownloadState.Downloading },
        position.takeIf { state is DownloadState.Queued })
    }.distinctUntilChanged().collect { (state, stall, position) ->
      printer.print(state, stall, position)
    }
  }

  val result = task.await()
  monitor.cancel()
  stallWatch.cancel()
  // The task lives only as long as this process: what a source kept beside its files to resume
  // it, such as a torrent's sidecar or creation log, would only clutter the folder.
  task.remove(deleteFiles = false)

  result.fold(
    onSuccess = { path ->
      progress.printBelow("Download completed: $path")
      DownloadExit.OK
    },
    onFailure = { error ->
      progress.end()
      err.println("Download failed: ${failureReason(error)}")
      DownloadExit.FAILED
    },
  )
}

/**
 * Prints the files or variants [ketch] resolves for [args]' URL, such as a torrent's files, to
 * [out]: one `ID  SIZE  PATH` row each, in the order `--sort` and `--reverse` ask for. Returns its
 * exit status, one of [DownloadExit]; why the URL could not be listed goes to [err] in one line
 * ([failureReason]).
 */
internal suspend fun listFiles(
  ketch: KetchApi,
  args: DownloadArgs.Download,
  out: PrintStream = System.out,
  err: PrintStream = System.err,
  noticeAfter: Duration = RESOLVE_NOTICE_DELAY,
): Int {
  // Resolving is quick but for a magnet link, which waits for a peer to send its metadata.
  val source = coroutineScope {
    val notice = launch {
      delay(noticeAfter)
      err.println(
        if (args.url.isMagnet()) {
          "Finding peers that have the torrent's metadata (Ctrl+C to stop)..."
        } else {
          "Still looking up the files (Ctrl+C to stop)..."
        },
      )
    }
    try {
      ketch.resolve(args.url, args.headers)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      err.println("Couldn't list the files: ${failureReason(e)}")
      null
    } finally {
      notice.cancel()
    }
  } ?: return DownloadExit.FAILED
  val rows = fileListRows(source, args.fileOrder, args.reverse)
  if (rows.isEmpty()) {
    err.println("This download has no files to choose.")
    return DownloadExit.FAILED
  }
  rows.forEach(out::println)
  if (source.selectionMode == FileSelectionMode.SINGLE) {
    err.println("These are variants: choose one with --files <id>.")
  }
  return DownloadExit.OK
}

/** How long `--list-files` resolves before it says what it waits for. */
private val RESOLVE_NOTICE_DELAY = 3.seconds

private fun String.isMagnet(): Boolean = startsWith("magnet:", ignoreCase = true)

