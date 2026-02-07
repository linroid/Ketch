package com.linroid.kdown.internal

import com.linroid.kdown.model.DownloadMetadata
import com.linroid.kdown.model.Segment
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InMemoryMetadataStoreTest {

  private fun createMetadata(taskId: String = "task-1") = DownloadMetadata(
    taskId = taskId,
    url = "https://example.com/file.bin",
    destPath = Path("/tmp/file.bin"),
    totalBytes = 1000,
    acceptRanges = true,
    etag = "\"abc\"",
    lastModified = null,
    segments = listOf(Segment(index = 0, start = 0, end = 999)),
    createdAt = 1000L,
    updatedAt = 1000L
  )

  @Test
  fun load_returnsNullForMissingTask() = runTest {
    val store = InMemoryMetadataStore()
    assertNull(store.load("nonexistent"))
  }

  @Test
  fun saveAndLoad_roundTrips() = runTest {
    val store = InMemoryMetadataStore()
    val metadata = createMetadata()
    store.save("task-1", metadata)
    assertEquals(metadata, store.load("task-1"))
  }

  @Test
  fun save_overwritesPrevious() = runTest {
    val store = InMemoryMetadataStore()
    val metadata1 = createMetadata()
    val metadata2 = metadata1.copy(totalBytes = 2000)
    store.save("task-1", metadata1)
    store.save("task-1", metadata2)
    assertEquals(2000, store.load("task-1")?.totalBytes)
  }

  @Test
  fun clear_removesEntry() = runTest {
    val store = InMemoryMetadataStore()
    store.save("task-1", createMetadata())
    store.clear("task-1")
    assertNull(store.load("task-1"))
  }

  @Test
  fun clear_nonexistent_doesNotThrow() = runTest {
    val store = InMemoryMetadataStore()
    store.clear("nonexistent")
  }

  @Test
  fun clearAll_removesAllEntries() = runTest {
    val store = InMemoryMetadataStore()
    store.save("task-1", createMetadata("task-1"))
    store.save("task-2", createMetadata("task-2"))
    store.clearAll()
    assertNull(store.load("task-1"))
    assertNull(store.load("task-2"))
  }

  @Test
  fun multipleTasks_independent() = runTest {
    val store = InMemoryMetadataStore()
    val m1 = createMetadata("task-1")
    val m2 = createMetadata("task-2").copy(totalBytes = 2000)
    store.save("task-1", m1)
    store.save("task-2", m2)
    assertEquals(m1, store.load("task-1"))
    assertEquals(m2, store.load("task-2"))
    store.clear("task-1")
    assertNull(store.load("task-1"))
    assertEquals(m2, store.load("task-2"))
  }
}
