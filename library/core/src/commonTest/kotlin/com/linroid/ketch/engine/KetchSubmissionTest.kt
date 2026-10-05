package com.linroid.ketch.engine

import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.task.InMemoryTaskStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
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
}
