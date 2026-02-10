package com.linroid.kdown.segment

import com.linroid.kdown.engine.HttpEngine
import com.linroid.kdown.engine.SpeedLimiter
import com.linroid.kdown.error.KDownError
import com.linroid.kdown.file.FileAccessor
import com.linroid.kdown.log.KDownLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal class SegmentDownloader(
  private val httpEngine: HttpEngine,
  private val fileAccessor: FileAccessor,
  private val taskLimiter: SpeedLimiter = SpeedLimiter.Unlimited,
  private val globalLimiter: SpeedLimiter = SpeedLimiter.Unlimited
) {
  suspend fun download(
    url: String,
    segment: Segment,
    headers: Map<String, String> = emptyMap(),
    onProgress: suspend (bytesDownloaded: Long) -> Unit
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

    KDownLogger.d("SegmentDownloader") {
      "Completed segment ${segment.index}: downloaded ${downloadedBytes - initialBytes} bytes"
    }

    return segment.copy(downloadedBytes = downloadedBytes)
  }
}
