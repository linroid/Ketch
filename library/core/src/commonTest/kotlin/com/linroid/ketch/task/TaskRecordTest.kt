package com.linroid.ketch.task

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.Segment
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

}
