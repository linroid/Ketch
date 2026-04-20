package com.linroid.ketch.torrent

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Additional edge case tests for [TorrentResumeState] serialization
 * beyond the basics covered in [TorrentResumeStateTest].
 */
class TorrentResumeStateEdgeCaseTest {

  private val lenientJson = Json { ignoreUnknownKeys = true }

  @Test
  fun deserialization_unknownFields_ignoredWithLenientJson() {
    val json = """
      {
        "infoHash": "aabbccddee11223344556677889900aabbccddee",
        "totalBytes": 1000,
        "resumeData": "base64data",
        "selectedFileIds": ["0"],
        "savePath": "/tmp",
        "futureField": "unknown"
      }
    """.trimIndent()
    val state = lenientJson.decodeFromString<TorrentResumeState>(json)
    assertEquals(
      "aabbccddee11223344556677889900aabbccddee",
      state.infoHash,
    )
    assertEquals(1000L, state.totalBytes)
  }

  @Test
  fun deserialization_missingRequiredField_throws() {
    // Missing "savePath"
    val json = """
      {
        "infoHash": "aabbccddee11223344556677889900aabbccddee",
        "totalBytes": 1000,
        "resumeData": "base64data",
        "selectedFileIds": ["0"]
      }
    """.trimIndent()
    assertFailsWith<Exception> {
      Json.decodeFromString<TorrentResumeState>(json)
    }
  }

  @Test
  fun serialization_largeResumeData() {
    val largeData = "A".repeat(100_000)
    val state = TorrentResumeState(
      infoHash = "aabbccddee11223344556677889900aabbccddee",
      totalBytes = 500L,
      resumeData = largeData,
      selectedFileIds = setOf("0"),
      savePath = "/tmp",
    )
    val json = Json.encodeToString(state)
    val decoded = Json.decodeFromString<TorrentResumeState>(json)
    assertEquals(largeData, decoded.resumeData)
  }

  @Test
  fun serialization_manySelectedFileIds() {
    val ids = (0 until 100).map { it.toString() }.toSet()
    val state = TorrentResumeState(
      infoHash = "aabbccddee11223344556677889900aabbccddee",
      totalBytes = 10_000L,
      resumeData = "data",
      selectedFileIds = ids,
      savePath = "/downloads",
    )
    val json = Json.encodeToString(state)
    val decoded = Json.decodeFromString<TorrentResumeState>(json)
    assertEquals(100, decoded.selectedFileIds.size)
    assertEquals(ids, decoded.selectedFileIds)
  }

  @Test
  fun serialization_specialCharsInSavePath() {
    val path = "/path/with spaces/and-special_chars/日本語"
    val state = TorrentResumeState(
      infoHash = "aabbccddee11223344556677889900aabbccddee",
      totalBytes = 0,
      resumeData = "",
      selectedFileIds = emptySet(),
      savePath = path,
    )
    val json = Json.encodeToString(state)
    val decoded = Json.decodeFromString<TorrentResumeState>(json)
    assertEquals(path, decoded.savePath)
  }

  @Test
  fun serialization_zeroTotalBytes() {
    val state = TorrentResumeState(
      infoHash = "aabbccddee11223344556677889900aabbccddee",
      totalBytes = 0L,
      resumeData = "",
      selectedFileIds = emptySet(),
      savePath = "/tmp",
    )
    val json = Json.encodeToString(state)
    val decoded = Json.decodeFromString<TorrentResumeState>(json)
    assertEquals(0L, decoded.totalBytes)
  }

  @Test
  fun serialization_maxLongTotalBytes() {
    val state = TorrentResumeState(
      infoHash = "aabbccddee11223344556677889900aabbccddee",
      totalBytes = Long.MAX_VALUE,
      resumeData = "data",
      selectedFileIds = setOf("0"),
      savePath = "/tmp",
    )
    val json = Json.encodeToString(state)
    val decoded = Json.decodeFromString<TorrentResumeState>(json)
    assertEquals(Long.MAX_VALUE, decoded.totalBytes)
  }
}
