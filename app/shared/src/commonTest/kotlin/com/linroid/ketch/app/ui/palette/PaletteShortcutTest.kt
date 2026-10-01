package com.linroid.ketch.app.ui.palette

import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.platform.DroppedFile
import com.linroid.ketch.app.platform.FilePicker
import com.linroid.ketch.app.platform.SystemClipboard
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.RecordingKetchApi
import com.linroid.ketch.app.ui.shell.ShellCommands
import com.linroid.ketch.app.ui.shell.ShellState
import com.linroid.ketch.config.KetchConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PaletteShortcutTest {
  private object NoClipboard : SystemClipboard {
    override val readsSilently: Boolean = true
    override val pasteEvents: Flow<String> = emptyFlow()

    override suspend fun hasLink(): Boolean = false

    override suspend fun readText(): String? = null

    override suspend fun writeText(text: String) {}
  }

  private object NoFiles : FilePicker {
    override val canPickFolder: Boolean = false

    override suspend fun pickFolder(initialFolder: String?): String? = null

    override suspend fun pickTorrentFiles(): List<DroppedFile> = emptyList()
  }

  @Test
  fun run_paletteCommand_togglesThePaletteWithoutFocusingSearch() = runTest {
    val controller = AppController(
      instanceManager = InstanceManager(
        factory = InstanceFactory(
          deviceName = "This Mac",
          embeddedFactory = { RecordingKetchApi() },
        ),
        configStore = RecordingConfigStore(KetchConfig()),
      ),
      context = StandardTestDispatcher(testScheduler),
    )
    val shell = ShellState(controller.state)
    val commands =
      ShellCommands(shell, backgroundScope, NoClipboard, NoFiles, KeyboardPlatform.Mac)
    val searchRequests = mutableListOf<Unit>()
    backgroundScope.launch { controller.state.focusSearchRequests.collect(searchRequests::add) }
    runCurrent()

    assertTrue(commands.binds(KetchCommands.Palette))
    assertTrue(commands.run(KetchCommands.Palette))
    runCurrent()
    assertTrue(shell.paletteOpen)
    assertEquals(emptyList(), searchRequests)

    commands.run(KetchCommands.Palette)
    assertFalse(shell.paletteOpen)
    controller.close()
  }
}
