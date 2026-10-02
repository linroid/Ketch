package com.linroid.ketch.app.ui.palette

import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.SettingsCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaletteRankingTest {
  private fun item(
    title: String,
    id: String = title,
    provider: PaletteProvider = PaletteProvider.Commands,
    keywords: List<String> = emptyList(),
    searchOnly: Boolean = false,
  ) = PaletteItem(
    id = id,
    provider = provider,
    title = title,
    icon = PaletteIcon.Glyph(KetchIcon.Command),
    action = PaletteAction.Search(title),
    keywords = keywords,
    searchOnly = searchOnly,
  )

  private fun titles(
    query: String,
    items: List<PaletteItem>,
    recent: List<String> = emptyList(),
  ): List<String> = paletteResults(query, items, recent).items.map { it.title }

  @Test
  fun matchText_titleStartsWithQuery_isExactPrefix() {
    assertEquals(PaletteMatch(MatchTier.ExactPrefix), matchText("Pau", "Pause all"))
  }

  @Test
  fun matchText_queryWordsStartTitleWordsInOrder_isWordPrefix() {
    assertEquals(PaletteMatch(MatchTier.WordPrefix), matchText("pause nas", "Pause all on NAS"))
    assertNull(matchText("nas pause", "Pause all on NAS"))
  }

  @Test
  fun matchText_lettersInOrder_isSubsequenceWithItsSpan() {
    assertEquals(PaletteMatch(MatchTier.Subsequence, 6), matchText("ubnu", "ubuntu.iso"))
  }

  @Test
  fun matchText_lettersSpreadTooFar_doesNotMatch() {
    assertNull(matchText("pz", "pause all downloads on every device z"))
  }

  @Test
  fun paletteResults_mixedMatches_ranksExactPrefixThenWordPrefixThenSubsequence() {
    val items = listOf(item("Show peers"), item("Settings › Speed"), item("Speed limit"))

    assertEquals(listOf("Speed limit", "Settings › Speed", "Show peers"), titles("sp", items))
  }

  @Test
  fun paletteResults_equalMatches_keepProviderOrder() {
    val items = listOf(
      item("Paused files", provider = PaletteProvider.Downloads),
      item("Pause all", provider = PaletteProvider.Commands)
    )

    assertEquals(listOf("Pause all", "Paused files"), titles("pause", items))
  }

  @Test
  fun paletteResults_recentRow_comesFirstAmongEqualMatches() {
    val items = listOf(item("Pause all"), item("Paste links"), item("Path settings"))

    val ranked = titles("pa", items, recent = listOf("Path settings", "Paste links"))

    assertEquals(listOf("Path settings", "Paste links", "Pause all"), ranked)
  }

  @Test
  fun paletteResults_recentRow_neverBeatsABetterMatch() {
    val items = listOf(item("Open archive"), item("Pause all"))

    val ranked = titles("pa", items, recent = listOf("Open archive"))

    assertEquals(listOf("Pause all", "Open archive"), ranked)
  }

  @Test
  fun paletteResults_keywordMatch_countsLikeTheTitle() {
    val items = listOf(item("Full speed", keywords = listOf("unlimited")), item("Undo"))

    assertEquals(listOf("Full speed"), titles("unl", items))
  }

  @Test
  fun paletteResults_noMatch_leavesTheRowOut() {
    assertEquals(emptyList(), titles("zzz", listOf(item("Pause all"))))
  }

  @Test
  fun paletteResults_slashQuery_listsOnlySettingsPages() {
    fun page(title: String) = item(
      title = "Settings › $title",
      provider = PaletteProvider.Navigation,
      keywords = listOf("/${title.lowercase()}"),
    )
    val items = listOf(item("Full speed"), page("General"), page("Speed"))

    assertEquals(listOf("Settings › Speed"), titles("/sp", items))
  }

  @Test
  fun paletteResults_emptyQuery_listsRecentThenEachProvider() {
    val items = listOf(
      item("Pause all"),
      item("Undo"),
      item("Downloads", provider = PaletteProvider.Navigation),
      item("ubuntu.iso", provider = PaletteProvider.Downloads, searchOnly = true)
    )

    val results = paletteResults("", items, recent = listOf("ubuntu.iso"))

    assertEquals(
      listOf(
        PaletteEntry.Header("Recent"),
        PaletteEntry.Row(items[3], 0),
        PaletteEntry.Header("Commands"),
        PaletteEntry.Row(items[0], 1),
        PaletteEntry.Row(items[1], 2),
        PaletteEntry.Header("Go to"),
        PaletteEntry.Row(items[2], 3)
      ),
      results.entries
    )
    assertEquals(3, results.entryIndex(1))
  }

  @Test
  fun paletteHistory_record_keepsTheFiveMostRecentOnce() {
    val history = PaletteHistory(listOf("a", "b", "c", "d", "e"))

    history.record("c")
    history.record("f")
    history.record("")

    assertEquals(listOf("f", "c", "a", "b", "d"), history.recent)
  }

  @Test
  fun paletteResults_speedToken_setsTheSlowLaneFirst() {
    val source = PaletteSource(
      query = "5m",
      devices = listOf(PaletteDevice(LOCAL_DEVICE_ID, "This Mac", 1, active = true, "Idle")),
      commands = listOf(KetchCommands.PauseAll, KetchCommands.SlowLane),
      speed = PaletteSpeed(modes = true, slowLaneSpeed = SpeedLimit.mbps(1)),
      platform = KeyboardPlatform.Mac,
    )

    val first = paletteResults(source.query, paletteItems(source)).items.first()

    assertEquals("Set Slow lane to 5 MB/s", first.title)
    assertEquals(PaletteAction.SlowLane(SpeedLimit.mbps(5)), first.action)
  }

  @Test
  fun paletteResults_pauseNas_putsTheNasCommandFirst() {
    val source = PaletteSource(
      query = "pause nas",
      devices = listOf(
        PaletteDevice(LOCAL_DEVICE_ID, "This Mac", 1, active = true, "Idle"),
        PaletteDevice(NAS_ID, "NAS-Basement", 2, active = false, "2 active · 4.0 MB/s")
      ),
      commands = KetchCommands.all.filter { it.scope == KetchCommands.PauseAll.scope },
      settings = SettingsCategory.entries,
      platform = KeyboardPlatform.Mac,
    )

    val results = paletteResults(source.query, paletteItems(source)).items

    assertEquals("Pause all on NAS-Basement", results.first().title)
    assertEquals(PaletteAction.DeviceBatch(NAS_ID, BatchVerb.Pause), results.first().action)
    assertTrue(results.none { it.title == "Pause all on This Mac" })
  }

  private companion object {
    const val NAS_ID = "nas.local:8642"
  }
}
