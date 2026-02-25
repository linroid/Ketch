package com.linroid.ketch.torrent

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class TorrentResumeStateTest {

  @Test
  fun serialization_roundtrip() {
    val state = TorrentResumeState(
      infoHash = "aabbccddee11223344556677889900aabbccddee",
      totalBytes = 1024000,
      resumeData = "base64encodeddata==",
      selectedFileIds = setOf("0", "2", "5"),
      savePath = "/downloads/torrent",
    )
    val json = Json.encodeToString(state)
    val decoded = Json.decodeFromString<TorrentResumeState>(json)
    assertEquals(state, decoded)
  }

  @Test
  fun deserialization_preservesSelectedFileIds() {
    val state = TorrentResumeState(
      infoHash = "0123456789abcdef0123456789abcdef01234567",
      totalBytes = 5000,
      resumeData = "data",
      selectedFileIds = setOf("1", "3"),
      savePath = "/tmp",
    )
    val json = Json.encodeToString(state)
    val decoded = Json.decodeFromString<TorrentResumeState>(json)
    assertEquals(setOf("1", "3"), decoded.selectedFileIds)
  }

  @Test
  fun deserialization_emptySelectedFileIds() {
    val state = TorrentResumeState(
      infoHash = "0123456789abcdef0123456789abcdef01234567",
      totalBytes = 0,
      resumeData = "",
      selectedFileIds = emptySet(),
      savePath = "/tmp",
    )
    val json = Json.encodeToString(state)
    val decoded = Json.decodeFromString<TorrentResumeState>(json)
    assertEquals(emptySet(), decoded.selectedFileIds)
  }
}
