package com.linroid.ketch.torrent

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [TorrentDownloadSource.buildResumeState].
 */
class TorrentDownloadSourceBuildResumeStateTest {

  @Test
  fun buildResumeState_createsValidSourceResumeState() {
    val resumeData = byteArrayOf(1, 2, 3, 4, 5)
    val state = TorrentDownloadSource.buildResumeState(
      infoHash = "aabbccddee11223344556677889900aabbccddee",
      totalBytes = 5000L,
      resumeData = resumeData,
      selectedFileIds = setOf("0", "2"),
      savePath = "/downloads/torrent",
    )
    assertEquals("torrent", state.sourceType)

    // Verify the data can be deserialized back
    val parsed =
      Json.decodeFromString<TorrentResumeState>(state.data)
    assertEquals(
      "aabbccddee11223344556677889900aabbccddee",
      parsed.infoHash,
    )
    assertEquals(5000L, parsed.totalBytes)
    assertEquals(setOf("0", "2"), parsed.selectedFileIds)
    assertEquals("/downloads/torrent", parsed.savePath)
    // resumeData should be base64-encoded, not empty
    assert(parsed.resumeData.isNotEmpty())
  }

  @Test
  fun buildResumeState_emptyResumeData() {
    val state = TorrentDownloadSource.buildResumeState(
      infoHash = "0123456789abcdef0123456789abcdef01234567",
      totalBytes = 0L,
      resumeData = ByteArray(0),
      selectedFileIds = emptySet(),
      savePath = "/tmp",
    )
    assertEquals("torrent", state.sourceType)

    val parsed =
      Json.decodeFromString<TorrentResumeState>(state.data)
    assertEquals(0L, parsed.totalBytes)
    assertEquals(emptySet(), parsed.selectedFileIds)
  }

  @Test
  fun buildResumeState_allFileIdsPreserved() {
    val ids = (0 until 50).map { it.toString() }.toSet()
    val state = TorrentDownloadSource.buildResumeState(
      infoHash = "aabbccddee11223344556677889900aabbccddee",
      totalBytes = 100_000L,
      resumeData = ByteArray(16) { it.toByte() },
      selectedFileIds = ids,
      savePath = "/data",
    )
    val parsed =
      Json.decodeFromString<TorrentResumeState>(state.data)
    assertEquals(50, parsed.selectedFileIds.size)
    assertEquals(ids, parsed.selectedFileIds)
  }
}
