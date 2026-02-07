package com.linroid.kdown.internal

import com.linroid.kdown.FileAccessor
import com.linroid.kdown.HttpEngine
import com.linroid.kdown.KDownLogger
import com.linroid.kdown.model.Segment
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal class SegmentDownloader(
  private val httpEngine: HttpEngine,
  private val fileAccessor: FileAccessor,
  private val logger: KDownLogger
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
    logger.d("SegmentDownloader") {
      "Starting segment ${segment.index}: range ${segment.start}..${segment.end} ($remainingBytes bytes remaining)"
    }

    val initialBytes = segment.downloadedBytes
    var downloadedBytes = segment.downloadedBytes
    val range = segment.currentOffset..segment.end

    httpEngine.download(url, range, headers) { data ->
      coroutineContext.ensureActive()

      val writeOffset = segment.start + downloadedBytes
      fileAccessor.writeAt(writeOffset, data)
      downloadedBytes += data.size
      onProgress(downloadedBytes)
    }

    logger.d("SegmentDownloader") {
      "Completed segment ${segment.index}: downloaded ${downloadedBytes - initialBytes} bytes"
    }

    return segment.copy(downloadedBytes = downloadedBytes)
  }
}
