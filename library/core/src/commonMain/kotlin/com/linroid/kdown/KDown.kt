package com.linroid.kdown

import com.linroid.kdown.internal.DownloadCoordinator
import com.linroid.kdown.internal.InMemoryMetadataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.io.files.Path

class KDown(
  private val httpEngine: HttpEngine,
  private val metadataStore: MetadataStore = InMemoryMetadataStore(),
  private val config: DownloadConfig = DownloadConfig.Default,
  private val fileAccessorFactory: (Path) -> FileAccessor = { path -> FileAccessor(path) }
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val coordinator = DownloadCoordinator(
    httpEngine = httpEngine,
    metadataStore = metadataStore,
    config = config,
    fileAccessorFactory = fileAccessorFactory
  )

  suspend fun download(request: DownloadRequest): DownloadTask {
    val stateFlow = coordinator.start(request, scope)

    return DownloadTask(
      taskId = request.taskId,
      state = stateFlow,
      pauseAction = { coordinator.pause(request.taskId) },
      resumeAction = { resume(request.taskId) },
      cancelAction = { coordinator.cancel(request.taskId) }
    )
  }

  suspend fun pause(taskId: String) {
    coordinator.pause(taskId)
  }

  suspend fun resume(taskId: String): DownloadTask? {
    val stateFlow = coordinator.resume(taskId, scope) ?: return null

    return DownloadTask(
      taskId = taskId,
      state = stateFlow,
      pauseAction = { coordinator.pause(taskId) },
      resumeAction = { resume(taskId) },
      cancelAction = { coordinator.cancel(taskId) }
    )
  }

  suspend fun cancel(taskId: String) {
    coordinator.cancel(taskId)
  }

  fun close() {
    httpEngine.close()
    scope.cancel()
  }

  companion object {
    const val VERSION = "1.0.0"
  }
}
