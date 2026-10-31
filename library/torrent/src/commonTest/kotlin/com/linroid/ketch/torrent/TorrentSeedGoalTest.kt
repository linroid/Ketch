package com.linroid.ketch.torrent

import com.linroid.ketch.api.defaultSelection
import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.torrent.api.TorrentEngineSettings
import com.linroid.ketch.torrent.magnet.MagnetUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrentSeedGoalTest {
  @Test
  fun noGoal_isNeverReached() {
    assertFalse(TorrentEngineSettings().seedGoalReached(Long.MAX_VALUE / 200, 1, Long.MAX_VALUE))
  }

  @Test
  fun ratio_isReachedAtItsShareOfTheSize() {
    val settings = TorrentEngineSettings(seedRatioPercent = 150)
    assertFalse(settings.seedGoalReached(uploaded = 149, total = 100, seededMs = 0))
    assertTrue(settings.seedGoalReached(uploaded = 150, total = 100, seededMs = 0))
  }

  @Test
  fun ratio_ofAnEmptyTorrent_isNeverReached() {
    val settings = TorrentEngineSettings(seedRatioPercent = 100)
    assertFalse(settings.seedGoalReached(uploaded = 0, total = 0, seededMs = 0))
  }

  @Test
  fun time_isReachedByItself() {
    val settings = TorrentEngineSettings(seedRatioPercent = 100, seedTimeMinutes = 2)
    assertFalse(settings.seedGoalReached(uploaded = 0, total = 100, seededMs = 119_999))
    assertTrue(settings.seedGoalReached(uploaded = 0, total = 100, seededMs = 120_000))
  }

  private val source = ResolvedSource("magnet:?", "torrent", 3, true, null, 3,
    files = listOf("0", "2", "3").map { SourceFile(it, "file$it", 1) },
    selectionMode = FileSelectionMode.MULTIPLE)

  private fun magnet(selectOnly: String) =
    MagnetUri.parse("magnet:?xt=urn:btih:${"a".repeat(40)}&so=$selectOnly")

  @Test
  fun pickingFilesOf_keepsTheFilesTheTorrentLists() {
    // File 1 is padding, which no table lists.
    val picked = source.pickingFilesOf(magnet("1,2,9"))
    assertEquals(setOf("2"), picked.defaultSelection())
    assertEquals(1, picked.totalBytes)
  }

  @Test
  fun pickingFilesOf_withNoneItLists_downloadsEveryFile() {
    assertEquals(setOf("0", "2", "3"), source.pickingFilesOf(magnet("9")).defaultSelection())
  }
}
