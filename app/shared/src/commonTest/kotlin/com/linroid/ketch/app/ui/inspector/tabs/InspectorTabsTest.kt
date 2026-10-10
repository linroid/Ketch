package com.linroid.ketch.app.ui.inspector.tabs

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.app.state.ListFixtures.row
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InspectorTabsTest {
  private val waiting =
    DownloadState.Paused(DownloadProgress(0, 0), PauseReason.AwaitingFileSelection)

  @Test
  fun inspectorTabs_waitingTorrent_showsFiles() {
    val listed = row("t", waiting, request = magnet(files = FILES))
    val unlisted = row("u", waiting, request = magnet(files = null))
    val control = setOf(KetchFeatures.TORRENT_FILE_SELECTION, KetchFeatures.TORRENT_CONTROL)

    assertEquals(
      listOf(InspectorTab.Overview, InspectorTab.Files),
      inspectorTabs(listed, null, emptyList(), control)
    )
    assertEquals(3, InspectorTab.Files.count(listed, control))
    // Without a file list, a device with torrent controls lists the files itself.
    assertEquals(
      listOf(InspectorTab.Overview, InspectorTab.Files),
      inspectorTabs(unlisted, null, emptyList(), control)
    )
    assertNull(InspectorTab.Files.count(unlisted, control))
    assertEquals(listOf(InspectorTab.Overview), inspectorTabs(unlisted, null, emptyList()))
  }

  @Test
  fun inspectorTabs_selectableTorrent_countsEveryFile() {
    val selection = magnet(files = FILES).copy(selectedFileIds = setOf("0"))
    val queued = row("t", DownloadState.Queued, request = selection)
    val features = setOf(KetchFeatures.TORRENT_FILE_SELECTION)

    assertEquals(3, InspectorTab.Files.count(queued, features))
    assertEquals(1, InspectorTab.Files.count(queued))
  }

  private fun magnet(files: List<SourceFile>?): DownloadRequest {
    val source = files?.let {
      ResolvedSource(
        url = URL,
        sourceType = "torrent",
        totalBytes = it.sumOf { file -> file.size },
        supportsResume = true,
        suggestedFileName = "Show",
        maxSegments = 1,
        files = it,
      )
    }
    return DownloadRequest(url = URL, resolvedSource = source)
  }

  private companion object {
    const val URL = "magnet:?xt=urn:btih:4c7f3e2b9d1a8f6e5c0b7a3d2e1f9c8b7a6d5e4f&dn=Show"
    val FILES = listOf(
      SourceFile("0", "Show/S01E01.mkv", 100),
      SourceFile("1", "Show/S01E02.mkv", 100),
      SourceFile("2", "Show/info.nfo", 2),
    )
  }
}
