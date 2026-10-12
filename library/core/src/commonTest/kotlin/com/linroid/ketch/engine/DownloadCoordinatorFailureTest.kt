package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolveInput
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * A download that fails with something other than an [Exception], or with a
 * [CancellationException] its job never received, fails on its own: the task is saved as
 * failed, and the other downloads keep running.
 */
class DownloadCoordinatorFailureTest {

  /** Downloads `/hang` until cancelled; `/error` and `/cancel` fail at once. */
  private class ScriptedSource : DownloadSource {
    val hanging = CompletableDeferred<Unit>()

    override val type: String = "scripted"
    override fun canHandle(input: ResolveInput): Boolean = input is ResolveInput.Url

    override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource {
      val url = (input as ResolveInput.Url).url
      return ResolvedSource(
        url = url,
        sourceType = type,
        totalBytes = 16L,
        supportsResume = false,
        suggestedFileName = url.substringAfterLast('/') + ".bin",
        maxSegments = 1,
      )
    }

    override suspend fun download(context: DownloadContext) {
      when (context.url.substringAfterLast('/')) {
        "hang" -> {
          hanging.complete(Unit)
          awaitCancellation()
        }
        "error" -> throw NotImplementedError("class failed to load")
        "cancel" -> throw CancellationException("leaked by a library")
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

  private fun createHandle(name: String, taskId: String = name): TaskHandle {
    val now = Clock.System.now()
    val request = DownloadRequest(
      url = "https://example.com/$name",
      destination = Destination("/tmp/ketch-failure-test/"),
    )
    val record = TaskRecord(
      taskId = taskId,
      request = request,
      state = TaskState.QUEUED,
      createdAt = now,
      updatedAt = now,
    )
    return object : TaskHandle {
      override val taskId = taskId
      override val request = request
      override val createdAt = now
      override val mutableState = MutableStateFlow<DownloadState>(DownloadState.Queued)
      override val mutableSegments = MutableStateFlow<List<Segment>>(emptyList())
      override val mutableQueuePosition = MutableStateFlow<Int?>(null)
      override val controlLock = Mutex()
      override val record = AtomicSaver(record) {}
    }
  }

  private fun createCoordinator(source: DownloadSource): DownloadCoordinator =
    DownloadCoordinator(
      sourceResolver = SourceResolver(listOf(source)),
      config = { DownloadConfig(retryCount = 0) },
      fileNameResolver = DefaultFileNameResolver(),
      dispatchers = KetchDispatchers(
        main = Dispatchers.Default,
        network = Dispatchers.Default,
        disk = Dispatchers.Default,
      ),
    )

  private suspend fun failingTask_failsAlone(name: String) = withContext(Dispatchers.Default) {
    val source = ScriptedSource()
    val coordinator = createCoordinator(source)
    try {
      val running = createHandle("hang")
      coordinator.start(running)
      withTimeout(5.seconds) { source.hanging.await() }

      val failing = createHandle(name)
      coordinator.start(failing)
      val state = withTimeout(5.seconds) {
        failing.mutableState.first { it.isTerminal }
      }

      assertIs<DownloadState.Failed>(state)
      assertIs<KetchError.Unknown>(state.error)
      assertEquals(TaskState.FAILED, failing.record.value.state)
      assertTrue(
        running.mutableState.value !is DownloadState.Canceled,
        "The other download was cancelled: ${running.mutableState.value}",
      )
      // The scope still runs new downloads. Under another ID: the failed task may not have left
      // the active downloads yet, and starting the same ID again would be skipped.
      val next = createHandle("error", taskId = "next")
      coordinator.start(next)
      withTimeout(5.seconds) { next.mutableState.first { it is DownloadState.Failed } }
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun start_sourceThrowsError_failsOnlyThatTask() = runTest {
    failingTask_failsAlone("error")
  }

  @Test
  fun start_sourceLeaksCancellation_failsOnlyThatTask() = runTest {
    failingTask_failsAlone("cancel")
  }
}
