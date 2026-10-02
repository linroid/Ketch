package com.linroid.ketch.app.ui.shell

import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.platform.DroppedFile
import com.linroid.ketch.app.platform.FilePicker
import com.linroid.ketch.app.platform.SystemClipboard
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.AppDestination
import com.linroid.ketch.app.state.RecordingKetchApi
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.UiPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ShellCommandsTest {

  private class FakeClipboard(var text: String?) : SystemClipboard {
    override val readsSilently: Boolean = true
    override val pasteEvents: Flow<String> = emptyFlow()

    override suspend fun hasLink(): Boolean = text != null

    override suspend fun readText(): String? = text

    override suspend fun writeText(text: String) {
      this.text = text
    }
  }

  private object NoFiles : FilePicker {
    override val canPickFolder: Boolean = false

    override suspend fun pickFolder(initialFolder: String?): String? = null

    override suspend fun pickTorrentFiles(): List<DroppedFile> = emptyList()
  }

  private class Fixture(
    val api: RecordingKetchApi,
    val controller: AppController,
    val shell: ShellState,
    val commands: ShellCommands,
  )

  private fun TestScope.fixture(
    clipboard: String? = null,
    platform: KeyboardPlatform = KeyboardPlatform.Mac,
    ui: UiPreferences = UiPreferences(quickAdd = true),
  ): Fixture {
    val api = RecordingKetchApi()
    val store = RecordingConfigStore(KetchConfig(ui = ui))
    val controller = AppController(
      instanceManager = InstanceManager(
        factory = InstanceFactory(deviceName = "This Mac", embeddedFactory = { api }),
        configStore = store,
      ),
      context = StandardTestDispatcher(testScheduler),
    )
    val shell = ShellState(controller.state)
    val commands =
      ShellCommands(shell, backgroundScope, FakeClipboard(clipboard), NoFiles, platform)
    return Fixture(api, controller, shell, commands)
  }

  @Test
  fun pasteAction_onePlainLink_addsItAtOnce() {
    assertEquals(
      ClipboardAdd.Now(listOf("https://example.com/ubuntu.iso")),
      pasteAction(" https://example.com/ubuntu.iso\n", quickAdd = true),
    )
  }

  @Test
  fun pasteAction_quickAddOff_opensTheSheet() {
    assertEquals(
      ClipboardAdd.Sheet("https://example.com/ubuntu.iso"),
      pasteAction("https://example.com/ubuntu.iso", quickAdd = false),
    )
  }

  @Test
  fun pasteAction_magnet_opensTheSheet() {
    val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
    assertEquals(ClipboardAdd.Sheet(magnet), pasteAction(magnet, quickAdd = true))
  }

  @Test
  fun pasteAction_blankText_addsNothing() {
    assertEquals(ClipboardAdd.Empty, pasteAction("  \n", quickAdd = true))
    assertEquals(ClipboardAdd.Empty, pasteAction(null, quickAdd = true))
  }

  @Test
  fun clipboardLinksAction_severalLinks_addsEachOnce() {
    val text = "https://example.com/a.iso\nhttps://example.com/b.iso https://example.com/a.iso"
    assertEquals(
      ClipboardAdd.Now(listOf("https://example.com/a.iso", "https://example.com/b.iso")),
      clipboardLinksAction(text),
    )
  }

  @Test
  fun clipboardLinksAction_curlCommand_opensTheSheet() {
    val curl = "curl 'https://example.com/a.iso' -H 'Cookie: session=1'"
    assertEquals(ClipboardAdd.Sheet(curl), clipboardLinksAction(curl))
  }

  @Test
  fun clipboardLinksAction_textWithoutLink_opensTheSheet() {
    assertEquals(ClipboardAdd.Sheet("blender for mac"), clipboardLinksAction("blender for mac"))
  }

  @Test
  fun run_pasteWithOneLink_addsItWithUndo() = runTest {
    val fixture = fixture(clipboard = "https://example.com/ubuntu.iso")

    assertTrue(fixture.commands.run(KetchCommands.PasteLinks))
    runCurrent()

    assertEquals("https://example.com/ubuntu.iso", fixture.api.requests.single().url)
    val toast = fixture.controller.messages.active.value.last()
    assertTrue("Undo" in toast.actions.map { it.label })
    assertFalse(fixture.controller.state.showAddDialog)
    fixture.controller.close()
  }

  @Test
  fun run_pasteOnTheWeb_leavesTheKeyToTheBrowser() = runTest {
    val fixture = fixture(
      clipboard = "https://example.com/ubuntu.iso",
      platform = KeyboardPlatform.WebMac,
    )

    assertFalse(fixture.commands.run(KetchCommands.PasteLinks))
    runCurrent()

    assertTrue(fixture.api.requests.isEmpty())
    fixture.controller.close()
  }

  @Test
  fun paste_browserPasteEvent_addsTheLink() = runTest {
    val fixture = fixture(platform = KeyboardPlatform.WebMac)

    fixture.commands.paste("https://example.com/ubuntu.iso")
    runCurrent()

    assertEquals("https://example.com/ubuntu.iso", fixture.api.requests.single().url)
    fixture.controller.close()
  }

  @Test
  fun paste_addSheetOpen_addsNothingBehindIt() = runTest {
    val fixture = fixture(platform = KeyboardPlatform.WebMac)
    fixture.controller.state.openIntake()

    fixture.commands.paste("https://example.com/ubuntu.iso")
    runCurrent()

    assertTrue(fixture.api.requests.isEmpty())
    assertTrue(fixture.controller.state.showAddDialog)
    fixture.controller.close()
  }

  @Test
  fun run_addClipboardLinkWithEmptyClipboard_saysSo() = runTest {
    val fixture = fixture(clipboard = null)

    fixture.commands.run(KetchCommands.AddClipboardLink)
    runCurrent()

    val message = fixture.controller.messages.active.value.last()
    assertEquals(MessageLevel.Warning, message.level)
    assertEquals("The clipboard holds no link", message.title)
    fixture.controller.close()
  }

  @Test
  fun run_tabCommand_showsDownloadsOnThatTab() = runTest {
    val fixture = fixture()
    fixture.shell.show(AppDestination.Devices)

    assertTrue(fixture.commands.run(KetchCommands.tab(StatusFilter.Failed)))

    assertEquals(AppDestination.Downloads, fixture.shell.destination)
    assertEquals(StatusFilter.Failed, fixture.controller.state.statusFilter)
    fixture.controller.close()
  }

  @Test
  fun run_discoverWhileHidden_leavesTheKey() = runTest {
    val fixture = fixture()
    fixture.shell.destinations = AppDestination.visible(aiSupported = false)

    assertFalse(fixture.commands.run(KetchCommands.Discover))
    assertEquals(AppDestination.Downloads, fixture.shell.destination)
    fixture.controller.close()
  }

  @Test
  fun run_toggleSidebarOnMediumWindow_leavesTheKey() = runTest {
    val fixture = fixture()
    fixture.shell.layout = KetchLayout.of(800.dp)

    assertFalse(fixture.commands.run(KetchCommands.ToggleSidebar))
    assertFalse(fixture.controller.appSettings.ui.sidebarCollapsed)
    fixture.controller.close()
  }

  @Test
  fun run_toggleSidebarOnWideWindow_collapsesIt() = runTest {
    val fixture = fixture()
    fixture.shell.layout = KetchLayout.of(1280.dp)

    assertTrue(fixture.commands.run(KetchCommands.ToggleSidebar))
    assertTrue(fixture.controller.appSettings.ui.sidebarCollapsed)
    fixture.controller.close()
  }

  @Test
  fun run_search_asksForTheSearchField() = runTest {
    val fixture = fixture()
    val requests = mutableListOf<Unit>()
    backgroundScope.launch { fixture.controller.state.focusSearchRequests.collect(requests::add) }
    runCurrent()

    assertTrue(fixture.commands.run(KetchCommands.Search))
    runCurrent()

    assertEquals(1, requests.size)
    fixture.controller.close()
  }

  @Test
  fun run_searchWhileSettingsShows_leavesTheKeyToSettings() = runTest {
    val fixture = fixture()
    fixture.shell.openSettings()

    assertFalse(fixture.commands.run(KetchCommands.Search))
    assertTrue(fixture.shell.settingsOpen)
    fixture.controller.close()
  }

  @Test
  fun run_shortcuts_opensAndClosesTheSheet() = runTest {
    val fixture = fixture()

    fixture.commands.run(KetchCommands.Shortcuts)
    assertTrue(fixture.shell.shortcutsOpen)
    fixture.commands.run(KetchCommands.Shortcuts)
    assertFalse(fixture.shell.shortcutsOpen)
    fixture.controller.close()
  }

  @Test
  fun binds_deviceCommands_bindsEachOne() = runTest {
    val fixture = fixture()

    assertTrue(fixture.commands.binds(KetchCommands.SwitchDevice))
    assertTrue(fixture.commands.binds(KetchCommands.AllDevices))
    assertTrue(fixture.commands.binds(KetchCommands.device(2)))
    fixture.controller.close()
  }

  @Test
  fun run_switchDevice_opensAndClosesTheSwitcher() = runTest {
    val fixture = fixture()

    assertTrue(fixture.commands.run(KetchCommands.SwitchDevice))
    assertTrue(fixture.controller.state.showInstanceSelector)
    fixture.commands.run(KetchCommands.SwitchDevice)
    assertFalse(fixture.controller.state.showInstanceSelector)
    fixture.controller.close()
  }

  @Test
  fun run_allDevicesWithOneDevice_leavesTheKey() = runTest {
    val fixture = fixture()

    assertFalse(fixture.commands.run(KetchCommands.AllDevices))
    fixture.controller.close()
  }
}
