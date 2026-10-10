package com.linroid.ketch.app.ui.settings

import com.linroid.ketch.app.state.SettingsCategory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsSearchTest {
  private val every = SettingsCategory.entries
  private val allFeatures = SettingsFeature.entries.toSet()

  @Test
  fun searchSettings_trackers_findsTheBitTorrentTrackersFirst() = runTest {
    val hits = searchSettings("trackers", every, allFeatures, loadSettingsSearchIndex())

    val first = hits.first().entry
    assertEquals(SettingsCategory.BitTorrent, first.category)
    assertEquals("Extra trackers", first.title)
    assertEquals(listOf("Extra trackers"), first.anchors)
    assertEquals("BitTorrent", first.page)
    assertEquals(listOf(6..13), hits.first().titleMatches)
  }

  @Test
  fun searchSettings_titleStartingWithTheQuery_ranksAboveOtherMatches() = runTest {
    val titles = searchSettings("speed", every, allFeatures, loadSettingsSearchIndex())
      .map { it.entry.title }

    assertEquals(listOf("Speed", "Speed mode"), titles.take(2))
    assertTrue(titles.indexOf("Full speed cap") < titles.indexOf("Auto rules"))
  }

  @Test
  fun searchSettings_wordsInAnyOrder_matchAcrossTitleAndKeywords() = runTest {
    val hits = searchSettings("dark  THEME", every, allFeatures, loadSettingsSearchIndex())

    assertEquals("Theme", hits.first().entry.title)
  }

  @Test
  fun searchSettings_accentNames_findTheAccentColor() = runTest {
    val hits = searchSettings("teal", every, allFeatures, loadSettingsSearchIndex())

    assertEquals("Accent color", hits.first().entry.title)
    assertEquals("Indigo, Teal, Green or Orange", hits.first().entry.description)
  }

  @Test
  fun searchSettings_permission_findsDiscoverPageAccess() = runTest {
    val hits = searchSettings("permission", every, allFeatures, loadSettingsSearchIndex())

    assertEquals(
      listOf("Before opening a website", "Always allowed"),
      hits.filter { it.entry.category == SettingsCategory.Discover }.map { it.entry.title }
    )
  }

  @Test
  fun searchSettings_trustedSites_findsTheAlwaysAllowedRow() = runTest {
    val hits = searchSettings("trusted sites", every, allFeatures, loadSettingsSearchIndex())

    assertEquals("Always allowed", hits.first().entry.title)
    assertEquals("Discover", hits.first().entry.page)
  }

  @Test
  fun searchSettings_pageNotOffered_leavesItsSettingsOut() = runTest {
    val withoutTorrents = every - SettingsCategory.BitTorrent

    val hits = searchSettings("trackers", withoutTorrents, allFeatures, loadSettingsSearchIndex())

    assertTrue(hits.none { it.entry.category == SettingsCategory.BitTorrent })
  }

  @Test
  fun searchSettings_featureMissing_leavesItsSettingsOut() = runTest {
    val index = loadSettingsSearchIndex()

    val hits = searchSettings("login", every, features = emptySet(), index = index)

    assertTrue(hits.none { it.entry.needs == SettingsFeature.Desktop })
    assertTrue(searchSettings("login", every, setOf(SettingsFeature.Desktop), index).isNotEmpty())
  }

  @Test
  fun searchSettings_blankQuery_findsNothing() = runTest {
    assertEquals(emptyList(), searchSettings("   ", every, allFeatures, loadSettingsSearchIndex()))
  }

  @Test
  fun searchSettings_pageByItsName_opensAtTheTop() = runTest {
    val page = searchSettings("sharing", every, allFeatures, loadSettingsSearchIndex())
      .first().entry

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
  fun settingsIndex_everyEntry_isNamedOnceOnItsPage() = runTest {
    val entries = loadSettingsSearchIndex().entries
    entries.forEach { entry ->
      assertTrue(entry.title.isNotBlank(), "Entry without a title on ${entry.category}")
      assertTrue(entry.anchors.all { it.isNotBlank() }, "Blank anchor for ${entry.title}")
      assertTrue(entry.keywords.all { it.isNotBlank() }, "Blank keyword for ${entry.title}")
    }
    val titles = entries.map { it.category to it.title }
    assertEquals(titles.distinct(), titles)
  }
}
