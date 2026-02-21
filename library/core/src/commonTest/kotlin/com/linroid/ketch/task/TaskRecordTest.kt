package com.linroid.ketch.task

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.engine.HttpDownloadSource
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskState
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class TaskRecordTest {

  private val json = Json { ignoreUnknownKeys = true }

  private fun defaultRequest(
    url: String = "https://example.com/file.bin",
    connections: Int = 1,
    headers: Map<String, String> = emptyMap(),
  ) = DownloadRequest(
    url = url,
    destination = Destination("/tmp/"),
    connections = connections,
    headers = headers,
  )

  @Test
  fun defaultValues() {
    val record = TaskRecord(
      taskId = "test-1",
      request = defaultRequest(),
      outputPath = "/tmp/file.bin",
      createdAt = Instant.fromEpochMilliseconds(1000),
      updatedAt = Instant.fromEpochMilliseconds(1000),
    )
    assertEquals(1, record.request.connections)
    assertEquals(emptyMap(), record.request.headers)
    assertEquals(TaskState.QUEUED, record.state)
    assertEquals(-1, record.totalBytes)
    assertEquals(0, record.downloadedBytes)
    assertNull(record.errorMessage)
    assertNull(record.acceptRanges)
    assertNull(record.etag)
    assertNull(record.lastModified)
    assertNull(record.segments)
    assertNull(record.sourceType)
    assertNull(record.sourceResumeState)
  }

  @Test
  fun serialization_roundTrips() {
    val record = TaskRecord(
      taskId = "test-1",
      request = defaultRequest(
        connections = 8,
        headers = mapOf("X-Custom" to "value"),
      ),
      outputPath = "/tmp/file.bin",
      state = TaskState.PAUSED,
      totalBytes = 2048,
      downloadedBytes = 1024,
      errorMessage = null,
      createdAt = Instant.fromEpochMilliseconds(1000),
      updatedAt = Instant.fromEpochMilliseconds(2000),
    )

    val serialized = json.encodeToString(
      TaskRecord.serializer(), record
    )
    val deserialized = json.decodeFromString(
      TaskRecord.serializer(), serialized
    )

    assertEquals(record.taskId, deserialized.taskId)
    assertEquals(record.request.url, deserialized.request.url)
    assertEquals(
      record.outputPath,
      deserialized.outputPath
    )
    assertEquals(record.request.connections, deserialized.request.connections)
    assertEquals(record.request.headers, deserialized.request.headers)
    assertEquals(record.state, deserialized.state)
    assertEquals(record.totalBytes, deserialized.totalBytes)
    assertEquals(record.downloadedBytes, deserialized.downloadedBytes)
    assertEquals(record.errorMessage, deserialized.errorMessage)
    assertEquals(record.acceptRanges, deserialized.acceptRanges)
    assertEquals(record.etag, deserialized.etag)
    assertEquals(record.lastModified, deserialized.lastModified)
    assertEquals(record.createdAt, deserialized.createdAt)
    assertEquals(record.updatedAt, deserialized.updatedAt)
  }

  @Test
  fun serialization_withErrorMessage() {
    val record = TaskRecord(
      taskId = "test-1",
      request = defaultRequest(),
      outputPath = "/tmp/file.bin",
      state = TaskState.FAILED,
      errorMessage = "Network timeout",
      createdAt = Instant.fromEpochMilliseconds(1000),
      updatedAt = Instant.fromEpochMilliseconds(2000),
    )

    val serialized = json.encodeToString(
      TaskRecord.serializer(), record
    )
    val deserialized = json.decodeFromString(
      TaskRecord.serializer(), serialized
    )

    assertEquals("Network timeout", deserialized.errorMessage)
    assertEquals(TaskState.FAILED, deserialized.state)
  }

  @Test
  fun copy_preservesValues() {
    val created = Instant.fromEpochMilliseconds(1000)
    val original = TaskRecord(
      taskId = "test-1",
      request = defaultRequest(),
      outputPath = "/tmp/file.bin",
      state = TaskState.DOWNLOADING,
      totalBytes = 1000,
      downloadedBytes = 500,
      createdAt = created,
      updatedAt = Instant.fromEpochMilliseconds(1000),
    )

    val newUpdated = Instant.fromEpochMilliseconds(2000)
    val updated = original.copy(
      state = TaskState.PAUSED,
      downloadedBytes = 600,
      updatedAt = newUpdated,
    )

    assertEquals("test-1", updated.taskId)
    assertEquals("https://example.com/file.bin", updated.request.url)
    assertEquals(TaskState.PAUSED, updated.state)
    assertEquals(600, updated.downloadedBytes)
    assertEquals(created, updated.createdAt)
    assertEquals(newUpdated, updated.updatedAt)
  }

  @Test
  fun serialization_withServerInfoFields() {
    val record = TaskRecord(
      taskId = "test-1",
      request = defaultRequest(),
      outputPath = "/tmp/file.bin",
      state = TaskState.DOWNLOADING,
      totalBytes = 2048,
      acceptRanges = true,
      etag = "\"abc123\"",
      lastModified = "Wed, 21 Oct 2023 07:28:00 GMT",
      createdAt = Instant.fromEpochMilliseconds(1000),
      updatedAt = Instant.fromEpochMilliseconds(2000),
    )

    val serialized = json.encodeToString(
      TaskRecord.serializer(), record
    )
    val deserialized = json.decodeFromString(
      TaskRecord.serializer(), serialized
    )

    assertEquals(true, deserialized.acceptRanges)
    assertEquals("\"abc123\"", deserialized.etag)
    assertEquals(
      "Wed, 21 Oct 2023 07:28:00 GMT",
      deserialized.lastModified
    )
  }

  @Test
  fun serialization_withSegments() {
    val segments = listOf(
      Segment(index = 0, start = 0, end = 499, downloadedBytes = 200),
      Segment(index = 1, start = 500, end = 999, downloadedBytes = 300),
    )
    val record = TaskRecord(
      taskId = "test-1",
      request = defaultRequest(),
      outputPath = "/tmp/file.bin",
      state = TaskState.PAUSED,
      totalBytes = 1000,
      downloadedBytes = 500,
      segments = segments,
      createdAt = Instant.fromEpochMilliseconds(1000),
      updatedAt = Instant.fromEpochMilliseconds(2000),
    )

    val serialized = json.encodeToString(
      TaskRecord.serializer(), record
    )
    val deserialized = json.decodeFromString(
      TaskRecord.serializer(), serialized
    )

    assertEquals(segments, deserialized.segments)
    assertEquals(2, deserialized.segments?.size)
    assertEquals(200, deserialized.segments?.get(0)?.downloadedBytes)
    assertEquals(300, deserialized.segments?.get(1)?.downloadedBytes)
  }

  @Test
  fun deserialization_withoutSegments_defaultsToNull() {
    val epoch = Instant.fromEpochMilliseconds(0)
    val jsonStr = """
      {
        "taskId": "t1",
        "request": {
          "url": "https://example.com/f",
          "destination": "/tmp/",
          "connections": 4,
          "headers": {},
          "properties": {}
        },
        "outputPath": "/tmp/f",
        "state": "COMPLETED",
        "totalBytes": 1000,
        "downloadedBytes": 1000,
        "createdAt": "$epoch",
        "updatedAt": "$epoch"
      }
    """.trimIndent()
    val record = json.decodeFromString<TaskRecord>(jsonStr)
    assertNull(record.segments)
  }

  @Test
  fun serialization_withSourceType() {
    val record = TaskRecord(
      taskId = "test-1",
      request = defaultRequest(),
      outputPath = "/tmp/file.bin",
      state = TaskState.DOWNLOADING,
      totalBytes = 2048,
      sourceType = "http",
      createdAt = Instant.fromEpochMilliseconds(1000),
      updatedAt = Instant.fromEpochMilliseconds(2000),
    )

    val serialized = json.encodeToString(
      TaskRecord.serializer(), record
    )
    val deserialized = json.decodeFromString(
      TaskRecord.serializer(), serialized
    )

    assertEquals("http", deserialized.sourceType)
  }

  @Test
  fun serialization_withSourceResumeState() {
    val resumeState = HttpDownloadSource.buildResumeState(
      etag = "\"abc123\"",
      lastModified = "Wed, 21 Oct 2023 07:28:00 GMT",
      totalBytes = 2048,
    )
    val record = TaskRecord(
      taskId = "test-1",
      request = defaultRequest(),
      outputPath = "/tmp/file.bin",
      state = TaskState.COMPLETED,
      totalBytes = 2048,
      downloadedBytes = 2048,
      sourceType = "http",
      sourceResumeState = resumeState,
      createdAt = Instant.fromEpochMilliseconds(1000),
      updatedAt = Instant.fromEpochMilliseconds(2000),
    )

    val serialized = json.encodeToString(
      TaskRecord.serializer(), record
    )
    val deserialized = json.decodeFromString(
      TaskRecord.serializer(), serialized
    )

    assertEquals("http", deserialized.sourceResumeState?.sourceType)
    val httpState = Json.decodeFromString<HttpDownloadSource.HttpResumeState>(
      deserialized.sourceResumeState!!.data
    )
    assertEquals("\"abc123\"", httpState.etag)
    assertEquals(
      "Wed, 21 Oct 2023 07:28:00 GMT",
      httpState.lastModified
    )
    assertEquals(2048, httpState.totalBytes)
  }

  @Test
  fun deserialization_withoutSourceFields_defaultsToNull() {
    val epoch = Instant.fromEpochMilliseconds(0)
    val jsonStr = """
      {
        "taskId": "t1",
        "request": {
          "url": "https://example.com/f",
          "destination": "/tmp/",
          "connections": 4,
          "headers": {},
          "properties": {}
        },
        "outputPath": "/tmp/f",
        "state": "COMPLETED",
        "totalBytes": 1000,
        "downloadedBytes": 1000,
        "createdAt": "$epoch",
        "updatedAt": "$epoch"
      }
    """.trimIndent()
    val record = json.decodeFromString<TaskRecord>(jsonStr)
    assertNull(record.sourceType)
    assertNull(record.sourceResumeState)
  }

  @Test
  fun deserialization_withoutServerInfoFields_defaultsToNull() {
    val epoch = Instant.fromEpochMilliseconds(0)
    val jsonStr = """
      {
        "taskId": "t1",
        "request": {
          "url": "https://example.com/f",
          "destination": "/tmp/",
          "connections": 4,
          "headers": {},
          "properties": {}
        },
        "outputPath": "/tmp/f",
        "state": "QUEUED",
        "totalBytes": 100,
        "downloadedBytes": 0,
        "createdAt": "$epoch",
        "updatedAt": "$epoch"
      }
    """.trimIndent()
    val record = json.decodeFromString<TaskRecord>(jsonStr)
    assertNull(record.acceptRanges)
    assertNull(record.etag)
    assertNull(record.lastModified)
  }

}
