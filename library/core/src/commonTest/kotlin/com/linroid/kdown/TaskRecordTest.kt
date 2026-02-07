package com.linroid.kdown

import com.linroid.kdown.model.TaskRecord
import com.linroid.kdown.model.TaskState
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaskRecordTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun defaultValues() {
    val record = TaskRecord(
      taskId = "test-1",
      url = "https://example.com/file.bin",
      destPath = Path("/tmp/file.bin"),
      createdAt = 1000L,
      updatedAt = 1000L
    )
    assertEquals(4, record.connections)
    assertEquals(emptyMap(), record.headers)
    assertEquals(TaskState.PENDING, record.state)
    assertEquals(-1, record.totalBytes)
    assertEquals(0, record.downloadedBytes)
    assertNull(record.errorMessage)
  }

  @Test
  fun serialization_roundTrips() {
    val record = TaskRecord(
      taskId = "test-1",
      url = "https://example.com/file.bin",
      destPath = Path("/tmp/file.bin"),
      connections = 8,
      headers = mapOf("X-Custom" to "value"),
      state = TaskState.PAUSED,
      totalBytes = 2048,
      downloadedBytes = 1024,
      errorMessage = null,
      createdAt = 1000L,
      updatedAt = 2000L
    )

    val serialized = json.encodeToString(TaskRecord.serializer(), record)
    val deserialized = json.decodeFromString(TaskRecord.serializer(), serialized)

    assertEquals(record.taskId, deserialized.taskId)
    assertEquals(record.url, deserialized.url)
    assertEquals(record.destPath.toString(), deserialized.destPath.toString())
    assertEquals(record.connections, deserialized.connections)
    assertEquals(record.headers, deserialized.headers)
    assertEquals(record.state, deserialized.state)
    assertEquals(record.totalBytes, deserialized.totalBytes)
    assertEquals(record.downloadedBytes, deserialized.downloadedBytes)
    assertEquals(record.errorMessage, deserialized.errorMessage)
    assertEquals(record.createdAt, deserialized.createdAt)
    assertEquals(record.updatedAt, deserialized.updatedAt)
  }

  @Test
  fun serialization_withErrorMessage() {
    val record = TaskRecord(
      taskId = "test-1",
      url = "https://example.com/file.bin",
      destPath = Path("/tmp/file.bin"),
      state = TaskState.FAILED,
      errorMessage = "Network timeout",
      createdAt = 1000L,
      updatedAt = 2000L
    )

    val serialized = json.encodeToString(TaskRecord.serializer(), record)
    val deserialized = json.decodeFromString(TaskRecord.serializer(), serialized)

    assertEquals("Network timeout", deserialized.errorMessage)
    assertEquals(TaskState.FAILED, deserialized.state)
  }

  @Test
  fun copy_preservesValues() {
    val original = TaskRecord(
      taskId = "test-1",
      url = "https://example.com/file.bin",
      destPath = Path("/tmp/file.bin"),
      connections = 4,
      state = TaskState.DOWNLOADING,
      totalBytes = 1000,
      downloadedBytes = 500,
      createdAt = 1000L,
      updatedAt = 1000L
    )

    val updated = original.copy(
      state = TaskState.PAUSED,
      downloadedBytes = 600,
      updatedAt = 2000L
    )

    assertEquals("test-1", updated.taskId)
    assertEquals("https://example.com/file.bin", updated.url)
    assertEquals(TaskState.PAUSED, updated.state)
    assertEquals(600, updated.downloadedBytes)
    assertEquals(1000L, updated.createdAt)
    assertEquals(2000L, updated.updatedAt)
  }
}
