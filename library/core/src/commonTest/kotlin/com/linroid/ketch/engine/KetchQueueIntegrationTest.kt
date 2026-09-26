package com.linroid.ketch.engine

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.task.InMemoryTaskStore
import com.linroid.ketch.core.task.TaskState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KetchQueueIntegrationTest {
  @Test
  fun pause_releasesSlotForQueuedDownload() = runTest {
    withKetch { ketch, source, _ ->
      val first = ketch.download(request("first"))
      runCurrent()
      val second = ketch.download(request("second"))
      assertEquals(DownloadState.Queued, second.state.value)
      first.pause()
      runCurrent()
      assertIs<DownloadState.Downloading>(second.state.value)
      assertEquals(1, source.maximumActive)
    }
  }

  @Test
  fun resume_respectsOccupiedQueueSlot() = runTest {
    withKetch { ketch, source, store ->
      val first = ketch.download(request("first"))
      runCurrent()
      first.pause()
      val second = ketch.download(request("second"))
      runCurrent()
      first.resume()
      runCurrent()
      assertEquals(DownloadState.Queued, first.state.value)
      assertEquals(TaskState.QUEUED, store.load(first.taskId)?.state)
      source.complete("second")
      runCurrent()
      assertIs<DownloadState.Completed>(second.state.value)
      assertIs<DownloadState.Downloading>(first.state.value)
      assertEquals(1, source.maximumActive)
    }
  }

  @Test
  fun resume_waitingInQueue_isRestoredAfterRestart() = runTest {
    withKetch { ketch, _, store ->
      val first = ketch.download(request("first"))
      runCurrent()
      first.pause()
      ketch.download(request("second"))
      runCurrent()
      first.resume()
      val snapshot = store.loadAll()
      ketch.close()
      runCurrent()
      // Copy the records captured at shutdown, without shutdown callbacks changing them.
      val restoredStore = InMemoryTaskStore()
      snapshot.forEach { restoredStore.save(it) }
      val dispatcher = StandardTestDispatcher(testScheduler)
      val restored = Ketch(
        httpEngine = FakeHttpEngine(),
        taskStore = restoredStore,
        config = DownloadConfig(maxConcurrentDownloads = 1, retryCount = 0),
        additionalSources = listOf(GatedSource()),
        dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
      )
      try {
        restored.start()
        runCurrent()
        val state = restored.tasks.value.first { it.taskId == first.taskId }.state.value
        assertIs<DownloadState.Downloading>(state)
      } finally {
        restored.close()
        runCurrent()
      }
    }
  }

  @Test
  fun retry_failedTask_respectsQueueAndReleasesSlotAfterCompletion() = runTest {
    withKetch { ketch, source, store ->
      source.failNext = true
      val first = ketch.download(request("first"))
      runCurrent()
      assertIs<DownloadState.Failed>(first.state.value)
      val second = ketch.download(request("second"))
      runCurrent()
      first.resume()
      val third = ketch.download(request("third"))
      runCurrent()
      assertEquals(DownloadState.Queued, first.state.value)
      assertEquals(TaskState.QUEUED, store.load(first.taskId)?.state)
      source.complete("second")
      runCurrent()
      assertIs<DownloadState.Completed>(second.state.value)
      assertIs<DownloadState.Downloading>(first.state.value)
      source.complete("first")
      runCurrent()
      assertIs<DownloadState.Downloading>(third.state.value)
      assertEquals(1, source.maximumActive)
    }
  }

  @Test
  fun cancel_waitsForTransferCleanupBeforePromotingNext() = runTest {
    withKetch { ketch, source, _ ->
      source.cleanupGate = CompletableDeferred()
      val first = ketch.download(request("first"))
      runCurrent()
      val second = ketch.download(request("second"))
      val cancel = launch { first.cancel() }
      runCurrent()
      assertEquals(DownloadState.Queued, second.state.value)
      source.cleanupGate!!.complete(Unit)
      cancel.join()
      runCurrent()
      assertIs<DownloadState.Downloading>(second.state.value)
      assertEquals(1, source.maximumActive)
    }
  }

  @Test
  fun urgent_preemptsAndLaterResumesVictim() = runTest {
    withKetch { ketch, source, store ->
      val first = ketch.download(request("first"))
      runCurrent()
      val urgent = ketch.download(request("urgent").copy(priority = DownloadPriority.URGENT))
      runCurrent()
      assertEquals(DownloadState.Queued, first.state.value)
      assertEquals(TaskState.QUEUED, store.load(first.taskId)?.state)
      assertIs<DownloadState.Downloading>(urgent.state.value)
      source.complete("urgent")
      runCurrent()
      assertIs<DownloadState.Downloading>(first.state.value)
      assertEquals(1, source.maximumActive)
    }
  }

  private suspend fun TestScope.withKetch(
    block: suspend (Ketch, GatedSource, InMemoryTaskStore) -> Unit,
  ) {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val source = GatedSource()
    val store = InMemoryTaskStore()
    val ketch = Ketch(
      httpEngine = FakeHttpEngine(),
      taskStore = store,
      config = DownloadConfig(maxConcurrentDownloads = 1, retryCount = 0),
      additionalSources = listOf(source),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
    try {
      block(ketch, source, store)
    } finally {
      source.cleanupGate?.complete(Unit)
      ketch.close()
      runCurrent()
    }
  }

  private fun request(name: String): DownloadRequest = DownloadRequest(
    url = "fixture://host/$name",
    destination = Destination("/test/$name"),
  )

  private class GatedSource : DownloadSource {
    override val type = "fixture"
    override val managesOwnFileIo = true
    var maximumActive = 0
    var failNext = false
    var cleanupGate: CompletableDeferred<Unit>? = null
    private var active = 0
    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    fun complete(name: String) { gates.getValue(name).complete(Unit) }
    override fun canHandle(url: String): Boolean = url.startsWith("fixture:")
    override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource =
      ResolvedSource(
        url = url, sourceType = type, totalBytes = 4, supportsResume = true,
        suggestedFileName = "fixture.bin", maxSegments = 1,
      )

    override suspend fun download(context: DownloadContext) {
      if (failNext) {
        failNext = false
        throw KetchError.Unsupported()
      }
      active++
      maximumActive = maxOf(maximumActive, active)
      try {
        context.segments.value = listOf(Segment(index = 0, start = 0, end = 3))
        context.onProgress(0, 4)
        gates.getOrPut(context.url.substringAfterLast('/')) { CompletableDeferred() }.await()
        context.segments.value = listOf(Segment(index = 0, start = 0, end = 3, downloadedBytes = 4))
      } finally {
        withContext(NonCancellable) { cleanupGate?.await() }
        active--
      }
    }

    override suspend fun resume(context: DownloadContext, resumeState: SourceResumeState) =
      download(context)

    override fun buildResumeState(resolved: ResolvedSource, totalBytes: Long): SourceResumeState =
      SourceResumeState(type, "{}")
  }
}
