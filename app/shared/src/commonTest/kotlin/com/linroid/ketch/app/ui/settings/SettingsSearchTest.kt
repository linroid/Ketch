package com.linroid.ketch.app.ui.settings

import com.linroid.ketch.app.state.SettingsCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsSearchTest {
  private val every = SettingsCategory.entries
  private val allFeatures = SettingsFeature.entries.toSet()

  @Test
  fun searchSettings_trackers_findsTheBitTorrentTrackersFirst() {
    val hits = searchSettings("trackers", every, allFeatures)

    val first = hits.first().entry
    assertEquals(SettingsCategory.BitTorrent, first.category)
    assertEquals("Extra trackers", first.title)
    assertEquals(listOf("Extra trackers"), first.anchors)
    assertEquals(listOf(6..13), hits.first().titleMatches)
  }

  @Test
  fun searchSettings_titleStartingWithTheQuery_ranksAboveOtherMatches() {
    val titles = searchSettings("speed", every, allFeatures).map { it.entry.title }

    assertEquals(listOf("Speed", "Speed mode"), titles.take(2))
    assertTrue(titles.indexOf("Full speed cap") < titles.indexOf("Auto rules"))
  }

  @Test
  fun searchSettings_wordsInAnyOrder_matchAcrossTitleAndKeywords() {
    val hits = searchSettings("dark  THEME", every, allFeatures)

    assertEquals("Theme", hits.first().entry.title)
  }

  @Test
  fun searchSettings_pageNotOffered_leavesItsSettingsOut() {
    val withoutTorrents = every - SettingsCategory.BitTorrent

    val hits = searchSettings("trackers", withoutTorrents, allFeatures)

    assertTrue(hits.none { it.entry.category == SettingsCategory.BitTorrent })
  }

  @Test
  fun searchSettings_featureMissing_leavesItsSettingsOut() {
    val hits = searchSettings("login", every, features = emptySet())

    assertTrue(hits.none { it.entry.needs == SettingsFeature.Desktop })
    assertTrue(searchSettings("login", every, setOf(SettingsFeature.Desktop)).isNotEmpty())
  }

  @Test
  fun searchSettings_blankQuery_findsNothing() {
    assertEquals(emptyList(), searchSettings("   ", every, allFeatures))
  }

  @Test
  fun searchSettings_pageByItsName_opensAtTheTop() {
    val page = searchSettings("sharing", every, allFeatures).first().entry

    assertEquals(SettingsCategory.Sharing, page.category)
    assertEquals(emptyList(), page.anchors)
  }

  @Test
  fun matchRanges_overlappingAndAdjacentWords_mergeIntoOneRange() {
    assertEquals(listOf(0..8), matchRanges("Slow lane speed", listOf("slow", "low l", "lane")))
  }

  @Test
  fun matchRanges_repeatedWord_marksEveryOccurrence() {
    assertEquals(listOf(0..3, 12..15), matchRanges("Open magnet open", listOf("open")))
  }

  @Test
  fun settingsIndex_everyEntry_isNamedOnceOnItsPage() {
    SettingsIndex.forEach { entry ->
      assertTrue(entry.title.isNotBlank(), "Entry without a title on ${entry.category}")
      assertTrue(entry.anchors.all { it.isNotBlank() }, "Blank anchor for ${entry.title}")
    }
    val titles = SettingsIndex.map { it.category to it.title }
    assertEquals(titles.distinct(), titles)
  }
}
