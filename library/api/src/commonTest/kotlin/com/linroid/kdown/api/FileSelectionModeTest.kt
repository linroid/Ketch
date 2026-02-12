package com.linroid.kdown.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class FileSelectionModeTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun serialization_roundTrip_multiple() {
    val original = FileSelectionMode.MULTIPLE
    val serialized = json.encodeToString(
      FileSelectionMode.serializer(), original,
    )
    val deserialized = json.decodeFromString(
      FileSelectionMode.serializer(), serialized,
    )
    assertEquals(original, deserialized)
  }

  @Test
  fun serialization_roundTrip_single() {
    val original = FileSelectionMode.SINGLE
    val serialized = json.encodeToString(
      FileSelectionMode.serializer(), original,
    )
    val deserialized = json.decodeFromString(
      FileSelectionMode.serializer(), serialized,
    )
    assertEquals(original, deserialized)
  }

  @Test
  fun values_containsBothModes() {
    val values = FileSelectionMode.entries
    assertEquals(2, values.size)
    assertEquals(FileSelectionMode.MULTIPLE, values[0])
    assertEquals(FileSelectionMode.SINGLE, values[1])
  }
}
