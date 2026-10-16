package com.linroid.ketch.engine

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.task.InMemoryTaskStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class KetchSubmissionTest {
  private val request = DownloadRequest(
    url = "https://example.com/file",
    requestId = "12345678-1234-1234-1234-123456789abc",
  )

  @Test
  fun download_concurrentRepeatedSubmission_createsOneTask() = runTest {
    val ketch = Ketch(httpEngine = FakeHttpEngine())
    try {
      val tasks = List(10) { async { ketch.download(request) } }.awaitAll()
      assertEquals(1, tasks.map { it.taskId }.distinct().size)
      assertEquals(1, ketch.tasks.value.size)
      assertFailsWith<IllegalArgumentException> {
        ketch.download(request.copy(url = "https://example.com/other"))
      }
    } finally {
      ketch.close()
    }
  }

  @Test
  fun download_linkNoSourceHandles_isRefusedUpFront() = runTest {
    val ketch = Ketch(httpEngine = FakeHttpEngine())
    try {
      // A name without a scheme, as a mistyped CLI or REST request sends it.
      for (url in listOf("wt-big-buck-bunny", "gopher://example.com/file")) {
        assertFailsWith<IllegalArgumentException> { ketch.download(DownloadRequest(url)) }
      }
      assertEquals(0, ketch.tasks.value.size)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun download_resolvedByASourceThatOnlyTakesContent_isAccepted() = runTest {
    val ketch = Ketch(httpEngine = FakeHttpEngine(), additionalSources = listOf(ContentSource()))
    try {
      // What resolving uploaded content gave: a link no source handles on its own.
      val resolved = ResolvedSource(url = "upload:1", sourceType = ContentSource.TYPE,
        totalBytes = 0, supportsResume = false, suggestedFileName = "upload.bin",
        maxSegments = 1)
      ketch.download(DownloadRequest(resolved.url, resolvedSource = resolved))
      assertFailsWith<IllegalArgumentException> { ketch.download(DownloadRequest(resolved.url)) }
      // A resolution of a type no source has is refused like a link.
      assertFailsWith<IllegalArgumentException> {
        ketch.download(DownloadRequest(resolved.url,
          resolvedSource = resolved.copy(sourceType = "gone")))
      }
      assertEquals(1, ketch.tasks.value.size)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun download_sameRequestIdAfterSelectionChange_returnsTheTask() = runTest {
    val source = SelectableSource()
    val ketch = Ketch(httpEngine = FakeHttpEngine(), additionalSources = listOf(source))
    try {
      val submitted = DownloadRequest(
        url = "pick:files",
        selectedFileIds = setOf("0"),
        schedule = DownloadSchedule.AfterDelay(60.seconds),
        resolvedSource = source.resolved("pick:files", "client"),
        requestId = "12345678-1234-1234-1234-123456789abd",
      )
      val task = ketch.download(submitted)
      task.selectFiles(setOf("0", "2"))
      assertEquals(setOf("0", "2"), task.request.selectedFileIds)

      assertEquals(task.taskId, ketch.download(submitted).taskId)
      assertEquals(task.taskId, ketch.download(submitted.copy(awaitFileSelection = true)).taskId)
      assertEquals(setOf("0", "2"), task.request.selectedFileIds)
      assertEquals(1, ketch.tasks.value.size)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun download_afterStoreRestart_returnsRetainedTask() = runTest {
    val store = InMemoryTaskStore()
    val first = Ketch(httpEngine = FakeHttpEngine(), taskStore = store)
    val task = first.download(request)
    val taskId = task.taskId
    task.setConnections(3)
    task.setSpeedLimit(SpeedLimit.of(1024))
    task.setPriority(DownloadPriority.HIGH)
    task.reschedule(DownloadSchedule.AfterDelay(60.seconds))
    assertEquals(taskId, first.download(request).taskId)
    assertEquals(3, task.request.connections)
    assertEquals(SpeedLimit.of(1024), task.request.speedLimit)
    assertEquals(DownloadPriority.HIGH, task.request.priority)
    first.close()
    val next = Ketch(httpEngine = FakeHttpEngine(), taskStore = store)
    try {
      next.start()
      assertEquals(taskId, next.download(request).taskId)
      assertEquals(1, store.loadAll().size)
      assertEquals(3, next.tasks.value.single().request.connections)
      assertEquals(SpeedLimit.of(1024), next.tasks.value.single().request.speedLimit)
      assertEquals(DownloadPriority.HIGH, next.tasks.value.single().request.priority)
      assertFailsWith<IllegalArgumentException> {
        next.download(request.copy(headers = mapOf("Cookie" to "different")))
      }
    } finally {
      next.close()
    }
  }

  /** A source that resolves uploaded content only, never a link. */
  private class ContentSource : DownloadSource<Unit> {
    override val stateSerializer = Unit.serializer()
    override val type = TYPE
    override fun canHandle(input: ResolveInput): Boolean = input is ResolveInput.Content
    override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource =
      error("Not resolved in this test")
    override suspend fun download(context: DownloadContext, state: Unit?) = context.onProgress(0, 0)

    companion object {
      const val TYPE = "content"
    }
  }
}
