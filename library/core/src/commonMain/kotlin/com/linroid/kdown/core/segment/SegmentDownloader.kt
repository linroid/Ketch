package com.linroid.kdown.core.segment

import com.linroid.kdown.core.engine.HttpEngine
import com.linroid.kdown.core.engine.SpeedLimiter
import com.linroid.kdown.core.file.FileAccessor
import com.linroid.kdown.core.log.KDownLogger
import com.linroid.kdown.api.KDownError
import com.linroid.kdown.api.Segment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal class SegmentDownloader(
  private val httpEngine: HttpEngine,
  private val fileAccessor: FileAccessor,
  private val taskLimiter: SpeedLimiter = SpeedLimiter.Companion.Unlimited,
  private val globalLimiter: SpeedLimiter = SpeedLimiter.Companion.Unlimited,
) {
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
    KDownLogger.d("SegmentDownloader") {
      "Starting segment ${segment.index}: range ${segment.start}..${segment.end} ($remainingBytes bytes remaining)"
    }

    val initialBytes = segment.downloadedBytes
    var downloadedBytes = segment.downloadedBytes
    val range = segment.currentOffset..segment.end

    httpEngine.download(url, range, headers) { data ->
      coroutineContext.ensureActive()
      taskLimiter.acquire(data.size)
      globalLimiter.acquire(data.size)

      val writeOffset = segment.start + downloadedBytes
      try {
        fileAccessor.writeAt(writeOffset, data)
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (e is KDownError) throw e
        throw KDownError.Disk(e)
      }
      downloadedBytes += data.size
      onProgress(downloadedBytes)
    }

    if (downloadedBytes < segment.totalBytes) {
      KDownLogger.w("SegmentDownloader") {
        "Incomplete segment ${segment.index}: " +
          "downloaded $downloadedBytes/${segment.totalBytes} bytes"
      }
      throw KDownError.Network(
        Exception(
          "Connection closed prematurely: received " +
            "$downloadedBytes of ${segment.totalBytes} bytes"
        )
      )
    }

    KDownLogger.d("SegmentDownloader") {
      "Completed segment ${segment.index}: " +
        "downloaded ${downloadedBytes - initialBytes} bytes"
    }

    return segment.copy(downloadedBytes = downloadedBytes)
  }
}
