package com.linroid.ketch.app

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.state.ListTestTask
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.state.SpeedUnit
import com.linroid.ketch.app.state.autoModeSummary
import com.linroid.ketch.app.state.countChoices
import com.linroid.ketch.app.state.elideMiddle
import com.linroid.ketch.app.state.folderName
import com.linroid.ketch.app.state.formatSpeedAmount
import com.linroid.ketch.app.state.formatSpeedLimit
import com.linroid.ketch.app.state.isAppPrivateFolder
import com.linroid.ketch.app.state.isDocumentTree
import com.linroid.ketch.app.state.isSameFolder
import com.linroid.ketch.app.state.newSpeedRule
import com.linroid.ketch.app.state.normalizeRuleTime
import com.linroid.ketch.app.state.offersDefaultFolder
import com.linroid.ketch.app.state.parseHostList
import com.linroid.ketch.app.state.parseSpeedLimit
import com.linroid.ketch.app.state.portError
import com.linroid.ketch.app.state.recentDownloadFolders
import com.linroid.ketch.app.state.ruleDaysLabel
import com.linroid.ketch.app.state.ruleIncludes
import com.linroid.ketch.app.state.slowLanePresets
import com.linroid.ketch.app.state.speedChoices
import com.linroid.ketch.app.state.toggleRuleDay
import com.linroid.ketch.config.Weekday
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SettingsChoicesTest {

  @Test
  fun countChoices_handEditedValue_joinsThePresetsInOrder() {
    assertEquals(listOf(1, 2, 7, 8, 0), countChoices(listOf(1, 2, 8, 0), 7))
  }

  @Test
  fun countChoices_unlimited_sortsLastOnce() {
    assertEquals(listOf(1, 4, 0), countChoices(listOf(0, 4, 1), 0))
  }

  @Test
  fun formatSpeedLimit_anyLimit_usesTheLargestSensibleUnit() {
    assertEquals("Unlimited", formatSpeedLimit(SpeedLimit.Unlimited))
    assertEquals("512 KB/s", formatSpeedLimit(SpeedLimit.kbps(512)))
    assertEquals("2 MB/s", formatSpeedLimit(SpeedLimit.mbps(2)))
    assertEquals("1.5 MB/s", formatSpeedLimit(SpeedLimit.kbps(1536)))
  }

  @Test
  fun parseSpeedLimit_decimalAmount_roundTrips() {
    val limit = parseSpeedLimit("1.5", SpeedUnit.MB)
    assertEquals(SpeedLimit.kbps(1536), limit)
    assertEquals("1.5", formatSpeedAmount(limit!!, SpeedUnit.MB))
    assertEquals("1536", formatSpeedAmount(limit, SpeedUnit.KB))
  }

  @Test
  fun parseSpeedLimit_zeroNegativeOrGarbage_isRejected() {
    assertNull(parseSpeedLimit("0", SpeedUnit.KB))
    assertNull(parseSpeedLimit("-5", SpeedUnit.MB))
    assertNull(parseSpeedLimit("", SpeedUnit.MB))
    assertNull(parseSpeedLimit("fast", SpeedUnit.MB))
    // Less than one byte per second rounds down to nothing.
    assertNull(parseSpeedLimit("0.0001", SpeedUnit.KB))
  }

  @Test
  fun slowLanePresets_suggestion_joinsTheFixedSpeedsInOrder() {
    val suggested = SpeedLimit.of(3_250_585)
    val presets = slowLanePresets(suggested)

    assertEquals(suggested, presets[presets.indexOf(SpeedLimit.mbps(2)) + 1])
    assertEquals(presets.sortedBy { it.bytesPerSecond }, presets)
  }

  @Test
  fun slowLanePresets_suggestionIsAFixedSpeed_listsItOnce() {
    val presets = slowLanePresets(SpeedLimit.mbps(1))

    assertEquals(1, presets.count { it == SpeedLimit.mbps(1) })
    assertFalse(SpeedLimit.Unlimited in slowLanePresets(SpeedLimit.Unlimited))
  }

  @Test
  fun speedChoices_limitSetElsewhere_joinsThePresetsInOrder() {
    val presets = listOf(SpeedLimit.Unlimited, SpeedLimit.mbps(1), SpeedLimit.mbps(10))

    assertEquals(
      listOf(SpeedLimit.Unlimited, SpeedLimit.mbps(1), SpeedLimit.mbps(10), SpeedLimit.mbps(20)),
      speedChoices(presets, SpeedLimit.mbps(20)),
    )
    assertEquals(presets, speedChoices(presets, SpeedLimit.mbps(1)))
  }

  @Test
  fun portError_outsideTheRange_explainsIt() {
    assertNull(portError("8642"))
    assertNull(portError(" 1 "))
    assertNotNull(portError("0"))
    assertNotNull(portError("65536"))
    assertNotNull(portError(""))
    assertNotNull(portError("http"))
  }

  @Test
  fun toggleRuleDay_everyDay_switchesOneOff() {
    val days = toggleRuleDay(emptySet(), Weekday.Sunday)

    assertEquals(Weekday.entries.toSet() - Weekday.Sunday, days)
    assertFalse(ruleIncludes(days, Weekday.Sunday))
  }

  @Test
  fun toggleRuleDay_lastMissingDay_storesEveryDayAsEmpty() {
    val days = toggleRuleDay(Weekday.entries.toSet() - Weekday.Sunday, Weekday.Sunday)

    assertEquals(emptySet(), days)
    assertTrue(Weekday.entries.all { ruleIncludes(days, it) })
  }

  @Test
  fun toggleRuleDay_onlyDay_staysOn() {
    assertEquals(setOf(Weekday.Monday), toggleRuleDay(setOf(Weekday.Monday), Weekday.Monday))
  }

  @Test
  fun ruleDaysLabel_commonSets_readAsWords() {
    assertEquals("Every day", ruleDaysLabel(emptySet()))
    assertEquals("Weekdays", ruleDaysLabel(newSpeedRule().days))
    assertEquals("Weekends", ruleDaysLabel(setOf(Weekday.Saturday, Weekday.Sunday)))
    assertEquals(
      "Mon, Wed, Fri",
      ruleDaysLabel(setOf(Weekday.Friday, Weekday.Monday, Weekday.Wednesday)),
    )
  }

  @Test
  fun normalizeRuleTime_shorthand_becomesHoursAndMinutes() {
    assertEquals("09:00", normalizeRuleTime("9"))
    assertEquals("09:30", normalizeRuleTime("9:30"))
    assertEquals("09:30", normalizeRuleTime("930"))
    assertEquals("18:05", normalizeRuleTime(" 1805 "))
    assertEquals("00:00", normalizeRuleTime("0:00"))
  }

  @Test
  fun normalizeRuleTime_notATime_returnsNull() {
    for (text in listOf("", "24", "9:60", "25:00", "noon", "9:", ":30", "12345", "1:234")) {
      assertNull(normalizeRuleTime(text), text)
    }
  }

  @Test
  fun autoModeSummary_slowLaneOnToday_namesTheEndTime() {
    val now = Instant.parse("2026-10-01T10:15:00Z")
    val mode = SpeedMode.Auto(slowLane = true, until = Instant.parse("2026-10-01T18:00:00Z"))

    assertEquals("Slow lane on until 18:00", autoModeSummary(mode, now, TimeZone.UTC))
  }

  @Test
  fun autoModeSummary_fullSpeedUntilLaterInTheWeek_namesTheDay() {
    val now = Instant.parse("2026-10-01T19:00:00Z")
    val tomorrow = SpeedMode.Auto(slowLane = false, until = Instant.parse("2026-10-02T09:00:00Z"))
    val monday = SpeedMode.Auto(slowLane = false, until = Instant.parse("2026-10-05T09:00:00Z"))

    assertEquals("Full speed until tomorrow 09:00", autoModeSummary(tomorrow, now, TimeZone.UTC))
    assertEquals("Full speed until Mon 09:00", autoModeSummary(monday, now, TimeZone.UTC))
  }

  @Test
  fun autoModeSummary_noChange_saysSo() {
    val now = Instant.parse("2026-10-01T19:00:00Z")

    assertEquals(
      "Slow lane on all week",
      autoModeSummary(SpeedMode.Auto(slowLane = true), now, TimeZone.UTC),
    )
  }

  @Test
  fun isAppPrivateFolder_androidDataPaths_areFlagged() {
    assertTrue(isAppPrivateFolder("/storage/emulated/0/Android/data/com.linroid.ketch/files"))
    assertTrue(isAppPrivateFolder("/storage/emulated/0/Android/data"))
    assertFalse(isAppPrivateFolder("/storage/emulated/0/Download"))
    assertFalse(isAppPrivateFolder("/Users/alex/Android/database"))
  }

  @Test
  fun folderName_pathsAndDocumentTrees_nameTheFolder() {
    assertEquals("Downloads", folderName("/Users/alex/Downloads/"))
    assertEquals("Downloads", folderName("C:\\Users\\alex\\Downloads"))
    assertEquals("/", folderName("/"))
    val tree = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FKetch"
    assertTrue(isDocumentTree(tree))
    assertEquals("Ketch", folderName(tree))
    assertEquals(
      "Internal storage",
      folderName("content://com.android.externalstorage.documents/tree/primary%3A"),
    )
  }

  @Test
  fun isSameFolder_trailingSeparatorOrNoOther_comparesThePaths() {
    assertTrue(isSameFolder("/Users/alex/Downloads/", "/Users/alex/Downloads"))
    assertTrue(isSameFolder("C:\\Users\\alex\\Downloads\\", "C:\\Users\\alex\\Downloads"))
    assertFalse(isSameFolder("/Users/alex/Downloads", "/Users/alex/Movies"))
    assertFalse(isSameFolder("/Users/alex/Downloads", null))
  }

  @Test
  fun offersDefaultFolder_chosenFolderBesideTheDefault_isOffered() {
    assertTrue(offersDefaultFolder("/Users/alex/Movies", "/Users/alex/Downloads"))
  }

  @Test
  fun offersDefaultFolder_defaultInUse_isNotOffered() {
    assertFalse(offersDefaultFolder(null, "/Users/alex/Downloads"))
    // The CLI daemon writes its default folder into the configuration.
    assertFalse(offersDefaultFolder("/Users/alex/Downloads/", "/Users/alex/Downloads"))
  }

  @Test
  fun offersDefaultFolder_unknownOrAppPrivateDefault_isNotOffered() {
    assertFalse(offersDefaultFolder("/Users/alex/Movies", null))
    assertFalse(
      offersDefaultFolder(
        "content://com.android.externalstorage.documents/tree/primary%3ADownload",
        "/storage/emulated/0/Android/data/com.linroid.ketch/files/Download",
      ),
    )
  }

  @Test
  fun elideMiddle_longPath_keepsBothEndsWithinTheLimit() {
    val path = "/storage/emulated/0/Android/data/com.linroid.ketch/files/Download"

    val shown = elideMiddle(path) { it.length <= 30 }

    assertEquals(30, shown.length)
    assertEquals("/storage/em…tch/files/Download", shown)
    assertEquals(path, elideMiddle(path) { it.length <= path.length })
    assertEquals("…", elideMiddle(path) { it.length <= 1 })
  }

  @Test
  fun parseHostList_mixedSeparators_listsEachHostOnce() {
    assertEquals(
      listOf("app.example.com", "*", "localhost:5173"),
      parseHostList(" app.example.com, *\nlocalhost:5173 ,app.example.com "),
    )
    assertEquals(emptyList(), parseHostList(" , "))
  }

  @Test
  fun recentDownloadFolders_mixedTasks_listsTheirFoldersNewestFirst() {
    val start = Instant.parse("2026-10-01T10:00:00Z")
    fun task(id: String, minute: Int, destination: String?, state: DownloadState) = ListTestTask(
      taskId = id,
      state = state,
      request = DownloadRequest(
        url = "https://example.com/$id.iso",
        destination = destination?.let(::Destination),
      ),
      createdAt = start + minute.minutes,
    )
    val tasks = listOf(
      task("old", 0, "/data/iso/", DownloadState.Queued),
      task("named", 1, "renamed.iso", DownloadState.Queued),
      task("done", 2, null, DownloadState.Completed("/data/movies/film.mkv")),
      task("file", 3, "/data/music/song.mp3", DownloadState.Queued),
      task("pinned", 4, "/data/pinned/", DownloadState.Queued),
      task("again", 5, "/data/iso/linux.iso", DownloadState.Queued),
    )

    assertEquals(
      listOf("/data/iso", "/data/music", "/data/movies"),
      recentDownloadFolders(tasks, exclude = listOf("/data/pinned/")),
    )
    assertEquals(listOf("/data/iso"), recentDownloadFolders(tasks, limit = 1))
  }
}
