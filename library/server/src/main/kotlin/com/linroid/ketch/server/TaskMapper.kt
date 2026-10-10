package com.linroid.ketch.server

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.withoutBulkMetadata
import com.linroid.ketch.endpoints.model.TaskSnapshot

internal object TaskMapper {

  fun toSnapshot(task: DownloadTask): TaskSnapshot {
    return TaskSnapshot(
      taskId = task.taskId,
      request = viewOf(task.request),
      state = task.state.value,
      segments = task.segments.value,
      createdAt = task.createdAt,
      queuePosition = task.queuePosition.value,
    )
  }

  /**
   * [request] as task views and events show it: its resolved source keeps its files but not the
   * bulky metadata, such as a torrent's metainfo, which the server keeps to itself.
   */
  fun viewOf(request: DownloadRequest): DownloadRequest {
    val resolved = request.resolvedSource ?: return request
    val view = resolved.withoutBulkMetadata()
    return if (view === resolved) request else request.copy(resolvedSource = view)
  }
}
