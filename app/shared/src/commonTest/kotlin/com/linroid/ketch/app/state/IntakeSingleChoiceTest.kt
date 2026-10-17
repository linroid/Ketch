package com.linroid.ketch.app.state

import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.app.platform.DroppedFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Rows whose source downloads one of several files, as a Metalink does. */
class IntakeSingleChoiceTest {
  private fun entry(mode: FileSelectionMode) =
    IntakeEntry(IntakeSource.Link("https://example.com/release.meta4")).apply {
      status = IntakeStatus.Ready(
        ResolvedSource(
          url = "https://example.com/release.meta4", sourceType = "metalink", totalBytes = 10,
          supportsResume = true, suggestedFileName = "a.iso", maxSegments = 4,
          files = listOf(SourceFile("0", "a.iso", 10), SourceFile("1", "b.iso", 20)),
          selectionMode = mode,
        ),
      )
    }

  @Test
  fun selectedFiles_singleChoice_keepsTheFileJustChecked() {
    val entry = entry(FileSelectionMode.SINGLE)
    assertTrue(entry.singleChoice)
    entry.selectedFiles = setOf("0")
    entry.selectedFiles = setOf("0", "1")
    assertEquals(setOf("1"), entry.selectedFiles)
    entry.selectedFiles = setOf("0", "1")
    assertEquals(setOf("0"), entry.selectedFiles)
  }

  @Test
  fun selectedFiles_anySubset_keepsThemAll() {
    val entry = entry(FileSelectionMode.MULTIPLE)
    assertFalse(entry.singleChoice)
    entry.selectedFiles = setOf("0", "1")
    assertEquals(setOf("0", "1"), entry.selectedFiles)
  }

  @Test
  fun isTorrent_metalinkFile_isNot() {
    fun file(name: String) =
      IntakeEntry(IntakeSource.File(DroppedFile(name) { byteArrayOf() }, name))
    val metalink = file("Release.meta4")
    val torrent = file("Pack.TORRENT")
    assertFalse(metalink.isTorrent)
    assertTrue(torrent.isTorrent)
    // Neither has a link to add before its content is read.
    assertFalse(metalink.addable)
    assertFalse(torrent.addable)
  }

  @Test
  fun isContentFile_torrentsAndMetalinks() {
    assertTrue(isContentFile("a.torrent"))
    assertTrue(isContentFile("Release.META4"))
    assertTrue(isContentFile("old.metalink"))
    assertFalse(isContentFile("links.txt"))
  }
}
