package com.linroid.kdown.internal

import com.linroid.kdown.FileAccessor
import com.linroid.kdown.HttpEngine
import com.linroid.kdown.model.Segment
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal class SegmentDownloader(
  private val httpEngine: HttpEngine,
  private val fileAccessor: FileAccessor
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

    var downloadedBytes = segment.downloadedBytes
    val range = segment.currentOffset..segment.end

    httpEngine.download(url, range, headers) { data ->
      coroutineContext.ensureActive()

      val writeOffset = segment.start + downloadedBytes
      fileAccessor.writeAt(writeOffset, data)
      downloadedBytes += data.size
      onProgress(downloadedBytes)
    }

    return segment.copy(downloadedBytes = downloadedBytes)
  }
}
