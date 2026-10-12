package com.linroid.ketch.torrent

import com.linroid.ketch.core.engine.SourceState
import com.linroid.ketch.core.engine.decodeState
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class TorrentSourceStateTest {

  @Test
  fun serialization_roundtrip() {
    val state = TorrentSourceState(
      infoHash = "aabbccddee11223344556677889900aabbccddee",
      totalBytes = 1024000,
      resumeData = "base64encodeddata==",
      selectedFileIds = setOf("0", "2", "5"),
      savePath = "/downloads/torrent",
    )
    val json = Json.encodeToString(state)
    val decoded = Json.decodeFromString<TorrentSourceState>(json)
    assertEquals(state, decoded)
  }

  @Test
  fun deserialization_preservesSelectedFileIds() {
    val state = TorrentSourceState(
      infoHash = "0123456789abcdef0123456789abcdef01234567",
      totalBytes = 5000,
      resumeData = "data",
      selectedFileIds = setOf("1", "3"),
      savePath = "/tmp",
    )
    val json = Json.encodeToString(state)
    val decoded = Json.decodeFromString<TorrentSourceState>(json)
    assertEquals(setOf("1", "3"), decoded.selectedFileIds)
  }

  @Test
  fun deserialization_emptySelectedFileIds() {
    val state = TorrentSourceState(
      infoHash = "0123456789abcdef0123456789abcdef01234567",
      totalBytes = 0,
      resumeData = "",
      selectedFileIds = emptySet(),
      savePath = "/tmp",
    )
    val json = Json.encodeToString(state)
    val decoded = Json.decodeFromString<TorrentSourceState>(json)
    assertEquals(emptySet(), decoded.selectedFileIds)
  }

  @Test
  fun decode_unknownKeys_areIgnored() {
    // A newer build may add fields; every decode site reads them leniently.
    val state = TorrentDownloadSource().decodeState(SourceState(
      TorrentDownloadSource.TYPE,
      """
      {"infoHash":"0123456789abcdef0123456789abcdef01234567","totalBytes":7,
       "resumeData":"","selectedFileIds":["1"],"savePath":"/tmp","metainfo":"",
       "version":2,"privacy":"PUBLIC","selectionGeneration":4,"seeding":{"intent":true}}
      """.trimIndent(),
    ))
    assertEquals(setOf("1"), state.selectedFileIds)
    assertEquals(2, state.version)
    assertEquals(TorrentDiscoveryPrivacy.PUBLIC, state.privacy)
  }
}
