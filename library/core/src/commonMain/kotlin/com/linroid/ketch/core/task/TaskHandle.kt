package com.linroid.ketch.core.task

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.Segment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Instant

/**
 * Internal bundle of mutable task state used by the engine layer.
 *
 * Implemented by [RealDownloadTask] so that scheduler, queue,
 * coordinator, and execution classes can accept a single object
 * instead of threading five separate parameters.
 */
internal interface TaskHandle {
  val taskId: String
  val request: DownloadRequest
  val createdAt: Instant
  val mutableState: MutableStateFlow<DownloadState>
  val mutableSegments: MutableStateFlow<List<Segment>>
}
