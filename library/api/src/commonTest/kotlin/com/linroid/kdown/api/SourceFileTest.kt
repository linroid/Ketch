package com.linroid.kdown.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceFileTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun serialization_roundTrip_allFields() {
    val original = SourceFile(
      id = "file-1",
      name = "Movie.mkv",
      size = 1500000000,
      metadata = mapOf(
        "path" to "Pack/Movie.mkv",
        "codec" to "h265",
      ),
    )
    val serialized = json.encodeToString(
      SourceFile.serializer(), original,
    )
    val deserialized = json.decodeFromString(
      SourceFile.serializer(), serialized,
    )
    assertEquals(original, deserialized)
  }

  @Test
  fun serialization_roundTrip_defaults() {
    val original = SourceFile(
      id = "track-1",
      name = "1080p MP4",
    )
    val serialized = json.encodeToString(
      SourceFile.serializer(), original,
    )
    val deserialized = json.decodeFromString(
      SourceFile.serializer(), serialized,
    )
    assertEquals(original, deserialized)
    assertEquals(-1L, deserialized.size)
    assertEquals(emptyMap(), deserialized.metadata)
  }

  @Test
  fun defaultSize_isNegativeOne() {
    val file = SourceFile(id = "a", name = "test")
    assertEquals(-1L, file.size)
  }

  @Test
  fun defaultMetadata_isEmpty() {
    val file = SourceFile(id = "a", name = "test")
    assertEquals(emptyMap(), file.metadata)
  }

  @Test
  fun equality_sameFields() {
    val a = SourceFile(
      id = "f1", name = "a.txt", size = 100,
    )
    val b = SourceFile(
      id = "f1", name = "a.txt", size = 100,
    )
    assertEquals(a, b)
  }
}
