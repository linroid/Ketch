package com.linroid.ketch.engine

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.task.InMemoryTaskStore
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class KetchStartTest {

  // A daemon listens before it restores its tasks, so a client can add a download while the
  // records load.
  @Test
  fun start_downloadAddedWhileRecordsLoad_staysInTheList() = runTest {
    val store = GatedTaskStore(InMemoryTaskStore())
    val ketch = Ketch(httpEngine = FakeHttpEngine(), taskStore = store)
    try {
      val starting = async { ketch.start() }
      store.loading.await()

      val added = ketch.download(DownloadRequest(url = "https://example.com/file"))
      store.release.complete(Unit)
      starting.await()

      assertEquals(listOf(added.taskId), ketch.tasks.value.map { it.taskId })
    } finally {
      ketch.close()
    }
  }

  /** Returns the records saved before [loadAll] was called, once [release] completes. */
  private class GatedTaskStore(private val inner: TaskStore) : TaskStore by inner {
    val loading = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    override suspend fun loadAll(): List<TaskRecord> {
      val snapshot = inner.loadAll()
      loading.complete(Unit)
      release.await()
      return snapshot
    }
  }
}
