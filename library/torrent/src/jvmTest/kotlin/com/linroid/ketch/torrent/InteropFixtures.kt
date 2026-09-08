package com.linroid.ketch.torrent

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.file.FileAccessor
import kotlinx.coroutines.flow.MutableStateFlow

internal fun sourceContext(
  url: String,
  resolved: ResolvedSource,
  output: String,
  taskId: String = "interop",
): DownloadContext =
  DownloadContext(taskId = taskId, url = url, request = DownloadRequest(url),
    fileAccessor = object : FileAccessor {
      override suspend fun writeAt(offset: Long, data: ByteArray) = Unit
      override suspend fun flush() = Unit
      override fun close() = Unit
      override suspend fun delete() = Unit
      override suspend fun size(): Long = 0
      override suspend fun preallocate(size: Long) = Unit
    },
    segments = MutableStateFlow(emptyList()), onProgress = { _, _ -> }, throttle = {},
    headers = emptyMap(), preResolved = resolved, outputPath = output,
  )
