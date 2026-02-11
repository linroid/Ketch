package com.linroid.kdown.task

import com.linroid.kdown.api.DownloadRequest
import com.linroid.kdown.core.task.InMemoryTaskStore
import com.linroid.kdown.core.task.TaskRecord
import com.linroid.kdown.core.task.TaskState
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryTaskStoreTest {

  private fun createRecord(
    taskId: String = "task-1",
    state: TaskState = TaskState.PENDING
  ) = TaskRecord(
    taskId = taskId,
    request = DownloadRequest(
      url = "https://example.com/file.bin",
      directory = Path("/tmp"),
      connections = 4,
      headers = mapOf("Authorization" to "Bearer token")
    ),
    destPath = Path("/tmp/file.bin"),
    state = state,
    totalBytes = 1000,
    downloadedBytes = 0,
    createdAt = Instant.fromEpochMilliseconds(1000),
    updatedAt = Instant.fromEpochMilliseconds(1000)
  )

  @Test
  fun load_returnsNullForMissingTask() = runTest {
    val store = InMemoryTaskStore()
    assertNull(store.load("nonexistent"))
  }

  @Test
  fun saveAndLoad_roundTrips() = runTest {
    val store = InMemoryTaskStore()
    val record = createRecord()
    store.save(record)
    assertEquals(record, store.load("task-1"))
  }

  @Test
  fun save_overwritesPrevious() = runTest {
    val store = InMemoryTaskStore()
    val record1 = createRecord()
    val record2 = record1.copy(state = TaskState.DOWNLOADING, downloadedBytes = 500)
    store.save(record1)
    store.save(record2)
    assertEquals(TaskState.DOWNLOADING, store.load("task-1")?.state)
    assertEquals(500, store.load("task-1")?.downloadedBytes)
  }

  @Test
  fun remove_removesEntry() = runTest {
    val store = InMemoryTaskStore()
    store.save(createRecord())
    store.remove("task-1")
    assertNull(store.load("task-1"))
  }

  @Test
  fun remove_nonexistent_doesNotThrow() = runTest {
    val store = InMemoryTaskStore()
    store.remove("nonexistent")
  }

  @Test
  fun loadAll_returnsAllEntries() = runTest {
    val store = InMemoryTaskStore()
    store.save(createRecord("task-1"))
    store.save(createRecord("task-2", TaskState.PAUSED))
    store.save(createRecord("task-3", TaskState.COMPLETED))

    val all = store.loadAll()
    assertEquals(3, all.size)
    assertEquals(
      setOf("task-1", "task-2", "task-3"),
      all.map { it.taskId }.toSet()
    )
  }

  @Test
  fun loadAll_returnsEmptyForEmptyStore() = runTest {
    val store = InMemoryTaskStore()
    assertTrue(store.loadAll().isEmpty())
  }

  @Test
  fun clearAll_removesAllEntries() = runTest {
    val store = InMemoryTaskStore()
    store.save(createRecord("task-1"))
    store.save(createRecord("task-2"))
    store.clearAll()
    assertTrue(store.loadAll().isEmpty())
  }

  @Test
  fun multipleTasks_independent() = runTest {
    val store = InMemoryTaskStore()
    val r1 = createRecord("task-1")
    val r2 = createRecord("task-2", TaskState.DOWNLOADING).copy(downloadedBytes = 500)
    store.save(r1)
    store.save(r2)
    assertEquals(r1, store.load("task-1"))
    assertEquals(r2, store.load("task-2"))
    store.remove("task-1")
    assertNull(store.load("task-1"))
    assertEquals(r2, store.load("task-2"))
  }
}
