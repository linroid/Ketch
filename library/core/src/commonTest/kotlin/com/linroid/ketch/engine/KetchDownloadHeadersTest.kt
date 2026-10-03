package com.linroid.ketch.engine

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.task.InMemoryTaskStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KetchDownloadHeadersTest {
  @Test
  fun download_headerWithLineBreak_isRejectedBeforeTaskIsSaved() = runTest {
    val store = InMemoryTaskStore()
    val ketch = Ketch(httpEngine = FakeHttpEngine(), taskStore = store)
    try {
      val request = DownloadRequest(
        url = "https://example.com/file",
        headers = mapOf("Cookie" to "sid=1\r\nX-Injected: 1"),
      )

      assertFailsWith<IllegalArgumentException> { ketch.download(request) }

      assertTrue(ketch.tasks.value.isEmpty())
      assertTrue(store.loadAll().isEmpty())
    } finally {
      ketch.close()
    }
  }
}
