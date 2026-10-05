package com.linroid.ketch.engine

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.task.InMemoryTaskStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    val taskId = first.download(request).taskId
    first.close()
    val next = Ketch(httpEngine = FakeHttpEngine(), taskStore = store)
    try {
      next.start()
      assertEquals(taskId, next.download(request).taskId)
      assertEquals(1, store.loadAll().size)
    } finally {
      next.close()
    }
  }
}
