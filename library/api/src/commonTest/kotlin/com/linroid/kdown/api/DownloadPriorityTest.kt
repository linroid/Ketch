package com.linroid.kdown.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadPriorityTest {

  @Test
  fun ordinal_ordering() {
    assertTrue(DownloadPriority.LOW.ordinal < DownloadPriority.NORMAL.ordinal)
    assertTrue(DownloadPriority.NORMAL.ordinal < DownloadPriority.HIGH.ordinal)
    assertTrue(DownloadPriority.HIGH.ordinal < DownloadPriority.URGENT.ordinal)
  }

  @Test
  fun values_containsAllPriorities() {
    val values = DownloadPriority.entries
    assertEquals(4, values.size)
    assertEquals(DownloadPriority.LOW, values[0])
    assertEquals(DownloadPriority.NORMAL, values[1])
    assertEquals(DownloadPriority.HIGH, values[2])
    assertEquals(DownloadPriority.URGENT, values[3])
  }

  @Test
  fun serialization_roundTrip() {
    val json = Json
    for (priority in DownloadPriority.entries) {
      val serialized = json.encodeToString(
        DownloadPriority.serializer(), priority
      )
      val deserialized = json.decodeFromString(
        DownloadPriority.serializer(), serialized
      )
      assertEquals(priority, deserialized)
    }
  }

  @Test
  fun serialization_asString() {
    val json = Json
    val serialized = json.encodeToString(
      DownloadPriority.serializer(), DownloadPriority.HIGH
    )
    assertEquals("\"HIGH\"", serialized)
  }

  @Test
  fun defaultPriority_inRequest_isNormal() {
    val request = DownloadRequest(
      url = "https://example.com/file.zip",
      directory = kotlinx.io.files.Path("/tmp")
    )
    assertEquals(DownloadPriority.NORMAL, request.priority)
  }

  @Test
  fun customPriority_inRequest_isPreserved() {
    val request = DownloadRequest(
      url = "https://example.com/file.zip",
      directory = kotlinx.io.files.Path("/tmp"),
      priority = DownloadPriority.URGENT
    )
    assertEquals(DownloadPriority.URGENT, request.priority)
  }
}
