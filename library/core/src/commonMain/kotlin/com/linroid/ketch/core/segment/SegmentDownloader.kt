package com.linroid.ketch.core.segment

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.engine.ConnectionHandle
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.SpeedLimiter
import com.linroid.ketch.core.file.FileAccessor
import com.linroid.ketch.api.log.KetchLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class SegmentDownloader(
  private val httpEngine: HttpEngine,
  private val fileAccessor: FileAccessor,
  private val taskLimiter: SpeedLimiter = SpeedLimiter.Unlimited,
  private val globalLimiter: SpeedLimiter = SpeedLimiter.Unlimited,
  taskId: String? = null,
) {
  private val log = KetchLogger("SegmentDownloader")
  private val owner = taskId?.let { " for taskId=$it" }.orEmpty()

  suspend fun download(
    url: String,
    segment: Segment,
    headers: Map<String, String> = emptyMap(),
    handle: ConnectionHandle = ConnectionHandle.None,
    onProgress: suspend (bytesDownloaded: Long) -> Unit,
  ): Segment {
    if (segment.isComplete) {
      log.d { "Skipping complete segment ${segment.index}$owner" }
      return segment
    }

    val remainingBytes = segment.totalBytes - segment.downloadedBytes
    log.d {
      "Starting segment ${segment.index}$owner: range ${segment.start}..${segment.end} " +
        "($remainingBytes bytes remaining)"
    }

    val initialBytes = segment.downloadedBytes
    var downloadedBytes = segment.downloadedBytes
    val range = segment.currentOffset..segment.end

    httpEngine.download(url, range, headers) { data ->
      currentCoroutineContext().ensureActive()
      if (data.size > segment.totalBytes - downloadedBytes) {
        throw KetchError.Unsupported(IllegalStateException("Response exceeds segment boundary"))
      }
      taskLimiter.acquire(data.size)
      globalLimiter.acquire(data.size)

      val writeOffset = segment.start + downloadedBytes
      try {
        fileAccessor.writeAt(writeOffset, data)
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (e is KetchError) throw e
        throw KetchError.Disk(e)
      }
      downloadedBytes += data.size
      handle.received(data.size)
      onProgress(downloadedBytes)
    }

    if (downloadedBytes < segment.totalBytes) {
      log.w {
        "Incomplete segment ${segment.index}$owner: " +
          "downloaded $downloadedBytes/${segment.totalBytes} bytes"
      }
      throw KetchError.Network(
        Exception(
          "Connection closed prematurely: received " +
            "$downloadedBytes of ${segment.totalBytes} bytes"
        )
      )
    }

    log.d {
      "Completed segment ${segment.index}$owner: " +
        "downloaded ${downloadedBytes - initialBytes} bytes"
    }

    return segment.copy(downloadedBytes = downloadedBytes)
  }
}
