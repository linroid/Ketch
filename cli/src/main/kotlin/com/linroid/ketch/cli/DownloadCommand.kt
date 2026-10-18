package com.linroid.ketch.cli

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.api.reason
import com.linroid.ketch.api.resolve
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.PrintStream
import kotlin.coroutines.cancellation.CancellationException

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
 * explanation for a [KetchError.SourceError] that has one, which its cause only repeats; else the
 * error and its causes, as a [KetchError]'s message alone is only a summary such as "Network error
 * occurred".
 */
internal fun failureReason(error: Throwable): String = when (error) {
  is KetchError.Unsupported -> error.reason()
  is KetchError.SourceError -> error.detail ?: error.describeCauses()
  else -> error.describeCauses()
}

/**
 * Downloads [request] with [ketch] until it completes or fails, printing its progress to [out],
 * and returns its exit status, one of [DownloadExit]. Why Ketch refused it or it failed goes to
 * [err] in one line ([failureReason]), without a stack trace. The task is removed once it ends.
 */
internal suspend fun downloadUntilDone(
  ketch: KetchApi,
  request: DownloadRequest,
  out: PrintStream = System.out,
  err: PrintStream = System.err,
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

  val speedLimit = request.speedLimit
  val limitLabel = if (speedLimit.isUnlimited) ""
    else " [limit: ${formatBytes(speedLimit.bytesPerSecond)}/s]"
  val monitor = launch {
    task.state.collect { state ->
      when (state) {
        is DownloadState.Scheduled ->
          out.println("[Scheduled] Waiting for scheduled time...")
        is DownloadState.Queued ->
          out.println("[Queued] Waiting for download slot...")
        is DownloadState.Downloading -> {
          val progress = state.progress
          val downloaded = formatBytes(progress.downloadedBytes)
          val amount = if (progress.totalBytes > 0) {
            val pct = (progress.percent * 100).toInt()
            "$pct%  $downloaded / ${formatBytes(progress.totalBytes)}"
          } else {
            downloaded
          }
          val speed = if (progress.bytesPerSecond > 0) {
            "  ${formatBytes(progress.bytesPerSecond)}/s"
          } else ""
          out.print("\r[Downloading] $amount$speed$limitLabel    ")
        }
        is DownloadState.Paused ->
          out.println("\n[Paused] Download paused.")
        is DownloadState.Completed -> {
          out.println("\n[Completed] Saved to ${state.outputPath}")
          state.checksum?.let { out.println("[Verified] ${it.algorithm.label} matches") }
        }
        is DownloadState.Failed ->
          out.println("\n[Failed] ${state.error.message}")
        is DownloadState.Canceled ->
          out.println("\n[Canceled] Download canceled.")
      }
    }
  }

  val result = task.await()
  monitor.cancel()
  // The task lives only as long as this process: what a source kept beside its files to resume
  // it, such as a torrent's sidecar or creation log, would only clutter the folder.
  task.remove(deleteFiles = false)

  result.fold(
    onSuccess = { path ->
      out.println("\nDownload completed: $path")
      DownloadExit.OK
    },
    onFailure = { error ->
      err.println("\nDownload failed: ${failureReason(error)}")
      DownloadExit.FAILED
    },
  )
}

/**
 * Prints the files or variants [ketch] resolves for [args]' URL, such as a torrent's files, to
 * [out]: one `ID  SIZE  PATH` row each, in the order `--sort` and `--reverse` ask for. Returns its
 * exit status, one of [DownloadExit]; why the URL could not be listed goes to [err].
 */
internal suspend fun listFiles(
  ketch: KetchApi,
  args: DownloadArgs.Download,
  out: PrintStream = System.out,
  err: PrintStream = System.err,
): Int {
  val source = try {
    ketch.resolve(args.url, args.headers)
  } catch (e: CancellationException) {
    throw e
  } catch (e: KetchError.Unsupported) {
    // Its own message is the same whatever the reason.
    err.println("Couldn't list the files: ${e.reason()}")
    return DownloadExit.FAILED
  } catch (e: Exception) {
    err.println("Couldn't list the files: ${e.message}")
    return DownloadExit.FAILED
  }
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
