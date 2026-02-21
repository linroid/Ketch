package com.linroid.ketch.core.segment

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.Segment
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
) {
  private val log = KetchLogger("SegmentDownloader")
  suspend fun download(
    url: String,
    segment: Segment,
    headers: Map<String, String> = emptyMap(),
    onProgress: suspend (bytesDownloaded: Long) -> Unit,
  ): Segment {
    if (segment.isComplete) {
      return segment
    }

    val remainingBytes = segment.totalBytes - segment.downloadedBytes
    log.d {
      "Starting segment ${segment.index}: range ${segment.start}..${segment.end} ($remainingBytes bytes remaining)"
    }

    val initialBytes = segment.downloadedBytes
    var downloadedBytes = segment.downloadedBytes
    val range = segment.currentOffset..segment.end

    httpEngine.download(url, range, headers) { data ->
      currentCoroutineContext().ensureActive()
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
      onProgress(downloadedBytes)
    }

    if (downloadedBytes < segment.totalBytes) {
      log.w {
        "Incomplete segment ${segment.index}: " +
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
      "Completed segment ${segment.index}: " +
        "downloaded ${downloadedBytes - initialBytes} bytes"
    }

    return segment.copy(downloadedBytes = downloadedBytes)
  }
}
