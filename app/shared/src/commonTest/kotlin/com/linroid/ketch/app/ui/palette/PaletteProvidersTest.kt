package com.linroid.ketch.app.ui.palette

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.state.AppDestination
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.ListFixtures
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.SettingsCategory
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.state.TaskKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaletteProvidersTest {
  private val thisMac = PaletteDevice(LOCAL_DEVICE_ID, "This Mac", 1, active = true, "Idle")
  private val nas = PaletteDevice(NAS_ID, "NAS-Basement", 2, active = false, "Offline")

  private val rows = listOf(
    ListFixtures.row("ubuntu", ListFixtures.downloading(400, 1000)),
    ListFixtures.row("weights", DownloadState.Failed(KetchError.Http(500))),
    ListFixtures.row("blender", DownloadState.Failed(KetchError.Network())),
    ListFixtures.row("studio", DownloadState.Paused(DownloadProgress(10, 100))),
    ListFixtures.row("report", DownloadState.Completed("/Users/alex/Downloads/report.bin"))
  )

  private fun source(
    query: String,
    devices: List<PaletteDevice> = listOf(thisMac, nas),
    destinations: List<AppDestination> = AppDestination.entries,
  ) = PaletteSource(
    query = query,
    rows = rows,
    devices = devices,
    commands = KetchCommands.all.filter { it.scope == KetchCommands.PauseAll.scope },
    destinations = destinations,
    settings = SettingsCategory.entries,
    platform = KeyboardPlatform.Mac,
  )

  private fun results(source: PaletteSource): List<PaletteItem> =
    paletteResults(source.query, paletteItems(source)).items

  @Test
  fun parsePaletteSpeed_unitsAndNumbers_readOnlySpeedsWithAUnit() {
    assertEquals(SpeedLimit.mbps(5), parsePaletteSpeed("5m"))
    assertEquals(SpeedLimit.kbps(500), parsePaletteSpeed("500k"))
    assertEquals(SpeedLimit.of(1_572_864), parsePaletteSpeed("1,5 MB/s"))
    assertEquals(SpeedLimit.mbps(2), parsePaletteSpeed("2mbps"))
    assertNull(parsePaletteSpeed("5"))
    assertNull(parsePaletteSpeed("5 movies"))
  }

  @Test
  fun paletteItems_speedWithoutModes_capsTheSpeed() {
    val first = results(source("2m")).first()

    assertEquals("Limit downloads to 2 MB/s", first.title)
    assertEquals(PaletteAction.SpeedCap(SpeedLimit.mbps(2)), first.action)
  }

  @Test
  fun paletteItems_full_offersFullSpeed() {
    val first = results(source("full")).first()

    assertEquals("Full speed", first.title)
    assertEquals(PaletteAction.FullSpeed, first.action)
  }

  @Test
  fun paletteItems_link_downloadsOnTheActiveDeviceFirstThenTheOthers() {
    val url = "https://releases.ubuntu.com/24.04/ubuntu-24.04-desktop-amd64.iso"

    val items = results(source(url))

    assertEquals("Download ubuntu-24.04-desktop-amd64.iso", items[0].title)
    assertEquals("on This Mac", items[0].subtitle)
    val download = PaletteAction.Download(url, listOf(url), LOCAL_DEVICE_ID, now = true)
    assertEquals(download, items[0].action)
    assertEquals(PaletteAction.AddWithOptions(url, LOCAL_DEVICE_ID), items[0].alternate)
    assertEquals("on NAS-Basement", items[1].subtitle)
    assertEquals("⌥⌘2", items[1].shortcut)
    assertEquals(PaletteAction.AddWithOptions(url, LOCAL_DEVICE_ID), items[2].action)
    assertTrue(items.none { it.action is PaletteAction.Discover })
  }

  @Test
  fun paletteItems_magnet_opensTheSheetForTheDevice() {
    val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=arch.iso"

    val download = results(source(magnet)).first().action as PaletteAction.Download

    assertFalse(download.now)
  }

  @Test
  fun paletteItems_liveCounts_nameTheTasksInScope() {
    val titles = paletteItems(source("")).map { it.title }

    assertTrue("Pause 1 download" in titles)
    assertTrue("Resume 1 paused" in titles)
    assertTrue("Retry 2 failed downloads" in titles)
  }

  @Test
  fun paletteItems_downloadName_pausesARunningTaskAndOpensAFinishedOne() {
    val running = results(source("ubuntu")).first { it.provider == PaletteProvider.Downloads }
    val finished = results(source("report")).first { it.provider == PaletteProvider.Downloads }

    val ubuntu = TaskKey(LOCAL_DEVICE_ID, "ubuntu")
    val report = TaskKey(LOCAL_DEVICE_ID, "report")
    assertEquals(PaletteAction.Task(ubuntu, RowAction.Pause), running.action)
    assertEquals("Pause", running.verb)
    assertEquals(PaletteAction.Task(report, RowAction.Open), finished.action)
    assertEquals(PaletteAction.Task(report, RowAction.ShowInFolder), finished.alternate)
  }

  @Test
  fun paletteItems_searchToken_listsOnlyMatchingDownloads() {
    val items = results(source("is:failed"))

    val downloads = items.filter { it.provider == PaletteProvider.Downloads }
    assertEquals(setOf("weights.bin", "blender.bin"), downloads.map { it.title }.toSet())
    assertEquals(PaletteAction.Search("is:failed"), items.last().action)
  }

  @Test
  fun paletteItems_tokenAndWords_matchTheWordsAmongTheFilteredTasks() {
    val downloads = results(source("is:failed blend")).filter {
      it.provider == PaletteProvider.Downloads
    }

    assertEquals(listOf("blender.bin"), downloads.map { it.title })
  }

  @Test
  fun paletteItems_plainText_fallsThroughToDiscover() {
    val items = results(source("blender for mac"))

    assertEquals(PaletteAction.Discover("blender for mac"), items.last().action)
    assertEquals("⌥↩", items.last().shortcut)
  }

  @Test
  fun paletteItems_discoverHidden_offersNoDiscoverRow() {
    val items = results(source("zzz", destinations = listOf(AppDestination.Downloads)))

    assertTrue(items.isEmpty())
  }

  @Test
  fun paletteItems_slashSpeed_jumpsToTheSpeedPage() {
    val first = results(source("/speed")).first()

    assertEquals("Settings › Speed", first.title)
    assertEquals(PaletteAction.Settings(SettingsTarget.Page.Speed), first.action)
  }

  @Test
  fun paletteItems_tabName_showsTheTabWithItsChord() {
    val first = results(source("failed")).first { it.provider == PaletteProvider.Navigation }

    assertEquals("Downloads › Failed", first.title)
    assertEquals("⌘6", first.shortcut)
  }

  @Test
  fun paletteItems_otherDevice_offersASwitchWithItsChord() {
    val switch = results(source("nas")).first { it.action is PaletteAction.SwitchDevice }

    assertEquals("Switch to NAS-Basement", switch.title)
    assertEquals("Offline", switch.subtitle)
    assertEquals("⌥⌘2", switch.shortcut)
  }

  @Test
  fun paletteItems_oneDevice_offersNoDeviceRows() {
    val items = paletteItems(source("nas", devices = listOf(thisMac)))

    assertTrue(items.none { it.provider == PaletteProvider.Devices })
  }

  private companion object {
    const val NAS_ID = "nas.local:8642"
  }
}
