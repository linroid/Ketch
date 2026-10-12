package com.linroid.ketch.torrent

import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [TorrentDownloadSource.buildResumeState].
 */
class TorrentDownloadSourceBuildResumeStateTest {
  private val source = TorrentDownloadSource()

  private fun resolved(infoHash: String, format: String?) = ResolvedSource(
    url = "torrent:$infoHash",
    sourceType = TorrentDownloadSource.TYPE,
    totalBytes = 30,
    supportsResume = true,
    suggestedFileName = "pack",
    maxSegments = 1,
    metadata = buildMap {
      put(TorrentDownloadSource.META_INFO_HASH, infoHash)
      put(ResolvedSource.METAINFO_KEY, "bWV0YWluZm8=")
      put(TorrentDownloadSource.META_PRIVACY, TorrentDiscoveryPrivacy.PUBLIC.name)
      format?.let { put("format", it) }
    },
    files = listOf(SourceFile("0", "a.bin", 10), SourceFile("2", "b.bin", 20)),
    selectionMode = FileSelectionMode.MULTIPLE,
  )

  @Test
  fun buildResumeState_v1_keepsMetainfoAndEveryFile() {
    val hash = "aabbccddee11223344556677889900aabbccddee"

    val state = source.buildResumeState(resolved(hash, format = null), totalBytes = 30)

    assertEquals(hash, state.infoHash)
    assertEquals(30, state.totalBytes)
    assertEquals(setOf("0", "2"), state.selectedFileIds)
    assertEquals("bWV0YWluZm8=", state.metainfo)
    assertEquals(2, state.version)
    assertEquals(TorrentDiscoveryPrivacy.PUBLIC, state.privacy)
  }

  @Test
  fun buildResumeState_v2_savesVersion3() {
    val hash = "ab".repeat(32)

    val state = source.buildResumeState(resolved(hash, format = "v2"), totalBytes = 10)

    assertEquals(3, state.version)
    assertEquals(10, state.totalBytes)
  }
}
