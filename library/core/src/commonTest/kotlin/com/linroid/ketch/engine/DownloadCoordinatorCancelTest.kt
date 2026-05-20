package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadCoordinator
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.SourceResolver
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.file.DefaultFileNameResolver
import com.linroid.ketch.core.task.AtomicSaver
import com.linroid.ketch.core.task.TaskHandle
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies that [DownloadCoordinator.cancel] does not return until the
 * active download job's `finally` chain (including [FileAccessor.close])
 * has completed. Without that guarantee, callers that follow `cancel`
 * with file-cleanup work (e.g. `remove(deleteFiles = true)`) race against
 * the still-running writer and may fail on lock-enforcing platforms.
 */
class DownloadCoordinatorCancelTest {

  private class SuspendingSource : DownloadSource {
    val downloadStarted = CompletableDeferred<Unit>()
    var downloadFinallyRan: Boolean = false

    override val type: String = "suspend"
    override fun canHandle(url: String): Boolean = true

    override suspend fun resolve(
      url: String,
      properties: Map<String, String>,
    ): ResolvedSource = ResolvedSource(
      url = url,
      sourceType = type,
      totalBytes = 16L,
      supportsResume = false,
      suggestedFileName = "file.bin",
      maxSegments = 1,
    )

    override suspend fun download(context: DownloadContext) {
      downloadStarted.complete(Unit)
      try {
        awaitCancellation()
      } finally {
        downloadFinallyRan = true
      }
    }

    override suspend fun resume(
      context: DownloadContext,
      resumeState: SourceResumeState,
    ) = Unit

    override fun buildResumeState(
      resolved: ResolvedSource,
      totalBytes: Long,
    ): SourceResumeState = SourceResumeState(type, "")
  }

  private fun createHandle(destination: String): TaskHandle {
    val now = Clock.System.now()
    val request = DownloadRequest(
      url = "https://example.com/file.bin",
      destination = Destination(destination),
    )
    val record = TaskRecord(
      taskId = "task-cancel",
      request = request,
      state = TaskState.QUEUED,
      createdAt = now,
      updatedAt = now,
    )
    return object : TaskHandle {
      override val taskId = "task-cancel"
      override val request = request
      override val createdAt = now
      override val mutableState =
        MutableStateFlow<DownloadState>(DownloadState.Queued)
      override val mutableSegments =
        MutableStateFlow<List<Segment>>(emptyList())
      override val record = AtomicSaver(record) {}
    }
  }

  private fun createCoordinator(source: DownloadSource): DownloadCoordinator =
    DownloadCoordinator(
      sourceResolver = SourceResolver(listOf(source)),
      config = DownloadConfig(),
      fileNameResolver = DefaultFileNameResolver(),
      dispatchers = KetchDispatchers(
        main = Dispatchers.Default,
        network = Dispatchers.Default,
        io = Dispatchers.Default,
      ),
    )

  @Test
  fun cancel_waitsForDownloadFinally() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val source = SuspendingSource()
        val coordinator = createCoordinator(source)
        val handle = createHandle("/tmp/ketch-cancel-test/")

        // start() launches the job and returns; the job calls
        // source.download() concurrently and suspends.
        coordinator.start(handle)
        withTimeout(5.seconds) { source.downloadStarted.await() }

        coordinator.cancel(handle)

        assertTrue(
          source.downloadFinallyRan,
          "cancel() returned before the download's finally ran — race " +
            "between cancellation propagation and follow-up cleanup work",
        )
      } finally {
        scope.cancel()
      }
    }
  }
}
