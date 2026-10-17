package com.linroid.ketch.cli

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.PrintStream

/** Exit statuses of `ketch <url>`, for scripts that run it. */
internal object DownloadExit {
  /** The download completed. */
  const val OK = 0

  /** Ketch refused the download, such as a link no source handles, or the download failed. */
  const val FAILED = 1
}

/**
 * Downloads [request] with [ketch] until it completes or fails, printing its progress to [out],
 * and returns its exit status, one of [DownloadExit]. Why Ketch refused it or it failed goes to
 * [err], without a stack trace. The task is removed once it ends.
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
      err.println("\nDownload failed: ${error.message}")
      DownloadExit.FAILED
    },
  )
}
